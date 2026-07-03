FROM eclipse-temurin:20-jdk@sha256:a8010918241007417c8c0ce7d203cf110f8c945b56da01a13eb55af7eb3d3175 AS build
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

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /workspace/quarkus-run.jar /app/quarkus-run.jar

ENTRYPOINT ["java", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
