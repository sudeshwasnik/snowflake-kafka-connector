package com.snowflake.kafka.connector;

import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.BUFFER_COUNT_RECORDS;
import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.INGESTION_METHOD_OPT;

import com.snowflake.kafka.connector.internal.LoggerHandler;
import com.snowflake.kafka.connector.internal.TestUtils;
import com.snowflake.kafka.connector.internal.ingestsdk.IngestSdkProvider;
import com.snowflake.kafka.connector.internal.ingestsdk.StreamingClientManager;
import com.snowflake.kafka.connector.internal.streaming.InMemorySinkTaskContext;
import com.snowflake.kafka.connector.internal.streaming.IngestionMethodConfig;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.JsonProcessingException;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.slf4j.Logger;

/**
 * Sink Task IT test which uses {@link
 * com.snowflake.kafka.connector.internal.streaming.SnowflakeSinkServiceV2}
 */
public class SnowflakeSinkTaskForStreamingIT {

  private String topicName;
  private static int partition = 0;
  private TopicPartition topicPartition;
  private Map<String, String> config;

  @Mock Logger logger = Mockito.mock(Logger.class);

  @InjectMocks @Spy
  private LoggerHandler loggerHandler = Mockito.spy(new LoggerHandler(this.getClass().getName()));

  @InjectMocks private SnowflakeSinkTask sinkTask1 = new SnowflakeSinkTask();

  @Before
  public void setup() {
    topicName = TestUtils.randomTableName();
    topicPartition = new TopicPartition(topicName, partition);

    // config
    this.config = TestUtils.getConfForStreaming();
    this.config.put(BUFFER_COUNT_RECORDS, "1"); // override
    this.config.put(INGESTION_METHOD_OPT, IngestionMethodConfig.SNOWPIPE_STREAMING.toString());
    SnowflakeSinkConnectorConfig.setDefaultValues(this.config);

    IngestSdkProvider.getStreamingClientManager()
        .createAllStreamingClients(this.config, "testkcid", 2, 1);
  }

  @After
  public void after() throws Exception {
    TestUtils.dropTable(topicName);
    IngestSdkProvider.setStreamingClientManager(
        new StreamingClientManager(new HashMap<>())); // reset to clean initial manager
  }

  @Test
  public void testSinkTask() throws Exception {
    SnowflakeSinkTask sinkTask = new SnowflakeSinkTask();

    // Inits the sinktaskcontext
    sinkTask.initialize(new InMemorySinkTaskContext(Collections.singleton(topicPartition)));
    sinkTask.start(config);
    ArrayList<TopicPartition> topicPartitions = new ArrayList<>();
    topicPartitions.add(new TopicPartition(topicName, partition));
    sinkTask.open(topicPartitions);

    // send regular data
    List<SinkRecord> records = TestUtils.createJsonStringSinkRecords(0, 1, topicName, partition);
    sinkTask.put(records);

    // commit offset
    final Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<>();
    offsetMap.put(topicPartitions.get(0), new OffsetAndMetadata(10000));

    TestUtils.assertWithRetry(() -> sinkTask.preCommit(offsetMap).size() == 1, 20, 5);

    TestUtils.assertWithRetry(
        () -> sinkTask.preCommit(offsetMap).get(topicPartitions.get(0)).offset() == 1, 20, 5);

    sinkTask.close(topicPartitions);
    sinkTask.stop();
  }

  // test two tasks map to one client behaves as expected
  @Test
  public void testTaskToClientMapping() throws Exception {
    // setup two tasks pointing to one client
    IngestSdkProvider.getStreamingClientManager()
        .createAllStreamingClients(this.config, "kcid", 2, 2);

    // setup task0
    int partition0 = 0;
    String topic0Name = "topic0";
    SnowflakeSinkTask sinkTask0 = new SnowflakeSinkTask();
    TopicPartition topicPartition0 = new TopicPartition(topic0Name, partition0);
    sinkTask0.initialize(new InMemorySinkTaskContext(Collections.singleton(topicPartition0)));
    sinkTask0.start(config);
    ArrayList<TopicPartition> topicPartitions0 = new ArrayList<>();
    topicPartitions0.add(topicPartition0);
    sinkTask0.open(topicPartitions0);

    // setup task1
    int partition1 = 1;
    String topic1Name = "topic1";
    SnowflakeSinkTask sinkTask1 = new SnowflakeSinkTask();
    TopicPartition topicPartition1 = new TopicPartition(topic1Name, partition1);
    sinkTask1.initialize(new InMemorySinkTaskContext(Collections.singleton(topicPartition0)));
    sinkTask1.start(config);
    ArrayList<TopicPartition> topicPartitions1 = new ArrayList<>();
    topicPartitions1.add(topicPartition1);
    sinkTask1.open(topicPartitions1);

    // send data to both tasks
    List<SinkRecord> records0 = TestUtils.createJsonStringSinkRecords(0, 1, topic0Name, partition0);
    sinkTask0.put(records0);
    List<SinkRecord> records1 = TestUtils.createJsonStringSinkRecords(0, 1, topic1Name, partition1);
    sinkTask1.put(records1);

    final Map<TopicPartition, OffsetAndMetadata> offsetMap0 = new HashMap<>();
    final Map<TopicPartition, OffsetAndMetadata> offsetMap1 = new HashMap<>();
    offsetMap0.put(topicPartitions0.get(0), new OffsetAndMetadata(10000));
    offsetMap1.put(topicPartitions1.get(0), new OffsetAndMetadata(10000));

    // verify that data was ingested
    TestUtils.assertWithRetry(() -> sinkTask0.preCommit(offsetMap0).size() == 1, 20, 5);
    TestUtils.assertWithRetry(() -> sinkTask1.preCommit(offsetMap1).size() == 1, 20, 5);

    TestUtils.assertWithRetry(
        () -> sinkTask0.preCommit(offsetMap0).get(topicPartitions0.get(0)).offset() == 1, 20, 5);
    TestUtils.assertWithRetry(
        () -> sinkTask1.preCommit(offsetMap1).get(topicPartitions1.get(0)).offset() == 1, 20, 5);

    // clean up tasks
    sinkTask0.close(topicPartitions0);
    sinkTask1.close(topicPartitions1);
    sinkTask0.stop();
    sinkTask1.stop();
  }

