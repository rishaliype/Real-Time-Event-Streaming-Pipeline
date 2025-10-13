#!/bin/bash

set -e

echo "========================================="
echo "CityStream AWS Cleanup"
echo "========================================="

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${RED}WARNING: This will delete all resources!${NC}"
echo "  • Stop all Docker containers on EC2"
echo "  • Delete all DynamoDB tables"
echo "  • (Optional) Terminate EC2 instance"
echo ""
read -p "Continue? (yes/no) " -r
echo
if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
    echo "Cleanup cancelled"
    exit 0
fi

# Get configuration
read -p "Enter EC2 public IP: " EC2_IP
read -p "Enter path to your .pem key: " PEM_KEY
read -p "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}

# Step 1: Stop Docker containers on EC2
echo -e "\n${YELLOW}Step 1/3: Stopping Docker containers on EC2...${NC}"
ssh -i "$PEM_KEY" ec2-user@"$EC2_IP" << 'ENDSSH'
cd ~/citystream
docker-compose down -v
docker system prune -af
echo "✓ All containers stopped and cleaned"
ENDSSH

# Step 2: Delete DynamoDB tables
echo -e "\n${YELLOW}Step 2/3: Deleting DynamoDB tables...${NC}"

echo "Deleting citystream-raw-events..."
aws dynamodb delete-table \
    --table-name citystream-raw-events \
    --region $AWS_REGION 2>/dev/null || echo "  Table already deleted or doesn't exist"

echo "Deleting citystream-aggregations..."
aws dynamodb delete-table \
    --table-name citystream-aggregations \
    --region $AWS_REGION 2>/dev/null || echo "  Table already deleted or doesn't exist"

echo "Deleting citystream-alerts..."
aws dynamodb delete-table \
    --table-name citystream-alerts \
    --region $AWS_REGION 2>/dev/null || echo "  Table already deleted or doesn't exist"

echo -e "${GREEN}✓ All DynamoDB tables deleted${NC}"

# Step 3: Optional - Stop EC2 instance
echo -e "\n${YELLOW}Step 3/3: EC2 Instance${NC}"
echo "Do you want to:"
echo "  1) Stop EC2 instance (can restart later)"
echo "  2) Terminate EC2 instance (permanent deletion)"
echo "  3) Leave EC2 running"
read -p "Enter choice (1/2/3): " choice

case $choice in
    1)
        read -p "Enter EC2 Instance ID (i-xxxxx): " INSTANCE_ID
        echo "Stopping EC2 instance..."
        aws ec2 stop-instances --instance-ids $INSTANCE_ID --region $AWS_REGION
        echo -e "${GREEN}✓ EC2 instance stopped${NC}"
        echo "To restart: aws ec2 start-instances --instance-ids $INSTANCE_ID --region $AWS_REGION"
        ;;
    2)
        read -p "Enter EC2 Instance ID (i-xxxxx): " INSTANCE_ID
        echo -e "${RED}WARNING: This will permanently delete the instance!${NC}"
        read -p "Are you sure? (yes/no) " -r
        if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
            echo "Terminating EC2 instance..."
            aws ec2 terminate-instances --instance-ids $INSTANCE_ID --region $AWS_REGION
            echo -e "${GREEN}✓ EC2 instance terminated${NC}"
        fi
        ;;
    3)
        echo "EC2 instance left running"
        ;;
esac

echo -e "\n${GREEN}=========================================${NC}"
echo -e "${GREEN}Cleanup Complete!${NC}"
echo -e "${GREEN}=========================================${NC}"

echo -e "\n${YELLOW}Remaining Resources to Check:${NC}"
echo "  • EC2 instance (if not stopped/terminated)"
echo "  • EBS volumes attached to EC2"
echo "  • Security Groups (will remain)"
echo "  • Key Pairs (will remain)"

echo -e "\nTo verify all resources are deleted:"
echo "  aws dynamodb list-tables --region $AWS_REGION"
echo "  aws ec2 describe-instances --region $AWS_REGION"