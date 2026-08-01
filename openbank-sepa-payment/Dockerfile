FROM eclipse-temurin:25-jdk@sha256:dfc0093e3dbf43dae57827111c6e374f5b44fac19a9451584b2b336b81474d64 AS build
WORKDIR /build

# The root settings.gradle.kts is the entry point for every openbank-* module and it
# `includeBuild("build-logic")` for the `openbank.quarkus-service` convention plugin,
# so the wrapper, the root build files and build-logic all have to be in the context —
# not just the service directory. gradle.properties matters too: it carries
# org.gradle.dependency.verification=lenient, so omitting it silently builds the
# image under Gradle's default STRICT verification - different rules than the repo.
COPY gradle /build/gradle
COPY gradlew gradle.properties build.gradle.kts settings.gradle.kts /build/
COPY build-logic /build/build-logic
# ADR-0122 split the shared code into sibling top-level modules; `COPY openbank-libs`
# alone leaves `project(":openbank-libs-domain")` etc. unresolvable at configuration time.
COPY openbank-libs /build/openbank-libs
COPY openbank-libs-domain /build/openbank-libs-domain
COPY openbank-libs-runtime /build/openbank-libs-runtime
COPY openbank-libs-temporal /build/openbank-libs-temporal
COPY openbank-libs-testing /build/openbank-libs-testing
COPY openbank-sepa-payment /build/openbank-sepa-payment

RUN chmod +x gradlew && \
    ./gradlew :openbank-sepa-payment:quarkusBuild \
      -Dquarkus.package.jar.type=fast-jar \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

# fast-jar layout: build/quarkus-app/{lib,app,quarkus,quarkus-run.jar}. There is no
# top-level <service>-<version>-runner.jar — that is the uber-jar output. Naming it
# here also hardcoded a version that does not track version.txt.
COPY --from=build /build/openbank-sepa-payment/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-sepa-payment/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-sepa-payment/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-sepa-payment/build/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8115
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
