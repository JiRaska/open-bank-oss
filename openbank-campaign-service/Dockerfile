FROM eclipse-temurin:25-jdk@sha256:dfc0093e3dbf43dae57827111c6e374f5b44fac19a9451584b2b336b81474d64 AS build
WORKDIR /build
COPY openbank-libs /build/openbank-libs
COPY openbank-campaign-service /build/openbank-campaign-service
WORKDIR /build/openbank-campaign-service
RUN chmod +x gradlew && ./gradlew quarkusBuild -Dquarkus.package.jar.type=fast-jar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY --from=build /build/openbank-campaign-service/build/openbank-campaign-service-0.1.0-SNAPSHOT-runner.jar /app/quarkus-run.jar
EXPOSE 8128
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
