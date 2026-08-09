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
# .github/scripts/check-dockerfile-no-build-stage.py, which also fails if the FROM above
# drifts from Dockerfile.deploy — the runtime base is stated in exactly one place (#3354).

FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539
WORKDIR /app
RUN groupadd --system --gid 101 openbank \
 && useradd --system --uid 100 --gid 101 --no-create-home --shell /usr/sbin/nologin openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/

# CycloneDX SBOM baked in by the deploy pipeline when the service produces one;
# served live at /q/openbank/sbom (admin-ui Tech Inventory reads it).
COPY sbom/bom.json /app/sbom/bom.json
ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json

EXPOSE 8122
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
