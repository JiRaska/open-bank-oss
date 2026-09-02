# ===========================================================================
# OpenBank CI runner image (ADR-0053). FROM the stock ARC ephemeral runner,
# plus the toolchain the workflows assume — the same set the retired EC2 AMI
# baked in (commit d75d05e), so the ephemeral runner is a drop-in replacement:
#
#   * docker compose v2 CLI plugin  — _service-ci.yml boots the test-infra
#                                      stack (postgres/kafka/valkey) via
#                                      `docker compose -f …`.
#   * yamllint + shellcheck         — Validate manifests (ci.yml).
#   * trivy                         — Trivy fs/config scan.
#   * gitleaks                      — secret-scan.
#   * yq (+ jq)                      — manifest / label workflows.
#   * cosign                        — image signing/attestation (runner-image.yml's own
#                                      supply-chain step, ADR-0030 D4 step 4). Previously
#                                      downloaded at runtime with no retry logic — a
#                                      transient GitHub-releases fetch failure silently
#                                      dropped the runner image's own SBOM attestation
#                                      (continue-on-error), which is what caused the
#                                      Kyverno Enforce graduation deadlock (issue #310):
#                                      no ARC runner using the unattested image could ever
#                                      start, including the one needed to rebuild it. Baking
#                                      it in removes the runtime network dependency entirely.
#   * aws CLI v2                    — _service-ci.yml "Configure CodeArtifact
#                                      Maven mirror" fetches the repo token via
#                                      IRSA; without the CLI the step soft-skips
#                                      and dep resolution falls back to public
#                                      Maven Central (NAT cost + 429 risk).
#   * Temurin JDK 21 + 25 preload   — pre-extracted at /opt/openbank-jdk-preload/,
#                                      laid out exactly as actions/setup-java's
#                                      Temurin installer expects the toolcache
#                                      (Java_Temurin-Hotspot_jdk/<version>/<arch>/
#                                      + a sibling <arch>.complete marker), but
#                                      NOT baked directly into RUNNER_TOOL_CACHE
#                                      itself — that path is a shared hostPath
#                                      volume (arc-runners.tf), and a Kubernetes
#                                      volume mount always shadows whatever the
#                                      image had at that mountPath, so anything
#                                      baked there would just be hidden at
#                                      container start. The init-jdk-toolcache-
#                                      preload init container (arc-runners.tf)
#                                      copies this staged layout into the
#                                      hostPath cache on pod start, atomically
#                                      (temp dir + rename, `.complete` written
#                                      last) and only if a version is missing —
#                                      this is what actually eliminates the race
#                                      two concurrent `build (openbank-<service>)`
#                                      jobs used to hit when both extracted the
#                                      same not-yet-cached JDK into the shared
#                                      cache at once (ENOENT/EACCES from
#                                      actions/setup-java, corrupted extraction).
#                                      setup-java now finds both JDKs already
#                                      complete and skips download+extract
#                                      entirely — faster for every job, and the
#                                      concurrent-write scenario no longer exists.
#
# arm64 only (the runners NodePool is Graviton).
#
# Supply chain (ADR-0030 applied to the runner image): the base image is
# pinned by digest and every downloaded tool is pinned to an explicit version
# with a sha256 check (yq excepted — it ships a non-standard multi-hash file;
# version-pinned URL only). Bump a tool by editing its ARG + SHA below. The
# image is rebuilt + pushed by .github/workflows/runner-image.yml; the produced
# digest is what local.runner_image references in arc-runners.tf.
# ===========================================================================

# ghcr.io/actions/actions-runner:latest @ 2026-06-01 (multi-arch index digest).
FROM ghcr.io/actions/actions-runner@sha256:e5496277be5d09bc968b3d64911b74e219ac4a3f2edce956a3ecf9271bea1ef4

USER root

