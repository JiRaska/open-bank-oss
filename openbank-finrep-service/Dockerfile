FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /build

# Build from the FULL repo root context, NOT a selective per-module COPY. The root
# settings.gradle.kts unconditionally `include`s all 30+ modules with an explicit
# projectDir each, so Gradle configuration fails ("Configuring project ':openbank-
# ledger-service' without an existing directory") unless every module dir is present.
# Building from inside the module with its own gradlew instead trips a
# quarkusGenerateCode -> jar -> classes circular dependency. Mirrors the canonical
# openbank-infra/docker/services/standalone-service.Dockerfile. .dockerignore strips
# build/, .gradle/, node_modules/ so the context stays lean.
COPY . /build

# Kotlin 2.3 + Quarkus 3.33 compile is heap-hungry; size the JVM up front.
ENV GRADLE_OPTS="-Xmx2g -Xms256m -XX:MaxMetaspaceSize=512m"
RUN chmod +x gradlew && \
    ./gradlew :openbank-finrep-service:quarkusBuild \
      -Dorg.gradle.java.installations.auto-detect=false \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-finrep-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-finrep-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-finrep-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-finrep-service/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8140
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
