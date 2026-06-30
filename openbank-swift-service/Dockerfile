FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-swift-service /build/openbank-swift-service

ENV GRADLE_OPTS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=256m"
RUN printf 'rootProject.name = "openbank"\n\
dependencyResolutionManagement {\n\
    versionCatalogs {\n\
        create("libs") { from(files("openbank-libs/gradle/libs.versions.toml")) }\n\
    }\n\
}\n\
include(":openbank-libs", ":openbank-swift-service")\n' > /build/settings.gradle.kts && \
    chmod +x gradlew && \
    ./gradlew :openbank-swift-service:quarkusBuild :openbank-swift-service:cyclonedxBom \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-swift-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-swift-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-swift-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-swift-service/build/quarkus-app/quarkus/ /app/quarkus/

# CycloneDX SBOM baked into the image; served live by libs SbomResource at
# /q/openbank/sbom (admin-ui Tech Inventory reads it). Travels with the image,
# so what the service reports is exactly what was built.
COPY --from=build /build/openbank-swift-service/build/reports/bom.json /app/sbom/bom.json
ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json

EXPOSE 8122
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
