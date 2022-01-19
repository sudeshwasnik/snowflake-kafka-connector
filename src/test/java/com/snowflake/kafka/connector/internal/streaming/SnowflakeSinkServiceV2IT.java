package com.snowflake.kafka.connector.internal.streaming;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import com.snowflake.kafka.connector.internal.SnowflakeSinkService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkServiceFactory;
import com.snowflake.kafka.connector.internal.TestUtils;
import com.snowflake.kafka.connector.records.SnowflakeConverter;
import com.snowflake.kafka.connector.records.SnowflakeJsonConverter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Test;

public class SnowflakeSinkServiceV2IT {

  private SnowflakeConnectionService conn = TestUtils.getConnectionServiceForStreamingIngest();
  private String table = TestUtils.randomTableName();
  private int partition = 0;
  private String topic = "test";
  private static ObjectMapper MAPPER = new ObjectMapper();

  private static final String jsonWithSchema =
      ""
          + "{\n"
          + "  \"schema\": {\n"
          + "    \"type\": \"struct\",\n"
          + "    \"fields\": [\n"
          + "      {\n"
          + "        \"type\": \"string\",\n"
          + "        \"optional\": false,\n"
          + "        \"field\": \"regionid\"\n"
          + "      },\n"
          + "      {\n"
          + "        \"type\": \"string\",\n"
          + "        \"optional\": false,\n"
          + "        \"field\": \"gender\"\n"
          + "      }\n"
          + "    ],\n"
          + "    \"optional\": false,\n"
          + "    \"name\": \"ksql.users\"\n"
          + "  },\n"
          + "  \"payload\": {\n"
          + "    \"regionid\": \"Region_5\",\n"
          + "    \"gender\": \"MALE\"\n"
          + "  }\n"
          + "}";
  private static final String jsonWithoutSchema = "{\"userid\": \"User_1\"}";

  @After
  public void afterEach() {
    //        TestUtils.dropTableStreaming(table);
  }

  @Test
  public void testSinkServiceV2Builder() {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .build();

    assert service instanceof SnowflakeSinkServiceV2;

    // connection test
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () ->
            SnowflakeSinkServiceFactory.builder(
                    null, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
                .build());
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () -> {
          SnowflakeConnectionService conn = TestUtils.getConnectionServiceForStreamingIngest();
          conn.close();
          SnowflakeSinkServiceFactory.builder(
                  conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
              .build();
        });
  }

  @Test
  public void testStreamingIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "test", input.schema(), input.value(), offset);

    service.insert(record1);

    long committedOffset = service.getOffset(new TopicPartition(topic, partition));
    System.out.println(committedOffset);
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 10, 10);

    //        service.closeAll();
  }
}