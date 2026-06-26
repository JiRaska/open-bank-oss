# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
#
# Fast-jar only (never uber-jar): the runtime stage COPYs quarkus-app/. The host-side
# build-push-service.sh runs quarkusBuild locally first; this in-image build is the CI/portable path.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-statement-service /build/openbank-statement-service
RUN chmod +x gradlew && ./gradlew :openbank-statement-service:quarkusBuild -Dquarkus.package.jar.type=fast-jar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY --from=build /build/openbank-statement-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-statement-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-statement-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-statement-service/build/quarkus-app/quarkus/ /app/quarkus/
EXPOSE 8136
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
