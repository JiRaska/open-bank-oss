#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every `docker buildx build --push` carries `--provenance=false` (and never `--sbom=true`).

WHY THIS EXISTS (#11573)

With the docker-container builder driver, buildx attaches its own provenance attestation by
default, and that turns a single-platform push into an OCI image INDEX. Kyverno's CEL
ImageValidatingPolicy loader resolves an index with no platform option -- to linux/amd64 -- and
our images are arm64-only, so the policy errors and, under Deny+Fail, blocks every Pod using the
image. admin-ui shipped exactly that (#11437, fixed by #11574).

WHAT DECIDES IT, AND WHAT THIS IS

The decisive control is the runtime refusal in `openbank-infra/scripts/lib/cosign-attest.sh`
(`assert_single_image_manifest`): it reads the pushed manifest and refuses to sign an index,
whatever driver built it. This static check is the cheap early warning in front of it: it fails a
PR that adds a push without the flag, instead of letting the build discover it after the push.
It is a text convention check over shell scripts and workflows -- `\\` continuations are joined
and a `"${arr[@]}"` argument is expanded from that array's literal definition in the same file.
An invocation it cannot see (a flag assembled at runtime) is the runtime guard's job.

Scope: every push site, GHCR included. `ghcr-publish.yml` is multi-arch ON PURPOSE (an index is
its product) and is not admitted by the ECR policy, but it already passes `--provenance=false`,
so it needs no exemption and there is no allowlist.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOTS = (
    (pathlib.Path("openbank-infra/scripts"), "*.sh"),
    (pathlib.Path(".github/scripts"), "*.sh"),
    (pathlib.Path(".github/workflows"), "*.yml"),
)
BUILD = re.compile(r"\bdocker\s+buildx\s+build\b")
ARRAY_REF = re.compile(r'"?\$\{(\w+)\[@\]\}"?')


def logical_lines(text: str) -> list[str]:
    out, buf = [], ""
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.lstrip()
        if stripped.startswith("#") and not buf:
            continue
        if line.endswith("\\"):
            buf += line[:-1] + " "
            continue
        out.append(buf + line)
        buf = ""
    if buf:
        out.append(buf)
    return out


def array_body(text: str, name: str) -> str:
    m = re.search(rf"\b{re.escape(name)}(\+?)=\(([^)]*)\)", text, re.S)
    return m.group(2) if m else ""


def findings_for(text: str) -> tuple[int, list[str]]:
    seen, bad = 0, []
    for line in logical_lines(text):
        if not BUILD.search(line):
            continue
        cmd = line
        for arr in ARRAY_REF.findall(line):
            cmd += " " + array_body(text, arr)
        cmd = re.sub(r"(?m)^\s*#.*$", "", cmd)
        if not re.search(r"(?<![\w-])--push\b|push=true", cmd):
            continue
        seen += 1
        if not re.search(r"--provenance[= ]false\b", cmd):
            bad.append("buildx build --push without --provenance=false: " + line.strip()[:160])
        if re.search(r"--sbom[= ]true\b", cmd):
            bad.append("buildx build --push with --sbom=true: " + line.strip()[:160])
    return seen, bad


def self_test() -> int:
    cases = {
        # must FAIL
        "plain push, no flag": ('docker buildx build --platform linux/arm64 -t x --push .', False),
        "continued push, no flag": ('docker buildx build \\\n  --platform p \\\n  --push \\\n  .', False),
        "array push, no flag": ('args=(\n  --platform p\n  --push\n)\ndocker buildx build "${args[@]}"', False),
        "sbom true": ('docker buildx build --provenance=false --sbom=true --push .', False),
        # must PASS
        "flag on one line": ('docker buildx build --provenance=false --push .', True),
        "flag continued": ('docker buildx build \\\n  --provenance=false \\\n  --push .', True),
        "flag in array": ('a=(\n --provenance=false\n --push\n)\ndocker buildx build "${a[@]}"', True),
        "no push (load)": ('docker buildx build --load -t x .', True),
        "commented example": ('# docker buildx build --push .\necho ok', True),
    }
    failed = 0
    for name, (text, want_clean) in cases.items():
        _, bad = findings_for(text)
        if (not bad) != want_clean:
            print(f"SELFTEST FAIL: {name}: findings={bad}")
            failed += 1
    # Known-positive on the real tree: stripping the flag from admin-ui's script must flag it.
    real = pathlib.Path("openbank-infra/scripts/build-push-admin-ui.sh")
    if real.exists():
        _, bad = findings_for(real.read_text().replace("--provenance=false", ""))
        if not bad:
            print("SELFTEST FAIL: build-push-admin-ui.sh without --provenance=false was not flagged")
            failed += 1
    print("self-test:", "FAIL" if failed else f"PASS ({len(cases) + 1} cases)")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    total, bad = 0, []
    for root, pattern in ROOTS:
        for path in sorted(root.rglob(pattern)):
            seen, found = findings_for(path.read_text(encoding="utf-8", errors="replace"))
            total += seen
            bad += [f"{path}: {f}" for f in found]
    print(f"SUBJECTS={total}")
    for f in bad:
        print(f"::error::{f}")
    if bad:
        print(f"{len(bad)} buildx push site(s) can push an OCI index (#11573). Add "
              "--provenance=false --sbom=false.")
        return 1
    print(f"OK: {total} buildx push site(s) all pass --provenance=false.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
