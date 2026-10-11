# Build stage: compile and package with the full JDK + Maven.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Run stage: only a JRE and the jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/matching-engine-0.1.0.jar app.jar
# Extra arguments are passed through, e.g. `docker run matching-engine 200000`.
ENTRYPOINT ["java", "-jar", "app.jar"]
