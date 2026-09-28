# syntax=docker/dockerfile:1
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# LiteLLM gateway image for the ai-platform namespace (ADR-0174/0175), built for a pod that
# runs as uid 1000 under PodSecurity `restricted` with a read-only root filesystem.
#
# WHY NOT THE UPSTREAM IMAGE AS-IS (#11265). From v1.83 the upstream image is built for root:
#   - the generated Prisma client and every Prisma engine live under /root/.cache, and /root is
#     mode 0700, so at uid 1000 the proxy dies on `Permission denied: '/.cache'` before it reads
#     its config;
#   - the Python package moved to /app/.venv/.../site-packages/prisma, so the old initContainer
#     that copied /usr/lib/python3.13/site-packages/prisma into an emptyDir copies nothing;
#   - the `-non_root` variant boots at uid 1000, but its arm64 image ships an x86-64
#     schema-engine, so `prisma migrate deploy` fails, the proxy "continues startup" anyway and
#     every /key/* call answers 500 (`LiteLLM_VerificationToken does not exist`).
# The engines this image needs are already IN the upstream image, for the right architecture;
# they are only unreachable. So nothing is downloaded here: the Prisma home is moved to a
# root-owned, world-readable directory and the client is regenerated against it, which rewrites
# the absolute engine paths baked into prisma/client.py. Nothing under / is written at runtime.
#
# Bump = change LITELLM_VERSION and the digest together (a `-stable` tag only; NEVER 1.82.7 or
# 1.82.8, the compromised PyPI releases). CI (platform-images.yml) builds, scans, signs and
# pushes it, and opens the gitops PR that pins the new digest.

ARG LITELLM_VERSION=v1.83.14-stable.patch.3

FROM ghcr.io/berriai/litellm:${LITELLM_VERSION}@sha256:f12d528d4a05add56cb09e54c5126088f2edc6bdf3a2f943bcd3a32b08769da2

# No `USER root` needed: the upstream image already builds and runs as root; the last line of
# this file is what drops to uid 1000.

# The Prisma home for every process in this image. Root-owned and read-only to the runtime uid
# on purpose: the engines are code, and a writable engine directory in the one pod with
# 0.0.0.0/0:443 egress (ADR-0175 section 4) would be a place to swap one.
ENV HOME=/opt/litellm-home \
    PRISMA_HOME_DIR=/opt/litellm-home \
    XDG_CACHE_HOME=/opt/litellm-home/.cache \
    # Prisma CLI telemetry writes a checkpoint file under the cache and phones home; neither is
    # wanted from a read-only, egress-restricted pod.
    CHECKPOINT_DISABLE=1 \
    PRISMA_HIDE_UPDATE_MESSAGE=1

RUN set -eux; \
    mkdir -p /opt/litellm-home/.cache; \
    mv /root/.cache/prisma /root/.cache/prisma-python /opt/litellm-home/.cache/; \
    # Regenerate the client so BINARY_PATHS in prisma/client.py name /opt/litellm-home, not /root.
    cd /app && prisma generate --schema=./schema.prisma; \
    # `! grep` would not trip `set -e`; test explicitly.
    if grep -q '/root/' /app/.venv/lib/python3.13/site-packages/prisma/client.py; then \
      echo 'prisma client still points at /root after regeneration' >&2; exit 1; \
    fi; \
    # Same layer as the move: a separate chmod RUN would copy ~260 MB of engines into a new layer.
    chmod -R a+rX /opt/litellm-home

# Fail the BUILD, not the pod, if the schema engine for the build platform is missing or foreign:
# the non_root arm64 image shipped an x86-64 schema-engine and only a running pod noticed.
RUN python3 - <<'PY'
import pathlib, platform
want = {"aarch64": (0xB7, "arm64"), "x86_64": (0x3E, "")}[platform.machine()]
root = pathlib.Path("/opt/litellm-home/.cache")
found = 0
for p in root.rglob("schema-engine*"):
    if not p.is_file() or p.suffix in (".sha256", ".gz"):
        continue
    if want[1] and want[1] not in str(p):
        continue
    if not want[1] and "arm64" in str(p):
        continue
    head = p.read_bytes()[:20]
    machine = int.from_bytes(head[18:20], "little")
    assert head[:4] == b"\x7fELF" and machine == want[0], f"{p}: e_machine {machine:#x}"
    print(f"ok {p}")
    found += 1
assert found, f"no {platform.machine()} schema-engine under {root}"
PY

USER 1000:1000
