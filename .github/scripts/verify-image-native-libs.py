#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Prove every native .so a service image ships can actually link in the runtime base (#3354).

WHAT WENT WRONG WITHOUT THIS
    openbank-fraud-service bundles com.microsoft.onnxruntime (ADR-0139 phase-1b). Its
    libonnxruntime.so is linked against glibc + libstdc++; the runtime base was
    eclipse-temurin:25-jre-alpine (musl). OrtEnvironment.getEnvironment() therefore threw
    UnsatisfiedLinkError on the first request in EVERY deployed environment, from the day the
    adapter shipped, and the model scored exactly nothing.

    Nothing in this repo could see it, and that is the interesting part:

      * the unit tests construct a real OrtEnvironment and assert a real score — but they run on
        a glibc CI runner, so they are green against a musl image by construction;
      * the Gradle build, detekt, ktlint and the image build itself never execute a line of
        native code;
      * the pod is Ready — the health probes never touch the model;
      * and the adapter degrades to `null` on purpose, so the only trace is one ERROR line
        (and, before #3376, a 500 on an unrelated read endpoint).

    A green build proves nothing here. The only thing that can be wrong out loud is running the
    real dynamic loader, from the real base image, over the real .so the image ships. That is
    what this does.

HOW
    1. Read the runtime base from .github/workflows/Dockerfile.deploy — the single source every
       image producer copies. NEVER give this script its own copy of the base: a second copy
       moves with the first and keeps passing about an image nobody deploys.
    2. Scan the service's fast-jar lib/ tree for jars carrying linux natives for the target
       architecture, and extract those .so files.
    3. `ldd` each one INSIDE that base image, on that platform.
    4. Fail on any unresolved dependency.

    Step 2 is what keeps this honest as the fleet changes: the scope is DERIVED from the jars a
    service actually ships, not from a hand-kept list of "services with native code". A service
    that gains a native dependency is covered the day it gains it, and one that drops it stops
    paying for the check with nothing to update.

    Intra-bundle sonames are not findings. onnxruntime ships libonnxruntime4j_jni.so, whose
    NEEDED entry `libonnxruntime.so.1` is resolved at runtime because the JVM System.load()s its
    sibling from the same extracted directory first. A missing dependency whose name matches an
    extracted sibling is therefore ignored — matched against the extracted file set, which is
    derived, not against a literal.

USAGE
    verify-image-native-libs.py <lib-dir> [--arch arm64|amd64] [--base IMAGE]
    verify-image-native-libs.py --self-test

    <lib-dir> is the fast-jar lib/ directory staged into the build context, e.g.
    openbank-fraud-service/build/quarkus-app/lib. Exits 0 when there is nothing to check.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCKERFILE_DEPLOY = REPO_ROOT / ".github" / "workflows" / "Dockerfile.deploy"

# Jar-internal directories holding Linux natives, per Docker platform arch. onnxruntime uses
# `linux-aarch64`/`linux-x64`; other packagers use the uname spellings, so accept both.
ARCH_DIR_PATTERNS = {
    "arm64": re.compile(r"(^|/)linux[-_/](aarch64|arm64)/", re.I),
    "amd64": re.compile(r"(^|/)linux[-_/](x64|x86[-_]64|amd64)/", re.I),
}

FROM_RE = re.compile(r"^\s*FROM\s+(\S+)", re.M)

# `ldd` says it two ways and we must catch both, because the musl and glibc loaders disagree:
#   glibc: "\tlibstdc++.so.6 => not found"
#   musl:  "Error loading shared library libstdc++.so.6: No such file or directory (needed by ..)"
GLIBC_MISSING_RE = re.compile(r"^\s*(\S+)\s+=>\s+not found\s*$", re.M)
MUSL_MISSING_RE = re.compile(r"^Error loading shared library ([^:]+):", re.M)
# musl also reports every unresolvable symbol; one line per symbol, thousands of them. Counted,
# never printed in full.
MUSL_RELOC_RE = re.compile(r"^Error relocating ", re.M)


def read_base_image() -> str:
    """The runtime base, read out of the committed Dockerfile — never a second copy."""
    text = DOCKERFILE_DEPLOY.read_text(encoding="utf-8")
    match = FROM_RE.search(text)
    if not match:
        sys.exit(f"::error::no FROM line in {DOCKERFILE_DEPLOY}")
    return match.group(1)


def find_native_entries(lib_dir: pathlib.Path, arch: str) -> list[tuple[pathlib.Path, str]]:
    """(jar, entry) pairs for every Linux .so this image would ship for `arch`."""
    pattern = ARCH_DIR_PATTERNS[arch]
    found: list[tuple[pathlib.Path, str]] = []
    for jar in sorted(lib_dir.rglob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as zf:
                for name in zf.namelist():
                    if name.endswith(".so") and pattern.search(name):
                        found.append((jar, name))
        except zipfile.BadZipFile:
            print(f"::warning::not a readable jar, skipped: {jar}")
    return found


def extract(entries: list[tuple[pathlib.Path, str]], dest: pathlib.Path) -> list[str]:
    names: list[str] = []
    for jar, entry in entries:
        base = pathlib.PurePosixPath(entry).name
        with zipfile.ZipFile(jar) as zf, open(dest / base, "wb") as out:
            shutil.copyfileobj(zf.open(entry), out)
        (dest / base).chmod(0o755)
        names.append(base)
    return names


def parse_ldd(output: str, sibling_names: list[str]) -> list[str]:
    """Unresolved dependencies in one `ldd` run, minus the ones a sibling in the bundle provides.

    `sibling_names` is the extracted file set, so the exemption cannot drift away from what the
    image actually ships. A NEEDED soname carries a version suffix the file on disk does not
    (`libonnxruntime.so.1` vs `libonnxruntime.so`), hence the prefix match.
    """
    missing = set(GLIBC_MISSING_RE.findall(output)) | set(MUSL_MISSING_RE.findall(output))
    unresolved = []
    for dep in sorted(missing):
        if any(dep == sib or dep.startswith(sib + ".") for sib in sibling_names):
            continue
        unresolved.append(dep)
    return unresolved


def platform_ref(base: str, arch: str) -> str:
    """Rewrite an index reference to the PLATFORM-SPECIFIC manifest digest for `arch`.

    An index digest cannot be materialised twice for two architectures in one daemon: the second
    pull is refused with `cannot overwrite digest ...` (measured on Docker 29's containerd store).
    ghcr-publish checks amd64 and arm64 back to back, so that is not hypothetical. The
    per-platform manifest digests are distinct, so pulling those instead has no collision.

    Falls back to `base` when the reference is not a multi-platform index, or when buildx is
    unavailable — the plain reference is correct in both of those cases.
    """
    repo = base.split("@", 1)[0].split(":")[0] if "@" in base else base.rsplit(":", 1)[0]
    proc = subprocess.run(
        ["docker", "buildx", "imagetools", "inspect", "--raw", base],
        capture_output=True, text=True, check=False,
    )
    if proc.returncode != 0:
        return base
    try:
        index = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return base
    for manifest in index.get("manifests", []):
        platform = manifest.get("platform", {})
        if platform.get("os") == "linux" and platform.get("architecture") == arch:
            return f"{repo}@{manifest['digest']}"
    return base


def resolve_local_image(base: str, arch: str) -> str:
    """Pull the platform-specific manifest for `arch` and return its local image ID."""
    ref = platform_ref(base, arch)
    pull = subprocess.run(
        ["docker", "pull", "--platform", f"linux/{arch}", ref],
        capture_output=True, text=True, check=False,
    )
    if pull.returncode != 0:
        sys.exit(f"::error::could not pull {ref} for linux/{arch}: {pull.stderr.strip()}")
    inspect = subprocess.run(
        ["docker", "image", "inspect", "--format", "{{.Id}}", ref],
        capture_output=True, text=True, check=False,
    )
    if inspect.returncode != 0 or not inspect.stdout.strip():
        sys.exit(f"::error::could not resolve a local image id for {ref}: {inspect.stderr.strip()}")
    return inspect.stdout.strip()


def ldd_in_base(base: str, arch: str, workdir: pathlib.Path, names: list[str]) -> dict[str, str]:
    """Run ldd on each .so inside the real base image; returns {name: raw ldd output}."""
    script = "; ".join(f'echo "@@ {n}"; ldd /n/{n} 2>&1 || true' for n in names)
    image_id = resolve_local_image(base, arch)
    proc = subprocess.run(
        [
            "docker", "run", "--rm", "--platform", f"linux/{arch}",
            "-v", f"{workdir}:/n:ro", "--entrypoint", "sh", image_id, "-c", script,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0 and not proc.stdout:
        sys.exit(f"::error::could not run ldd in {base}: {proc.stderr.strip()}")
    blocks: dict[str, str] = {}
    current = None
    for line in proc.stdout.splitlines():
        if line.startswith("@@ "):
            current = line[3:].strip()
            blocks[current] = ""
        elif current is not None:
            blocks[current] += line + "\n"
    return blocks


def self_test() -> int:
    """Falsify the verdict logic against REAL captured ldd output from both loaders.

    The docker half cannot run everywhere; the parsing half is where a wrong answer would be
    silent, so it is what gets a known-positive and a known-negative. Both fixtures are verbatim
    excerpts of runs against the two candidate bases on linux/arm64.
    """
    musl_bad = (
        "\t/lib/ld-musl-aarch64.so.1 (0xffffb93a0000)\n"
        "\tlibdl.so.2 => /lib/ld-musl-aarch64.so.1 (0xffffb93a0000)\n"
        "Error loading shared library libstdc++.so.6: No such file or directory"
        " (needed by /n/libonnxruntime.so)\n"
        "Error loading shared library libgcc_s.so.1: No such file or directory"
        " (needed by /n/libonnxruntime.so)\n"
        "Error loading shared library ld-linux-aarch64.so.1: No such file or directory"
        " (needed by /n/libonnxruntime.so)\n"
        "Error relocating /n/libonnxruntime.so: __cxa_begin_catch: symbol not found\n"
    )
    glibc_good = (
        "\tlinux-vdso.so.1 (0x0000ffffbccdb000)\n"
        "\tlibstdc++.so.6 => /usr/lib/aarch64-linux-gnu/libstdc++.so.6 (0x0000ffffbb870000)\n"
        "\tlibgcc_s.so.1 => /usr/lib/aarch64-linux-gnu/libgcc_s.so.1 (0x0000ffffbb760000)\n"
        "\tlibc.so.6 => /usr/lib/aarch64-linux-gnu/libc.so.6 (0x0000ffffbb590000)\n"
        "\t/lib/ld-linux-aarch64.so.1 (0x0000ffffbcc90000)\n"
    )
    # The JNI shim on the WORKING base: its only "missing" entry is its own sibling, which the
    # JVM loads first. This is the case the check must NOT flag, and the one a naive
    # "any `not found` fails" rule would have failed on the correct image.
    glibc_sibling = (
        "\tlinux-vdso.so.1 (0x0000ffffa20cc000)\n"
        "\tlibonnxruntime.so.1 => not found\n"
        "\tlibc.so.6 => /usr/lib/aarch64-linux-gnu/libc.so.6 (0x0000ffffa1e70000)\n"
    )
    siblings = ["libonnxruntime.so", "libonnxruntime4j_jni.so"]
    failures = []

    got = parse_ldd(musl_bad, siblings)
    want = ["ld-linux-aarch64.so.1", "libgcc_s.so.1", "libstdc++.so.6"]
    if got != want:
        failures.append(f"musl fixture: expected {want}, got {got}")

    if parse_ldd(glibc_good, siblings):
        failures.append(f"glibc fixture must be clean, got {parse_ldd(glibc_good, siblings)}")

    if parse_ldd(glibc_sibling, siblings):
        failures.append(
            "an intra-bundle soname must not be a finding, got "
            f"{parse_ldd(glibc_sibling, siblings)}"
        )

    # ...but it must still be a finding when no such sibling is shipped, or the exemption would
    # swallow a genuinely absent library.
    if parse_ldd(glibc_sibling, []) != ["libonnxruntime.so.1"]:
        failures.append("a missing lib with no sibling must be reported")

    # The base must come from the committed Dockerfile, and must be a glibc one today.
    base = read_base_image()
    if "alpine" in base or "musl" in base:
        failures.append(f"Dockerfile.deploy base is musl again: {base} — see #3354")

    for line in failures:
        print(f"::error::self-test: {line}")
    if failures:
        return 1
    print(f"self-test OK (base from Dockerfile.deploy: {base})")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("lib_dir", nargs="?", help="fast-jar lib/ directory staged into the image")
    ap.add_argument("--arch", default="arm64", choices=sorted(ARCH_DIR_PATTERNS))
    ap.add_argument("--base", default=None, help="override the base image (testing only)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.lib_dir:
        ap.error("lib_dir is required unless --self-test is given")

    lib_dir = pathlib.Path(args.lib_dir)
    if not lib_dir.is_dir():
        sys.exit(f"::error::not a directory: {lib_dir}")

    entries = find_native_entries(lib_dir, args.arch)
    if not entries:
        print(f"native-lib check: no linux/{args.arch} .so shipped under {lib_dir} — nothing to do")
        return 0

    base = args.base or read_base_image()
    print(f"native-lib check: {len(entries)} .so for linux/{args.arch} against {base}")

    with tempfile.TemporaryDirectory() as tmp:
        workdir = pathlib.Path(tmp)
        names = extract(entries, workdir)
        blocks = ldd_in_base(base, args.arch, workdir, names)

        failed = False
        for name in names:
            output = blocks.get(name, "")
            unresolved = parse_ldd(output, names)
            relocs = len(MUSL_RELOC_RE.findall(output))
            if unresolved:
                failed = True
                print(f"::error::{name}: unresolved in {base}: {', '.join(unresolved)}")
                if relocs:
                    print(f"::error::{name}: and {relocs} unresolvable relocation(s)")
            else:
                print(f"  ok  {name}")

    if failed:
        print(
            "::error::the runtime base cannot load a native library this image ships. "
            "This is exactly the #3354 failure: it is invisible to the build, to the health "
            "probes and to every test that runs on a glibc runner. Fix the base in "
            ".github/workflows/Dockerfile.deploy, do not skip this check."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
