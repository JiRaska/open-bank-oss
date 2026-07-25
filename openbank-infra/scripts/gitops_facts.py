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
    "module_dir",
    "module_names",
    "money_path_services",
    "service_namespace",
    "workload_namespaces",
    "podmonitor_namespaces",
    "declared_datastore",
    "is_stateless",
]

# Most services live in `openbank-<short>-service`, but the three payment modules do not:
# `openbank-sepa-payment`, `openbank-sepa-instant` and `openbank-domestic-payment` drop the
# suffix. Anything that hardcodes the suffix silently skips them — which is exactly how they
# stayed absent from the prod-readiness matrix while `rules.yaml` declared all three money-path
# (#2364). Resolve the directory instead of assuming its shape.
_MODULE_SHAPES = ("openbank-{short}-service", "openbank-{short}")


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def module_dir(short: str, repo: Path) -> Path:
    """The directory of this module, whichever naming shape it uses.

    Falls back to the `-service` form when neither exists, so a caller that only wants a path
    to report about a missing module still gets a sensible one.
    """
    for shape in _MODULE_SHAPES:
        candidate = repo / shape.format(short=short)
        if candidate.is_dir():
            return candidate
    return repo / _MODULE_SHAPES[0].format(short=short)


def module_names(short: str) -> set[str]:
    """Every name this module is known by — both directory shapes and their bare workload names."""
    return {
        f"openbank-{short}-service",
        f"openbank-{short}",
        f"{short}-service",
        short,
    }


def money_path_services(repo: Path) -> set[str]:
    """Short names of `rules.yaml: money_path_services` — the AUTHORITATIVE money-path set.

    The collector used to carry a hand-copied 14-name literal of this list while rules.yaml
    declared 20. Six declared money-path services were therefore scored with the lenient
    non-money-path gate and read GO, and three were absent from the matrix entirely (#2364).
    CLAUDE.md's rule applies to code as much as to docs: never keep a second copy of a list that
    lives in rules.yaml — the second copy IS the drift.

    Raises when the list cannot be read. That is deliberate: an empty set would make EVERY
    service non-money-path and quietly relax the gate for all of them, which is the precise
    failure mode this repo keeps finding — a broken probe that reports the reassuring answer.
    """
    rules = repo / "openbank-libs" / "governance" / "rules.yaml"
    text = read(rules)
    if "money_path_services:" not in text:
        raise RuntimeError(
            f"money_path_services not found in {rules} — refusing to score with an empty "
            f"money-path set, which would relax the gate for every service"
        )
    out: set[str] = set()
    for line in text.split("money_path_services:", 1)[1].splitlines():
        m = re.match(r"^\s+-\s+openbank-([a-z0-9-]+?)(?:-service)?\s*(?:#.*)?$", line)
        if m:
            out.add(m.group(1))
            continue
        if line.strip() and not line.strip().startswith("#") and not line.startswith(" " * 4):
            break  # dedented out of the list
    if not out:
        raise RuntimeError(
            f"money_path_services in {rules} parsed to an empty set — refusing to score, "
            f"since that would silently relax the gate for every service"
        )
    return out


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
    names = module_names(short)
    # Pre-filter on the image reference, which carries the module's own name in either shape
    # (`openbank-sepa-payment:` as much as `openbank-ledger-service:`). Filtering on the
    # `-service` form alone skipped the three payment modules entirely (#2364).
    haystacks = (f"openbank-{short}-service", f"openbank-{short}")
    found: set[str] = set()
    for f in sorted(gitops.rglob("*.yaml")):
        text = read(f)
        if not any(h in text for h in haystacks):
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
    txt = read(module_dir(short, repo) / "governance.yaml")
    m = re.search(r"^primaryDatastore:\s*(.+?)\s*$", txt, re.M)
    return m.group(1).strip() if m else ""


def is_stateless(datastore: str) -> bool:
    """True when the service declares no primary datastore at all."""
    return (datastore or "").strip().lower() in ("", "none", "n/a", "—", "-")
