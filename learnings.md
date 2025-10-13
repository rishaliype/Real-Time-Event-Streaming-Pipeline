# CityStream Real-Time Event Processing Pipeline

## Project Overview

CityStream is a distributed real-time event processing system that demonstrates a complete data engineering pipeline using modern big data technologies. The system simulates city events (traffic, weather, incidents, construction) and processes them through Kafka, Spark Structured Streaming, DynamoDB, and exposes the data via a REST API.

## Architecture

```
Producer (Spring Boot) 
    ↓ (generates events)
Kafka (message queue)
    ↓ (streams events)
Spark Structured Streaming (processing)
    ↓ (writes to)
DynamoDB (storage: raw events, aggregations, alerts)
    ↓ (queries)
REST API (Spring Boot)
    ↓ (serves)
End Users
```

## Tech Stack

- **Message Queue**: Apache Kafka + Zookeeper
- **Stream Processing**: Apache Spark 3.4.1 (Structured Streaming)
- **Storage**: AWS DynamoDB
- **Producer**: Spring Boot (Java)
- **Consumer**: Spark with custom ForeachWriter
- **API**: Spring Boot REST API
- **Infrastructure**: Docker Compose, AWS EC2
- **Region**: us-east-2 (Ohio)

## Key Components

### 1. Producer (`citystream-producer`)
- Generates synthetic city events every 5 seconds
- Event types: traffic, weather, incident, construction
- Cities: NYC, LA, Chicago, SF, Boston, Seattle
- Severity levels: low, medium, high, critical
- Publishes to Kafka topic `city-events`

### 2. Kafka Cluster
- **Zookeeper**: Manages Kafka cluster coordination
- **Kafka Broker**: Single broker setup for demo
- Topic: `city-events` (auto-created)
- Retention: 24 hours

### 3. Spark Consumer (`citystream-consumer`)
Processes three streaming queries simultaneously:

#### Query 1: Raw Events Storage
- Reads from Kafka, parses JSON events
- Writes to `citystream-raw-events` table
- Adds TTL (30 days) for automatic cleanup
- Keys: `event_id` (partition), `timestamp` (sort)

#### Query 2: Windowed Aggregations
- 5-minute tumbling windows
- Groups by: window, city, event_type
- Aggregates: count, severity list
- Writes to `citystream-aggregations` table
- Uses watermark (10 minutes) for late data handling

#### Query 3: High-Severity Alerts
- Filters events with severity = "high" or "critical"
- Writes to `citystream-alerts` table
- Keys: `city` (partition), `timestamp` (sort)
- Real-time alerting for critical situations

#### Query 4: Console Monitoring
- Live dashboard in Spark console
- Shows aggregated counts by city/type/severity

### 4. DynamoDB Tables

#### `citystream-raw-events`
- **Partition Key**: `event_id` (String)
- **Sort Key**: `timestamp` (String)
- **TTL**: 30 days
- **Purpose**: Store all raw events

#### `citystream-aggregations`
- **Partition Key**: `partition_key` (String) - format: `city#event_type#window_start`
- **Attributes**: window_start, window_end, event_count, severities, city, event_type
- **Purpose**: Pre-computed analytics

#### `citystream-alerts`
- **Partition Key**: `city` (String)
- **Sort Key**: `timestamp` (String)
- **Purpose**: Quick access to high-priority alerts by city

### 5. REST API (`citystream-api`)

Endpoints:
- `GET /api/v1/health` - Health check
- `GET /api/v1/events/{city}?limit=20` - Recent events for a city
- `GET /api/v1/summary/{city}` - Aggregated summary
- `GET /api/v1/alerts?city={city}&hours=24` - Recent alerts
- `GET /api/v1/cities` - List all cities with event counts
- `GET /api/v1/aggregations?city={city}&event_type={type}&limit=10` - Windowed aggregations
- `GET /api/v1/stats` - Overall statistics

## AWS Commands Reference

