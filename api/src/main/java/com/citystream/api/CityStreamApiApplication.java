package com.citystream.api;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@SpringBootApplication
@RestController
@RequestMapping("/api/v1")
public class CityStreamApiApplication {

    private final DynamoDB dynamoDB;
    private final Table rawEventsTable;
    private final Table aggregationsTable;
    private final Table alertsTable;

    public CityStreamApiApplication() {
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard()
            .withRegion(region)
            .withCredentials(new DefaultAWSCredentialsProviderChain())
            .build();
        
        this.dynamoDB = new DynamoDB(client);
        this.rawEventsTable = dynamoDB.getTable("citystream-raw-events");
        this.aggregationsTable = dynamoDB.getTable("citystream-aggregations");
        this.alertsTable = dynamoDB.getTable("citystream-alerts");
    }

    public static void main(String[] args) {
        SpringApplication.run(CityStreamApiApplication.class, args);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());
        health.put("service", "CityStream API");
        return ResponseEntity.ok(health);
    }

    /**
     * Get recent events for a specific city
     * GET /api/v1/events/{city}?limit=20
     */
    @GetMapping("/events/{city}")
    public ResponseEntity<Map<String, Object>> getEventsByCity(
            @PathVariable String city,
            @RequestParam(defaultValue = "20") int limit) {
        
        try {
            // Scan with filter (not ideal for production, but works for demo)
            ScanSpec scanSpec = new ScanSpec()
                .withFilterExpression("city = :city")
                .withValueMap(new ValueMap().withString(":city", city))
                .withMaxResultSize(limit);
            
            ItemCollection<ScanOutcome> items = rawEventsTable.scan(scanSpec);
            
            List<Map<String, Object>> events = StreamSupport
                .stream(items.spliterator(), false)
                .limit(limit)
                .map(this::itemToMap)
                .sorted((a, b) -> 
                    ((String)b.get("timestamp")).compareTo((String)a.get("timestamp")))
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("city", city);
            response.put("count", events.size());
            response.put("events", events);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get aggregated summary for a city
     * GET /api/v1/summary/{city}
     */
    @GetMapping("/summary/{city}")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String city) {
        try {
            // Scan aggregations table for this city
            ScanSpec scanSpec = new ScanSpec()
                .withFilterExpression("city = :city")
                .withValueMap(new ValueMap().withString(":city", city));
            
            ItemCollection<ScanOutcome> items = aggregationsTable.scan(scanSpec);
            
            Map<String, Integer> eventTypeCounts = new HashMap<>();
            int totalEvents = 0;
            
            for (Item item : items) {
                String eventType = item.getString("event_type");
                int count = item.getInt("event_count");
                eventTypeCounts.put(eventType, 
                    eventTypeCounts.getOrDefault(eventType, 0) + count);
                totalEvents += count;
            }
            
            Map<String, Object> summary = new HashMap<>();
            summary.put("city", city);
            summary.put("total_events", totalEvents);
            summary.put("event_type_breakdown", eventTypeCounts);
            summary.put("generated_at", Instant.now().toString());
            
            return ResponseEntity.ok(summary);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get high-severity alerts
     * GET /api/v1/alerts?city=NYC&hours=24
     */
    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> getAlerts(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "24") int hours) {
        
        try {
            List<Map<String, Object>> alerts = new ArrayList<>();
            String cutoffTime = Instant.now()
                .minus(hours, ChronoUnit.HOURS)
                .toString();
            
            if (city != null && !city.isEmpty()) {
                // Query specific city
                QuerySpec querySpec = new QuerySpec()
                    .withHashKey("city", city)
                    .withRangeKeyCondition(
                        new RangeKeyCondition("timestamp").ge(cutoffTime)
                    )
                    .withScanIndexForward(false)
                    .withMaxResultSize(50);
                
                ItemCollection<QueryOutcome> items = alertsTable.query(querySpec);
                
                for (Item item : items) {
                    alerts.add(itemToMap(item));
                }
            } else {
                // Scan all cities (for demo purposes)
                ScanSpec scanSpec = new ScanSpec()
                    .withFilterExpression("#ts >= :cutoff")
                    .withNameMap(Map.of("#ts", "timestamp"))
                    .withValueMap(new ValueMap().withString(":cutoff", cutoffTime))
                    .withMaxResultSize(50);
                
                ItemCollection<ScanOutcome> items = alertsTable.scan(scanSpec);
                
                for (Item item : items) {
                    alerts.add(itemToMap(item));
                }
            }
            
            // Sort by timestamp descending
            alerts.sort((a, b) -> 
                ((String)b.get("timestamp")).compareTo((String)a.get("timestamp")));
            
            Map<String, Object> response = new HashMap<>();
            response.put("count", alerts.size());
            response.put("time_range_hours", hours);
            if (city != null) response.put("city", city);
            response.put("alerts", alerts);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get list of all cities with event counts
     * GET /api/v1/cities
     */
    @GetMapping("/cities")
    public ResponseEntity<Map<String, Object>> getCities() {
        try {
            // Scan aggregations to get unique cities
            ScanSpec scanSpec = new ScanSpec()
                .withProjectionExpression("city, event_count");
            
            ItemCollection<ScanOutcome> items = aggregationsTable.scan(scanSpec);
            
            Map<String, Integer> cityCounts = new HashMap<>();
            
            for (Item item : items) {
                String city = item.getString("city");
                int count = item.getInt("event_count");
                cityCounts.put(city, 
                    cityCounts.getOrDefault(city, 0) + count);
            }
            
            List<Map<String, Object>> cities = cityCounts.entrySet().stream()
                .map(entry -> Map.of(
                    "city", (Object)entry.getKey(),
                    "total_events", entry.getValue()
                ))
                .sorted((a, b) -> 
                    ((Integer)b.get("total_events")).compareTo(
                        (Integer)a.get("total_events")))
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("count", cities.size());
            response.put("cities", cities);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get windowed aggregations for a city and event type
     * GET /api/v1/aggregations?city=NYC&event_type=traffic&limit=10
     */
    @GetMapping("/aggregations")
    public ResponseEntity<Map<String, Object>> getAggregations(
            @RequestParam String city,
            @RequestParam String eventType,
            @RequestParam(defaultValue = "10") int limit) {
        
        try {
            // Scan with filter
            ScanSpec scanSpec = new ScanSpec()
                .withFilterExpression("city = :city AND event_type = :type")
                .withValueMap(new ValueMap()
                    .withString(":city", city)
                    .withString(":type", eventType))
                .withMaxResultSize(limit);
            
            ItemCollection<ScanOutcome> items = aggregationsTable.scan(scanSpec);
            
            List<Map<String, Object>> aggregations = StreamSupport
                .stream(items.spliterator(), false)
                .limit(limit)
                .map(this::itemToMap)
                .sorted((a, b) -> 
                    ((String)b.get("window_start")).compareTo(
                        (String)a.get("window_start")))
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("city", city);
            response.put("event_type", eventType);
            response.put("count", aggregations.size());
            response.put("aggregations", aggregations);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get statistics across all data
     * GET /api/v1/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            // Get total event count from aggregations
            ScanSpec scanSpec = new ScanSpec()
                .withProjectionExpression("event_count");
            
            ItemCollection<ScanOutcome> items = aggregationsTable.scan(scanSpec);
            
            int totalEvents = StreamSupport
                .stream(items.spliterator(), false)
                .mapToInt(item -> item.getInt("event_count"))
                .sum();
            
            // Count alerts
            ScanSpec alertSpec = new ScanSpec()
                .withProjectionExpression("severity");
            
            ItemCollection<ScanOutcome> alertItems = alertsTable.scan(alertSpec);
            
            Map<String, Integer> severityCounts = new HashMap<>();
            severityCounts.put("high", 0);
            severityCounts.put("critical", 0);
            
            for (Item item : alertItems) {
                String severity = item.getString("severity");
                severityCounts.put(severity, 
                    severityCounts.getOrDefault(severity, 0) + 1);
            }
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("total_events_processed", totalEvents);
            stats.put("high_severity_alerts", severityCounts.get("high"));
            stats.put("critical_alerts", severityCounts.get("critical"));
            stats.put("generated_at", Instant.now().toString());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Helper method to convert DynamoDB Item to Map
     */
    private Map<String, Object> itemToMap(Item item) {
        Map<String, Object> map = new HashMap<>();
        item.attributes().forEach(attr -> {
            Object value = item.get(attr.getKey());  // Changed from getName() to getKey()
            if (value != null) {
                map.put(attr.getKey(), value);  // Changed from getName() to getKey()
            }
        });
        return map;
    }
}