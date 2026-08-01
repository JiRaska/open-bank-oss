#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-probe-port-has-listener.py — the Deployment and the service config must agree (#2872).

WHY
---
Rolling campaign-service into the sandbox hit five defects that each kill the pod, the sidecar or
its readiness within the first seconds of the process. All five were on `main`, merged, with every
required check green. Four of the five were pure repo-internal contradictions: two artifacts in this
tree disagree, and nothing compared them.

This closes one of those pairs — the one that produced defect #5. The Deployment probed
`/q/health/{live,ready}` on port 8085 and a PodMonitor scraped the same port, while the service's
`application.yaml` had no `quarkus.management` block, so nothing ever listened there. The pod never
went ready and liveness restarted it forever.

Nothing could have caught it. `Validate manifests` lints the YAML in isolation; the service build
compiles and runs tests; unit tests cannot see it because the management interface is disabled under
`%test` and `@ApplicationScoped` is lazy. The controls were green about something they never
examined — neither of them ever read both files.

WHAT IT CHECKS
--------------
For every Deployment/Rollout under `openbank-infra/gitops/components/`, for each container whose
name maps to an `openbank-<name>` module in this repo (sidecars like `opa` are out of scope — they
are not built here):

  * every port named by a `livenessProbe` / `readinessProbe` / `startupProbe`, and
  * every port a `PodMonitor` selecting that workload scrapes

must be DECLARED by the service's own `application.yaml` — `quarkus.http.port` or
`quarkus.management.port`. The check is deliberately about whether the two artifacts name the same
ports, not about whether Quarkus would bind them: re-deriving the framework's activation rules here
would just be a second, less accurate copy of them.

Scope is DERIVED: the containers come from the committed manifests and the modules from the tree, so
a new service is covered the day its component lands. There is no list to keep in step.

NOT COVERED, AND WORTH SAYING
-----------------------------
This is the cheaper half of the trade recorded on #2872. Measured against that rollout: four static
checks like this one would have caught ~3 of 17 defects; a boot smoke test (run the image against
ephemeral Postgres/Redis with the Deployment's env and assert readiness) would have caught ~8,
including the missing-config and inactive-bean classes this cannot see. The static check is here
because it is verifiable without Docker; it is not a substitute for the boot test.

Usage: python3 .github/scripts/check-probe-port-has-listener.py
Exit:  0 clean, 1 a probed or scraped port has no listener
"""

from __future__ import annotations

import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"


def load_yaml_docs(path: pathlib.Path) -> list[dict]:
    import yaml

    try:
        return [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if isinstance(d, dict)]
    except Exception:  # noqa: BLE001 - a component may hold a Helm template or a non-YAML fragment
        return []


def service_ports(module: str) -> tuple[set[int], list[str]]:
    """Ports the service's application.yaml actually opens, plus notes for the failure message."""
    import yaml

    cfg = REPO / module / "src" / "main" / "resources" / "application.yaml"
    if not cfg.is_file():
        return set(), [f"{module} has no src/main/resources/application.yaml"]

    try:
        doc = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
    except Exception as exc:  # noqa: BLE001
        return set(), [f"{module}: application.yaml is unparsable ({exc})"]

    quarkus = doc.get("quarkus") or {}
    ports: set[int] = set()
    notes: list[str] = []

    http_port = (quarkus.get("http") or {}).get("port")
    if isinstance(http_port, int):
        ports.add(http_port)

    # Any DECLARED management port counts, with or without an explicit `enabled: true`.
    #
    # An earlier version of this gate required `enabled: true` and flagged openbank-customer-edge,
    # which declares only a port. That was wrong, and it was disproved against the live pod: the
    # deployed image is built from a commit with no `enabled` key, the pod is 2/2 with zero
    # restarts, and `/q/health/ready` on 8085 answers UP. Quarkus 3.38 brings the management
    # interface up without it. Modelling Quarkus's activation rules here would make this gate a
    # second, worse copy of them — and a gate that is wrong about the framework produces false
    # positives on correct services, which is how a gate gets ignored.
    #
    # Comparing DECLARED ports still catches the defect this exists for: campaign-service had no
    # `quarkus.management` block at all, so the probed 8085 matched nothing.
    mgmt_port = (quarkus.get("management") or {}).get("port")
    if isinstance(mgmt_port, int):
        ports.add(mgmt_port)

    return ports, notes


def resolve_port(value: object, container: dict) -> int | None:
    """A probe port is a number or the NAME of a declared containerPort."""
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        for port in container.get("ports") or []:
            if port.get("name") == value:
                cp = port.get("containerPort")
                return cp if isinstance(cp, int) else None
        if value.isdigit():
            return int(value)
    return None


def podmonitor_ports(docs_by_file: dict[pathlib.Path, list[dict]], component: pathlib.Path) -> set[str]:
    """Port NAMES scraped by any PodMonitor in the same component directory."""
    names: set[str] = set()
    for path, docs in docs_by_file.items():
        if component not in path.parents and path.parent != component:
            continue
        for doc in docs:
            if doc.get("kind") != "PodMonitor":
                continue
            for endpoint in (doc.get("spec") or {}).get("podMetricsEndpoints") or []:
                port = endpoint.get("port") or endpoint.get("targetPort")
                if port is not None:
                    names.add(str(port))
    return names


def main() -> int:
    if not COMPONENTS.is_dir():
        print(f"::error::check-probe-port-has-listener: {COMPONENTS} not found")
        return 1

    docs_by_file = {p: load_yaml_docs(p) for p in sorted(COMPONENTS.rglob("*.yaml"))}

    failures = 0
    checked = 0

    for path, docs in docs_by_file.items():
        for doc in docs:
            if doc.get("kind") not in ("Deployment", "Rollout"):
                continue
            spec = ((doc.get("spec") or {}).get("template") or {}).get("spec") or {}
            component = path.parent

            for container in spec.get("containers") or []:
                module = f"openbank-{container.get('name', '')}"
                if not (REPO / module).is_dir():
                    continue  # a sidecar (opa, envoy, …) — not built in this repo

                opened, notes = service_ports(module)
                if not opened:
                    continue  # no parseable config; other gates own that

                checked += 1
                wanted: set[int] = set()

                for probe in ("livenessProbe", "readinessProbe", "startupProbe"):
                    http_get = (container.get(probe) or {}).get("httpGet") or {}
                    resolved = resolve_port(http_get.get("port"), container)
                    if resolved is not None:
                        wanted.add(resolved)

                for name in podmonitor_ports(docs_by_file, component):
                    resolved = resolve_port(name, container)
                    if resolved is not None:
                        wanted.add(resolved)

                missing = sorted(wanted - opened)
                for port in missing:
                    failures += 1
                    print(
                        f"::error::{path.relative_to(REPO)}: {module} is probed or scraped on port "
                        f"{port}, but its application.yaml declares only {sorted(opened)} — the manifest and "
                        f"the service config name different ports, so nothing serves that probe.",
                    )
                    for note in notes:
                        print(f"::error::  {note}")

    if failures:
        print(
            f"\n{failures} probed/scraped port(s) with no listener (#2872). The Deployment and the "
            "service's application.yaml describe different processes; one of the two is wrong. "
            "Fix the config to open the port (usually a `quarkus.management` block with "
            "`enabled: true`), or point the probe at a port the service actually binds.",
        )
        return 1

    print(f"OK: {checked} service container(s) — every probed and scraped port has a listener.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
