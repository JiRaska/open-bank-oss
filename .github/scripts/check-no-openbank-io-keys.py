#!/usr/bin/env python3
"""No new `openbank.io/` keys (gate `no-openbank-io-keys`).

The project does not own the `openbank.io` domain, so every Kubernetes label, annotation,
selector and taint key moved to `open-bank.tech/` (docs/runbooks/node-keys-open-bank-tech-migration.md).
Old-prefix keys kept reappearing afterwards because nothing refused them: a new manifest is
usually copied from an old one. This gate refuses them.

What is scanned: every tracked file with a code/config suffix (YAML, TF, Kotlin, scripts, JSON,
rego, TS/JS, Dockerfiles). Markdown is scanned only inside fenced code blocks: a fenced block is
what someone pastes into a shell (`kubectl get ... -l <key>=...`), whereas prose about the
migration has to be able to name the old prefix. Prose mentions are exempt.

The only allowed occurrences are the four NODE keys the runbook moves in stages, and only in the
files that set or read them today (ALLOWED_FILES). A node key in any other file fails too, so the
staged set cannot grow; when stage 3 lands, delete the entries and the allowlist empties.

    check-no-openbank-io-keys.py              # scan the repo (enforced)
    check-no-openbank-io-keys.py --no-allowlist   # proof: the node keys are then reported
    check-no-openbank-io-keys.py --self-test
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SELF = ".github/scripts/check-no-openbank-io-keys.py"

NODE_KEYS = frozenset({"pool", "stateful", "runner", "runner-tier"})

# Files that set or read a node key under the staged runbook. Anything else must not carry one.
ALLOWED_FILES = frozenset({
    ".github/gates/gates.yaml",
    ".github/scripts/check-stateful-not-on-spot.py",
    "openbank-infra/aws/envs/sandbox-platform/arc-runners.tf",
    "openbank-infra/aws/envs/sandbox-platform/bootstrap-schedule.tf",
    "openbank-infra/aws/envs/sandbox-platform/main.tf",
    "openbank-infra/aws/modules/eks/main.tf",
    "openbank-infra/gitops/apps/alloy.yaml",
    "openbank-infra/gitops/apps/falco.yaml",
    "openbank-infra/gitops/components/ai-platform/langfuse-clickhouse.yaml",
    "openbank-infra/gitops/components/ai-platform/langfuse-v3.yaml",
    "openbank-infra/gitops/components/ai-platform/langfuse-valkey.yaml",
    "openbank-infra/gitops/components/customer-edge/redis.yaml",
    "openbank-infra/gitops/components/kyverno/stateful-on-demand-cel.yaml",
})

CODE_SUFFIXES = (
    ".yaml", ".yml", ".tf", ".tfvars", ".hcl", ".kt", ".kts", ".java", ".py", ".sh", ".bash",
    ".json", ".rego", ".ts", ".tsx", ".js", ".mjs", ".cjs", ".toml", ".properties", ".tpl",
    ".gotmpl", ".cue", ".jsonnet", ".libsonnet",
)
CODE_NAMES = ("Dockerfile", "Makefile", "Justfile")

# A key: `openbank.io/` followed by a DNS-label-ish name. Trailing dots are prose punctuation.
KEY_RE = re.compile(r"openbank\.io/([A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9])?)")
FENCE_RE = re.compile(r"^\s*(```|~~~)")


def is_code(path: str) -> bool:
    name = path.rsplit("/", 1)[-1]
    return path.endswith(CODE_SUFFIXES) or name in CODE_NAMES or name.startswith("Dockerfile")


def scan_lines(path: str, text: str):
    """Yield (lineno, line) that are in scope for `path`."""
    lines = text.splitlines()
    if path.endswith(".md"):
        in_fence = False
        for i, line in enumerate(lines, 1):
            if FENCE_RE.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                yield i, line
    else:
        yield from enumerate(lines, 1)


def findings_for(path: str, text: str, allowlist: bool) -> list[str]:
    out = []
    for i, line in scan_lines(path, text):
        for m in KEY_RE.finditer(line):
            key = m.group(1)
            if allowlist and key in NODE_KEYS and path in ALLOWED_FILES:
                continue
            why = ("node key outside the files the staged runbook covers" if key in NODE_KEYS
                   else f"use open-bank.tech/{key}")
            out.append(f"{path}:{i}: openbank.io/{key} ({why})")
    return out


def scan(root: Path, files: list[str], allowlist: bool) -> tuple[int, list[str]]:
    subjects, findings = 0, []
    for f in files:
        if f == SELF or not (is_code(f) or f.endswith(".md")):
            continue
        p = root / f
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
            continue
        subjects += 1
        findings += findings_for(f, text, allowlist)
    return subjects, findings


def tracked(root: Path) -> list[str]:
    r = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
    return [x for x in r.stdout.decode().split("\0") if x]


def self_test() -> int:
    k = "openbank" + ".io/"  # keep literal keys out of this file's own scan surface
    cases = [
        # (path, content, allowlist, must_fail, label)
        ("gitops/x/postgres.yaml", f"labels:\n  {k}durability-class: ledger-class\n", True, True,
         "known-positive: CNPG label"),
        ("gitops/x/es.yaml", f'  {k}rotation-deadline: "2027-01-01"\n', True, True,
         "known-positive: annotation"),
        ("scripts/x.sh", f"kubectl get pods -l {k}team=x\n", True, True, "known-positive: selector in script"),
        ("svc/src/main/kotlin/A.kt", f'val k = "{k}owner"\n', True, True, "known-positive: Kotlin literal"),
        ("gitops/new/redis.yaml", f"nodeSelector:\n  {k}pool: stateful\n", True, True,
         "known-positive: node key in a NEW file"),
        ("openbank-infra/gitops/apps/alloy.yaml", f"  - key: {k}stateful-ish\n", True, True,
         "known-positive: lookalike of a node key"),
        ("docs/runbooks/r.md", f"prose\n```bash\nkubectl get nodes -l {k}x=y\n```\n", True, True,
         "known-positive: md code block"),
        ("openbank-infra/gitops/apps/alloy.yaml", f"  - key: {k}stateful\n", False, True,
         "known-positive: allowlist removed"),
        ("openbank-infra/gitops/apps/alloy.yaml", f"  - key: {k}stateful\n", True, False,
         "known-negative: node key in allowlisted file"),
        ("openbank-infra/aws/envs/sandbox-platform/arc-runners.tf",
         f'  "{k}runner-tier" = "warm"\n', True, False, "known-negative: runner-tier allowlisted"),
        ("docs/runbooks/r.md", f"The old `{k}*` prefix moved; {k}durability-class is gone.\n", True, False,
         "known-negative: md prose"),
        ("gitops/x/postgres.yaml", "labels:\n  open-bank.tech/durability-class: ledger-class\n", True, False,
         "known-negative: new prefix"),
    ]
    bad = 0
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        for path, content, allow, must_fail, label in cases:
            p = root / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            n, f = scan(root, [path], allow)
            ok = n == 1 and bool(f) == must_fail
            print(f"self-test {'ok  ' if ok else 'FAIL'}: {label} -> {len(f)} finding(s)")
            bad += 0 if ok else 1
            p.unlink()
    print(f"self-test: {len(cases) - bad}/{len(cases)} passed")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--no-allowlist", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    root = Path(a.root).resolve()
    subjects, findings = scan(root, tracked(root), allowlist=not a.no_allowlist)
    print(f"SUBJECTS={subjects}")
    if subjects == 0:
        print("::error::no-openbank-io-keys scanned zero files, refusing to report clean")
        return 2
    for f in findings:
        print(f"::error::{f}")
    print(f"no-openbank-io-keys: {len(findings)} finding(s) over {subjects} file(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
