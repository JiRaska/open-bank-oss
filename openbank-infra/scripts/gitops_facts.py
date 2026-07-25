#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Facts about a service that can only be answered from the gitops manifests.

Three different tools were each about to grow their own copy of "which namespace does this
service run in" — the runbook generator, the prod-readiness collector's C8 scorer, and
`.github/scripts/check-podmonitor-namespace-coverage.py`. Three copies of a resolver is three
chances to answer differently, and the whole reason this module exists is that two of those
tools had already been answering it WRONG in two distinct ways:

* The runbook generator interpolated the service short name, so a third of the fleet's runbooks
  named a namespace that does not exist (document-service runs in `documents`, ap2/mcp in
  `platform`, settlement/vop/card-issuance/standing-order in `payments`).
* The collector's C8 scorer asked `short in read(podmonitor.yaml)` — a substring match over the
  whole file INCLUDING COMMENTS. A comment claiming sdd-service was covered "via `payments`"
  was false (sdd runs in namespace `sdd`), and that false claim scored sdd as scraped while its
  metrics reached nothing (#2255, fixed in #2257).

Both bugs share one shape: the question is "where does the workload actually run", and both
tools answered it by pattern-matching a name instead. So the answer is derived here, once, from
the manifest that IS the workload.

Stdlib only, no pyyaml: this module is imported by scripts that must run on a bare runner.
"""
from __future__ import annotations

import re
from pathlib import Path

__all__ = [
    "read",
    "service_namespace",
    "workload_namespaces",
    "podmonitor_namespaces",
    "declared_datastore",
    "is_stateless",
]


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def _nearest_kustomize_namespace(manifest: Path, gitops: Path) -> str | None:
    """The `namespace:` of the closest kustomization.yaml at or above the manifest."""
    for parent in manifest.parents:
        if parent != gitops and gitops not in parent.parents:
            break
        m = re.search(r"^namespace:\s*(\S+)", read(parent / "kustomization.yaml"), re.M)
        if m:
            return m.group(1)
    return None


def workload_namespaces(short: str, gitops: Path) -> set[str]:
    """Every namespace in which a Deployment/Rollout for this service is declared.

    A manifest that merely MENTIONS the service does not count. A NetworkPolicy naming it as a
    peer, an env var holding its URL, or an initContainer waiting on it would otherwise resolve
    to some caller's namespace — which is exactly the class of mistake this module exists to
    prevent. The workload's own `metadata.name` must be the service.
    """
    names = {f"{short}-service", f"openbank-{short}-service"}
    found: set[str] = set()
    for f in sorted(gitops.rglob("*.yaml")):
        text = read(f)
        if f"openbank-{short}-service" not in text:
            continue
        for doc in text.split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("Deployment", "Rollout"):
                continue
            name = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            if not name or name.group(1) not in names:
                continue
            explicit = re.search(r"^\s{2}namespace:\s*(\S+)", doc, re.M)
            if explicit:
                found.add(explicit.group(1))
            else:
                inherited = _nearest_kustomize_namespace(f, gitops)
                if inherited:
                    found.add(inherited)
    return found


def service_namespace(short: str, gitops: Path) -> str | None:
    """The single namespace this service runs in, or None if it is not deployed.

    Returns None rather than guessing when there is no workload: a caller that needs a
    displayable value can fall back to the short name, but a caller deciding whether the
    service is SCRAPED must not be handed a namespace nobody deployed.
    """
    namespaces = workload_namespaces(short, gitops)
    if not namespaces:
        return None
    # Deterministic when a service is (unusually) declared in more than one namespace.
    return sorted(namespaces)[0]


def podmonitor_namespaces(gitops: Path) -> set[str]:
    """`namespaceSelector.matchNames` of the fleet PodMonitor — the namespaces Prometheus scrapes."""
    podmon = gitops / "components" / "observability" / "podmonitor-openbank-services.yaml"
    text = read(podmon)
    if "matchNames:" not in text:
        return set()
    out: set[str] = set()
    for line in text.split("matchNames:", 1)[1].splitlines():
        m = re.match(r"^(\s+)-\s+(\S+)\s*$", line)
        if m:
            out.add(m.group(2))
        elif line.strip():
            break  # dedented back out of the list
    return out


def declared_datastore(short: str, repo: Path) -> str:
    """`primaryDatastore` from the service's governance.yaml (ADR-0071), '' when undeclared."""
    txt = read(repo / f"openbank-{short}-service" / "governance.yaml")
    m = re.search(r"^primaryDatastore:\s*(.+?)\s*$", txt, re.M)
    return m.group(1).strip() if m else ""


def is_stateless(datastore: str) -> bool:
    """True when the service declares no primary datastore at all."""
    return (datastore or "").strip().lower() in ("", "none", "n/a", "—", "-")