  @Test
  public void testMultipleSinkTaskWithLogs() throws Exception {
    // set up task0, the real one
    int task0Id = 0;
    String topicName0 = "topic0";
    Map<String, String> config0 = this.getConfig(task0Id);
    ArrayList<TopicPartition> topicPartitions0 = getTopicPartitions(topicName0, 1);
    SnowflakeSinkTask sinkTask0 = new SnowflakeSinkTask();

    // set up task1, the !real one
    int task1Id = 1;
    String topicName1 = "topic1";
    Map<String, String> config1 = this.getConfig(task1Id);
    ArrayList<TopicPartition> topicPartitions1 = getTopicPartitions(topicName1, 1);
    // task1 logging
    int taskOpen1Count = 0;
    MockitoAnnotations.initMocks(this);
    Mockito.when(logger.isInfoEnabled()).thenReturn(true);
    Mockito.when(logger.isDebugEnabled()).thenReturn(true);
    Mockito.when(logger.isWarnEnabled()).thenReturn(true);
    String expectedTask1Tag =
        TestUtils.getExpectedLogTagWithoutCreationCount(task1Id + "", taskOpen1Count);
    Mockito.doCallRealMethod().when(loggerHandler).setLoggerInstanceTag(expectedTask1Tag);

    // init tasks
    sinkTask0.initialize(
        new InMemorySinkTaskContext(Collections.singleton(topicPartitions0.get(0))));
    sinkTask1.initialize(
        new InMemorySinkTaskContext(Collections.singleton(topicPartitions1.get(0))));

    // start tasks
    sinkTask0.start(config0);
    sinkTask1.start(config1);

    // verify task1 start logs
    Mockito.verify(loggerHandler, Mockito.times(1))
        .setLoggerInstanceTag(Mockito.contains(expectedTask1Tag));
    Mockito.verify(logger, Mockito.times(2))
        .debug(
            AdditionalMatchers.and(Mockito.contains(expectedTask1Tag), Mockito.contains("start")));

    // open tasks
    sinkTask0.open(topicPartitions0);
    sinkTask1.open(topicPartitions1);

    // verify task1 open logs
    taskOpen1Count++;
    expectedTask1Tag =
        TestUtils.getExpectedLogTagWithoutCreationCount(task1Id + "", taskOpen1Count);
    Mockito.verify(logger, Mockito.times(1))
        .debug(
            AdditionalMatchers.and(Mockito.contains(expectedTask1Tag), Mockito.contains("open")));

    // send data to tasks
    sinkTask0.put(TestUtils.createJsonStringSinkRecords(0, 1, topicName0, 0));
    sinkTask1.put(TestUtils.createJsonStringSinkRecords(0, 1, topicName1, 0));

    // verify task1 put logs
    Mockito.verify(logger, Mockito.times(1))
        .debug(AdditionalMatchers.and(Mockito.contains(expectedTask1Tag), Mockito.contains("put")));

    // commit offsets
    final Map<TopicPartition, OffsetAndMetadata> offsetMap0 = new HashMap<>();
    final Map<TopicPartition, OffsetAndMetadata> offsetMap1 = new HashMap<>();
    offsetMap0.put(topicPartitions0.get(0), new OffsetAndMetadata(10000));
    offsetMap1.put(topicPartitions1.get(0), new OffsetAndMetadata(10000));

    TestUtils.assertWithRetry(() -> sinkTask0.preCommit(offsetMap0).size() == 1, 20, 5);
    TestUtils.assertWithRetry(() -> sinkTask1.preCommit(offsetMap1).size() == 1, 20, 5);

    // verify task1 precommit logs
    Mockito.verify(logger, Mockito.times(1))
        .debug(
            AdditionalMatchers.and(
                Mockito.contains(expectedTask1Tag), Mockito.contains("precommit")));

    TestUtils.assertWithRetry(
        () -> sinkTask0.preCommit(offsetMap0).get(topicPartitions0.get(0)).offset() == 1, 20, 5);
    TestUtils.assertWithRetry(
        () -> sinkTask1.preCommit(offsetMap1).get(topicPartitions1.get(0)).offset() == 1, 20, 5);

    // close tasks
    sinkTask0.close(topicPartitions0);
    sinkTask1.close(topicPartitions1);

    // verify task1 close logs
    Mockito.verify(logger, Mockito.times(1))
        .debug(
            AdditionalMatchers.and(Mockito.contains(expectedTask1Tag), Mockito.contains("closed")));

    // stop tasks
    sinkTask0.stop();
    sinkTask1.stop();

    // verify task1 stop logs
    Mockito.verify(logger, Mockito.times(1))
        .debug(
            AdditionalMatchers.and(Mockito.contains(expectedTask1Tag), Mockito.contains("stop")));
  }

