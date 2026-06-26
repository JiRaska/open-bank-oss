FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-sanctions-service /build/openbank-sanctions-service

ENV GRADLE_OPTS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=256m"
RUN chmod +x gradlew && \
    ./gradlew :openbank-sanctions-service:quarkusBuild \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-sanctions-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-sanctions-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-sanctions-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-sanctions-service/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8123
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
