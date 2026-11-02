# ClusterFuzzLite build container (https://google.github.io/clusterfuzzlite/) — CI fuzzing
# of the libs-domain parsers with the SAME Jazzer targets as the OSS-Fuzz proposal package
# (fuzz/ossfuzz/README.md): same base image, same maintainer-owned build script, so an
# eventual google/oss-fuzz onboarding stays a two-line shim.
#
# Digest-pinned (repo convention, Scorecard Pinned-Dependencies): oss-fuzz-base only
# publishes :latest, so bump the digest deliberately when the Jazzer driver needs it.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:14f07ee7b8179a24000fab8784c082d53225411f1767e0a538e1edab97da57e7
# libs-domain targets JVM 25; the base image's bundled JDK lags — install the
# checksum-verified Temurin 25.0.5+8 release (same step as the Dockerfile in
# fuzz/ossfuzz/README.md).
RUN curl -fsSL 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz' \
      -o /tmp/jdk25.tar.gz \
      && echo '68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb  /tmp/jdk25.tar.gz' | sha256sum -c - \
      && mkdir -p /opt/jdk25 \
      && tar -xzf /tmp/jdk25.tar.gz -C /opt/jdk25 --strip-components=1 && rm /tmp/jdk25.tar.gz
ENV JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH
COPY . $SRC/open-bank-oss
COPY .clusterfuzzlite/build.sh $SRC/build.sh
WORKDIR $SRC/open-bank-oss
