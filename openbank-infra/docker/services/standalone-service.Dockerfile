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

# RUNTIME BASE — keep this digest byte-identical to the FROM in
# .github/workflows/Dockerfile.deploy, which is the ONE runtime recipe the deploy path uses
# (auto-deploy.yml, ghcr-publish.yml and openbank-infra/scripts/build-push-service.sh all copy
# that file verbatim). It is glibc, not musl, since #3354: openbank-fraud-service bundles
# com.microsoft.onnxruntime, whose libonnxruntime.so is linked against glibc + libstdc++, so on
# eclipse-temurin:25-jre-alpine OrtEnvironment.getEnvironment() throws UnsatisfiedLinkError —
# and installing libstdc++ via apk does NOT fix it, it only moves the failure to the glibc
# dynamic loader. Leaving THIS file on musl meant `docker compose up` and the deployed image
# had different libc, so a native dependency could work in one and fail in the other; that
# divergence is exactly how #3354 stayed hidden. Read the reasoning and the measurements in
# .github/workflows/Dockerfile.deploy — do not keep a second copy of them here.
#
# NOTE for whoever bumps this: .github/scripts/verify-image-native-libs.py reads the base out of
# Dockerfile.deploy ONLY, so it will not tell you whether this file drifted. The check is that
# the two digests are equal.
FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539
WORKDIR /app

# groupadd/useradd, not busybox `adduser -S` — the glibc base has no busybox. uid 100 / gid 101
# reproduce exactly what `adduser -S` yielded on the alpine base, and match Dockerfile.deploy.
RUN groupadd --system --gid 101 openbank \
 && useradd --system --uid 100 --gid 101 --no-create-home --shell /usr/sbin/nologin openbank
USER openbank

COPY --from=build /workspace/quarkus-run.jar /app/quarkus-run.jar

# -XX:+UseZGC: generational ZGC is default in JDK 25 and gives sub-10ms GC pauses
# for the latency-sensitive payment / ledger services (see scénář B krok E).
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
