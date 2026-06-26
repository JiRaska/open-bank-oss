FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY gradle /build/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /build/
COPY openbank-libs /build/openbank-libs
COPY openbank-sepa-instant /build/openbank-sepa-instant

ENV GRADLE_OPTS="-Xmx512m -Xms128m -XX:MaxMetaspaceSize=256m"
RUN printf 'rootProject.name = "openbank"\n\
dependencyResolutionManagement {\n\
    versionCatalogs {\n\
        create("libs") { from(files("openbank-libs/gradle/libs.versions.toml")) }\n\
    }\n\
}\n\
include(":openbank-libs", ":openbank-sepa-instant")\n' > /build/settings.gradle.kts && \
    chmod +x gradlew && \
    ./gradlew :openbank-sepa-instant:quarkusBuild \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-sepa-instant/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-sepa-instant/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-sepa-instant/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-sepa-instant/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8127
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
