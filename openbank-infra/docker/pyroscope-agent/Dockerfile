# Pyroscope Java agent — carrier image (ADR-0082, profiling 4th pillar).
#
# WHY a dedicated image instead of baking the agent into every service image:
# JVM profiling is OPT-IN and OFF fleet-wide (a bank enables it deliberately).
# This image carries ONLY the pinned `pyroscope.jar`; a service opts in by adding
# an init container that `cp`s the jar into a shared emptyDir, then attaching it
# via `-javaagent` in JAVA_TOOL_OPTIONS. No service-image rebuild is needed to
# enable/disable profiling on a service — it is a pure gitops Deployment change,
# and the agent binary is never present in a pod that has not opted in.
#
# Supply chain (bank-grade): the agent is fetched ONCE at build time from the
# pinned upstream release and verified by SHA-256; nothing is downloaded at pod
# runtime. Bump = raise PYROSCOPE_VERSION + PYROSCOPE_SHA256 and rebuild.
#
# Build + push (arm64, matches the ECR service images):
#   openbank-infra/scripts/build-push-pyroscope-agent.sh

# ── Stage 1: fetch + verify the pinned agent jar (build-time only) ────────────
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS fetch
ARG PYROSCOPE_VERSION=2.5.4
ARG PYROSCOPE_SHA256=ea53924454a65af9ecd3c683350b113c397eace7a35f2c165e129a312db2e0ad
RUN apk add --no-cache curl \
 && curl -fsSL -o /pyroscope.jar \
      "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_VERSION}/pyroscope.jar" \
 && echo "${PYROSCOPE_SHA256}  /pyroscope.jar" | sha256sum -c -

# ── Stage 2: minimal carrier. The init container runs `cp` out of this image ──
# busybox provides /bin/cp; the jar is arch-agnostic (async-profiler natives for
# both linux-x86-64 and linux-arm64 are bundled and self-extract at attach time).
FROM busybox:1.38@sha256:dc2d74b28e4cf8984fa52af1f39bc7c3d9c73760b41a74d629f5d11b1ab28616
COPY --from=fetch /pyroscope.jar /pyroscope.jar
# Documented, no entrypoint needed: the init container overrides command with
#   ["/bin/cp", "/pyroscope.jar", "/opt/pyroscope/pyroscope.jar"]
# Run as nobody (65534) — this is a carrier-only init container with no long-running process.
USER 65534
