#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression test for #13837: a train member must not weaken a privileged gate's DECLARATION.

Builds a throwaway repository whose gates.yaml declares a privileged (freshness) gate, commits
each weakening shape the issue names on its own branch, derives the member's file list with
merge-train.py's own execution-free `changed_paths`, and requires `qualifies()` to refuse it and
`strict_lane_state()` to take it. The control is an unrelated gate's check script, which still
rides a train. Re-adding `.github/gates/gates.yaml` to GATE_CODE_GLOBS makes every shape fail.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "merge-train.py"
GATES = ".github/gates/gates.yaml"
PRIV = ".github/scripts/check-adr-registry.sh"

BASE = f"""gates:
  - id: adr-registry
    group: registry
    mode: enforced
    budget_seconds: 60
    when: ["docs/adr/**"]
    selftest: bash {PRIV} --self-test
    run: bash {PRIV}
  - id: unrelated
    group: lint
    mode: advisory
    run: python3 .github/scripts/check-unrelated.py
"""

SHAPES = {
    "no-op run": BASE.replace(f"    run: bash {PRIV}\n", "    run: \"true\"\n"),
    "mode flip": BASE.replace("    mode: enforced\n", "    mode: advisory\n", 1),
    "narrowed when": BASE.replace('["docs/adr/**"]', '["docs/adr/never/**"]'),
    "deleted privileged entry": BASE.split("  - id: unrelated\n")[0].split("  - id: adr-registry\n")[0]
    + "  - id: unrelated\n" + BASE.split("  - id: unrelated\n")[1],
    "budget changed": BASE.replace("budget_seconds: 60", "budget_seconds: 1"),
}


def load():
    spec = importlib.util.spec_from_file_location("merge_train", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["merge_train"] = mod   # @dataclass resolves annotations through sys.modules
    spec.loader.exec_module(mod)
    return mod


def sh(cwd: Path, *args: str) -> str:
    return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=cwd, text=True,
                          capture_output=True, check=True).stdout.strip()


def write(root: Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def main() -> int:
    mt = load()
    req = ["all-green", "Gitleaks", mt.STATUS_CONTEXT]
    green = {"all-green": "success", "Gitleaks": "success"}
    base_pr = {"draft": False, "auto_merge": {"x": 1}, "same_repo": True, "head_ref": "feat/x", "state": "open"}
    untrainable = mt.Untrainable("")
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        sh(root, "init", "-q", "-b", "main")
        sh(root, "config", "user.email", "t@example.invalid")
        sh(root, "config", "user.name", "t")
        sh(root, "config", "commit.gpgsign", "false")
        write(root, GATES, BASE)
        write(root, PRIV, "exit 0\n")
        write(root, ".github/scripts/check-unrelated.py", "print(1)\n")
        sh(root, "add", "-A")
        sh(root, "commit", "-qm", "base")
        git = mt.Git(str(root))
        cases = [(name, {GATES: text}, False) for name, text in SHAPES.items()]
        cases.append(("unrelated gate's check script (control)", {".github/scripts/check-unrelated.py": "print(2)\n"}, True))
        for i, (name, edits, want_train) in enumerate(cases):
            sh(root, "checkout", "-q", "-b", f"m{i}", "main")
            for rel, text in edits.items():
                write(root, rel, text)
            sh(root, "commit", "-qam", name)
            files = mt.changed_paths(git, f"main...m{i}")
            pr = {**base_pr, "files": files}
            ok, why = mt.qualifies(pr, req, green, None, untrainable)
            strict = mt.strict_lane_state(pr, req, green, None, untrainable, [], None, None)[0]
            passed = (ok and strict is None) if want_train else (not ok and strict == "green")
            print(f"{'PASS' if passed else 'FAIL'} {name}: files={files} train={ok} ({why}) strict={strict}")
            if not passed:
                failures.append(name)
            sh(root, "checkout", "-q", "main")
    print(f"merge-train gates.yaml regression: {len(cases)} cases, {len(failures)} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
