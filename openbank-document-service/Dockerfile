# NOT a build recipe — this file is never built, by anything.
#
# The image for openbank-document-service is built host-side from `.github/workflows/Dockerfile.deploy`,
# which auto-deploy.yml / ghcr-publish.yml / openbank-infra/scripts/build-push-service.sh copy into
# a generated context. `build-push-service.sh` documents why the in-image Gradle build was
# abandoned. This file's ONLY live contract is the EXPOSE line below, which those three grep to set
# the container port (falling back to 8080 when absent).
#
# It used to carry a Gradle build stage that could not run: no `COPY build-logic` (the root
# settings.gradle.kts includeBuild's it for the openbank.quarkus-service plugin) and no COPY of the
# ADR-0122 openbank-libs-* siblings, so a real `docker build` died at configuration time with
# "Included build '/build/build-logic' does not exist" — on all 49 of them (#3016).
#
# The runtime stage is deliberately NOT reproduced here either: Dockerfile.deploy already specifies
# the base image, user and entrypoint, and a second copy nothing builds is a copy free to drift
# invisibly. Change the runtime shape there, and the port here.
EXPOSE 8143
