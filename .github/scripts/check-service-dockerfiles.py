#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A per-service Dockerfile declares a port. It must not pretend to be a build recipe.

Why this exists
---------------
All 49 Gradle `openbank-*/Dockerfile` files carried a build stage that could not run. None had
`COPY build-logic` (the root `settings.gradle.kts` `includeBuild`s it for the
`openbank.quarkus-service` plugin) and none copied the ADR-0122 `openbank-libs-*` siblings, so a
real `docker build` died at configuration time with `Included build '/build/build-logic' does not
exist` — measured, 77 s, on the file usually cited as the correct shape (#3016).

Nothing noticed because nothing builds them. `auto-deploy.yml`, `ghcr-publish.yml` and
`build-push-service.sh` all copy `.github/workflows/Dockerfile.deploy` into a generated context and
build host-side; `build-push-service.sh` documents why the in-image Gradle build was abandoned. All
three read exactly one thing from the per-service file: its `EXPOSE` line, used to set the
container port, **falling back to 8080 when absent**.

That fallback is what makes the second rule below matter as much as the first: a Dockerfile that
loses its `EXPOSE` does not fail, it silently publishes the wrong port.

WHAT IT CHECKS, for every `openbank-*/Dockerfile` except the two that are genuinely built:

  1. exactly one `EXPOSE <port>` line;
  2. no Gradle build stage — no `gradlew`, no `gradle:` base image, no `COPY --from=build`.

EXEMPT, and they must stay exempt: `openbank-developer-portal` (static nginx) and
`openbank-document-renderer` (WeasyPrint sidecar, ADR-0162 D3) are real, buildable, non-JVM images
with their own recipes. #3016 counts them among the 51 dead files; they are not, and stripping them
would have deleted two working builds.

Usage:  check-service-dockerfiles.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
EXPOSE_RE = re.compile(r"^EXPOSE\s+(\d+)\s*$", re.MULTILINE)
# A build stage, in any of the spellings the stripped files used.
BUILD_STAGE_RE = re.compile(r"(?m)^(?!\s*#).*(gradlew|FROM\s+gradle:|--from=build)")

# Real, buildable images with their own recipes — not the dead-Gradle shape this gate is about.
GENUINELY_BUILT = {
    "openbank-developer-portal": "static nginx image, built from this Dockerfile",
    "openbank-document-renderer": "WeasyPrint sidecar (ADR-0162 D3), built from this Dockerfile",
}


def dockerfiles() -> list[pathlib.Path]:
    return sorted(REPO.glob("openbank-*/Dockerfile"))


def findings() -> tuple[list[str], int]:
    messages: list[str] = []
    checked = 0
    for path in dockerfiles():
        service = path.parts[len(REPO.parts)]
        rel = path.relative_to(REPO)
        text = path.read_text(encoding="utf-8")

        ports = EXPOSE_RE.findall(text)
        if len(ports) != 1:
            messages.append(
                f"::error file={rel}::{service}'s Dockerfile has {len(ports)} EXPOSE lines, expected "
                f"exactly 1. auto-deploy / ghcr-publish / build-push-service.sh grep this to set the "
                f"container port and FALL BACK TO 8080 when it is absent — so losing it publishes "
                f"the wrong port silently, rather than failing (#3016).",
            )

        if service in GENUINELY_BUILT:
            continue
        checked += 1
        if BUILD_STAGE_RE.search(text):
            messages.append(
                f"::error file={rel}::{service}'s Dockerfile has a Gradle build stage. Nothing "
                f"builds these — the image comes from .github/workflows/Dockerfile.deploy, copied "
                f"into a generated context and built host-side. A build stage here cannot run "
                f"(no COPY build-logic, no ADR-0122 openbank-libs-* siblings) and reads as the "
                f"recipe anyway (#3016).",
            )
    return messages, checked


def selftest() -> int:
    """Feed both rules inputs they MUST flag and inputs they must NOT."""
    files = dockerfiles()
    if len(files) < 20:
        print(f"selftest FAIL: only {len(files)} Dockerfile(s) found — the scan is broken.")
        return 1
    for service in GENUINELY_BUILT:
        if not (REPO / service / "Dockerfile").is_file():
            print(f"selftest FAIL: {service}/Dockerfile is missing — the exemption is stale.")
            return 1

    cases = [
        ("FROM x\nEXPOSE 8101\n", False, "the stripped shape"),
        ("FROM x AS build\nRUN ./gradlew build\nEXPOSE 8101\n", True, "a gradle build stage"),
        ("FROM gradle:8\nEXPOSE 8101\n", True, "a gradle base image"),
        ("FROM x\nCOPY --from=build /a /b\nEXPOSE 8101\n", True, "a leftover --from=build COPY"),
        # The prose in the stripped header NAMES gradlew and build-logic. A comment must not trip
        # it — the code-about-code collision this repo hits repeatedly.
        ("# it used to run ./gradlew and lacked COPY build-logic\nFROM x\nEXPOSE 8101\n", False,
         "prose naming gradlew"),
    ]
    for text, must_flag, what in cases:
        if bool(BUILD_STAGE_RE.search(text)) != must_flag:
            verb = "missed" if must_flag else "wrongly flagged"
            print(f"selftest FAIL: {verb} {what}")
            return 1

    if len(EXPOSE_RE.findall("FROM x\nEXPOSE 8101\nEXPOSE 8102\n")) != 2:
        print("selftest FAIL: the EXPOSE counter cannot see a duplicate.")
        return 1

    print(f"selftest OK: {len(cases)} build-stage cases both ways (including prose that names "
          f"gradlew), duplicate-EXPOSE detected, {len(files)} Dockerfile(s) scanned.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    messages, checked = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-service-dockerfiles: {checked} declaration-only Dockerfile(s) + "
          f"{len(GENUINELY_BUILT)} genuinely-built exempt — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
