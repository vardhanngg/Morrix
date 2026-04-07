# ── Stage 1: Build the WAR with Maven ────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-11 AS build

WORKDIR /app
COPY pom.xml .
# Download dependencies first (cached layer — speeds up rebuilds)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -q -DskipTests
# Output: /app/target/morix.war

# ── Stage 2: Run on Tomcat 9 ─────────────────────────────────────────────────
FROM tomcat:9.0-jdk11-temurin

# Remove default Tomcat webapps so only Morix is served
RUN rm -rf /usr/local/tomcat/webapps/*

# Deploy WAR as ROOT so it's at / not /morix/
COPY --from=build /app/target/morix.war /usr/local/tomcat/webapps/ROOT.war

# Render injects PORT env var — Tomcat listens on 8080 by default.
# We update server.xml to honour the PORT variable at startup.
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
