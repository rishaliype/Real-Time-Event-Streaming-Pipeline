#!/bin/bash

set -e

echo "========================================="
echo "CityStream Complete AWS Deployment"
echo "========================================="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Get user inputs
read -p "Enter EC2 public IP: " EC2_IP
read -p "Enter path to your .pem key: " PEM_KEY
read -p "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}

echo -e "\n${BLUE}Configuration:${NC}"
echo "  EC2 IP: $EC2_IP"
echo "  AWS Region: $AWS_REGION"
echo "  PEM Key: $PEM_KEY"
read -p "Continue? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 1
fi

# Step 1: Create DynamoDB tables
#echo -e "\n${YELLOW}Step 1/6: Creating DynamoDB tables...${NC}"
#export AWS_REGION=$AWS_REGION
#bash setup-dynamodb.sh

# Step 2: Build JARs locally
echo -e "\n${YELLOW}Step 2/6: Building application JARs...${NC}"

echo "  Building producer..."
cd producer
mvn clean package -DskipTests
cd ..

echo "  Building consumer..."
cd consumer
mvn clean package -DskipTests
cd ..

echo "  Building API..."
cd api
mvn clean package -DskipTests
cd ..

echo -e "${GREEN}✓ All JARs built successfully${NC}"

# Step 3: Prepare EC2 instance
echo -e "\n${YELLOW}Step 3/6: Preparing EC2 instance...${NC}"

ssh -i "$PEM_KEY" ec2-user@"$EC2_IP" << 'ENDSSH'
# Install Docker if not already installed
if ! command -v docker &> /dev/null; then
    echo "Installing Docker..."
    sudo yum update -y
    sudo yum install -y docker
    sudo systemctl start docker
    sudo systemctl enable docker
    sudo usermod -aG docker ec2-user
fi

# Install Docker Compose if not already installed
if ! command -v docker-compose &> /dev/null; then
    echo "Installing Docker Compose..."
    sudo curl -L "https://github.com/docker/compose/releases/download/v2.23.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    sudo chmod +x /usr/local/bin/docker-compose
fi

# Create project directory
mkdir -p ~/citystream/{producer,consumer,api}

echo "✓ EC2 instance prepared"
ENDSSH

# Step 4: Copy files to EC2
echo -e "\n${YELLOW}Step 4/6: Copying files to EC2...${NC}"

# Copy docker-compose.yml
scp -i "$PEM_KEY" docker-compose.yml ec2-user@"$EC2_IP":~/citystream/

# Copy producer files
scp -i "$PEM_KEY" producer/Dockerfile ec2-user@"$EC2_IP":~/citystream/producer/
mkdir -p producer/target  # Ensure local target exists
scp -i "$PEM_KEY" -r producer/target ec2-user@"$EC2_IP":~/citystream/producer/
scp -i "$PEM_KEY" producer/src/main/resources/application-docker.yml ec2-user@"$EC2_IP":~/citystream/producer/

# Copy consumer files
mkdir -p consumer/target  # Ensure local target exists
scp -i "$PEM_KEY" -r consumer/target ec2-user@"$EC2_IP":~/citystream/consumer/

# Copy API files
scp -i "$PEM_KEY" api/Dockerfile ec2-user@"$EC2_IP":~/citystream/api/
mkdir -p api/target  # Ensure local target exists
scp -i "$PEM_KEY" -r api/target ec2-user@"$EC2_IP":~/citystream/api/
scp -i "$PEM_KEY" api/src/main/resources/application.yml ec2-user@"$EC2_IP":~/citystream/api/

echo -e "${GREEN}✓ Files copied to EC2${NC}"

# Step 5: Get AWS credentials for consumer
echo -e "\n${YELLOW}Step 5/6: AWS Credentials Setup${NC}"
echo "The Spark consumer and API need AWS credentials to access DynamoDB."
echo "You have two options:"
echo "  1) Use IAM role attached to EC2 (recommended)"
echo "  2) Provide AWS access keys manually"
echo ""
read -p "Does your EC2 instance have an IAM role with DynamoDB access? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Using IAM role credentials (no keys needed)"
    AWS_ACCESS_KEY_ID=""
    AWS_SECRET_ACCESS_KEY=""
else
    echo "Please enter AWS credentials:"
    read -p "AWS Access Key ID: " AWS_ACCESS_KEY_ID
    read -sp "AWS Secret Access Key: " AWS_SECRET_ACCESS_KEY
    echo
fi

# Step 6: Deploy on EC2
echo -e "\n${YELLOW}Step 6/6: Deploying on EC2...${NC}"

ssh -i "$PEM_KEY" ec2-user@"$EC2_IP" << ENDSSH
cd ~/citystream

# Stop existing containers
echo "Stopping existing containers..."
docker-compose down -v 2>/dev/null || true

