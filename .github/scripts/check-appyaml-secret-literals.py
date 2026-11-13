#!/usr/bin/env python3
"""A secret-named key in a service's DEPLOYED config must come from the environment.

ADR-0007 / ADR-0017: secrets reach a service from OpenBao via an ExternalSecret and an env
var, never from the image. `application.yaml` is baked into the image, so any value it carries
for a secret-named key outside a `%dev` / `%test` profile is a credential every pod -- and every
reader of this public repo -- has.

Three shapes are accepted for a secret-named leaf outside a dev/test profile:

  ${ENV_VAR}                              -- required, no fallback
  ${ENV_VAR:}                             -- optional, empty fallback
  ${ENV_VAR:CHANGE_ME_LOCAL_DEV_ONLY}     -- the fleet's sentinel (also nested ${A:${B:...}})

Anything else is a finding: a bare literal (`password: openbank`) or an env reference whose
fallback is a plausible credential (`${DB_PASSWORD:campaign}`) -- the latter is the dangerous
one, because it reads as "from the env" while a pod whose Secret failed to mount boots on the
literal instead of failing.

Profiles: a top-level `"%dev"`, `"%test"`, `"%dev,test"` or IT-only (`"%<name>-it"`,
`"%test-<name>"`) mapping is local-only and skipped. `"%prod"`, any other named profile and
unprofiled keys are checked.

Baseline-ratcheted: `appyaml-secret-literals-baseline.txt` holds `<file>::<dotted.key>` lines
that pre-date the gate. A new finding fails; a baseline line that no longer finds anything is
reported as stale and also fails, so the baseline can only shrink.

  --enforce     exit 1 on new or stale findings
  --self-test   run the built-in known-positive / known-negative cases
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASELINE = ROOT / ".github/scripts/appyaml-secret-literals-baseline.txt"

SECRET_LEAF = re.compile(
    r"(^|[-.])(password|passwd|secret|client-secret|secret-key|api-key|apikey|access-key|"
    r"private-key|pepper|hmac-secret|hmac-key|signing-key|token)$",
    re.IGNORECASE,
)
# Leaves that contain a secret-word but are not credentials.
NOT_SECRET = re.compile(
    r"(tokens-per-run|token-ttl|token-lifetime|token-url|token-path|secret-name|secret-ref|"
    r"secret-path|secret-provider|-file|-path|-location|-type|-enabled|-header|-name|-mode)$",
    re.IGNORECASE,
)
LOCAL_PROFILES = ("%dev", "%test")
SENTINEL = "CHANGE_ME_LOCAL_DEV_ONLY"
ENV_REF = re.compile(r"^\$\{[A-Za-z0-9_.\-]+(:(?P<rest>.*))?\}$")


def is_local_profile(segment: str) -> bool:
    """`%dev`, `%test`, `%dev,test`, and IT-only profiles (`%slack-it`, `%test-kafka`)."""
    if not segment.startswith("%"):
        return False
    names = [n.strip().lstrip("%") for n in segment.split(",")]
    return all(n in ("dev", "test") or n.startswith("test") or n.endswith("-it") for n in names)


def value_ok(value: object) -> bool:
    if value is None or value == "":
        return True
    if not isinstance(value, str):
        # booleans / numbers under a secret name are config, not credentials (e.g. `token: false`)
        return isinstance(value, bool)
    v = value.strip()
    m = ENV_REF.match(v)
    if not m:
        return False
    rest = m.group("rest")
    if rest is None or rest == "" or rest == SENTINEL:
        return True
    if rest.startswith("${"):
        return value_ok(rest)
    return False


def walk(node: object, path: list[str], out: list[tuple[str, object]]) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, path + [str(k)], out)
    else:
        out.append((".".join(path), node))


def findings_for(text: str) -> list[str]:
    try:
        doc = yaml.safe_load(text) or {}
    except yaml.YAMLError:
        return []  # yamllint / duplicate-key gates own malformed YAML
    leaves: list[tuple[str, object]] = []
    walk(doc, [], leaves)
    found = []
    for key, value in leaves:
        first = key.split(".", 1)[0]
        if is_local_profile(first):
            continue
        leaf = key.rsplit(".", 1)[-1]
        if not SECRET_LEAF.search(leaf) or NOT_SECRET.search(leaf):
            continue
        if not value_ok(value):
            found.append(key)
    return found


def scan() -> tuple[set[str], int]:
    files = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "*/src/main/resources/application*.yaml",
         "*/src/main/resources/application*.yml"],
        check=True, capture_output=True, text=True,
    ).stdout.split()
    result = set()
    for f in files:
        for key in findings_for((ROOT / f).read_text()):
            result.add(f"{f}::{key}")
    return result, len(files)


def self_test() -> int:
    cases = [
        ("quarkus:\n  datasource:\n    password: openbank\n", ["quarkus.datasource.password"]),
        ("a:\n  password: ${DB_PASSWORD:campaign}\n", ["a.password"]),
        ("a:\n  password: ${DB_PASSWORD}\n", []),
        ("a:\n  password: ${DB_PASSWORD:}\n", []),
        ("a:\n  secret: ${X:CHANGE_ME_LOCAL_DEV_ONLY}\n", []),
        ("a:\n  api-key: ${A:${B:}}\n", []),
        ("a:\n  api-key: ${A:${B:leaked}}\n", ["a.api-key"]),
        ('"%test":\n  a:\n    password: dev_password_x\n', []),
        ('"%dev":\n  a:\n    password: dev_password_x\n', []),
        ('"%prod":\n  a:\n    password: dev_password_x\n', ["%prod.a.password"]),
        ('"%dev,test":\n  a:\n    password: dev_password_x\n', []),
        ('"%slack-it":\n  a:\n    password: dev_password_x\n', []),
        ('"%staging":\n  a:\n    password: dev_password_x\n', ["%staging.a.password"]),
        ('"%dev,prod":\n  a:\n    password: dev_password_x\n', ["%dev,prod.a.password"]),
        ("a:\n  tokens-per-run: 100000\n", []),
        ("a:\n  hmac-secret: test-only\n", ["a.hmac-secret"]),
    ]
    bad = 0
    for text, want in cases:
        got = findings_for(text)
        if got != want:
            print(f"SELFTEST FAIL: {text!r}: want {want} got {got}")
            bad += 1
    print(f"self-test: {len(cases) - bad}/{len(cases)} cases pass")
    return 1 if bad else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    found, nfiles = scan()
    baseline = set()
    if BASELINE.exists():
        baseline = {
            ln.strip() for ln in BASELINE.read_text().splitlines()
            if ln.strip() and not ln.startswith("#")
        }
    new = sorted(found - baseline)
    stale = sorted(baseline - found)
    print(f"SUBJECTS={nfiles}")
    print(f"scanned {nfiles} application yaml files; {len(found)} findings, "
          f"{len(baseline)} baselined, {len(new)} new, {len(stale)} stale")
    for n in new:
        print(f"NEW: {n} -- read it from an env var (${{VAR}}, ${{VAR:}} or ${{VAR:{SENTINEL}}})")
    for s in stale:
        print(f"STALE baseline entry (fixed -- delete the line): {s}")
    if nfiles == 0:
        print("ERROR: scanned zero files -- the probe is broken, not the subject clean")
        return 1
    if "--enforce" in sys.argv and (new or stale):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