# --- pinned tool versions + arm64 sha256 ----------------------------------
ARG COMPOSE_VERSION=v5.1.4
ARG COMPOSE_SHA256=d4fb48b72857810314d3ee77123c89954101844efa4788031221f4c370495946
ARG TRIVY_VERSION=0.70.0
ARG TRIVY_SHA256=2f6bb988b553a1bbac6bdd1ce890f5e412439564e17522b88a4541b4f364fc8d
ARG GITLEAKS_VERSION=8.30.1
ARG GITLEAKS_SHA256=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
ARG COSIGN_VERSION=v2.4.3
ARG COSIGN_SHA256=bd0f9763bca54de88699c3656ade2f39c9a1c7a2916ff35601caf23a79be0629
ARG YQ_VERSION=v4.53.2
ARG AWSCLI_VERSION=2.33.14
ARG AWSCLI_SHA256=d4914cdbcfccfe151d1f4672bf7aeb81bd7cdfc5edc41425a9e54c900cddab24

# Temurin JDK preload (see the header comment). Versions match what the fleet's
# jvmToolchain(..) actually resolves (_service-ci.yml, api-fuzz.yml, fleet-lint.yml,
# ghcr-publish.yml: `java-version: 21 / 25`). Pin the exact Adoptium release (not
# just the major) so the sha256 is meaningful; bump both the version and hash
# together from https://api.adoptium.net/v3/assets/latest/<major>/hotspot?vendor=eclipse&architecture=aarch64&os=linux&image_type=jdk
# when Temurin ships a new build. TEMURIN_*_TOOLCACHE_VERSION is the SAME string
# with `+` -> `-`: that's the literal directory name actions/setup-java's
# getToolcacheVersionName() computes and looks for under Java_Temurin-Hotspot_jdk/.
ARG TEMURIN_21_VERSION=21.0.11+10
ARG TEMURIN_21_TOOLCACHE_VERSION=21.0.11-10.0.LTS
ARG TEMURIN_21_SHA256=8d498ec88e1c1989fab95c6784240ab92d011e29c54d20a3f9c324b13476f9ad
ARG TEMURIN_25_VERSION=25.0.3+9
ARG TEMURIN_25_TOOLCACHE_VERSION=25.0.3-9.0.LTS
ARG TEMURIN_25_SHA256=3e4287cb98870ba824ed698854bdc27cff984254caf66dd12cc291e7bfdde26b

