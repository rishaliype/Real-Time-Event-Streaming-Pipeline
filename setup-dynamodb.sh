#!/bin/bash

set -e

echo "======================================"
echo "Creating DynamoDB Tables for CityStream"
echo "======================================"

AWS_REGION=${AWS_REGION:-us-east-1}

echo -e "\nUsing AWS Region: $AWS_REGION"

# Table 1: Raw Events (partition key: event_id)
echo -e "\n[1/3] Creating citystream-raw-events table..."
aws dynamodb create-table \
    --table-name citystream-raw-events \
    --attribute-definitions \
        AttributeName=event_id,AttributeType=S \
        AttributeName=timestamp,AttributeType=S \
    --key-schema \
        AttributeName=event_id,KeyType=HASH \
        AttributeName=timestamp,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region $AWS_REGION \
    --tags Key=Project,Value=CityStream Key=Environment,Value=Development \
    --stream-specification StreamEnabled=false

# Enable TTL for automatic deletion after 30 days
aws dynamodb update-time-to-live \
    --table-name citystream-raw-events \
    --time-to-live-specification "Enabled=true, AttributeName=ttl" \
    --region $AWS_REGION

echo "✓ citystream-raw-events created with TTL enabled"

# Table 2: Aggregations (partition key: city#event_type#window_start)
echo -e "\n[2/3] Creating citystream-aggregations table..."
aws dynamodb create-table \
    --table-name citystream-aggregations \
    --attribute-definitions \
        AttributeName=partition_key,AttributeType=S \
    --key-schema \
        AttributeName=partition_key,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region $AWS_REGION \
    --tags Key=Project,Value=CityStream Key=Environment,Value=Development

echo "✓ citystream-aggregations created"

# Table 3: High-Severity Alerts (partition key: city, sort key: timestamp)
echo -e "\n[3/3] Creating citystream-alerts table..."
aws dynamodb create-table \
    --table-name citystream-alerts \
    --attribute-definitions \
        AttributeName=city,AttributeType=S \
        AttributeName=timestamp,AttributeType=S \
    --key-schema \
        AttributeName=city,KeyType=HASH \
        AttributeName=timestamp,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region $AWS_REGION \
    --tags Key=Project,Value=CityStream Key=Environment,Value=Development \
    --global-secondary-indexes \
        "[
            {
                \"IndexName\": \"severity-timestamp-index\",
                \"KeySchema\": [
                    {\"AttributeName\":\"severity\",\"KeyType\":\"HASH\"},
                    {\"AttributeName\":\"timestamp\",\"KeyType\":\"RANGE\"}
                ],
                \"Projection\": {\"ProjectionType\":\"ALL\"},
                \"ProvisionedThroughput\": {
                    \"ReadCapacityUnits\": 5,
                    \"WriteCapacityUnits\": 5
                }
            }
        ]" || true

echo "✓ citystream-alerts created"

# Wait for tables to become active
echo -e "\nWaiting for tables to become ACTIVE..."
aws dynamodb wait table-exists --table-name citystream-raw-events --region $AWS_REGION
aws dynamodb wait table-exists --table-name citystream-aggregations --region $AWS_REGION
aws dynamodb wait table-exists --table-name citystream-alerts --region $AWS_REGION

echo -e "\n======================================"
echo "✓ All DynamoDB tables created successfully!"
echo "======================================"

# Display table information
echo -e "\nTable Details:"
aws dynamodb list-tables --region $AWS_REGION --output table

echo -e "\nEstimated Monthly Cost (Free Tier):"
echo "  • First 25 GB storage: FREE"
echo "  • First 25 WCU + 25 RCU: FREE"
echo "  • On-demand pricing after free tier: ~$1.25/million writes, ~$0.25/million reads"
echo "  • Expected cost for this demo: < $1/day"

echo -e "\nTo delete tables later:"
echo "  aws dynamodb delete-table --table-name citystream-raw-events --region $AWS_REGION"
echo "  aws dynamodb delete-table --table-name citystream-aggregations --region $AWS_REGION"
echo "  aws dynamodb delete-table --table-name citystream-alerts --region $AWS_REGION"