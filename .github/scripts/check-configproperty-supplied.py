#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A required @ConfigProperty must have a value somewhere, or the service dies at boot.

WHY THIS EXISTS
---------------
The root CLAUDE.md has documented this footgun for months with nothing enforcing it:

    `@ConfigProperty` optional fields must be `Optional<String>`, not plain `String`, or a
    missing value throws `SRCFG00040` at boot.

campaign-service shipped the same shape as #2872's second defect — `Failed to load config value …
analytics.clickhouse-user`, credentials never wired — and it was on `main`, merged, with every
required check green. A missing value for a NON-optional property is not a warning: SmallRye
refuses to start the application.

WHAT IT CHECKS
--------------
For every `@ConfigProperty(name = "…")` in a service's `src/main` that is
  - NOT declared `Optional<…>`, and
  - has NO `defaultValue`,
the property must be supplied by EITHER the service's own `application.yaml` (including a
`%profile` root) OR an env var on its gitops workload (Quarkus' `FOO_BAR` mapping).

Those three together are the whole contract: a property is required, so it needs a value; a
property with a default or an `Optional` type does not.

WHY BOTH SOURCES
----------------
Because either alone is a false positive waiting to happen. Several services here supply config
purely from their Deployment env and say nothing about it in `application.yaml`. A guard that models
only the source its author happened to think of reports the author's imagination back as a finding —
learned expensively on a different gate the same night (#3444/#3453).

WHAT IT DOES NOT CHECK
----------------------
That the value is CORRECT, or that a `defaultValue` is a sensible one. An empty-string default is
still the silent-placeholder shape that #2872 defect 2 actually had — it boots and fails later — but
flagging every `defaultValue = ""` is a judgement call with no mechanical answer, so it is out of
scope rather than guessed at.

Usage:  check-configproperty-supplied.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra/gitops/components"
WORKLOADS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet"}
SKIP_PREFIXES = ("openbank-libs",)

# `@ConfigProperty(name = "x.y")` followed by the declaration whose type decides whether it is
# required. Kotlin allows annotations and modifiers in between, hence the tolerant middle.
CONFIG_RE = re.compile(
    r'@ConfigProperty\s*\(\s*name\s*=\s*"([^"]+)"([^)]*)\)'
    r'\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:private\s+|internal\s+|public\s+)?(?:lateinit\s+)?va[lr]\s+\w+\s*:\s*([A-Za-z_][\w.]*)',
    re.S,
)


def env_key(prop: str) -> str:
    return prop.upper().replace(".", "_").replace("-", "_")


def deployment_env(components: pathlib.Path) -> dict[str, set[str]]:
    out: dict[str, set[str]] = collections.defaultdict(set)
    if not components.is_dir():
        return out
    for path in components.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, OSError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOADS:
                continue
            name = (doc.get("metadata") or {}).get("name", "")
            podspec = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            for container in podspec.get("containers") or []:
                for entry in container.get("env") or []:
                    if entry.get("name"):
                        out[name].add(entry["name"])
    return out


def declared_keys(application_yaml: pathlib.Path) -> set[str]:
    """Dotted property names present in the file, parsed rather than grepped.

    Parsed on purpose: a comment naming a property must not count as supplying it. That collision
    runs silently in the direction that matters — a stale comment would mark a service that cannot
    boot as configured.
    """
    if not application_yaml.is_file():
        return set()
    try:
        doc = yaml.safe_load(application_yaml.read_text(encoding="utf-8")) or {}
    except (yaml.YAMLError, OSError, UnicodeDecodeError):
        return set()

    def walk(node, prefix=""):
        if not isinstance(node, dict):
            return
        for key, value in node.items():
            path = f"{prefix}{key}"
            yield path
            yield from walk(value, path + ".")

    keys = set(walk(doc))
    for key, value in doc.items():
        if isinstance(key, str) and key.startswith("%"):
            keys |= set(walk(value))
    return keys


def findings(repo: pathlib.Path = REPO) -> tuple[list[str], int]:
    env = deployment_env(repo / "openbank-infra/gitops/components")
    out: list[str] = []
    required = 0

    for main in sorted(repo.glob("openbank-*/src/main")):
        service = main.parts[len(repo.parts)]
        if service.startswith(SKIP_PREFIXES):
            continue
        keys = declared_keys(main / "resources/application.yaml")
        short = service.removeprefix("openbank-")
        supplied_env: set[str] = set()
        for candidate in (service, short, f"{short}-service"):
            supplied_env |= env.get(candidate, set())

        for source in main.rglob("*.kt"):
            try:
                text = source.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for match in CONFIG_RE.finditer(text):
                prop, params, type_name = match.group(1), match.group(2), match.group(3)
                if "defaultValue" in params or type_name == "Optional":
                    continue
                required += 1
                if prop in keys or env_key(prop) in supplied_env:
                    continue
                rel = source.relative_to(repo)
                out.append(
                    f"{rel}: @ConfigProperty(\"{prop}\") is required — not Optional, no "
                    f"defaultValue — and no value is supplied. It is absent from "
                    f"{service}'s application.yaml and there is no {env_key(prop)} on its gitops "
                    f"workload, so SmallRye throws SRCFG00040 and the service does not start. "
                    f"Supply it, give it a defaultValue, or make the field Optional<…>.")

    if required == 0:
        out.append("no required @ConfigProperty was found anywhere — the scan is broken. "
                   "Not reporting a clean run on that.")
    return out, required


def selftest() -> int:
    import tempfile

    def service(root: pathlib.Path, decl: str, app_yaml: str) -> None:
        main = root / "openbank-demo/src/main"
        (main / "kotlin").mkdir(parents=True, exist_ok=True)
        (main / "kotlin/C.kt").write_text(decl, encoding="utf-8")
        (main / "resources").mkdir(parents=True, exist_ok=True)
        (main / "resources/application.yaml").write_text(app_yaml, encoding="utf-8")

    required = '@ConfigProperty(name = "openbank.demo.url")\n    lateinit var url: String\n'
    with_default = '@ConfigProperty(name = "openbank.demo.url", defaultValue = "x")\n    var url: String = "x"\n'
    optional = '@ConfigProperty(name = "openbank.demo.url")\n    lateinit var url: Optional<String>\n'
    supplied = "openbank:\n  demo:\n    url: http://x\n"
    profile = "'%prod':\n  openbank:\n    demo:\n      url: http://x\n"
    # A comment naming the property must NOT count — the silent direction.
    comment = "# openbank.demo.url comes from the Deployment env\nquarkus:\n  http:\n    port: 8080\n"
    empty = "quarkus:\n  http:\n    port: 8080\n"

    cases = [
        ("required and supplied", required, supplied, 1, 0),
        ("required and supplied under a profile root", required, profile, 1, 0),
        ("required and NOT supplied — the SRCFG00040 shape", required, empty, 1, 1),
        ("only a COMMENT names it", required, comment, 1, 1),
        ("has a defaultValue", with_default, empty, 0, 0),
        ("is Optional", optional, empty, 0, 0),
    ]
    for label, decl, app_yaml, want_required, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            service(root, decl, app_yaml)
            got, req = findings(root)
        if req != want_required:
            print(f"selftest FAIL: {label} — expected {want_required} required property, saw {req}")
            return 1
        # The empty-scan guard adds a finding when nothing was required; discount it.
        real = [f for f in got if "the scan is broken" not in f]
        if len(real) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(real)}: {real}")
            return 1

    with tempfile.TemporaryDirectory() as d:
        got, req = findings(pathlib.Path(d))
        if req != 0 or not got:
            print("selftest FAIL: an empty tree did not report that it found nothing.")
            return 1

    print(f"selftest OK: {len(cases)} fixture(s) — supplied, profile-scoped, unsupplied, "
          f"comment-only, defaultValue, Optional, plus the empty-scan guard.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found, required = findings()
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-configproperty-supplied: {required} required @ConfigProperty declaration(s) — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
