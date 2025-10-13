#!/bin/bash

# CityStream API Test Script
echo "================================"
echo "CityStream API Endpoint Tests"
echo "================================"

# Health Check
echo -e "\n=== Health Check ==="
curl -s http://localhost:8082/api/v1/health | python3 -m json.tool

# Statistics
echo -e "\n=== Statistics ===" 
curl -s http://localhost:8082/api/v1/stats | python3 -m json.tool

# All Cities
echo -e "\n=== All Cities ===" 
curl -s http://localhost:8082/api/v1/cities | python3 -m json.tool

# All Alerts
echo -e "\n=== All Alerts (Last 24 hours) ===" 
curl -s http://localhost:8082/api/v1/alerts | python3 -m json.tool

# City-Specific Alerts
echo -e "\n=== Alerts for Chicago (Last 2 hours) ===" 
curl -s "http://localhost:8082/api/v1/alerts?city=Chicago&hours=2" | python3 -m json.tool

echo -e "\n=== Alerts for Seattle (Last 12 hours) ===" 
curl -s "http://localhost:8082/api/v1/alerts?city=Seattle&hours=12" | python3 -m json.tool

# Events by City
echo -e "\n=== Events for SF (Limit 3) ==="
curl -s "http://localhost:8082/api/v1/events/SF?limit=3" | python3 -m json.tool

echo -e "\n=== Events for NYC (Limit 5) ==="
curl -s "http://localhost:8082/api/v1/events/NYC?limit=5" | python3 -m json.tool

# City Summaries
echo -e "\n=== Summary for LA ===" 
curl -s http://localhost:8082/api/v1/summary/LA | python3 -m json.tool

echo -e "\n=== Summary for Boston ===" 
curl -s http://localhost:8082/api/v1/summary/Boston | python3 -m json.tool

# Aggregations
echo -e "\n=== Aggregations: NYC Traffic (Limit 5) ===" 
curl -s "http://localhost:8082/api/v1/aggregations?city=NYC&eventType=traffic&limit=5" | python3 -m json.tool

echo -e "\n=== Aggregations: SF Weather (Limit 3) ===" 
curl -s "http://localhost:8082/api/v1/aggregations?city=SF&eventType=weather&limit=3" | python3 -m json.tool

echo -e "\n=== Aggregations: Seattle Construction (Limit 5) ===" 
curl -s "http://localhost:8082/api/v1/aggregations?city=Seattle&eventType=construction&limit=5" | python3 -m json.tool

echo -e "\n=== Aggregations: Chicago Incident (Limit 5) ===" 
curl -s "http://localhost:8082/api/v1/aggregations?city=Chicago&eventType=incident&limit=5" | python3 -m json.tool

echo -e "\n================================"
echo "All tests completed!"
echo "================================"