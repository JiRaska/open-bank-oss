FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /build

# Build from the FULL repo root context, NOT a selective per-module COPY. The root
# settings.gradle.kts discovers modules by listing rootDir, so a selective COPY silently
# drops :openbank-libs-domain / :openbank-libs-runtime — which this service depends on —
# and Gradle then fails to resolve them. Mirrors openbank-anacredit-service/Dockerfile.
# .dockerignore strips build/, .gradle/, node_modules/ so the context stays lean.
COPY . /build

# Kotlin 2.3 + Quarkus 3.33 compile is heap-hungry; size the JVM up front.
ENV GRADLE_OPTS="-Xmx2g -Xms256m -XX:MaxMetaspaceSize=512m"
RUN chmod +x gradlew && \
    ./gradlew :openbank-vop-service:quarkusBuild \
      -Dquarkus.package.jar.type=fast-jar \
      -Dorg.gradle.java.installations.auto-detect=false \
      --no-daemon \
      --console=plain

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank

COPY --from=build /build/openbank-vop-service/build/quarkus-app/lib/ /app/lib/
COPY --from=build /build/openbank-vop-service/build/quarkus-app/*.jar /app/
COPY --from=build /build/openbank-vop-service/build/quarkus-app/app/ /app/app/
COPY --from=build /build/openbank-vop-service/build/quarkus-app/quarkus/ /app/quarkus/

# Must match the gitops containerPort (payments-services.yaml) and quarkus.http.port:
# auto-deploy greps EXPOSE from this file to set the pushed image's port, falling back
# to 8080 when it is absent — which is exactly the drift this file exists to prevent.
EXPOSE 8149
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
