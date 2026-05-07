FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV TRADINGBOT_BIND_HOST=0.0.0.0
ENV TRADINGBOT_PORT=7070
ENV TRADINGBOT_DB_PATH=/data/tradingbot.db
ENV TRADINGBOT_VERSION=container

RUN mkdir -p /data /app/logs

COPY --from=build /workspace/target/backend-*-all.jar /app/tradingbot-backend.jar

EXPOSE 7070

CMD ["sh", "-c", "java -Dtradingbot.db.path=${TRADINGBOT_DB_PATH} -Dtradingbot.version=${TRADINGBOT_VERSION} -jar /app/tradingbot-backend.jar"]
