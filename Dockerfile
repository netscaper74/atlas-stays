# syntax=docker/dockerfile:1

# ---- build: Maven + JDK 21 ------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, so editing Java code doesn't re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run: just a JRE and the fat jar -------------------------------------
FROM eclipse-temurin:21-jre
ENV LANG=C.UTF-8 \
    PORT=8080
WORKDIR /app
COPY --from=build /src/target/atlas-stays.jar app.jar
RUN useradd --system --uid 10001 stays
USER stays
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
