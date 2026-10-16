# NOT a build recipe — and it never was one.
#
# The image is built by .github/workflows/Dockerfile.deploy from a quarkus-app/ produced
# host-side (auto-deploy.yml, ghcr-publish.yml, openbank-infra/scripts/build-push-service.sh).
# Those three read exactly ONE thing from this file: the EXPOSE line, used to set the container
# port (falling back to 8080 when absent).
#
# check-dockerfile-no-build-stage.py fails if a build stage is added back here, or if the FROM
# below drifts from Dockerfile.deploy — the runtime base is stated in exactly one place (#3354).

FROM eclipse-temurin:25-jre@sha256:fd4b1c02c64eef378212e23656fc0b0caa41a357876fa0b60e51ceda94a3db64

# Pebble is unused by this Java runtime; remove its vulnerable embedded Go runtime.
RUN rm /usr/bin/pebble
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

EXPOSE 8159
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
