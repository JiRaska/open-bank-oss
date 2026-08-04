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

FROM eclipse-temurin:25-jre-ubi10-minimal@sha256:6031e5480f5d5818252a348980a192af0eab09e3380a55f75927e019f4d8136c
WORKDIR /app

RUN groupadd -r openbank && useradd -r -g openbank openbank
USER openbank

COPY --from=build /workspace/quarkus-run.jar /app/quarkus-run.jar

ENTRYPOINT ["java", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
