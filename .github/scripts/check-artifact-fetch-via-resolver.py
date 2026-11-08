#!/usr/bin/env python3
"""Flag CI downloads that bypass the artifact resolver (#15216, gate `ci-artifact-fetch-via-resolver`).

Every external tool and chart CI fetches is meant to come through `fetch-artifact.py`, which
verifies it against `ci/artifacts.lock` and can fall back to a cache and a mirror when upstream is
down. A direct `curl`/`wget`/`helm pull`/`helm repo add` of a lock-eligible upstream has neither
property: it trusts whatever bytes arrive, and it turns an upstream outage into a red required
check. Lock-eligible upstreams today: GitHub Releases downloads, `*.github.io` (Helm repositories
whose archives are themselves Release assets) and the OPA download site.

ADVISORY until the existing call sites are migrated (#15216 rollout step 3). Today's offenders
are KNOWN DEBT: they print as `::warning` and the gate exits 0. `--enforce` makes any finding fail;
the gate flips to that once the sweep closes.

Scope: every file under `.github/` except the resolver, this checker and their tests. Shell line
continuations are joined first, so `curl -o x \\` + a URL on the next line is still one command.
Comment lines are skipped (a comment describing a download is not one).
"""
from __future__ import annotations

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
EXEMPT = {
    ".github/scripts/fetch-artifact.py",
    ".github/scripts/fetch_artifact_test.py",
    ".github/scripts/check-artifact-fetch-via-resolver.py",
    ".github/scripts/check-artifact-lock.py",
}
VERB = re.compile(r"\b(curl|wget|helm\s+pull|helm\s+repo\s+add|Invoke-WebRequest)\b")
TARGET = re.compile(
    r"(github\.com/[^\s\"']+/releases/download"
    r"|[A-Za-z0-9-]+\.github\.io"
    r"|openpolicyagent\.org/downloads)"
)
# A checker that asserts a workflow does NOT download (`"…/releases/download" in workflow`) names
# the URL as data; that is a membership test, not a fetch.
MEMBERSHIP = re.compile(r"[\"'][^\"']*" + TARGET.pattern + r"[^\"']*[\"']\s+(not\s+)?in\b")
SUFFIXES = {".yml", ".yaml", ".sh", ".bash", ".py", ".mjs", ".js", ".ts", ""}


def logical_lines(text: str):
    """Yield (first line number, joined logical line), joining trailing-backslash continuations."""
    buf, start = "", 0
    for no, raw in enumerate(text.splitlines(), 1):
        if not buf:
            start = no
        stripped = raw.rstrip()
        if stripped.endswith("\\"):
            buf += stripped[:-1] + " "
            continue
        yield start, buf + raw
        buf = ""
    if buf:
        yield start, buf


def findings_in(text: str) -> list[tuple[int, str]]:
    out = []
    for no, line in logical_lines(text):
        if line.lstrip().startswith("#"):
            continue
        if VERB.search(line) and TARGET.search(line) and not MEMBERSHIP.search(line):
            out.append((no, line.strip()[:160]))
    return out


def scan(root: pathlib.Path) -> tuple[list[str], int]:
    hits, files = [], 0
    for path in sorted((root / ".github").rglob("*")):
        if not path.is_file() or path.suffix not in SUFFIXES:
            continue
        rel = path.relative_to(root).as_posix()
        if rel in EXEMPT:
            continue
        try:
            text = path.read_text(errors="replace")
        except OSError:
            continue
        files += 1
        for no, line in findings_in(text):
            hits.append(f"{rel}:{no}: {line}")
    return hits, files


def self_test() -> int:
    must_flag = {
        "curl a release asset": 'curl -fsSL -o x https://github.com/o/r/releases/download/v1/x.tgz',
        "continued curl": 'curl -fsSL -o /tmp/x.tgz \\\n  "https://github.com/o/r/releases/download/v1/x.tgz"',
        "wget github.io": "wget https://charts.github.io/index.yaml",
        "helm repo add": "helm repo add kyverno https://kyverno.github.io/kyverno/",
        "helm pull --repo": "helm pull loki --repo https://grafana.github.io/helm-charts",
        "opa download": "curl -L -o opa https://openpolicyagent.org/downloads/v1.0.0/opa_linux_amd64",
    }
    must_not = {
        "comment": "# curl https://github.com/o/r/releases/download/v1/x.tgz",
        "resolver": "python3 -I .github/scripts/fetch-artifact.py gitleaks --out /tmp/x",
        "api call": "curl -fsSL https://api.github.com/repos/o/r/releases/latest",
        "membership test": 'if "github.com/o/r/releases/download" in workflow or "curl -fsSL" in workflow:',
        "url without verb": 'URL="https://github.com/o/r/releases/download/v1/x.tgz"',
    }
    for label, text in must_flag.items():
        if not findings_in(text):
            print(f"FAIL self-test: must-FLAG case '{label}' was not flagged", file=sys.stderr)
            return 1
    for label, text in must_not.items():
        if findings_in(text):
            print(f"FAIL self-test: must-NOT case '{label}' was flagged", file=sys.stderr)
            return 1
    print(f"PASS self-test: {len(must_flag)} bypassing downloads flagged, {len(must_not)} clean cases cleared")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = pathlib.Path(argv[argv.index("--root") + 1]) if "--root" in argv else REPO
    hits, files = scan(root)
    enforce = "--enforce" in argv
    for hit in hits:
        prefix = "FAIL" if enforce else "::warning::known debt (#15216 step 3):"
        print(f"{prefix} download bypasses fetch-artifact.py: {hit}")
    print(f"{len(hits)} direct download(s) of lock-eligible upstreams under .github/ "
          f"({'enforced' if enforce else 'ADVISORY: known debt, not a failure'}); {files} file(s) scanned")
    print(f"SUBJECTS={files}")
    return 1 if (enforce and hits) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
