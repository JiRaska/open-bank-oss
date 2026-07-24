FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-ap2-service /build/openbank-ap2-service

RUN chmod +x gradlew && \
    ./gradlew :openbank-ap2-service:quarkusBuild :openbank-ap2-service:cyclonedxBom \
      -Dquarkus.package.jar.type=fast-jar \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER 100

COPY --from=build /build/openbank-ap2-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-ap2-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-ap2-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-ap2-service/build/quarkus-app/quarkus/ /app/quarkus/

COPY --from=build /build/openbank-ap2-service/build/reports/bom.json /app/sbom/bom.json
ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json

EXPOSE 8151
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
