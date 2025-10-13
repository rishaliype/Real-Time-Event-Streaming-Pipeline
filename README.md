# CityStream - Real-Time Event Processing Pipeline

A production-grade distributed stream processing system built with Kafka, Spark Structured Streaming, and AWS DynamoDB.

## 🎯 Project Overview

CityStream simulates and processes real-time city events (traffic, weather, incidents, construction) through a complete data engineering pipeline, demonstrating skills in:
- Distributed stream processing
- Message queue architecture
- NoSQL database design
- REST API development
- Docker orchestration
- AWS cloud integration

## 🏗️ Architecture

```
Producer (Spring Boot)     → Generates events every 5s
        ↓
Kafka + Zookeeper         → Message buffering & streaming
        ↓
Spark Streaming           → 4 concurrent queries processing
        ↓
DynamoDB (3 tables)       → Storage: raw events, aggregations, alerts
        ↓
REST API (Spring Boot)    → 7 endpoints for data access
```

## 🛠️ Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Stream Processing | Apache Spark | 3.4.1 |
| Message Queue | Apache Kafka | 7.5.0 |
| Database | AWS DynamoDB | N/A |
| Producer & API | Spring Boot | 3.1.x |
| Language | Java | 17 |
| Build Tool | Maven | 3.8+ |
| Container | Docker + Compose | 24.x |
| Cloud | AWS EC2 | t2.large |

## 📊 Data Flow

1. **Event Generation**: Producer creates events every 5 seconds
2. **Kafka Buffering**: Events published to `city-events` topic
3. **Spark Processing**: 4 concurrent streaming queries:
   - Raw events writer
   - 5-minute windowed aggregations
   - High-severity alerts filter
   - Console monitoring output
4. **DynamoDB Storage**: Data persisted in 3 optimized tables
5. **REST API**: HTTP endpoints expose data for consumption

## 🚀 Quick Start

### Prerequisites
- AWS account with DynamoDB access
- Java 17+, Maven 3.8+
- Docker Desktop
- AWS CLI configured

### Deploy in 5 Steps

```bash
# 1. Create DynamoDB tables
./setup-dynamodb.sh

# 2. Build JARs
mvn clean package

# 3. Launch EC2 and upload files (see DEPLOYMENT.md)

# 4. Start services
docker-compose up -d

# 5. Submit Spark job
./submit_spark_job.sh

# Verify
curl http://localhost:8082/api/v1/stats
```

## 📈 Results After 1 Hour

- **Raw Events**: 720+ items (1 per 5 seconds)
- **Alerts**: 290+ high/critical severity events
- **Aggregations**: 70+ windowed summaries
- **API Response Time**: < 100ms average
- **Spark Processing**: < 1 second per micro-batch

## 🔧 Key Features

### Stream Processing
- ✅ Real-time event processing with Spark Structured Streaming
- ✅ Windowed aggregations (5-minute tumbling windows)
- ✅ Watermarking for late data handling (10-minute threshold)
- ✅ Stateful operations with checkpointing
- ✅ Multiple concurrent queries in single application

### Data Storage
- ✅ Optimized DynamoDB schema design
- ✅ Composite keys for efficient querying
- ✅ TTL for automatic data expiration (30 days)
- ✅ Three specialized tables for different access patterns

### REST API
- ✅ 7 RESTful endpoints
- ✅ Health checks and monitoring
- ✅ City-based filtering
- ✅ Time-range queries
- ✅ Pre-computed aggregations

### DevOps
- ✅ Docker Compose orchestration
- ✅ Multi-container application
- ✅ Health checks for all services
- ✅ AWS cloud deployment
- ✅ Monitoring and logging

## 📝 API Endpoints

| Endpoint | Description | Example |
|----------|-------------|---------|
| `GET /api/v1/health` | Health check | `curl /api/v1/health` |
| `GET /api/v1/stats` | Overall statistics | `curl /api/v1/stats` |
| `GET /api/v1/cities` | List all cities | `curl /api/v1/cities` |
| `GET /api/v1/events/{city}` | Events by city | `curl /api/v1/events/NYC?limit=5` |
| `GET /api/v1/summary/{city}` | City summary | `curl /api/v1/summary/Boston` |
| `GET /api/v1/alerts` | High-severity alerts | `curl /api/v1/alerts?hours=24` |
| `GET /api/v1/aggregations` | Windowed data | `curl /api/v1/aggregations?city=NYC&eventType=traffic` |

## 🎓 Key Learnings

### Problem-Solving Highlights

**1. DynamoDB Schema Mismatch**
- **Problem**: ValidationException - Missing keys
- **Solution**: Separated composite keys (partition + sort) into individual fields
- **Learning**: NoSQL schema design must match query patterns

**2. Null Primary Keys**
- **Problem**: `unix_timestamp()` returning null
- **Solution**: Used timestamp string directly instead of conversion
- **Learning**: Always validate primary key fields are non-null

**3. Credentials Propagation**
- **Problem**: Spark executors couldn't access AWS
- **Solution**: Passed credentials via Spark configuration to executors
- **Learning**: Distributed systems require explicit credential propagation

**4. Checkpoint Permissions**
- **Problem**: Spark couldn't create checkpoint directories
- **Solution**: Created directories with proper ownership before job submission
- **Learning**: File permissions critical in containerized environments

**5. API Parameter Convention**
- **Problem**: HTTP 400 errors on aggregations endpoint
- **Solution**: Used camelCase (`eventType`) instead of snake_case
- **Learning**: Maintain consistent naming conventions across stack

## 💰 Cost Estimate

**Monthly AWS Costs (us-east-2)**:
- EC2 t2.large: ~$67/month (24/7)
- DynamoDB: ~$1/month (on-demand, low volume)
- Data Transfer: < $1/month
- **Total**: ~$68/month

**Cost Optimization**:
- Use Spot instances: Save 70%
- Stop when not in use: Pay only runtime
- Reserved instances: Save 40% for 1-year

## 🧪 Testing

```bash
# Test producer
curl http://localhost:8080/metrics

# Test Kafka
docker exec citystream-kafka kafka-console-consumer \
  --topic city-events --bootstrap-server localhost:9092 --max-messages 5

# Test Spark console output
docker logs citystream-spark-master --tail 50

# Test DynamoDB
aws dynamodb scan --table-name citystream-raw-events --region us-east-2 --max-items 3

# Test API
./test-api.sh
```

## 🔍 Monitoring

```bash
# View all service logs
docker-compose logs -f

# Monitor Spark UI
open http://<EC2_IP>:8081

# Check DynamoDB metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/DynamoDB \
  --metric-name ConsumedReadCapacityUnits \
  --dimensions Name=TableName,Value=citystream-raw-events \
  --start-time 2025-10-13T00:00:00Z \
  --end-time 2025-10-13T23:59:59Z \
  --period 3600 \
  --statistics Sum
```

## 🧹 Cleanup

```bash
# Stop all services
docker-compose down -v

# Delete DynamoDB tables
./cleanup-aws.sh

# Terminate EC2 instance
# (Go to AWS Console → EC2 → Terminate)