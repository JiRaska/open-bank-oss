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

So three rules:

  1. No build stage. A `FROM ... AS build` here is either dead text or a second,
     divergent definition of how images are made. Both are worse than nothing.
  2. EXPOSE is mandatory. It is the only live contract — losing it does not fail
     anything loudly, it silently sets the container port to 8080.
  3. The FROM must be byte-identical to the one in `Dockerfile.deploy` (#3354).

Rule 3 exists because #3618 moved the fleet runtime base from
`eclipse-temurin:25-jre-alpine` (musl) to `eclipse-temurin:25-jre` (glibc) — the
ONNX Runtime `.so` openbank-fraud-service bundles is glibc-linked and had never
loaded — and 53 files went on declaring the musl base. Nothing broke, which is the
problem: a wrong FROM here is exactly the "reads as authoritative while being
fiction" failure #3016 exists to prevent.

Deleting the FROM instead would be worse, and that is not obvious. These files are
NOT read only by the deploy pipeline: `openbank-admin-ui/scripts/generate-cluster-topology.mjs`
parses `openbank-ledger-service/Dockerfile` for `imageFacts()` and renders the base
image into the /docs/cluster dossier (ADR-0081). With no FROM to parse it falls back
to a HARDCODED `eclipse-temurin:25-jre-alpine` literal — i.e. removing the second
copy would resurrect the fiction in the UI rather than retire it. So: keep the FROM,
and make it impossible for it to disagree.

Rules 1 and 2 assume the file describes a Quarkus service deployed by that pipeline.
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
    check-dockerfile-no-build-stage.py --self-test  # falsify rule 3 both ways
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
# Matched against comment-STRIPPED text on purpose. Dockerfile.deploy's own header
# quotes the retired `eclipse-temurin:25-jre-alpine` three times to explain why it is
# retired, and every per-service header now names Dockerfile.deploy; a scan over raw
# text would read that prose as a declaration. Same collision class as #2450.
FROM_RE = re.compile(r'^FROM\s+(\S+)\s*$', re.M)

DEPLOY_RECIPE = ".github/workflows/Dockerfile.deploy"

# Files outside the openbank-*/Dockerfile glob that still declare the fleet runtime
# base and so must track it. None is referenced by any workflow or script today —
# they are declarations, same as the per-service ones, and drift the same way.
EXTRA_FLEET_BASE = [
    "Dockerfile.scanner",
    "openbank-security-scanner/Dockerfile.prebuilt",
    "openbank-notification-service/Dockerfile.runtime",
]

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


def fleet_base_files(root: pathlib.Path) -> list[pathlib.Path]:
    """Every file that declares the fleet runtime base, Dockerfile.deploy excluded."""
    files = [p for p in sorted(root.glob("openbank-*/Dockerfile"))
             if p.parts[-2] not in SELF_BUILT]
    files += [root / name for name in EXTRA_FLEET_BASE if (root / name).is_file()]
    return files


def check_fleet_bases(root: pathlib.Path) -> list[str]:
    """Rule 3: every declared runtime base equals Dockerfile.deploy's, exactly."""
    deploy = root / DEPLOY_RECIPE
    if not deploy.is_file():
        return [f"{DEPLOY_RECIPE} is missing — it is the single source for the runtime "
                f"base image and this check cannot run without it."]

    froms = FROM_RE.findall(strip_comments(deploy.read_text(encoding="utf-8")))
    if len(froms) != 1:
        return [f"{DEPLOY_RECIPE} declares {len(froms)} FROM lines; expected exactly 1. "
                f"The fleet runtime base must be stated once."]
    expected = froms[0]

    problems: list[str] = []
    for path in fleet_base_files(root):
        found = FROM_RE.findall(strip_comments(path.read_text(encoding="utf-8")))
        if len(found) != 1:
            problems.append(
                f"{path.relative_to(root)}: declares {len(found)} FROM lines; expected "
                f"exactly 1, matching {DEPLOY_RECIPE}.")
            continue
        if found[0] != expected:
            problems.append(
                f"{path.relative_to(root)}: FROM {found[0]} does not match "
                f"{DEPLOY_RECIPE}, which declares FROM {expected}. Nothing builds this "
                f"file, so a stale base breaks nothing and says the wrong thing forever "
                f"(#3354) — and admin-ui's cluster dossier renders it. Copy the line.")
    return problems


def self_test() -> int:
    """Falsify rule 3 in BOTH directions against a synthetic tree.

    A check that has only ever passed is unfalsified. The negative case matters as
    much as the positive one here: the rule reads comment-stripped text, and every
    file it inspects contains prose naming both the old and the new base image, so a
    scan over raw text would flag a correct tree.
    """
    import shutil
    import tempfile

    good = "FROM eclipse-temurin:25-jre@sha256:" + "a" * 64
    bad = "FROM eclipse-temurin:25-jre-alpine@sha256:" + "b" * 64
    # Prose naming the retired base, of the exact shape the real files carry.
    prose = f"# This used to be {bad.removeprefix('FROM ')} and must never go back.\n"

    failures = []
    tmp = pathlib.Path(tempfile.mkdtemp())
    try:
        (tmp / ".github" / "workflows").mkdir(parents=True)
        (tmp / DEPLOY_RECIPE).write_text(prose + good + "\nEXPOSE 8080\n")
        (tmp / "openbank-a-service").mkdir()
        (tmp / "openbank-b-service").mkdir()
        agreeing = tmp / "openbank-a-service" / "Dockerfile"
        drifted = tmp / "openbank-b-service" / "Dockerfile"

        agreeing.write_text(prose + good + "\nEXPOSE 8100\n")
        drifted.write_text(prose + good + "\nEXPOSE 8101\n")
        if check_fleet_bases(tmp):
            failures.append("a tree where every FROM agrees was flagged (false positive)")

        drifted.write_text(prose + bad + "\nEXPOSE 8101\n")
        found = check_fleet_bases(tmp)
        if not any("openbank-b-service/Dockerfile" in p for p in found):
            failures.append("a drifted FROM was NOT flagged (the rule cannot fire)")
        if any("openbank-a-service/Dockerfile" in p for p in found):
            failures.append("the agreeing file was flagged alongside the drifted one")

        # An EXTRA_FLEET_BASE file must be in scope too, not just the glob.
        drifted.write_text(prose + good + "\nEXPOSE 8101\n")
        (tmp / EXTRA_FLEET_BASE[0]).write_text(prose + bad + "\nEXPOSE 8120\n")
        if not any(EXTRA_FLEET_BASE[0] in p for p in check_fleet_bases(tmp)):
            failures.append(f"{EXTRA_FLEET_BASE[0]} drift was NOT flagged (out of scope)")

        # A SELF_BUILT directory has its own base on purpose and must be exempt.
        (tmp / EXTRA_FLEET_BASE[0]).unlink()
        exempt = tmp / next(iter(SELF_BUILT))
        exempt.mkdir()
        (exempt / "Dockerfile").write_text(prose + bad + "\nEXPOSE 3000\n")
        if check_fleet_bases(tmp):
            failures.append("a SELF_BUILT Dockerfile was held to the fleet base")
    finally:
        shutil.rmtree(tmp)

    for f in failures:
        print(f"self-test FAILED: {f}")
    if failures:
        return 1
    print("check-dockerfile-no-build-stage --self-test: OK — rule 3 fires on drift, "
          "stays quiet on agreement, covers the non-glob files, exempts SELF_BUILT")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    problems: list[str] = check_fleet_bases(pathlib.Path("."))
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
