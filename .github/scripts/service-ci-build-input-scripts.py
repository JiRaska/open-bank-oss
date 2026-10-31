#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Decide whether `.github/scripts/**` paths can affect a service build (Services CI selector).

stdin: one repo-relative path per line.  stdout: those paths that ARE build inputs.
A script is a build input when its basename occurs in the build recipe corpus (the service
CI workflows, Dockerfile.deploy, composite actions, Gradle/build-logic, module build files), or
in any script already in that closure (fixpoint).  Matching is by substring, so it over-
includes, never under-includes.  Names starting with `service-ci` and the `service-ci-steps/`
directory are always inputs.  Everything not under `.github/scripts/` is out of scope and is
echoed back (the caller keeps its own rules for those).  `--self-test` proves both verdicts.
"""
import os, subprocess, sys
from pathlib import Path

ROOTS = [".github/workflows/services-ci.yml", ".github/workflows/_service-ci.yml",
         ".github/workflows/_service-ci-pr.yml", ".github/workflows/Dockerfile.deploy",
         ".github/actions", "build-logic", "gradle", "settings.gradle.kts", "build.gradle.kts",
         "gradle.properties"]
# Gate/test/generator scripts name dozens of siblings (they are hubs); being a build input
# themselves they stay in, but what they MENTION is not pulled in transitively.
NO_EXPAND = ("check-", "check_", "gen-", "test-", "test_", "run-gates", "probe", "verify-", "regen-",
             "service-ci-build-input-scripts")  # the last one names paths in its own self-test
TEXT = {".kt", ".kts", ".java", ".sh", ".py", ".yaml", ".yml", ".gradle", ".properties", ".toml", ".json", ".txt"}


def files_under(root: Path, rel: str):
    p = root / rel
    if p.is_file():
        yield p
    elif p.is_dir():
        for d, dn, fn in os.walk(p):
            dn[:] = [x for x in dn if x not in ("node_modules", "build", ".gradle", ".git")]
            for f in fn:
                if Path(f).suffix in TEXT or f == "Dockerfile.deploy":
                    yield Path(d) / f


def read(p: Path) -> str:
    try:
        return p.read_text(errors="ignore")
    except OSError:
        return ""


def corpus_closure(root: Path):
    corpus = []
    for r in ROOTS:
        corpus += [read(f) for f in files_under(root, r)]
    # Module build files (not sources: Kotlin only cites scripts in comments) can name scripts.
    for m in sorted(root.glob("openbank-*/build.gradle.kts")):
        corpus.append(read(m))
    text = "\n".join(corpus)
    scripts = [p for p in (root / ".github/scripts").rglob("*") if p.is_file() and p.suffix != ".pyc"]
    inside = set()
    pending = list(scripts)
    chunk = text  # only newly added text can newly match a still-pending script
    while chunk and pending:
        hit = [x for x in pending if x.name in chunk]
        pending = [x for x in pending if x.name not in chunk]
        chunk = ""
        for x in hit:
            inside.add(x)
            if not x.name.startswith(NO_EXPAND):
                chunk += "\n" + strip_assertions(read(x))
        text += chunk
    return text, inside


def strip_assertions(src: str) -> str:
    """Drop the selector's own self-test assertion lines: they NAME paths to prove verdicts."""
    return "\n".join(l for l in src.splitlines()
                     if "is_inert_service_path" not in l and "selector self-test" not in l)


def is_input(path: str, text: str) -> bool:
    if not path.startswith(".github/scripts/"):
        return True
    rel = path[len(".github/scripts/"):]
    name = os.path.basename(path)
    return rel.startswith("service-ci-steps/") or name.startswith("service-ci") or name in text


def main():
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    cache = os.environ.get("SCI_CORPUS_FILE")  # per-selector-run memo, never shared across runs
    if cache and os.path.getsize(cache) > 0:
        text = Path(cache).read_text(errors="ignore")
    else:
        text, _ = corpus_closure(root)
        if cache:
            Path(cache).write_text(text)
    for line in sys.stdin.read().splitlines():
        if line and is_input(line, text):
            print(line)


def self_test():
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    text, _ = corpus_closure(root)
    must_build = [".github/scripts/service-ci-select-modules.sh", ".github/scripts/service-ci-steps/build-test.sh",
                  ".github/scripts/derive-verification-modules.py", ".github/scripts/verify-packaged-library-sbom.py",
                  ".github/scripts/filter-lib-test-only-paths.py", ".github/scripts/brand-new-unreferenced-but-service-ci-x.sh"[:0] or ".github/scripts/service-ci-new.sh"]
    must_skip = [".github/scripts/merge-train.py", ".github/scripts/check-security-checklist.py",
                 ".github/scripts/test-merge-train-owner-approval.py"]
    bad = [p for p in must_build if not is_input(p, text)] + [p for p in must_skip if is_input(p, text)]
    if bad:
        print("SELF-TEST FAIL:", bad)
        sys.exit(1)
    print("ok: build-input scripts selected, unrelated scripts skipped")


if __name__ == "__main__":
    self_test() if "--self-test" in sys.argv else main()
