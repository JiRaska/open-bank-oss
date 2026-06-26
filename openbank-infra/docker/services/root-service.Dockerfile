FROM eclipse-temurin:20-jdk AS build
ARG SERVICE_DIR

WORKDIR /workspace
COPY . /workspace

RUN chmod +x gradlew && \
    for attempt in 1 2 3; do \
      rm -rf /root/.gradle/wrapper/dists/gradle-8.8-bin && \
      ./gradlew :${SERVICE_DIR}:quarkusBuild \
        -Dquarkus.package.type=uber-jar \
        --no-daemon \
        --console=plain && \
      cp /workspace/${SERVICE_DIR}/build/*-runner.jar /workspace/quarkus-run.jar && \
      exit 0; \
      sleep 2; \
    done; \
    exit 1

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /workspace/quarkus-run.jar /app/quarkus-run.jar

ENTRYPOINT ["java", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
