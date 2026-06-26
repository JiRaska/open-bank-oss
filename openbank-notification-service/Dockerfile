FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-notification-service /build/openbank-notification-service
RUN chmod +x gradlew && ./gradlew :openbank-notification-service:quarkusBuild -Dquarkus.package.jar.type=fast-jar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY --from=build /build/openbank-notification-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-notification-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-notification-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-notification-service/build/quarkus-app/quarkus/ /app/quarkus/
EXPOSE 8112
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
