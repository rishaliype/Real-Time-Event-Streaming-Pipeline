FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the JAR file (pre-built locally)
COPY target/*.jar app.jar

# Install curl for health checks
RUN apk add --no-cache curl

# Expose port
EXPOSE 8080

# Run with reduced memory for t3.small
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]