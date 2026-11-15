#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every job that installs the GitHub API call counter declares a budget, and every budget names one.

gh-call-counter.sh compares a job's request count with .github/api-call-budgets.yaml at the end of
the job. A counted job with no entry reports `budget=undeclared` and can never warn, so a new
consumer would only be noticed when the shared quota ran out again; an entry whose job was renamed
or lost its counter is a budget nothing checks. Both directions fail here.

Labels are "<workflow name>/<job id>", the counter's own "$GITHUB_WORKFLOW/$GITHUB_JOB".

Usage: check-api-call-budgets.py [--root .]   |   --self-test
"""
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

INSTALL = re.compile(r"gh-call-counter\.sh\s+install\b")
BUDGET_LINE = re.compile(r'^"([^"]+)":\s*(\d+)\s*(#.*)?$')


# A sparse checkout must carry these, or install fails behind continue-on-error and the job's budget
# line describes counting that never happens (auto-retry-cancelled, caught in review of #16137).
COUNTER_FILES = (".github/scripts/gh-call-counter.sh", ".github/scripts/gh_call_counter_site/sitecustomize.py",
                 ".github/api-call-budgets.yaml")


def _jobs(root: pathlib.Path):
    for wf in sorted((root / ".github/workflows").glob("*.y*ml")):
        doc = gatelib.load_yaml(wf) or {}
        name = str(doc.get("name") or wf.stem)
        for job_id, job in (doc.get("jobs") or {}).items():
            steps = (job or {}).get("steps") or []
            if any(INSTALL.search(str((st or {}).get("run") or "")) for st in steps):
                yield wf.name, f"{name}/{job_id}", steps


def instrumented(root: pathlib.Path) -> set[str]:
    return {label for _, label, _ in _jobs(root)}


def sparse_errors(root: pathlib.Path) -> list[str]:
    errors = []
    for fname, label, steps in _jobs(root):
        for st in steps:
            if "actions/checkout@" not in str((st or {}).get("uses") or ""):
                continue
            sparse = str(((st or {}).get("with") or {}).get("sparse-checkout") or "")
            if not sparse.strip():
                continue
            listed = {line.strip() for line in sparse.splitlines()}
            missing = [f for f in COUNTER_FILES if f not in listed]
            if missing:
                errors.append(f"{label} ({fname}): sparse checkout omits {', '.join(missing)} - the counter "
                              "cannot install and its budget would check nothing")
    return errors


def budgets(root: pathlib.Path) -> tuple[set[str], list[str]]:
    keys, errors = set(), []
    path = root / ".github/api-call-budgets.yaml"
    if not path.is_file():
        return keys, [f"{path} is missing"]
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = BUDGET_LINE.match(line)
        if not m:
            errors.append(f"api-call-budgets.yaml:{n}: not '\"<workflow>/<job>\": <int>': {line!r}")
            continue
        if m.group(1) in keys:
            errors.append(f"api-call-budgets.yaml:{n}: duplicate key {m.group(1)!r} (the counter reads the first)")
        keys.add(m.group(1))
    return keys, errors


def check(root: pathlib.Path) -> list[str]:
    jobs = instrumented(root)
    keys, errors = budgets(root)
    errors += [f"{j}: installs gh-call-counter but has no entry in .github/api-call-budgets.yaml"
               for j in sorted(jobs - keys)]
    errors += [f"{k}: budget declared for a job that does not install gh-call-counter (renamed or removed?)"
               for k in sorted(keys - jobs)]
    errors += sparse_errors(root)
    if not jobs:
        errors.append("no job installs gh-call-counter - the workflow parse found nothing, refusing to pass")
    return errors


def self_test() -> int:
    wf = ("name: Demo\non:\n  push:\njobs:\n  counted:\n    steps:\n"
          "      - run: bash .github/scripts/gh-call-counter.sh install\n"
          "  other:\n    steps:\n      - run: echo gh-call-counter.sh report\n")
    cases = [  # name, budgets file, expected error count
        ("declared", '"Demo/counted": 5\n', 0),
        ("missing-entry", "# none\n", 1),
        ("stale-entry", '"Demo/counted": 5\n"Demo/gone": 1\n', 1),
        ("malformed", '"Demo/counted": lots\n', 2),
        ("duplicate", '"Demo/counted": 5\n"Demo/counted": 6\n', 1),
        ("sparse-without-counter", '"Demo/counted": 5\n', 1),
    ]
    bad = 0
    for name, text, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / ".github/workflows").mkdir(parents=True)
            (root / ".github/workflows/demo.yml").write_text(
                wf.replace("    steps:\n      - run: bash", "    steps:\n      - uses: actions/checkout@x\n"
                           "        with:\n          sparse-checkout: |\n            other.py\n      - run: bash", 1)
                if name == "sparse-without-counter" else wf)
            (root / ".github/api-call-budgets.yaml").write_text(text)
            got = check(root)
            ok = len(got) == want
            bad += not ok
            print(f"{'ok  ' if ok else 'FAIL'} {name}: {len(got)} error(s) {got}")
    print(f"SUBJECTS={len(cases)}")
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        return self_test()
    root = pathlib.Path(argv[argv.index("--root") + 1] if "--root" in argv else ".")
    errors = check(root)
    for e in errors:
        print(f"::error title=api-call-budget-coverage::{e}")
    print(f"SUBJECTS={len(instrumented(root))}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