### SSH Access
```bash
# Connect to EC2 instance
ssh -i ~/.ssh/citystream-deployment-key.pem ec2-user@<EC2_PUBLIC_IP>

# Example with actual IP
ssh -i ~/.ssh/citystream-deployment-key.pem ec2-user@3.131.91.181
```

### File Transfer (SCP)

#### Upload Consumer JAR
```bash
# Upload consumer JAR to target directory
scp -i ~/.ssh/citystream-deployment-key.pem \
    consumer/target/consumer-1.0.0.jar \
    ec2-user@<EC2_IP>:~/citystream/consumer/target/

# Alternative: Upload to jars directory
scp -i ~/.ssh/citystream-deployment-key.pem \
    consumer/target/consumer-1.0.0.jar \
    ec2-user@<EC2_IP>:~/citystream/jars/
```

#### Upload Producer JAR
```bash
# Upload producer JAR
scp -i ~/.ssh/citystream-deployment-key.pem \
    producer/target/producer-1.0.0.jar \
    ec2-user@<EC2_IP>:~/citystream/producer/target/
```

#### Upload API JAR
```bash
# Upload API JAR
scp -i ~/.ssh/citystream-deployment-key.pem \
    api/target/api-1.0.0.jar \
    ec2-user@<EC2_IP>:~/citystream/api/target/
```

#### Upload Docker Compose File
```bash
# Upload docker-compose.yml
scp -i ~/.ssh/citystream-deployment-key.pem \
    docker-compose.yml \
    ec2-user@<EC2_IP>:~/citystream/
```

#### Upload Multiple Files
```bash
# Upload entire directory
scp -i ~/.ssh/citystream-deployment-key.pem -r \
    ./citystream \
    ec2-user@<EC2_IP>:~/
```

### Docker Commands

#### Start Services
```bash
# Start all services
docker-compose up -d

# Start with logs
docker-compose up

# Start specific service
docker-compose up -d kafka
```

#### View Logs
```bash
# View all logs
docker-compose logs

# Follow logs
docker-compose logs -f

# Logs for specific service
docker logs citystream-producer --tail 20
docker logs citystream-kafka --tail 50
docker logs citystream-spark-master --tail 100
```

#### Service Management
```bash
# Check service status
docker-compose ps

# Restart service
docker-compose restart producer

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

### Kafka Commands

#### Consumer Testing
```bash
# Consume messages from beginning
docker exec -it citystream-kafka kafka-console-consumer \
    --topic city-events \
    --bootstrap-server localhost:9092 \
    --from-beginning

# Consume with max messages
docker exec -it citystream-kafka kafka-console-consumer \
    --topic city-events \
    --bootstrap-server localhost:9092 \
    --max-messages 5
```

#### Topic Management
```bash
# List topics
docker exec -it citystream-kafka kafka-topics \
    --list \
    --bootstrap-server localhost:9092

# Describe topic
docker exec -it citystream-kafka kafka-topics \
    --describe \
    --topic city-events \
    --bootstrap-server localhost:9092

# Check topic health
docker exec -it citystream-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --describe \
    --topic city-events
```

### Spark Commands

#### Submit Spark Job
```bash
# Submit Spark Structured Streaming job
docker exec -it citystream-spark-master \
    /opt/spark/bin/spark-submit \
    --master spark://spark-master:7077 \
    --class com.citystream.consumer.SparkDynamoDBConsumer \
    --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.4.1 \
    /opt/spark-apps/consumer-1.0.0.jar
```

#### View Spark Console Output
```bash
# View streaming query output (batches)
docker exec citystream-spark-master bash -c \
    "ps aux | grep SparkSubmit | grep -v grep | awk '{print \$2}' | head -1 | xargs -I {} cat /proc/{}/fd/1"
```

#### Check Spark UI
```bash
# Access Spark Master UI
http://<EC2_IP>:8081

