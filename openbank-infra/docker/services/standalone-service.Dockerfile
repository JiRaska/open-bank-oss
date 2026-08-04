FROM eclipse-temurin:25-jdk@sha256:68868d04fa9cfd5f5c6abec0b5cef86d8de2bf9c62c37c7d3e4f0f80f5cfd7ff AS build
ARG SERVICE_DIR

WORKDIR /workspace
COPY . /workspace

# Build from the root project (composite settings.gradle.kts) — NOT from the per-service
# directory. Per-service settings.gradle.kts uses includeBuild("../openbank-libs") which,
# combined with Gradle 9.5 + Quarkus 3.33's quarkusGenerateCode → jar wiring, deadlocks into
# a circular task dependency (:classes → :compileJava → :compileKotlin → :quarkusGenerateCode
# → :jar → :classes). Root-level build resolves :openbank-libs as a sibling project, no
# composite cycle.
#
# Bigger heap: Kotlin 2.3 + Quarkus 3.33 compile is heavier than the 2.0 / 3.13 combo this
# template was originally sized for.
ENV GRADLE_OPTS="-Xmx2g -Xms256m -XX:MaxMetaspaceSize=512m"
# Surface failures from the inner gradlew command (the original `for attempt ... exit 0` loop
# swallowed the exit code so docker compose reported success on a failed build — that bit us
# during the SBOM-2 rollout). Stop on first failure, no retries.
RUN chmod +x gradlew && \
    ./gradlew :${SERVICE_DIR}:quarkusBuild \
      -Dquarkus.package.jar.type=uber-jar \
      --no-daemon \
      --console=plain && \
    cp /workspace/${SERVICE_DIR}/build/*-runner.jar /workspace/quarkus-run.jar

FROM eclipse-temurin:25-jre-ubi10-minimal@sha256:6031e5480f5d5818252a348980a192af0eab09e3380a55f75927e019f4d8136c
WORKDIR /app

RUN groupadd -r openbank && useradd -r -g openbank openbank
USER openbank

COPY --from=build /workspace/quarkus-run.jar /app/quarkus-run.jar

# -XX:+UseZGC: generational ZGC is default in JDK 25 and gives sub-10ms GC pauses
# for the latency-sensitive payment / ledger services (see scénář B krok E).
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
