# syntax=docker/dockerfile:1
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# LiteLLM gateway image for the ai-platform namespace (ADR-0174/0175), built for a pod that
# runs as uid 1000 under PodSecurity `restricted` with a read-only root filesystem.
#
# HISTORY (#11265). v1.83 upstream kept the generated Prisma client and every engine under
# /root/.cache (mode 0700), so at uid 1000 the proxy died before reading its config, and this
# file moved the Prisma home and regenerated the client. From v1.99 upstream fixed that itself:
# engines live in /opt/prisma (0755, root-owned) and the client no longer references /root.
# Measured on v1.99.4, 2026-09-28 (and again on v1.104.0, 2026-10-04): the arm64 schema-engine is readable as uid 1000 and is an
# aarch64 ELF. What remains here is a GUARD, not a workaround: the build fails if a future
# upstream again ships an engine for the wrong architecture or out of reach of uid 1000.
#
# Bump = change LITELLM_VERSION and the digest together. Upstream stopped `-stable` tags after
# v1.83; use the newest release tag. NEVER 1.82.7 or 1.82.8 (the compromised PyPI releases).
# CI (platform-images.yml) builds, scans, signs and pushes it, and opens the gitops PR.

ARG LITELLM_VERSION=v1.104.0

FROM ghcr.io/berriai/litellm:${LITELLM_VERSION}@sha256:625981c83410a3ea68eb0697590a57ec1d764d634514d54fa5db0591077ee839

# No `USER root` needed: the upstream image already builds and runs as root; the last line of
# this file is what drops to uid 1000.

# PyJWT: v1.99.4 shipped 2.13.0 and this file force-installed a hashed 2.14.0 wheel for
# CVE-2026-102268. Upstream v1.104.0 ships 2.15.0 in /app/.venv, so the override is gone; the
# assertion below keeps the floor so a future base cannot silently regress below the fix.
RUN /app/.venv/bin/python3 -c 'import jwt; v = tuple(int(x) for x in jwt.__version__.split(".")[:2]); assert v >= (2, 14), jwt.__version__'

# The Prisma home for every process in this image. Root-owned and read-only to the runtime uid
# on purpose: the engines are code, and a writable engine directory in the one pod with
# 0.0.0.0/0:443 egress (ADR-0175 section 4) would be a place to swap one.
# HOME must be writable-free at runtime: point it at a path the pod mounts as an emptyDir (/tmp).
ENV HOME=/tmp \
    CHECKPOINT_DISABLE=1 \
    PRISMA_HIDE_UPDATE_MESSAGE=1
RUN python3 - <<'PY'
import pathlib, platform
want = {"aarch64": (0xB7, "arm64"), "x86_64": (0x3E, "")}[platform.machine()]
root = pathlib.Path("/opt/prisma/.cache")
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
# Guard 2: the engine must be readable and executable by the runtime user, not only by root.
RUN set -eux; for f in /opt/prisma/.cache/prisma/master/*/linux-*/schema-engine; do test -r "$f" && test -x "$f"; done
