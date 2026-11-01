FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f

# Pebble is unused by this Java runtime; remove its vulnerable embedded Go runtime.
RUN rm /usr/bin/pebble
WORKDIR /app
RUN groupadd --system --gid 101 openbank && useradd --system --uid 100 --gid 101 --no-create-home --shell /usr/sbin/nologin openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/
COPY sbom/bom.json /app/sbom/bom.json
ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json
EXPOSE 8149
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
