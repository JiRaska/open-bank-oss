#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A Quarkus extension bean the code injects must have the config that activates it.

WHY THIS EXISTS
---------------
campaign-service's rollout hit five boot failures, all on `main` with every required check green
(#2872). The third was:

    InactiveBeanException: ReactiveRedisDataSource

Quarkus DEACTIVATES an extension's beans when the extension is on the classpath but unconfigured.
The service produced a `RedisApprovalStore` over `ReactiveRedisDataSource`, declared no
`quarkus.redis.hosts` anywhere, and shipped no Redis workload. It compiled, its tests passed, and it
died on the first request that touched the bean.

Unit tests cannot catch this: `@ApplicationScoped` is LAZY, so the producer is never invoked, and
the failure surfaces only when a real request resolves the bean — which for a rarely-called path can
be much later than the deploy.

WHAT IT CHECKS
--------------
For each service, if any `src/main` Kotlin source references an extension bean in the table below,
the corresponding property must be supplied by EITHER:

  - the service's own `application.yaml`, or
  - an env var on its Deployment/Rollout in `openbank-infra/gitops` (Quarkus' `FOO_BAR` mapping).

Both sources, because either alone is a false positive waiting to happen: fraud-service supplies
`QUARKUS_REDIS_HOSTS` purely from its Deployment env and its `application.yaml` says nothing about
Redis. An earlier draft of this check that read only `application.yaml` flagged it — a service that
is entirely correct. The general lesson, learned the expensive way tonight on a different gate: a
guard that models only the part of the environment its author happened to think of reports the
author's imagination back as a finding.

WHY THE TABLE IS DECLARED AND NOT DERIVED
-----------------------------------------
This repo rightly distrusts hand-kept lists, so the exception is worth stating. BEAN_CONFIG maps a
Quarkus framework fact — which extension bean deactivates without which property — and that fact
lives in Quarkus, not in this tree. There is nothing here to derive it from. What IS derived is
everything about this repo: which services reference the bean, and what config each one supplies.
A missing table row makes this gate narrower, never wrong.

Usage:  check-extension-bean-config.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra/gitops/components"
WORKLOADS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet"}

# Quarkus framework facts: bean type -> the property whose absence deactivates it.
BEAN_CONFIG = {
    "ReactiveRedisDataSource": "quarkus.redis.hosts",
    "RedisDataSource": "quarkus.redis.hosts",
}

# A library has no deployable config of its own; its consumers supply it.
SKIP_PREFIXES = ("openbank-libs",)


def env_key(prop: str) -> str:
    return prop.upper().replace(".", "_").replace("-", "_")


def deployment_env() -> dict[str, set[str]]:
    """workload name -> env var names declared on any of its containers."""
    out: dict[str, set[str]] = {}
    if not COMPONENTS.is_dir():
        return out
    for path in gatelib.rglob(COMPONENTS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, OSError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOADS:
                continue
            name = (doc.get("metadata") or {}).get("name", "")
            podspec = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            names = out.setdefault(name, set())
            for container in podspec.get("containers") or []:
                for entry in container.get("env") or []:
                    if entry.get("name"):
                        names.add(entry["name"])
    return out


def supplies(prop: str, application_yaml: str) -> bool:
    """Does this application.yaml set `prop`, in either flat or nested form?

    Parsed, not grepped: a comment mentioning `quarkus.redis.hosts` — and fraud-service's manifest
    has exactly such a comment — must not count as supplying it. That collision runs silently in
    the direction that matters (a stale comment would mark a broken service as configured).
    """
    try:
        doc = yaml.safe_load(application_yaml) or {}
    except yaml.YAMLError:
        return False

    def walk(node, prefix=""):
        if not isinstance(node, dict):
            return
        for key, value in node.items():
            path = f"{prefix}{key}"
            if path == prop or path.endswith("." + prop) or prop.endswith("." + path):
                if not isinstance(value, dict):
                    yield True
            yield from walk(value, path + ".")

    if any(walk(doc)):
        return True
    # Profile-scoped roots (%prod, %dev) nest the whole tree again.
    for key, value in doc.items():
        if isinstance(key, str) and key.startswith("%") and any(walk(value)):
            return True
    return False


def findings(repo: pathlib.Path = REPO) -> tuple[list[str], int]:
    env = deployment_env()
    out: list[str] = []
    checked = 0

    for main in gatelib.glob(repo, "openbank-*/src/main"):
        service = main.parts[len(repo.parts)]
        if service.startswith(SKIP_PREFIXES):
            continue

        used: dict[str, str] = {}
        for source in gatelib.rglob(main, "*.kt"):
            try:
                text = gatelib.read_text(source, errors="ignore")
            except OSError:
                continue
            for bean, prop in BEAN_CONFIG.items():
                if re.search(rf"\b{re.escape(bean)}\b", text):
                    used.setdefault(prop, bean)
        if not used:
            continue

        app = main / "resources/application.yaml"
        app_text = gatelib.read_text(app, errors="ignore") if app.is_file() else ""

        short = service.removeprefix("openbank-")
        declared: set[str] = set()
        for candidate in (service, short, f"{short}-service"):
            declared |= env.get(candidate, set())

        for prop, bean in sorted(used.items()):
            checked += 1
            if supplies(prop, app_text) or env_key(prop) in declared:
                continue
            out.append(
                f"{service} injects {bean} but supplies no '{prop}' — not in its application.yaml "
                f"and not as {env_key(prop)} on its gitops workload. Quarkus DEACTIVATES the "
                f"extension's beans when it is on the classpath and unconfigured, so this compiles, "
                f"passes tests (@ApplicationScoped is lazy, so the producer never runs) and dies "
                f"with InactiveBeanException on the first request that resolves it (#2872/#2865).")

    if checked == 0:
        out.append(f"no service referencing any of {sorted(BEAN_CONFIG)} was found — the scan is "
                   f"broken or the beans were renamed. Not reporting a clean run on that.")
    return out, checked


def selftest() -> int:
    import tempfile

    def service(root: pathlib.Path, name: str, bean: str | None, app_yaml: str) -> None:
        main = root / name / "src/main"
        (main / "kotlin").mkdir(parents=True, exist_ok=True)
        body = f"import io.quarkus.redis.datasource.{bean}\nclass X(val r: {bean})\n" if bean else "class X\n"
        (main / "kotlin/X.kt").write_text(body, encoding="utf-8")
        (main / "resources").mkdir(parents=True, exist_ok=True)
        (main / "resources/application.yaml").write_text(app_yaml, encoding="utf-8")

    configured = "quarkus:\n  redis:\n    hosts: redis://localhost:6379\n"
    # The shape that must NOT count: a comment naming the property. fraud-service's manifest has
    # one, and treating it as configuration would mark a genuinely broken service as fine.
    comment_only = "# quarkus.redis.hosts is supplied by the Deployment env\nquarkus:\n  http:\n    port: 8080\n"
    profile = "'%prod':\n  quarkus:\n    redis:\n      hosts: redis://prod:6379\n"

    cases = [
        ("configured in application.yaml", "openbank-a", "ReactiveRedisDataSource", configured, 0),
        ("configured under a profile root", "openbank-b", "ReactiveRedisDataSource", profile, 0),
        ("only a COMMENT names the property", "openbank-c", "ReactiveRedisDataSource", comment_only, 1),
        ("no config at all — campaign's #2865 shape", "openbank-d", "RedisDataSource", "quarkus:\n  http:\n    port: 8080\n", 1),
    ]
    for label, name, bean, app_yaml, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            service(root, name, bean, app_yaml)
            got, checked = findings(root)
        if checked != 1:
            print(f"selftest FAIL: {label} — expected to check 1 service, checked {checked}")
            return 1
        if len(got) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(got)}: {got}")
            return 1

    # A service that never mentions the bean must not be checked at all.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        service(root, "openbank-e", None, "quarkus:\n  http:\n    port: 8080\n")
        got, checked = findings(root)
        if checked != 0 or not got:
            print(f"selftest FAIL: a service not using the bean was checked ({checked}), or an "
                  f"empty scan reported clean.")
            return 1

    print(f"selftest OK: {len(cases)} fixture(s) — application.yaml, a profile root, a "
          f"comment-only mention, no config at all, plus the empty-scan guard.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found, checked = findings()
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-extension-bean-config: {checked} (service, extension-property) pair(s) — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
