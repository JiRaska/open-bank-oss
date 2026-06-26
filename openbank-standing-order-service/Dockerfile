FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-standing-order-service /build/openbank-standing-order-service

ENV GRADLE_OPTS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=256m"
RUN printf 'rootProject.name = "openbank"\n\
dependencyResolutionManagement {\n\
    versionCatalogs {\n\
        create("libs") { from(files("openbank-libs/gradle/libs.versions.toml")) }\n\
    }\n\
}\n\
include(":openbank-libs", ":openbank-standing-order-service")\n' > /build/settings.gradle.kts && \
    chmod +x gradlew && \
    ./gradlew :openbank-standing-order-service:quarkusBuild \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-standing-order-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-standing-order-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-standing-order-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-standing-order-service/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8121
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