# yamllint + shellcheck + tooling deps (jq used by the workflows + curl/tar here).
RUN apt-get update \
 && apt-get install -y --no-install-recommends yamllint shellcheck curl ca-certificates jq tar unzip \
 && rm -rf /var/lib/apt/lists/*

# docker compose — pinned CLI plugin (scanned from /usr/local/lib/docker/cli-plugins).
RUN mkdir -p /usr/local/lib/docker/cli-plugins \
 && curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-aarch64" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose \
 && echo "${COMPOSE_SHA256}  /usr/local/lib/docker/cli-plugins/docker-compose" | sha256sum -c - \
 && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose \
 && /usr/local/lib/docker/cli-plugins/docker-compose version

# trivy — pinned release tarball (not the mutable curl|sh installer).
RUN curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-ARM64.tar.gz" -o /tmp/trivy.tgz \
 && echo "${TRIVY_SHA256}  /tmp/trivy.tgz" | sha256sum -c - \
 && tar -xz -C /usr/local/bin -f /tmp/trivy.tgz trivy \
 && rm /tmp/trivy.tgz \
 && trivy --version

# gitleaks — pinned release tarball.
RUN curl -fsSL "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_arm64.tar.gz" -o /tmp/gitleaks.tgz \
 && echo "${GITLEAKS_SHA256}  /tmp/gitleaks.tgz" | sha256sum -c - \
 && tar -xz -C /usr/local/bin -f /tmp/gitleaks.tgz gitleaks \
 && rm /tmp/gitleaks.tgz \
 && gitleaks version

# cosign — pinned release binary (not the mutable install script). Verified against
# cosign's own published cosign_checksums.txt for this release before pinning here.
RUN curl -fsSL "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-linux-arm64" -o /usr/local/bin/cosign \
 && echo "${COSIGN_SHA256}  /usr/local/bin/cosign" | sha256sum -c - \
 && chmod +x /usr/local/bin/cosign \
 && cosign version

# aws CLI v2 — pinned installer zip (sha256 computed from the versioned, immutable
# release URL; AWS publishes only PGP sigs, no checksum file). Used by the
# CodeArtifact Maven mirror step in _service-ci.yml (token via the pod's IRSA role).
RUN curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-aarch64-${AWSCLI_VERSION}.zip" -o /tmp/awscliv2.zip \
 && echo "${AWSCLI_SHA256}  /tmp/awscliv2.zip" | sha256sum -c - \
 && unzip -q /tmp/awscliv2.zip -d /tmp \
 && /tmp/aws/install \
 && rm -rf /tmp/aws /tmp/awscliv2.zip \
 && aws --version

# yq (mikefarah) — version-pinned (ships a non-standard multi-hash checksums file).
RUN curl -fsSL "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_arm64" \
      -o /usr/local/bin/yq \
 && chmod +x /usr/local/bin/yq \
 && yq --version

# Temurin JDK 21 + 25 preload — staged at /opt/openbank-jdk-preload/ (NOT at
# RUNNER_TOOL_CACHE; see the header comment for why). Layout matches
# actions/setup-java's Temurin installer toolcache convention exactly:
#   Java_Temurin-Hotspot_jdk/<toolcache-version>/<arch>/<jdk contents>
#   Java_Temurin-Hotspot_jdk/<toolcache-version>/<arch>.complete   (empty marker)
# The tarball's single top-level dir (e.g. jdk-21.0.11+10/) is unwrapped so its
# CONTENTS land directly under <arch>/ — actions/toolkit's cacheDir does the same
# unwrap (temurin/installer.ts: archivePath = extractedJavaPath/<archiveName>).
RUN set -eu; \
    preload_jdk() { \
      major="$1"; version="$2"; toolcache_version="$3"; sha256="$4"; \
      url="https://github.com/adoptium/temurin${major}-binaries/releases/download/jdk-$(echo "$version" | sed 's/+/%2B/')/OpenJDK${major}U-jdk_aarch64_linux_hotspot_$(echo "$version" | sed 's/+/_/').tar.gz"; \
      dest="/opt/openbank-jdk-preload/Java_Temurin-Hotspot_jdk/${toolcache_version}/arm64"; \
      curl -fsSL "$url" -o "/tmp/jdk${major}.tar.gz"; \
      echo "${sha256}  /tmp/jdk${major}.tar.gz" | sha256sum -c -; \
      mkdir -p "/tmp/jdk${major}-extract" "$dest"; \
      tar -xzf "/tmp/jdk${major}.tar.gz" -C "/tmp/jdk${major}-extract"; \
      inner="$(find "/tmp/jdk${major}-extract" -mindepth 1 -maxdepth 1 -type d)"; \
      cp -r "${inner}/." "$dest/"; \
      touch "/opt/openbank-jdk-preload/Java_Temurin-Hotspot_jdk/${toolcache_version}/arm64.complete"; \
      rm -rf "/tmp/jdk${major}.tar.gz" "/tmp/jdk${major}-extract"; \
      "${dest}/bin/java" -version; \
    }; \
    preload_jdk 21 "${TEMURIN_21_VERSION}" "${TEMURIN_21_TOOLCACHE_VERSION}" "${TEMURIN_21_SHA256}"; \
    preload_jdk 25 "${TEMURIN_25_VERSION}" "${TEMURIN_25_TOOLCACHE_VERSION}" "${TEMURIN_25_SHA256}"

USER runner