# Create .env file with AWS credentials
cat > .env << EOF
AWS_REGION=$AWS_REGION
AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
EOF

# Build producer image
echo "Building producer image..."
cd producer
docker build -t citystream-producer .
cd ..

# Build API image
echo "Building API image..."
cd api
docker build -t citystream-api .
cd ..

# Start infrastructure services first
echo "Starting Kafka infrastructure..."
docker-compose up -d zookeeper kafka

# Wait for Kafka to be ready
echo "Waiting for Kafka to be healthy (60 seconds)..."
sleep 60

# Start producer
echo "Starting producer..."
docker-compose up -d producer

# Wait for producer
echo "Waiting for producer to start (30 seconds)..."
sleep 30

# Start Spark cluster
echo "Starting Spark cluster..."
docker-compose up -d spark-master spark-worker

# Wait for Spark
echo "Waiting for Spark cluster (30 seconds)..."
sleep 30

# Submit Spark consumer job
echo "Submitting Spark consumer to DynamoDB..."
docker exec citystream-spark-master /opt/spark/bin/spark-submit \
    --master spark://spark-master:7077 \
    --deploy-mode client \
    --driver-memory 512m \
    --executor-memory 512m \
    --conf spark.executor.cores=1 \
    --conf spark.driver.extraJavaOptions="-Daws.region=$AWS_REGION" \
    --conf spark.executor.extraJavaOptions="-Daws.region=$AWS_REGION" \
    --class com.citystream.consumer.SparkDynamoDBConsumer \
    /opt/spark-apps/consumer-1.0.0.jar > /tmp/spark-consumer.log 2>&1 &

echo "Spark consumer job submitted"

# Start API
echo "Starting API service..."
docker-compose up -d api

# Wait for API
sleep 20

# Final status check
echo ""
echo "===== Deployment Status ====="
docker-compose ps

echo ""
echo "===== Kafka Topics ====="
docker exec citystream-kafka kafka-topics --list --bootstrap-server localhost:9092

echo ""
echo "===== Producer Logs (last 10 lines) ====="
docker logs citystream-producer --tail 10

ENDSSH

# Step 7: Verification
echo -e "\n${GREEN}=========================================${NC}"
echo -e "${GREEN}Deployment Complete!${NC}"
echo -e "${GREEN}=========================================${NC}"

echo -e "\n${BLUE}Access Points:${NC}"
echo "  Producer Metrics:  http://${EC2_IP}:8080/actuator/prometheus"
echo "  Spark UI:          http://${EC2_IP}:8081"
echo "  API Health:        http://${EC2_IP}:8082/api/v1/health"
echo "  API Docs:          See below for endpoints"

echo -e "\n${BLUE}API Endpoints:${NC}"
echo "  GET  /api/v1/health"
echo "  GET  /api/v1/cities"
echo "  GET  /api/v1/events/{city}?limit=20"
echo "  GET  /api/v1/summary/{city}"
echo "  GET  /api/v1/alerts?city=NYC&hours=24"
echo "  GET  /api/v1/aggregations?city=NYC&event_type=traffic&limit=10"
echo "  GET  /api/v1/stats"

echo -e "\n${BLUE}Test Commands:${NC}"
cat << EOF
# Check API health
curl http://${EC2_IP}:8082/api/v1/health

# Get all cities
curl http://${EC2_IP}:8082/api/v1/cities

# Get NYC events
curl http://${EC2_IP}:8082/api/v1/events/NYC?limit=5

# Get high-severity alerts
curl http://${EC2_IP}:8082/api/v1/alerts?hours=1

# Get overall stats
curl http://${EC2_IP}:8082/api/v1/stats
EOF

echo -e "\n${BLUE}Monitoring Commands:${NC}"
cat << EOF
# SSH into EC2
ssh -i "$PEM_KEY" ec2-user@$EC2_IP

# View all container logs
docker-compose logs -f

# View producer logs
docker logs -f citystream-producer

# View Spark consumer logs
ssh -i "$PEM_KEY" ec2-user@$EC2_IP "docker exec citystream-spark-master cat /tmp/spark-consumer.log 2>/dev/null || echo 'No logs yet'"

# Check Kafka messages
docker exec citystream-kafka kafka-console-consumer \
  --topic city-events \
  --bootstrap-server localhost:9092 \
  --max-messages 5

# View DynamoDB data
aws dynamodb scan --table-name citystream-alerts --region $AWS_REGION --limit 5
EOF

echo -e "\n${YELLOW}Wait 2-3 minutes for data to flow through the pipeline, then test the API!${NC}"

echo -e "\n${RED}IMPORTANT: To avoid AWS charges, run cleanup when done:${NC}"
echo "  bash cleanup-aws.sh"