# NOT a build recipe — and it never was one.
#
# The image is built by .github/workflows/Dockerfile.deploy from a quarkus-app/
# produced host-side (auto-deploy.yml, ghcr-publish.yml,
# openbank-infra/scripts/build-push-service.sh). Those three read exactly ONE thing
# from this file: the EXPOSE line, used to set the container port (falling back to
# 8080 when absent). See openbank-vop-service/Dockerfile / issue #3016 / #3354 for
# why this file has no build stage and must not grow one.

FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539
WORKDIR /app
RUN groupadd --system --gid 101 openbank \
 && useradd --system --uid 100 --gid 101 --no-create-home --shell /usr/sbin/nologin openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8153
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
