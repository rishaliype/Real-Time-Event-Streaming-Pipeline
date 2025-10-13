FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the JAR file
COPY target/*.jar app.jar

# Install curl for health checks
RUN apk add --no-cache curl

# Expose port
EXPOSE 8082

# Run the application
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]