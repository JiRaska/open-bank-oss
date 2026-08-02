# NOT a build recipe — and it never was one.
#
# The image is built by .github/workflows/Dockerfile.deploy from a quarkus-app/
# produced host-side (auto-deploy.yml, ghcr-publish.yml,
# openbank-infra/scripts/build-push-service.sh). Those three read exactly ONE thing
# from this file: the EXPOSE line, used to set the container port (falling back to
# 8080 when absent).
#
# Until #3016 this file carried a Gradle build stage that could not run — no
# COPY build-logic, no ADR-0122 libs siblings, no gradle.properties — so it read as
# authoritative while being unbuildable. It now mirrors Dockerfile.deploy exactly,
# so what it says is what actually happens: given a prepared context containing
# quarkus-app/, this builds the real image.
#
# Adding a build stage back here is enforced against by
# .github/scripts/check-dockerfile-no-build-stage.py.

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8116
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
