# SPDX-License-Identifier: Apache-2.0
# Runtime image only; the deploy workflow supplies quarkus-app/.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
WORKDIR /app
RUN groupadd --system --gid 101 openbank \
 && useradd --system --uid 100 --gid 101 --no-create-home --shell /usr/sbin/nologin openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/
EXPOSE 8155
ENTRYPOINT ["java","-XX:+UseZGC","-Djava.util.logging.manager=org.jboss.logmanager.LogManager","-jar","/app/quarkus-run.jar"]
