/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.tez;

import com.google.common.primitives.UnsignedBytes;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;

import com.google.common.collect.LinkedListMultimap;
import org.apache.hadoop.hive.ql.io.HiveInputFormat.HiveInputSplit;
import org.apache.hadoop.mapred.split.SplitLocationProvider;
import org.apache.tez.runtime.api.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.plan.TezWork.VertexType;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.split.TezGroupedSplit;
import org.apache.hadoop.mapreduce.split.TezMapReduceSplitsGrouper;
import org.apache.tez.common.TezUtils;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.EdgeProperty.DataMovementType;
import org.apache.tez.dag.api.EdgeManagerPluginDescriptor;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.UserPayload;
import org.apache.tez.dag.api.VertexLocationHint;
import org.apache.tez.dag.api.VertexManagerPlugin;
import org.apache.tez.dag.api.VertexManagerPluginContext;
import org.apache.tez.mapreduce.hadoop.MRInputHelpers;
import org.apache.tez.mapreduce.protos.MRRuntimeProtos.MRInputUserPayloadProto;
import org.apache.tez.mapreduce.protos.MRRuntimeProtos.MRSplitProto;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.InputSpecUpdate;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.protobuf.ByteString;

/*
 * This is the central piece for Bucket Map Join and SMB join. It has the following
 * responsibilities:
 * 1. Group incoming splits based on bucketing.
 * 2. Generate new serialized events for the grouped splits.
 * 3. Create a routing table for the bucket map join and send a serialized version as payload
 * for the EdgeManager.
 * 4. For SMB join, generate a grouping according to bucketing for the "small" table side.
 */
public class CustomPartitionVertex extends VertexManagerPlugin {

  private static class HiveInputSplitComparator implements Comparator<HiveInputSplit> {

