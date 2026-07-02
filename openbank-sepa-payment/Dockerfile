FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY openbank-libs /build/openbank-libs
COPY openbank-sepa-payment /build/openbank-sepa-payment
WORKDIR /build/openbank-sepa-payment
RUN chmod +x gradlew && ./gradlew quarkusBuild -Dquarkus.package.jar.type=fast-jar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY --from=build /build/openbank-sepa-payment/build/openbank-sepa-payment-0.1.0-SNAPSHOT-runner.jar /app/quarkus-run.jar
EXPOSE 8115
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
