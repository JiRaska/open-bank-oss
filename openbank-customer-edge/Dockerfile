FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-customer-edge /build/openbank-customer-edge

RUN chmod +x gradlew && \
    ./gradlew :openbank-customer-edge:quarkusBuild \
      -Dquarkus.package.jar.type=fast-jar \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-customer-edge/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-customer-edge/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-customer-edge/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-customer-edge/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8128
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