    @Override
    public int compare(HiveInputSplit inp1, HiveInputSplit inp2) {
      return UnsignedBytes.lexicographicalComparator().compare(inp1.getBytesForEquality(), inp2.getBytesForEquality());
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(CustomPartitionVertex.class.getName());

  VertexManagerPluginContext context;

  private InputConfigureVertexTasksEvent configureVertexTaskEvent;
  private int numBuckets = -1;
  private Configuration conf = null;
  private final SplitGrouper grouper = new SplitGrouper();
  private int taskCount = 0;
  private VertexType vertexType;
  private String mainWorkName;
  private final Multimap<Integer, Integer> bucketToTaskMap = HashMultimap.<Integer, Integer> create();

  private final Map<String, Multimap<Integer, InputSplit>> inputToGroupedSplitMap =
      new HashMap<>();

  private int numInputsAffectingRootInputSpecUpdate = 1;
  private int numInputsSeenSoFar = 0;
  private final Map<String, EdgeManagerPluginDescriptor> emMap = Maps.newHashMap();
  private final List<InputSplit> finalSplits = Lists.newLinkedList();
  private final Map<String, InputSpecUpdate> inputNameInputSpecMap =
      new HashMap<>();
  private Map<String, Integer> inputToBucketMap;

  public CustomPartitionVertex(VertexManagerPluginContext context) {
    super(context);
  }

  @Override
  public void initialize() {
    this.context = getContext();
    ByteBuffer payload = context.getUserPayload().getPayload();
    CustomVertexConfiguration vertexConf = new CustomVertexConfiguration();
    DataInputByteBuffer dibb = new DataInputByteBuffer();
    dibb.reset(payload);
    try {
      vertexConf.readFields(dibb);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    this.numBuckets = vertexConf.getNumBuckets();
    this.mainWorkName = vertexConf.getInputName();
    this.vertexType = vertexConf.getVertexType();
    this.numInputsAffectingRootInputSpecUpdate = vertexConf.getNumInputs();
    this.inputToBucketMap = vertexConf.getInputToBucketMap();
  }

  @Override
  public void onVertexStarted(Map<String, List<Integer>> completions) {
    int numTasks = context.getVertexNumTasks(context.getVertexName());
    List<VertexManagerPluginContext.TaskWithLocationHint> scheduledTasks =
      new ArrayList<VertexManagerPluginContext.TaskWithLocationHint>(numTasks);
    for (int i = 0; i < numTasks; ++i) {
      scheduledTasks.add(new VertexManagerPluginContext.TaskWithLocationHint(Integer.valueOf(i), null));
    }
    context.scheduleVertexTasks(scheduledTasks);
  }

  @Override
  public void onSourceTaskCompleted(String srcVertexName, Integer attemptId) {
  }

  @Override
  public void onVertexManagerEventReceived(VertexManagerEvent vmEvent) {
  }

  // One call per root Input
  @Override
  public void onRootVertexInitialized(String inputName, InputDescriptor inputDescriptor,
      List<Event> events) {
    numInputsSeenSoFar++;
    LOG.info("On root vertex initialized " + inputName);
    try {
      // This is using the payload from the RootVertexInitializer corresponding
      // to InputName. Ideally it should be using its own configuration class -
      // but that
      // means serializing another instance.
      MRInputUserPayloadProto protoPayload =
          MRInputHelpers.parseMRInputPayload(inputDescriptor.getUserPayload());
      this.conf = TezUtils.createConfFromByteString(protoPayload.getConfigurationBytes());

      /*
       * Currently in tez, the flow of events is thus:
       * "Generate Splits -> Initialize Vertex" (with parallelism info obtained
       * from the generate splits phase). The generate splits phase groups
       * splits using the TezGroupedSplitsInputFormat. However, for bucket map
       * joins the grouping done by this input format results in incorrect
       * results as the grouper has no knowledge of buckets. So, we initially
       * set the input format to be HiveInputFormat (in DagUtils) for the case
       * of bucket map joins so as to obtain un-grouped splits. We then group
       * the splits corresponding to buckets using the tez grouper which returns
       * TezGroupedSplits.
       */

      // This assumes that Grouping will always be used.
      // Enabling grouping on the payload.
      MRInputUserPayloadProto updatedPayload =
          MRInputUserPayloadProto.newBuilder(protoPayload).setGroupingEnabled(true).build();
      inputDescriptor.setUserPayload(UserPayload.create(updatedPayload.toByteString().asReadOnlyByteBuffer()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    boolean dataInformationEventSeen = false;
    Map<Integer, Set<HiveInputSplit>> bucketFileSplitsMap = new TreeMap<>();

    for (Event event : events) {
      if (event instanceof InputConfigureVertexTasksEvent) {
        // No tasks should have been started yet. Checked by initial state
        // check.
        LOG.info("Got a input configure vertex event for input: " + inputName);
        Preconditions.checkState(dataInformationEventSeen == false);
        InputConfigureVertexTasksEvent cEvent = (InputConfigureVertexTasksEvent) event;

        // The vertex cannot be configured until all DataEvents are seen - to
        // build the routing table.
        configureVertexTaskEvent = cEvent;
        LOG.info("Configure task for input name: " + inputName + " num tasks: "
            + configureVertexTaskEvent.getNumTasks());
      }
      if (event instanceof InputUpdatePayloadEvent) {
        // this event can never occur. If it does, fail.
        Preconditions.checkState(false);
      } else if (event instanceof InputDataInformationEvent) {
        dataInformationEventSeen = true;
        InputDataInformationEvent diEvent = (InputDataInformationEvent) event;
        HiveInputSplit inputSplit;
        try {
          inputSplit = getInputSplitFromEvent(diEvent);
        } catch (IOException e) {
          throw new RuntimeException("Failed to get file split for event: " + diEvent, e);
        }
        final int bucketId = inputSplit.getBucketId().orElse(-1);
        Set<HiveInputSplit> inputSplits = bucketFileSplitsMap.computeIfAbsent(bucketId,
            k -> new TreeSet<>(new HiveInputSplitComparator()));
        inputSplits.add(inputSplit);
      }
    }

    LOG.debug("Bucket splits map for input name: {} is {}", inputName, bucketFileSplitsMap);

    Multimap<Integer, InputSplit> bucketToInitialSplitMap =
        getBucketSplitMapForBucket(inputName, bucketFileSplitsMap);
    Preconditions.checkState(
        bucketToInitialSplitMap.keySet().stream().allMatch(i -> 0 <= i && i < numBuckets));

    try {
      int totalResource = context.getTotalAvailableResource().getMemory();
      int taskResource = context.getVertexTaskResource().getMemory();
      float waves =
          conf.getFloat(TezMapReduceSplitsGrouper.TEZ_GROUPING_SPLIT_WAVES,
              TezMapReduceSplitsGrouper.TEZ_GROUPING_SPLIT_WAVES_DEFAULT);

      int availableSlots = totalResource / taskResource;

      LOG.debug("Grouping splits. {} available slots, {} waves. Bucket initial splits map: {}", availableSlots, waves,
          bucketToInitialSplitMap);

      JobConf jobConf = new JobConf(conf);
      ShimLoader.getHadoopShims().getMergedCredentials(jobConf);

      Multimap<Integer, InputSplit> bucketToGroupedSplitMap =
          HashMultimap.<Integer, InputSplit> create();
      boolean secondLevelGroupingDone = false;
      if ((mainWorkName.isEmpty()) || (inputName.compareTo(mainWorkName) == 0)) {
        SplitLocationProvider splitLocationProvider = Utils.getSplitLocationProvider(conf, LOG);
        for (Integer key : bucketToInitialSplitMap.keySet()) {
          InputSplit[] inputSplitArray =
              (bucketToInitialSplitMap.get(key).toArray(new InputSplit[0]));
          Multimap<Integer, InputSplit> groupedSplit =
              grouper.generateGroupedSplits(jobConf, conf, inputSplitArray, waves,
                  availableSlots, inputName, mainWorkName.isEmpty(), splitLocationProvider);
          if (mainWorkName.isEmpty() == false) {
            Multimap<Integer, InputSplit> singleBucketToGroupedSplit =
                HashMultimap.<Integer, InputSplit> create();
            singleBucketToGroupedSplit.putAll(key, groupedSplit.values());
            groupedSplit =
                grouper.group(jobConf, singleBucketToGroupedSplit, availableSlots,
                    HiveConf.getFloatVar(conf, HiveConf.ConfVars.TEZ_SMB_NUMBER_WAVES), null);
            secondLevelGroupingDone = true;
          }
          bucketToGroupedSplitMap.putAll(key, groupedSplit.values());
        }
        processAllEvents(inputName, bucketToGroupedSplitMap, secondLevelGroupingDone);
      } else {
        SplitLocationProvider splitLocationProvider = Utils.getSplitLocationProvider(conf, LOG);
        // do not group across files in case of side work because there is only 1 KV reader per
        // grouped split. This would affect SMB joins where we want to find the smallest key in
        // all the bucket files.
        for (Integer key : bucketToInitialSplitMap.keySet()) {
          InputSplit[] inputSplitArray =
              (bucketToInitialSplitMap.get(key).toArray(new InputSplit[0]));
          Multimap<Integer, InputSplit> groupedSplit =
              grouper.generateGroupedSplits(jobConf, conf, inputSplitArray, waves,
                    availableSlots, inputName, false, splitLocationProvider);
            bucketToGroupedSplitMap.putAll(key, groupedSplit.values());
        }
        /*
         * this is the small table side. In case of SMB join, we need to send each split to the
         * corresponding bucket-based task on the other side. In case a split needs to go to
         * multiple downstream tasks, we need to clone the event and send it to the right
         * destination.
         */
        LOG.info("This is the side work - multi-mr work.");
        processAllSideEventsSetParallelism(inputName, bucketToGroupedSplitMap);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void processAllSideEventsSetParallelism(String inputName,
      Multimap<Integer, InputSplit> bucketToGroupedSplitMap) throws IOException {
    // the bucket to task map should have been setup by the big table.
    LOG.info("Processing events for input " + inputName);
    if (inputNameInputSpecMap.get(mainWorkName) == null) {
      LOG.info("We don't have a routing table yet. Will need to wait for the main input "
          + mainWorkName + " initialization");
      inputToGroupedSplitMap.put(inputName, bucketToGroupedSplitMap);
      return;
    }
    processAllSideEvents(inputName, bucketToGroupedSplitMap);
    setVertexParallelismAndRootInputSpec(inputNameInputSpecMap);
  }

  private void processAllSideEvents(String inputName,
      Multimap<Integer, InputSplit> bucketToGroupedSplitMap) throws IOException {
    List<InputDataInformationEvent> taskEvents = new ArrayList<InputDataInformationEvent>();
    LOG.info("We have a routing table and we are going to set the destination tasks for the"
        + " multi mr inputs. " + bucketToTaskMap);

    Integer[] numSplitsForTask = new Integer[taskCount];
    Arrays.fill(numSplitsForTask, 0);

    Multimap<Integer, ByteBuffer> bucketToSerializedSplitMap = LinkedListMultimap.create();

    // Create the list of serialized splits for each bucket.
    for (Entry<Integer, Collection<InputSplit>> entry : bucketToGroupedSplitMap.asMap().entrySet()) {
      for (InputSplit split : entry.getValue()) {
        MRSplitProto serializedSplit = MRInputHelpers.createSplitProto(split);
        ByteBuffer bs = serializedSplit.toByteString().asReadOnlyByteBuffer();
        bucketToSerializedSplitMap.put(entry.getKey(), bs);
      }
    }

    for (Entry<Integer, Collection<ByteBuffer>> entry : bucketToSerializedSplitMap.asMap().entrySet()) {
      Collection<Integer> destTasks = bucketToTaskMap.get(entry.getKey());
      if ((destTasks == null) || (destTasks.isEmpty())) {
        continue;
      }
      for (Integer task : destTasks) {
        int count = 0;
        for (ByteBuffer buf : entry.getValue()) {
          count++;
          InputDataInformationEvent diEvent =
              InputDataInformationEvent.createWithSerializedPayload(count, buf);
          diEvent.setTargetIndex(task);
          taskEvents.add(diEvent);
        }
        numSplitsForTask[task] += count;
      }
    }

    inputNameInputSpecMap.put(inputName,
        InputSpecUpdate.createPerTaskInputSpecUpdate(Arrays.asList(numSplitsForTask)));

    LOG.info("For input name: " + inputName + " task events size is " + taskEvents.size());

    context.addRootInputEvents(inputName, taskEvents);
  }

  private void processAllEvents(String inputName,
      Multimap<Integer, InputSplit> bucketToGroupedSplitMap, boolean secondLevelGroupingDone)
      throws IOException {

    int totalInputsCount = 0;
    List<Integer> numSplitsForTask = new ArrayList<Integer>();
    for (Entry<Integer, Collection<InputSplit>> entry : bucketToGroupedSplitMap.asMap().entrySet()) {
      int bucketNum = entry.getKey();
      Collection<InputSplit> initialSplits = entry.getValue();
      finalSplits.addAll(initialSplits);
      for (InputSplit inputSplit : initialSplits) {
        bucketToTaskMap.put(bucketNum, taskCount);
        if (secondLevelGroupingDone) {
          TezGroupedSplit groupedSplit = (TezGroupedSplit) inputSplit;
          numSplitsForTask.add(groupedSplit.getGroupedSplits().size());
          totalInputsCount += groupedSplit.getGroupedSplits().size();
        } else {
          numSplitsForTask.add(1);
          totalInputsCount += 1;
        }
        taskCount++;
      }
    }

    inputNameInputSpecMap.put(inputName,
        InputSpecUpdate.createPerTaskInputSpecUpdate(numSplitsForTask));

    // Construct the EdgeManager descriptor to be used by all edges which need
    // the routing table.
    EdgeManagerPluginDescriptor hiveEdgeManagerDesc = null;
    if ((vertexType == VertexType.MULTI_INPUT_INITIALIZED_EDGES)
        || (vertexType == VertexType.INITIALIZED_EDGES)) {
      hiveEdgeManagerDesc = EdgeManagerPluginDescriptor.create(CustomPartitionEdge.class.getName());
      UserPayload payload = getBytePayload(bucketToTaskMap);
      hiveEdgeManagerDesc.setUserPayload(payload);
    }

    // Replace the edge manager for all vertices which have routing type custom.
    for (Entry<String, EdgeProperty> edgeEntry : context.getInputVertexEdgeProperties().entrySet()) {
      if (edgeEntry.getValue().getDataMovementType() == DataMovementType.CUSTOM
          && edgeEntry.getValue().getEdgeManagerDescriptor().getClassName()
              .equals(CustomPartitionEdge.class.getName())) {
        emMap.put(edgeEntry.getKey(), hiveEdgeManagerDesc);
      }
    }

    LOG.info("Task count is " + taskCount + " for input name: " + inputName);

    List<InputDataInformationEvent> taskEvents = Lists.newArrayListWithCapacity(totalInputsCount);
    // Re-serialize the splits after grouping.
    int count = 0;
    for (InputSplit inputSplit : finalSplits) {
      if (secondLevelGroupingDone) {
        TezGroupedSplit tezGroupedSplit = (TezGroupedSplit)inputSplit;
        for (InputSplit subSplit : tezGroupedSplit.getGroupedSplits()) {
          if ((subSplit instanceof TezGroupedSplit) == false) {
            throw new IOException("Unexpected split type found: "
                + subSplit.getClass().getCanonicalName());
          }
          MRSplitProto serializedSplit = MRInputHelpers.createSplitProto(subSplit);
          InputDataInformationEvent diEvent =
              InputDataInformationEvent.createWithSerializedPayload(count, serializedSplit
                  .toByteString().asReadOnlyByteBuffer());
          diEvent.setTargetIndex(count);
          taskEvents.add(diEvent);
        }
      } else {
        MRSplitProto serializedSplit = MRInputHelpers.createSplitProto(inputSplit);
        InputDataInformationEvent diEvent =
            InputDataInformationEvent.createWithSerializedPayload(count, serializedSplit
                .toByteString().asReadOnlyByteBuffer());
        diEvent.setTargetIndex(count);
        taskEvents.add(diEvent);
      }
      count++;
    }

    // Set the actual events for the tasks.
    LOG.info("For input name: " + inputName + " task events size is " + taskEvents.size());
    context.addRootInputEvents(inputName, taskEvents);

    if (!inputToGroupedSplitMap.isEmpty()) {
      for (Entry<String, Multimap<Integer, InputSplit>> entry : inputToGroupedSplitMap.entrySet()) {
        processAllSideEvents(entry.getKey(), entry.getValue());
      }
      setVertexParallelismAndRootInputSpec(inputNameInputSpecMap);
      inputToGroupedSplitMap.clear();
    }

    // Only done when it is a bucket map join only no SMB.
    if (numInputsAffectingRootInputSpecUpdate == 1) {
      setVertexParallelismAndRootInputSpec(inputNameInputSpecMap);
      // Send the bucket IDs associated with the tasks, must happen after parallelism is set.
      sendBucketIdsToProcessor();
    }
  }

  private void sendBucketIdsToProcessor() {
    for (Entry<Integer, Collection<Integer>> entry : bucketToTaskMap.asMap().entrySet()) {
      int bucketNum = entry.getKey();
      for (Integer taskId : entry.getValue()) {
        // Create payload
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(numBuckets);
        buffer.putInt(bucketNum);
        buffer.flip();
        // Create the event and send it tez. Tez will route it to appropriate processor
        CustomProcessorEvent cpEvent = CustomProcessorEvent.create(buffer);
        context.sendEventToProcessor(Collections.singletonList(cpEvent), taskId);
      }
    }
  }

  private void
      setVertexParallelismAndRootInputSpec(Map<String, InputSpecUpdate> rootInputSpecUpdate)
          throws IOException {
    if (numInputsAffectingRootInputSpecUpdate != numInputsSeenSoFar) {
      return;
    }

    LOG.info("Setting vertex parallelism since we have seen all inputs.");

    boolean generateConsistentSplits =  HiveConf.getBoolVar(
        conf, HiveConf.ConfVars.HIVE_TEZ_GENERATE_CONSISTENT_SPLITS);
    LOG.info("GenerateConsistenSplitsInHive=" + generateConsistentSplits);
    context.setVertexParallelism(taskCount, VertexLocationHint.create(grouper
            .createTaskLocationHints(finalSplits.toArray(new InputSplit[finalSplits.size()]),
                generateConsistentSplits)), emMap,
        rootInputSpecUpdate);
    finalSplits.clear();
  }

  UserPayload getBytePayload(Multimap<Integer, Integer> routingTable) throws IOException {
    CustomEdgeConfiguration edgeConf =
        new CustomEdgeConfiguration(numBuckets, routingTable);
    DataOutputBuffer dob = new DataOutputBuffer();
    edgeConf.write(dob);
    byte[] serialized = dob.getData();
    return UserPayload.create(ByteBuffer.wrap(serialized));
  }

  private HiveInputSplit getInputSplitFromEvent(InputDataInformationEvent event) throws IOException {
    final InputSplit inputSplit;
    if (event.getDeserializedUserPayload() != null) {
      inputSplit = (InputSplit) event.getDeserializedUserPayload();
    } else {
      MRSplitProto splitProto = MRSplitProto.parseFrom(ByteString.copyFrom(event.getUserPayload()));
      SerializationFactory serializationFactory = new SerializationFactory(new Configuration());
      inputSplit = MRInputHelpers.createOldFormatSplitFromUserPayload(splitProto, serializationFactory);
    }

    if (!(inputSplit instanceof HiveInputSplit)) {
      throw new UnsupportedOperationException(
          "Cannot handle splits other than HiveInputSplit for the moment. Current input split type: "
              + inputSplit.getClass().getSimpleName());
    }
    return (HiveInputSplit) inputSplit;
  }

  /*
   * This method generates the map of bucket to file splits.
   */
  private Multimap<Integer, InputSplit> getBucketSplitMapForBucket(String inputName,
      Map<Integer, Set<HiveInputSplit>> bucketSplitsMap) {

    boolean isSMBJoin = numInputsAffectingRootInputSpecUpdate != 1;
    boolean isMainWork = mainWorkName.isEmpty() || inputName.compareTo(mainWorkName) == 0;
    Preconditions.checkState(
        isMainWork || isSMBJoin && inputToBucketMap != null && inputToBucketMap.containsKey(inputName),
        "CustomPartitionVertex.inputToBucketMap is not defined for {}", inputName);
    int inputBucketSize = isMainWork ? numBuckets : inputToBucketMap.get(inputName);

    Multimap<Integer, InputSplit> bucketToSplitMap = ArrayListMultimap.create();

    boolean fallback = false;
    for (Map.Entry<Integer, Set<HiveInputSplit>> entry : bucketSplitsMap.entrySet()) {
      int bucketId = entry.getKey();
      if (bucketId < 0) {
        fallback = true;
        LOG.info("Fallback to using older sort based logic to assign buckets to splits.");
        bucketToSplitMap.clear();
        break;
      }

      // Make sure the bucketId is at max the numBuckets
      bucketId %= inputBucketSize;

      bucketToSplitMap.putAll(bucketId, entry.getValue());
    }

    if (fallback) {
      // This is the old logic which assumes that the filenames are sorted in
      // alphanumeric order and mapped to appropriate bucket number.
      int curSplitIndex = 0;
      for (Map.Entry<Integer, Set<HiveInputSplit>> entry : bucketSplitsMap.entrySet()) {
        int bucketId = curSplitIndex % inputBucketSize;
        bucketToSplitMap.putAll(bucketId, entry.getValue());
        curSplitIndex++;
      }
    }

    if (isSMBJoin && numBuckets != inputBucketSize) {
      // This is just for SMB join use-case. The numBuckets would be equal to that of the big table
      // and the small table could have different number of buckets. In this case, we want to send the
      // data from the right buckets to the big table side. For e.g. Big table has 6 buckets and small
      // table has 4 buckets, bucket 1 of small table needs to be sent to bucket 1, 3, 5 of the big table
      // because (4*n + 1) % 6 can be 1, 3, or 5.

      int gcd = BigInteger.valueOf(numBuckets).gcd(BigInteger.valueOf(inputBucketSize)).intValue();
      Multimap<Integer, InputSplit> bucketIdRemainderSplitMap = ArrayListMultimap.create();
      for (Entry<Integer, Collection<InputSplit>> entry: bucketToSplitMap.asMap().entrySet()) {
        int smallTableBucketId = entry.getKey();
        int remainder = smallTableBucketId % gcd;
        bucketIdRemainderSplitMap.putAll(remainder, entry.getValue());
      }

      Multimap<Integer, InputSplit> redistributedBucketSplitMap = ArrayListMultimap.create();
      for (int bigTableBucketId = 0; bigTableBucketId < numBuckets; bigTableBucketId++) {
        int remainder = bigTableBucketId % gcd;
        redistributedBucketSplitMap.putAll(bigTableBucketId, bucketIdRemainderSplitMap.get(remainder));
      }

      bucketToSplitMap = redistributedBucketSplitMap;
    }

    return bucketToSplitMap;
  }
}
