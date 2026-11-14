#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every outgoing payment rail must declare a sanctions screening port (rules.yaml:
outgoing_payment_rails_screening). A rail is covered when its src/main declares an interface named
*Screening*Port AND references com.openbank.libs.fincrime.ScreeningPolicy. Both-ways baseline:
an uncovered, unbaselined rail fails; a baselined rail that is covered fails as stale; a listed
module that does not exist fails (a typo must not read as clean)."""
import argparse
import pathlib
import re
import shutil
import sys
import tempfile

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

PORT_RE = re.compile(r"\binterface\s+\w*Screening\w*Port\b")
POLICY_RE = re.compile(r"\bcom\.openbank\.libs\.fincrime\.ScreeningPolicy\b")
BASELINE = ".github/scripts/rail-screening-port-baseline.txt"
_checked = [0]
RULES = "openbank-libs/governance/rules.yaml"


def covered(module: pathlib.Path) -> bool:
    port = policy = False
    for f in (module / "src/main").rglob("*.kt"):
        text = f.read_text(encoding="utf-8", errors="replace")
        port = port or bool(PORT_RE.search(text))
        policy = policy or bool(POLICY_RE.search(text))
        if port and policy:
            return True
    return False


def check(root: pathlib.Path) -> list[str]:
    rules = yaml.safe_load((root / RULES).read_text(encoding="utf-8"))
    services = (rules.get("outgoing_payment_rails_screening") or {}).get("services") or []
    if not services:
        return [f"{RULES}: outgoing_payment_rails_screening.services is empty or missing"]
    bl_path = root / BASELINE
    baseline = set()
    if bl_path.exists():
        baseline = {
            ln.strip() for ln in bl_path.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")
        }
    errors = []
    for svc in baseline - set(services):
        errors.append(f"{svc}: baselined but not a listed rail — remove it from {BASELINE}")
    n = 0
    _checked[0] = 0
    for svc in services:
        mod = root / svc
        if not (mod / "src/main").is_dir():
            errors.append(f"{svc}: listed rail has no src/main — wrong module name?")
            continue
        n += 1
        _checked[0] = n
        ok = covered(mod)
        if not ok and svc not in baseline:
            errors.append(
                f"{svc}: no sanctions screening port (interface *Screening*Port + "
                "com.openbank.libs.fincrime.ScreeningPolicy) in src/main"
            )
        if ok and svc in baseline:
            errors.append(f"{svc}: now screens — remove it from {BASELINE} (stale baseline)")
    print(f"rail-screening-port: {n} rails checked, {len(baseline)} baselined, {len(errors)} finding(s)")
    return errors


def _mk(root: pathlib.Path, svc: str, port: bool, policy: bool) -> None:
    d = root / svc / "src/main/kotlin"
    d.mkdir(parents=True)
    body = ""
    if port:
        body += "interface SanctionsScreeningPort { fun screen() }\n"
    if policy:
        body += "import com.openbank.libs.fincrime.ScreeningPolicy\n"
    (d / "A.kt").write_text(body + "class A\n", encoding="utf-8")


def selftest() -> int:
    def run(rails, baseline, mods):
        with tempfile.TemporaryDirectory() as t:
            r = pathlib.Path(t)
            (r / RULES).parent.mkdir(parents=True)
            (r / RULES).write_text(yaml.safe_dump({"outgoing_payment_rails_screening": {"services": rails}}))
            (r / BASELINE).parent.mkdir(parents=True)
            (r / BASELINE).write_text("\n".join(baseline) + "\n")
            for svc, (p, q) in mods.items():
                _mk(r, svc, p, q)
            return check(r)

    cases = [
        ("covered rail passes", run(["a"], [], {"a": (True, True)}), 0),
        ("missing port fails", run(["a"], [], {"a": (False, True)}), 1),
        ("missing policy fails", run(["a"], [], {"a": (True, False)}), 1),
        ("baselined gap passes", run(["a"], ["a"], {"a": (False, False)}), 0),
        ("stale baseline fails", run(["a"], ["a"], {"a": (True, True)}), 1),
        ("unknown module fails", run(["zz"], [], {}), 1),
        ("empty list fails", run([], [], {}), 1),
    ]
    bad = [(name, len(errs), want) for name, errs, want in cases if len(errs) != want]
    for name, got, want in bad:
        print(f"SELFTEST FAIL: {name}: {got} findings, expected {want}")
    print("selftest:", "FAIL" if bad else "pass")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return selftest()
    errs = check(pathlib.Path(a.root))
    gatelib.subjects(_checked[0], "outgoing rails checked")
    for e in errs:
        print("ERROR:", e)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
