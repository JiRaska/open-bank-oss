# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
# fast-jar only (never uber-jar): the runtime stage COPYs quarkus-app/ (CLAUDE.md GitOps rules).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-clearing-simulator /build/openbank-clearing-simulator

ENV GRADLE_OPTS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=256m"
RUN chmod +x gradlew && \
    ./gradlew :openbank-clearing-simulator:quarkusBuild \
      -Dquarkus.package.jar.type=fast-jar \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-clearing-simulator/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-clearing-simulator/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-clearing-simulator/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-clearing-simulator/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8139
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
