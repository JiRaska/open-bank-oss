#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Keep openbank-*/Dockerfile honest: runtime recipe only, and always an EXPOSE.

Background (#3016). These files used to carry a Gradle build stage that could not
run — none copied `build-logic`, none copied the ADR-0122 `openbank-libs-*`
siblings, none copied `gradle.properties`. Measured: 0 of 51 were buildable, and
`openbank-ledger-service/Dockerfile`, the one everyone cited as the correct shape,
failed in 77 s. They read as authoritative while being fiction.

Nothing builds them. `auto-deploy.yml`, `ghcr-publish.yml` and
`openbank-infra/scripts/build-push-service.sh` all generate their own context from
`.github/workflows/Dockerfile.deploy`, and read exactly one thing from these files:
the EXPOSE line.

So two rules:

  1. No build stage. A `FROM ... AS build` here is either dead text or a second,
     divergent definition of how images are made. Both are worse than nothing.
  2. EXPOSE is mandatory. It is the only live contract — losing it does not fail
     anything loudly, it silently sets the container port to 8080.

BOTH rules assume the file describes a Quarkus service deployed by that pipeline.
Three `openbank-*/` directories are not that, and for them a build stage is the
whole point (SELF_BUILT below). The first sweep flattened them into the generic
Quarkus recipe, which broke the admin-ui image build for real: `admin-ui-deploy.yml`
runs `build-push-admin-ui.sh`, which does `docker buildx build --file
openbank-admin-ui/Dockerfile` and would then have copied a `quarkus-app/` a Next.js
app never produces.

The exemption is checked in BOTH directions. An exempted file that has lost its
build stage, or that copies `quarkus-app/`, is a stale declaration and fails —
otherwise this list would quietly re-authorise exactly the flattening it exists to
prevent.

Usage:
    check-dockerfile-no-build-stage.py            # warn
    check-dockerfile-no-build-stage.py --enforce  # fail
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

# re.M is load-bearing on all three: without it `^` anchors to the start of the whole
# file, so only a violation on the FIRST non-comment line could ever match. That made
# the `COPY --from=` and `gradlew` rules structurally unable to fire (neither is ever a
# first line), and the build-stage rule a coin flip.
BUILD_STAGE_RE = re.compile(r'^\s*FROM\s+.*\s+AS\s+\S+', re.I | re.M)
COPY_FROM_RE = re.compile(r'^\s*COPY\s+--from=', re.I | re.M)
GRADLEW_RE = re.compile(r'^\s*RUN\s+.*gradlew', re.I | re.M)
EXPOSE_RE = re.compile(r'^EXPOSE\s+(\d+)\s*$', re.M)
QUARKUS_APP_RE = re.compile(r'^\s*COPY\s+.*\bquarkus-app/', re.I | re.M)

# Directories under openbank-*/ that are NOT Quarkus services built by the deploy
# pipeline. Their Dockerfile is the real, self-contained recipe for the image, so
# the no-build-stage rule does not apply. Keep the reason with the entry — it is
# what a reviewer needs to judge a fourth one.
SELF_BUILT = {
    "openbank-admin-ui": (
        "Next.js app. Built for real by openbank-infra/scripts/build-push-admin-ui.sh "
        "(--file openbank-admin-ui/Dockerfile), invoked from admin-ui-deploy.yml."
    ),
    "openbank-developer-portal": (
        "Static site served by nginx-unprivileged. No JVM, no quarkus-app/."
    ),
    "openbank-document-renderer": (
        "Python/WeasyPrint PDF sidecar (ADR-0162 D3). No JVM, no quarkus-app/."
    ),
}


def strip_comments(text: str) -> str:
    """Drop full-line comments so prose ABOUT a build stage is not read as one.

    The header these files carry explains the build stage that used to be here; a
    naive scan would flag every file for the sentence describing why it must not.
    """
    return "\n".join(l for l in text.split("\n") if not l.lstrip().startswith("#"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    problems: list[str] = []
    ports: dict[str, list[str]] = {}
    seen_self_built: set[str] = set()
    checked = 0

    for path in sorted(pathlib.Path(".").glob("openbank-*/Dockerfile")):
        checked += 1
        raw = path.read_text(encoding="utf-8")
        code = strip_comments(raw)

        if path.parts[0] in SELF_BUILT:
            seen_self_built.add(path.parts[0])
            # Falsify the exemption rather than trust it: a self-built recipe that has
            # lost its build stage, or that copies quarkus-app/, has been flattened into
            # the generic template and this entry is now covering the bug.
            if QUARKUS_APP_RE.search(code):
                problems.append(
                    f"{path}: is declared self-built in SELF_BUILT ({SELF_BUILT[path.parts[0]]}) "
                    f"but copies `quarkus-app/`, i.e. it has been overwritten with the generic "
                    f"Quarkus runtime recipe. Restore the real recipe, or drop the SELF_BUILT entry "
                    f"if this directory really did become a Quarkus service."
                )
            continue

        if BUILD_STAGE_RE.search(code):
            problems.append(
                f"{path}: has a `FROM ... AS <stage>` build stage. Images are built from "
                f".github/workflows/Dockerfile.deploy against a host-side quarkus-app/; a build "
                f"stage here is dead text at best and a divergent second definition at worst "
                f"(#3016)."
            )
        if COPY_FROM_RE.search(code):
            problems.append(f"{path}: has `COPY --from=`, which only makes sense with a build stage (#3016).")
        if GRADLEW_RE.search(code):
            problems.append(f"{path}: runs `gradlew`. The build happens host-side, not in the image (#3016).")

        found = EXPOSE_RE.search(raw)
        if not found:
            problems.append(
                f"{path}: no EXPOSE line. That is the ONLY thing the deploy pipeline reads from "
                f"this file; without it the container port silently falls back to 8080."
            )
        else:
            ports.setdefault(found.group(1), []).append(path.parts[0])

    # The other stale direction: an entry naming a directory that no longer has a
    # Dockerfile. Left unchecked, the list accumulates names nobody can evaluate.
    for name, reason in sorted(SELF_BUILT.items()):
        if name not in seen_self_built:
            problems.append(
                f"SELF_BUILT names {name} ({reason}) but {name}/Dockerfile does not exist. "
                f"Remove the entry."
            )

    # Duplicate ports are reported, never failed: two services CAN legitimately share
    # a port number (separate pods), but a duplicate is more often a copy-paste slip.
    for port, owners in sorted(ports.items()):
        if len(owners) > 1:
            print(f"::warning title=Duplicate EXPOSE port::{port} is declared by {', '.join(owners)} "
                  f"— legal in Kubernetes, but worth confirming it is deliberate.")

    if not problems:
        print(f"check-dockerfile-no-build-stage: OK — {checked} Dockerfiles, runtime-only, all with EXPOSE")
        return 0

    level = "error" if args.enforce else "warning"
    for p in problems:
        print(f"::{level}::{p}")
    print(f"\n{len(problems)} problem(s) across {checked} Dockerfiles.")
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
