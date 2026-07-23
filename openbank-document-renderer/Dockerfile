# openbank-document-renderer — WeasyPrint HTTP sidecar (ADR-0162 D3).
#
# Multi-stage: the builder stage carries a C toolchain + WeasyPrint's build
# headers so pip can compile any dependency that lacks a prebuilt wheel for
# this platform/arch; the runtime stage only ships the shared libraries
# WeasyPrint actually dlopen's at request time (Pango/HarfBuzz text shaping)
# plus the already-built virtualenv — no compiler, no dev headers.
#
# This is a standalone, non-JVM, non-release-please-tracked helper image
# (like the OPA sidecar): it is not an `openbank-*-service` with a
# version.txt, so it is built/deployed independently of the fleet's Gradle
# release axis.

# Digest-pinned per Scorecard Pinned-Dependencies (multi-arch manifest-list digest — the
# platform-specific image is resolved from this at pull time). Refresh alongside a Python
# patch bump: `crane digest python:3.14-slim` or the registry API.
FROM python:3.14-slim@sha256:cea0e6040540fb2b965b6e7fb5ffa00871e632eef63719f0ea54bca189ce14a6 AS builder

# Build-time-only packages. libpango/libharfbuzz runtime .so's are also
# installed here so `pip install` can link/validate against them if a
# dependency has no prebuilt wheel for this arch; see the runtime stage
# below for the authoritative *runtime* package list and its source.
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        libpango-1.0-0 \
        libpangoft2-1.0-0 \
        libharfbuzz-subset0 \
        libjpeg-dev \
        libopenjp2-7-dev \
        libffi-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY requirements.txt .
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}"
# requirements.txt is a hash-locked pip-compile output of requirements.in (Scorecard
# Pinned-Dependencies) — includes pip itself, so this one --require-hashes install both
# upgrades pip to a verified version and installs every (transitive) runtime dependency.
RUN pip install --no-cache-dir --require-hashes -r requirements.txt

FROM python:3.14-slim@sha256:cea0e6040540fb2b965b6e7fb5ffa00871e632eef63719f0ea54bca189ce14a6 AS runtime

# Runtime-only shared libraries WeasyPrint needs (text shaping via Pango +
# HarfBuzz, image decoding via libjpeg/openjp2). This mirrors the official
# Debian/Ubuntu package list at
# https://doc.courtbouillon.org/weasyprint/stable/first_steps.html
# (WeasyPrint >=53 renders via pydyf + Pango/cffi, not Cairo/GTK, so this
# list is intentionally shorter than older WeasyPrint deployment guides).
# fonts-dejavu-core gives the renderer a baseline font so text isn't blank
# when a template doesn't embed/@font-face its own.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libpango-1.0-0 \
        libpangoft2-1.0-0 \
        libharfbuzz-subset0 \
        libjpeg62-turbo \
        libopenjp2-7 \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system openbank \
    && useradd --system --gid openbank --no-create-home --shell /usr/sbin/nologin openbank

COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}"

WORKDIR /app
COPY app.py .

USER openbank

EXPOSE 8200

# Liveness/readiness probe target — GET /health, 200 == ok.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request as u,sys; sys.exit(0 if u.urlopen('http://127.0.0.1:8200/health', timeout=2).status == 200 else 1)"

ENTRYPOINT ["python", "app.py"]
