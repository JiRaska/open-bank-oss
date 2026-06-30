FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-consent-service /build/openbank-consent-service

RUN chmod +x gradlew && \
    ./gradlew :openbank-consent-service:quarkusBuild :openbank-consent-service:cyclonedxBom \
      -Dquarkus.package.jar.type=fast-jar \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-consent-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-consent-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-consent-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-consent-service/build/quarkus-app/quarkus/ /app/quarkus/

# CycloneDX SBOM baked into the image; served live by libs SbomResource at
# /q/openbank/sbom (admin-ui Tech Inventory reads it). Travels with the image,
# so what the service reports is exactly what was built.
COPY --from=build /build/openbank-consent-service/build/reports/bom.json /app/sbom/bom.json
ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json

EXPOSE 8106
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