  @Test
  public void testSinkTaskWithMultipleOpenClose() throws Exception {
    SnowflakeSinkTask sinkTask = new SnowflakeSinkTask();
    // Inits the sinktaskcontext
    sinkTask.initialize(new InMemorySinkTaskContext(Collections.singleton(topicPartition)));

    sinkTask.start(this.config);
    ArrayList<TopicPartition> topicPartitions = new ArrayList<>();
    topicPartitions.add(new TopicPartition(topicName, partition));
    sinkTask.open(topicPartitions);

    final long noOfRecords = 1l;
    final long lastOffsetNo = noOfRecords - 1;

    // send regular data
    List<SinkRecord> records =
        TestUtils.createJsonStringSinkRecords(0, noOfRecords, topicName, partition);
    sinkTask.put(records);

    // commit offset
    final Map<TopicPartition, OffsetAndMetadata> offsetMap = new HashMap<>();
    offsetMap.put(topicPartitions.get(0), new OffsetAndMetadata(lastOffsetNo));

    TestUtils.assertWithRetry(() -> sinkTask.preCommit(offsetMap).size() == 1, 20, 5);

    // precommit is one more than offset last inserted
    TestUtils.assertWithRetry(
        () -> sinkTask.preCommit(offsetMap).get(topicPartitions.get(0)).offset() == noOfRecords,
        20,
        5);

    sinkTask.close(topicPartitions);

    // Add one more partition
    topicPartitions.add(new TopicPartition(topicName, partition + 1));

    sinkTask.open(topicPartitions);

    // trying to put same records
    sinkTask.put(records);

    List<SinkRecord> recordsWithAnotherPartition =
        TestUtils.createJsonStringSinkRecords(0, noOfRecords, topicName, partition + 1);
    sinkTask.put(recordsWithAnotherPartition);

    // Adding to offsetMap so that this gets into precommit
    offsetMap.put(topicPartitions.get(1), new OffsetAndMetadata(lastOffsetNo));

    TestUtils.assertWithRetry(() -> sinkTask.preCommit(offsetMap).size() == 2, 20, 5);

    TestUtils.assertWithRetry(
        () -> sinkTask.preCommit(offsetMap).get(topicPartitions.get(0)).offset() == 1, 20, 5);

    TestUtils.assertWithRetry(
        () -> sinkTask.preCommit(offsetMap).get(topicPartitions.get(1)).offset() == 1, 20, 5);

    sinkTask.close(topicPartitions);

    sinkTask.stop();

    ResultSet resultSet = TestUtils.showTable(topicName);
    LinkedList<String> contentResult = new LinkedList<>();
    LinkedList<String> metadataResult = new LinkedList<>();

    while (resultSet.next()) {
      contentResult.add(resultSet.getString("RECORD_CONTENT"));
      metadataResult.add(resultSet.getString("RECORD_METADATA"));
    }
    resultSet.close();
    assert metadataResult.size() == 2;
    assert contentResult.size() == 2;
    ObjectMapper mapper = new ObjectMapper();

    Set<Long> partitionsInTable = new HashSet<>();
    metadataResult.forEach(
        s -> {
          try {
            JsonNode metadata = mapper.readTree(s);
            metadata.get("offset").asText().equals("0");
            partitionsInTable.add(metadata.get("partition").asLong());
          } catch (JsonProcessingException e) {
            Assert.fail();
          }
        });

    assert partitionsInTable.size() == 2;
  }

  private Map<String, String> getConfig(int taskId) {
    Map<String, String> config = TestUtils.getConfForStreaming();
    config.put(BUFFER_COUNT_RECORDS, "1"); // override
    config.put(INGESTION_METHOD_OPT, IngestionMethodConfig.SNOWPIPE_STREAMING.toString());
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    config.put(Utils.TASK_ID, taskId + "");

    return config;
  }

  private ArrayList<TopicPartition> getTopicPartitions(String topicName, int numPartitions) {
    ArrayList<TopicPartition> topicPartitions = new ArrayList<>();
    for (int i = 0; i < numPartitions; i++) {
      topicPartitions.add(new TopicPartition(topicName, i));
    }

    return topicPartitions;
  }
}