# Check running applications
http://<EC2_IP>:8081/#/apps/running
```

### API Testing Commands

#### Health Check
```bash
curl http://localhost:8082/api/v1/health
```

#### Get Events by City
```bash
# Get events for SF (limit 3)
curl -s "http://localhost:8082/api/v1/events/SF?limit=3" | python3 -m json.tool
```

#### Get City Summary
```bash
# Summary for LA
curl -s http://localhost:8082/api/v1/summary/LA | python3 -m json.tool
```

#### Get Alerts
```bash
# Alerts for Chicago (last 2 hours)
curl -s "http://localhost:8082/api/v1/alerts?city=Chicago&hours=2" | python3 -m json.tool

# All alerts (last 24 hours)
curl -s http://localhost:8082/api/v1/alerts | python3 -m json.tool
```

#### Get Aggregations
```bash
# NYC traffic aggregations (limit 5)
curl -s "http://localhost:8082/api/v1/aggregations?city=NYC&event_type=traffic&limit=5" | python3 -m json.tool
```

#### Get Statistics
```bash
# Overall stats
curl -s http://localhost:8082/api/v1/stats | python3 -m json.tool
```

#### Get All Cities
```bash
# List cities with event counts
curl -s http://localhost:8082/api/v1/cities | python3 -m json.tool
```

### AWS DynamoDB Commands

#### Using AWS CLI (from local machine)
```bash
# List tables
aws dynamodb list-tables --region us-east-2

# Describe table
aws dynamodb describe-table \
    --table-name citystream-raw-events \
    --region us-east-2

# Scan table (get items)
aws dynamodb scan \
    --table-name citystream-alerts \
    --region us-east-2 \
    --max-items 10

# Query by partition key
aws dynamodb query \
    --table-name citystream-alerts \
    --key-condition-expression "city = :city" \
    --expression-attribute-values '{":city":{"S":"Chicago"}}' \
    --region us-east-2

# Get item count
aws dynamodb scan \
    --table-name citystream-raw-events \
    --select "COUNT" \
    --region us-east-2
```

### System Monitoring

#### Check EC2 Instance Resources
```bash
# CPU and memory usage
top

# Disk usage
df -h

# Docker container stats
docker stats

# Network connections
netstat -tuln
```

#### Monitor Application Health
```bash
# Check if producer is sending events
docker logs citystream-producer --tail 20 --follow

# Check Kafka consumer lag
docker exec citystream-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe \
    --group spark-kafka-streaming

# Check API health
curl http://localhost:8082/api/v1/health
```

## Deployment Steps

### 1. Setup AWS Resources
```bash
# Create DynamoDB tables (run locally)
cd citystream
./setup-dynamodb.sh

# Launch EC2 instance (t2.large or larger)
# Open security group ports: 22, 8080, 8081, 8082, 9092, 2181
```

### 2. Build JARs Locally
```bash
# Build producer
cd producer
mvn clean package

# Build consumer
cd ../consumer
mvn clean package

# Build API
cd ../api
mvn clean package
```

### 3. Deploy to EC2
```bash
# Create directory structure
ssh -i ~/.ssh/citystream-deployment-key.pem ec2-user@<EC2_IP> << 'EOF'
mkdir -p ~/citystream/{producer,consumer,api}/{target,}
mkdir -p ~/citystream/jars
EOF

# Upload files
scp -i ~/.ssh/citystream-deployment-key.pem docker-compose.yml ec2-user@<EC2_IP>:~/citystream/
scp -i ~/.ssh/citystream-deployment-key.pem producer/target/producer-1.0.0.jar ec2-user@<EC2_IP>:~/citystream/producer/target/
scp -i ~/.ssh/citystream-deployment-key.pem consumer/target/consumer-1.0.0.jar ec2-user@<EC2_IP>:~/citystream/consumer/target/
scp -i ~/.ssh/citystream-deployment-key.pem api/target/api-1.0.0.jar ec2-user@<EC2_IP>:~/citystream/api/target/
```

### 4. Start Services
```bash
# SSH into EC2
ssh -i ~/.ssh/citystream-deployment-key.pem ec2-user@<EC2_IP>

