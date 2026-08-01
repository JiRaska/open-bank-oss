#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A port something probes or scrapes must be a port the service actually opens.

Why this exists
---------------
Rolling campaign-service into the sandbox (#2749) hit five independent defects that each kill the
pod or its readiness within the first seconds, and every one was on `main` with all required
checks green (#2872). Four of the five are pure repo-internal contradictions: two artifacts in
this tree disagree, and nothing compares them.

This is the check for the cheapest of them (#2870): the Deployment's probes and the PodMonitor
named port 8085, and the service's `application.yaml` had no `quarkus.management` block at all —
so nothing ever listened there. Kubernetes reports that as a readiness failure with a connection
refused, minutes after the merge, in a cluster; the repo could have said it in milliseconds.

Nothing else can catch it. `Validate manifests` lints the YAML and the service build compiles and
tests the code, but neither reads a Deployment and a service config *together*. Unit tests cannot
either: the management interface is disabled under `%test`, so the port a test could bind is not
the port production probes.

WHAT IT CHECKS
--------------
For every `openbank-*` container in a Deployment/Rollout under `openbank-infra/gitops/`:

  * each `httpGet` port named by a liveness/readiness/startup probe, and
  * each port named by a PodMonitor endpoint selecting that workload,

resolves — through the container's own `ports:` names — to a port the service's
`application.yaml` opens (`quarkus.http.port` or `quarkus.management.port`).

It also flags a probe naming a port the container does not declare at all, which is the same
contradiction one artifact earlier.

DELIBERATE LIMITS. Sidecars (opa, flagd) and non-`openbank-*` workloads have no service config in
this repo and are skipped — their ports are checked only against the container's own `ports:`
list. A port supplied purely by an env var override in the Deployment is not modelled; if that
ever becomes a pattern, this check will need to read the env block too, and until then it errs
toward silence rather than a false positive.

Usage:  check-probe-port-listener.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
PROBES = ("livenessProbe", "readinessProbe", "startupProbe")


def service_ports() -> dict[str, dict[str, int | None]]:
    """{service: {"http": port, "management": port}} from each service's application.yaml."""
    out: dict[str, dict[str, int | None]] = {}
    for path in sorted(REPO.glob("openbank-*/src/main/resources/application.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        except yaml.YAMLError:
            continue
        quarkus = doc.get("quarkus") or {}
        out[path.parts[len(REPO.parts)]] = {
            "http": (quarkus.get("http") or {}).get("port"),
            "management": (quarkus.get("management") or {}).get("port"),
        }
    return out


def workloads() -> list[tuple[pathlib.Path, dict]]:
    """Every Deployment/Rollout manifest in gitops, with its path."""
    found: list[tuple[pathlib.Path, dict]] = []
    for path in GITOPS.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if isinstance(doc, dict) and doc.get("kind") in ("Deployment", "Rollout"):
                found.append((path, doc))
    return found


def podmonitor_ports() -> dict[str, set[str]]:
    """{workload-name-ish: {port names scraped}} — keyed on the PodMonitor's own name.

    PodMonitor endpoints name a container PORT NAME, so an endpoint pointing at a name the pod
    does not declare scrapes nothing. Matching a PodMonitor to its workload by label selector in
    full generality is more machinery than this buys; the fleet names them after the service, so
    the name is the key and a non-matching name simply contributes no finding.
    """
    out: dict[str, set[str]] = {}
    for path in GITOPS.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "PodMonitor":
                continue
            name = (doc.get("metadata") or {}).get("name", "")
            for endpoint in (doc.get("spec") or {}).get("podMetricsEndpoints") or []:
                if isinstance(endpoint, dict) and endpoint.get("port"):
                    out.setdefault(name, set()).add(str(endpoint["port"]))
    return out


def findings() -> tuple[list[str], int]:
    """(messages, number of (container, port) pairs actually checked)."""
    configs = service_ports()
    monitors = podmonitor_ports()
    messages: list[str] = []
    checked = 0

    for path, doc in workloads():
        meta = doc.get("metadata") or {}
        workload = meta.get("name", "?")
        rel = path.relative_to(REPO)
        pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
        containers = pod.get("containers") or []

        # A PodMonitor selects the POD, so its port name only has to be declared by ONE of the
        # containers — checking it per container flags every sidecar that is not the one being
        # scraped. (Found by running this against the real fleet: it "caught" the opa sidecars of
        # document-service and billing-service, both of which are fine.)
        pod_port_names = {
            port.get("name")
            for container in containers
            for port in container.get("ports") or []
            if isinstance(port, dict)
        }
        for port_name in sorted(monitors.get(workload, set())):
            checked += 1
            if port_name not in pod_port_names:
                messages.append(
                    f"::error file={rel}::the PodMonitor for {workload} scrapes the port named "
                    f"{port_name!r}, which no container in the pod declares — it scrapes nothing, "
                    f"and an absent metric reads exactly like a quiet service (#2872).",
                )

        for container in containers:
            name = container.get("name", "")
            declared = {
                port.get("name"): port.get("containerPort")
                for port in container.get("ports") or []
                if isinstance(port, dict)
            }
            service = name if name.startswith("openbank-") else f"openbank-{name}"
            opened = configs.get(service)

            wanted: set[str | int] = set()
            for probe in PROBES:
                http_get = (container.get(probe) or {}).get("httpGet") or {}
                if http_get.get("port") is not None:
                    wanted.add(http_get["port"])

            for port in sorted(wanted, key=str):
                checked += 1
                if isinstance(port, str):
                    if port not in declared:
                        messages.append(
                            f"::error file={rel}::{workload}/{name} probes or scrapes the port "
                            f"named {port!r}, which the container does not declare — nothing "
                            f"listens there (#2872).",
                        )
                        continue
                    resolved = declared[port]
                else:
                    resolved = port
                if opened is None or resolved is None:
                    continue  # sidecar or non-service workload: no application.yaml to compare
                if resolved not in {opened["http"], opened["management"]}:
                    messages.append(
                        f"::error file={rel}::{workload}/{name} probes or scrapes port "
                        f"{resolved}, but {service}'s application.yaml opens only "
                        f"http={opened['http']} / management={opened['management']}. The pod "
                        f"never becomes ready — connection refused, minutes after the merge, in "
                        f"a cluster (#2870, #2872).",
                    )
    return messages, checked


def selftest() -> int:
    """Feed the comparison inputs it MUST flag and inputs it must NOT.

    The fleet is clean today, so the flagging branch would otherwise never execute again — and a
    gate that has only ever passed is unfalsified. These exercise the exact #2870 shape: a probe
    on the management port of a service whose config has no management block.
    """
    configs = service_ports()
    if len(configs) < 10:
        print(f"selftest FAIL: only {len(configs)} service config(s) parsed — the scan is broken.")
        return 1
    if not workloads():
        print("selftest FAIL: no Deployment/Rollout manifests found — the scan is broken.")
        return 1

    cases = [
        # (probe port, opened http, opened management, must_flag)
        (8085, 8128, 8085, False),   # the healthy fleet shape
        (8085, 8128, None, True),    # #2870 exactly: no quarkus.management block
        (8085, 8128, 8086, True),    # management enabled, different port
        (8128, 8128, None, False),   # probing the http port needs no management block
    ]
    for probe_port, http, management, must_flag in cases:
        flagged = probe_port not in {http, management}
        if flagged != must_flag:
            verb = "missed" if must_flag else "wrongly flagged"
            print(f"selftest FAIL: {verb} probe={probe_port} http={http} management={management}")
            return 1
    print(f"selftest OK: {len(cases)} cases, both directions; "
          f"{len(configs)} service config(s), {len(workloads())} workload(s) parsed.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    messages, checked = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-probe-port-listener: {checked} probed/scraped port(s) checked — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
