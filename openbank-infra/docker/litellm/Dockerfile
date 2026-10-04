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
# Measured on v1.99.4, 2026-09-28: the arm64 schema-engine is readable as uid 1000 and is an
# aarch64 ELF. What remains here is a GUARD, not a workaround: the build fails if a future
# upstream again ships an engine for the wrong architecture or out of reach of uid 1000.
#
# Bump = change LITELLM_VERSION and the digest together. Upstream stopped `-stable` tags after
# v1.83; use the newest release tag. NEVER 1.82.7 or 1.82.8 (the compromised PyPI releases).
# CI (platform-images.yml) builds, scans, signs and pushes it, and opens the gitops PR.

ARG LITELLM_VERSION=v1.99.4

FROM ghcr.io/berriai/litellm:${LITELLM_VERSION}@sha256:475f4ae80a09d769d84c92202e70502abe52a62a760c97e598261c9c8cf8244f

# No `USER root` needed: the upstream image already builds and runs as root; the last line of
# this file is what drops to uid 1000.

# Upstream v1.99.4 ships PyJWT 2.13.0. The platform image's CRITICAL scan requires 2.14.0
# (CVE-2026-102268). Its Python lives in /app/.venv and has no pip, so bootstrap pip only
# for this hashed wheel install, then remove it again. Do not install into system Python:
# that would leave the vulnerable version active in the proxy's virtualenv.
# Wheel hash checked against PyPI's published digest for pyjwt-2.14.0-py3-none-any.whl.
RUN python3 -m ensurepip --default-pip \
    && printf '%s\n' 'PyJWT==2.14.0 --hash=sha256:ad0cef71c756a56e74863c2919cf0985f72decbcfcb550ee2f422e7c62b5eedc' > /tmp/pyjwt-requirements.txt \
    && python3 -m pip install --no-cache-dir --no-deps --only-binary=:all: --require-hashes -r /tmp/pyjwt-requirements.txt \
    && python3 -c 'import jwt; assert jwt.__version__ == "2.14.0", jwt.__version__' \
    && python3 -m pip uninstall -y pip \
    && rm /tmp/pyjwt-requirements.txt

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
