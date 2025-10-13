package com.citystream.consumer;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.TimeoutException;

import static org.apache.spark.sql.functions.*;

/**
 * Spark Structured Streaming consumer that reads from Kafka and writes to DynamoDB.
 * Processes city events in real-time and stores aggregations in DynamoDB tables.
 */
public class SparkDynamoDBConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(SparkDynamoDBConsumer.class);
    
    // Configuration from environment variables
    private static final String KAFKA_BOOTSTRAP_SERVERS = 
        System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
    private static final String KAFKA_TOPIC = 
        System.getenv().getOrDefault("KAFKA_TOPIC", "city-events");
    private static final String AWS_REGION = 
        System.getenv().getOrDefault("AWS_REGION", "us-east-2");
    private static final String CHECKPOINT_LOCATION = 
        System.getenv().getOrDefault("CHECKPOINT_LOCATION", "/tmp/spark-checkpoint");
    
    // DynamoDB table names
    private static final String RAW_EVENTS_TABLE = "citystream-raw-events";
    private static final String AGGREGATIONS_TABLE = "citystream-aggregations";
    private static final String ALERTS_TABLE = "citystream-alerts";
    
    public static void main(String[] args) throws TimeoutException, StreamingQueryException {
        logger.info("Starting Spark DynamoDB Consumer");
        logger.info("Kafka Bootstrap Servers: {}", KAFKA_BOOTSTRAP_SERVERS);
        logger.info("Kafka Topic: {}", KAFKA_TOPIC);
        logger.info("AWS Region: {}", AWS_REGION);
        
        // Create Spark session
        SparkSession spark = SparkSession.builder()
            .appName("CityStream DynamoDB Consumer")
            .master("spark://spark-master:7077")
            .config("spark.sql.streaming.checkpointLocation", CHECKPOINT_LOCATION)
            .getOrCreate();
        
        // Set log level
        spark.sparkContext().setLogLevel("WARN");
        
        // Initialize DynamoDB client (used by ForeachWriter)
        logger.info("Initializing DynamoDB connection...");
        
        // Define schema for city events
        StructType schema = new StructType()
            .add("city", DataTypes.StringType)
            .add("event_type", DataTypes.StringType)
            .add("severity", DataTypes.StringType)
            .add("description", DataTypes.StringType)
            .add("timestamp", DataTypes.StringType);
        
        // Read from Kafka
        Dataset<Row> kafkaStream = spark
            .readStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS)
            .option("subscribe", KAFKA_TOPIC)
            .option("startingOffsets", "earliest")
            .option("failOnDataLoss", "false")
            .load();
        
        logger.info("Kafka stream initialized");
        
        // Parse JSON events
        Dataset<Row> events = kafkaStream
            .selectExpr("CAST(value AS STRING) as json")
            .select(from_json(col("json"), schema).as("data"))
            .select("data.*")
            .withColumn("processing_time", current_timestamp())
            .withColumn("event_id", concat(
                col("city"), 
                lit("-"), 
                col("event_type"), 
                lit("-"),
                col("timestamp")  // Use the actual timestamp string instead of unix_timestamp
            ));
        
        // Query 1: Write raw events to DynamoDB
        // FIXED: Keep both event_id and timestamp for composite key
        Dataset<Row> rawEvents = events.select(
            col("event_id"),
            col("timestamp"),  // IMPORTANT: Keep original timestamp for sort key
            col("city"),
            col("event_type"),
            col("severity"),
            col("description"),
            col("processing_time")
        );
        
        StreamingQuery rawEventsQuery = rawEvents
            .writeStream()
            .foreach(new DynamoDBWriter(RAW_EVENTS_TABLE, AWS_REGION))
            .outputMode("append")
            .option("checkpointLocation", CHECKPOINT_LOCATION + "/raw-events")
            .start();
        
        logger.info("Raw events query started");
        
        // Query 2: 5-minute windowed aggregations
        Dataset<Row> windowedAggregations = events
            .withWatermark("processing_time", "10 minutes")
            .groupBy(
                window(col("processing_time"), "5 minutes"),
                col("city"),
                col("event_type")
            )
            .agg(
                count("*").as("event_count"),
                collect_list("severity").as("severities"),
                max("processing_time").as("last_updated")
            )
            .select(
                concat(
                    col("city"),
                    lit("#"),
                    col("event_type"),
                    lit("#"),
                    date_format(col("window.start"), "yyyy-MM-dd'T'HH:mm:ss")
                ).as("partition_key"),
                col("window.start").as("window_start"),
                col("window.end").as("window_end"),
                col("city"),
                col("event_type"),
                col("event_count"),
                col("severities"),
                col("last_updated")
            );
        
        StreamingQuery aggregationsQuery = windowedAggregations
            .writeStream()
            .foreach(new DynamoDBWriter(AGGREGATIONS_TABLE, AWS_REGION))
            .outputMode("update")
            .option("checkpointLocation", CHECKPOINT_LOCATION + "/aggregations")
            .start();
        
        logger.info("Aggregations query started");
        
        // Query 3: High-severity alerts
        // FIXED: Use city and timestamp as separate fields (not concatenated partition_key)
        Dataset<Row> alerts = events
            .filter(col("severity").isin("high", "critical"))
            .select(
                col("city"),           // Partition key
                col("timestamp"),      // Sort key
                col("event_type"),
                col("severity"),
                col("description"),
                col("processing_time"),
                col("event_id")
            );
        
        StreamingQuery alertsQuery = alerts
            .writeStream()
            .foreach(new DynamoDBWriter(ALERTS_TABLE, AWS_REGION))
            .outputMode("append")
            .option("checkpointLocation", CHECKPOINT_LOCATION + "/alerts")
            .start();
        
        logger.info("Alerts query started");
        
        // Console output for monitoring
        StreamingQuery consoleQuery = events
            .groupBy("city", "event_type", "severity")
            .count()
            .writeStream()
            .outputMode("complete")
            .format("console")
            .option("truncate", false)
            .option("checkpointLocation", CHECKPOINT_LOCATION + "/console")
            .start();
        
        logger.info("Console monitoring query started");
        
        // Wait for termination
        logger.info("All streaming queries started. Waiting for termination...");
        spark.streams().awaitAnyTermination();
    }
    
    /**
     * Custom ForeachWriter to write rows to DynamoDB
     */
    static class DynamoDBWriter extends ForeachWriter<Row> {
        private final String tableName;
        private final String region;
        private transient DynamoDB dynamoDB;
        private transient Table table;
        
        public DynamoDBWriter(String tableName, String region) {
            this.tableName = tableName;
            this.region = region;
        }
        
        @Override
        public boolean open(long partitionId, long epochId) {
            try {
                AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(new DefaultAWSCredentialsProviderChain())
                    .build();
                
                dynamoDB = new DynamoDB(client);
                table = dynamoDB.getTable(tableName);
                logger.info("Successfully opened DynamoDB connection to table: {} for partition: {}", 
                    tableName, partitionId);
                return true;
            } catch (Exception e) {
                logger.error("Failed to open DynamoDB connection to table: {}", tableName, e);
                return false;
            }
        }
        
        @Override
        public void process(Row row) {
            try {
                Item item = new Item();
                
                // Add all columns to item
                for (String field : row.schema().fieldNames()) {
                    Object value = row.getAs(field);
                    if (value != null) {
                        if (value instanceof scala.collection.Seq) {
                            // Convert Scala Seq to Java List (Scala 2.12 compatible)
                            scala.collection.Seq<?> seq = (scala.collection.Seq<?>) value;
                            java.util.List<Object> list = new java.util.ArrayList<>();
                            scala.collection.Iterator<?> iterator = seq.iterator();
                            while (iterator.hasNext()) {
                                list.add(iterator.next());
                            }
                            item.withList(field, list);
                        } else if (value instanceof java.sql.Timestamp) {
                            item.withString(field, value.toString());
                        } else {
                            item.with(field, value);
                        }
                    }
                }
                
                // Add TTL (30 days from now) for raw events
                if (tableName.equals(RAW_EVENTS_TABLE)) {
                    long ttl = Instant.now().getEpochSecond() + (30 * 24 * 60 * 60);
                    item.withLong("ttl", ttl);
                }
                
                table.putItem(item);
                logger.debug("Successfully wrote item to {}: {}", tableName, item.toJSON());
                
            } catch (Exception e) {
                logger.error("Failed to write to DynamoDB table {}: {}", tableName, e.getMessage(), e);
                // Re-throw to fail the batch and trigger retry
                throw new RuntimeException("DynamoDB write failed", e);
            }
        }
        
        @Override
        public void close(Throwable errorOrNull) {
            if (errorOrNull != null) {
                logger.error("Closing DynamoDBWriter with error", errorOrNull);
            }
            if (dynamoDB != null) {
                dynamoDB.shutdown();
            }
        }
    }
}