# Set environment variables
export AWS_ACCESS_KEY_ID=<your-key>
export AWS_SECRET_ACCESS_KEY=<your-secret>
export AWS_REGION=us-east-2

# Start infrastructure
cd ~/citystream
docker-compose up -d zookeeper kafka spark-master spark-worker

# Wait for services to be healthy (30-60 seconds)
docker-compose ps

# Start producer
docker-compose up -d producer

# Start API
docker-compose up -d api

# Submit Spark job (manual)
docker exec -it citystream-spark-master \
    /opt/spark/bin/spark-submit \
    --master spark://spark-master:7077 \
    --class com.citystream.consumer.SparkDynamoDBConsumer \
    --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.4.1 \
    /opt/spark-apps/consumer-1.0.0.jar
```

## Troubleshooting

### Common Issues

#### 1. Kafka Not Receiving Messages
```bash
# Check producer logs
docker logs citystream-producer --tail 50

# Check Kafka broker
docker logs citystream-kafka --tail 100

# Test Kafka connectivity
docker exec -it citystream-kafka kafka-topics --list --bootstrap-server localhost:9092
```

#### 2. Spark Job Failing
```bash
# Check Spark logs
docker logs citystream-spark-master --tail 200

# Check if JAR exists
docker exec citystream-spark-master ls -la /opt/spark-apps/

# Verify AWS credentials
docker exec citystream-spark-master env | grep AWS
```

#### 3. API Not Responding
```bash
# Check API logs
docker logs citystream-api --tail 50

# Test DynamoDB connectivity
aws dynamodb list-tables --region us-east-2

# Check port binding
netstat -tuln | grep 8082
```

#### 4. DynamoDB Access Issues
```bash
# Verify IAM permissions
aws sts get-caller-identity

# Test table access
aws dynamodb scan --table-name citystream-alerts --max-items 1 --region us-east-2

# Check security group rules (if running on EC2)
```

### Debug Commands
```bash
# View container resource usage
docker stats

# Execute command in container
docker exec -it citystream-producer bash

# View all container logs
docker-compose logs --tail=100

# Restart problematic service
docker-compose restart spark-master
```

## Key Learning Points

### 1. Stream Processing Patterns
- **Tumbling windows**: Fixed-size, non-overlapping time windows
- **Watermarking**: Handling late-arriving data
- **Stateful streaming**: Maintaining aggregations across micro-batches
- **Fault tolerance**: Checkpointing for exactly-once processing

### 2. Kafka Integration
- Producer with proper serialization
- Consumer group management
- Topic partitioning strategies
- Offset management

### 3. Spark Structured Streaming
- DataFrame/Dataset API for streaming
- ForeachWriter for custom sinks
- Multiple concurrent queries
- Output modes: append, update, complete

### 4. DynamoDB Design
- Choosing partition and sort keys
- Query patterns (scan vs query vs get-item)
- TTL for automatic data expiration
- Efficient data modeling for time-series

### 5. Docker Orchestration
- Multi-container applications
- Service dependencies
- Health checks
- Volume management
- Network configuration

### 6. AWS Deployment
- EC2 instance sizing for Spark workloads
- Security group configuration
- IAM roles and permissions
- Cross-service integration (EC2 + DynamoDB)

## Performance Optimization Tips

1. **Kafka**: Increase partitions for parallel processing
2. **Spark**: Tune executor memory and cores based on workload
3. **DynamoDB**: Use provisioned capacity or on-demand based on throughput
4. **Network**: Use VPC endpoints for DynamoDB to reduce latency
5. **Monitoring**: Enable CloudWatch metrics for all AWS services

## Cleanup

```bash
# Stop all services
docker-compose down -v

# Delete DynamoDB tables
./cleanup-aws.sh

# Terminate EC2 instance (from AWS Console)
```

---

**Instance Type**: t2.large (8GB RAM minimum recommended)