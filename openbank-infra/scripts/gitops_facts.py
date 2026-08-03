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
    "management_port_workloads",
    "management_scraped_namespaces",
    "declared_datastore",
    "is_stateless",
    "cnpg_backup_configured",
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


def workload_kind(short: str, gitops: Path) -> str:
    """`Rollout` or `Deployment` — which kind actually carries this service.

    They are NOT interchangeable to kubectl, and the fleet is split: 21 Rollouts (essentially the
    whole money path — ledger, consent, sepa-payment, transaction, settlement, fraud, sanctions, kyc)
    against 121 Deployments. A runbook that names the wrong one hands an operator
    `Error from server (NotFound)` during the incident it was written for (issue #2662).

    Same matching discipline as [workload_namespaces]: a manifest that merely MENTIONS the service
    does not count — a NetworkPolicy naming it as a peer or an env var holding its URL would
    otherwise decide the kind. The workload's own `metadata.name` must be the service.

    Defaults to `Deployment` when nothing matches, because that is what an unmanaged or not-yet-
    declared service will be, and because the wrong default is the one that fails loudly rather
    than the one that silently addresses a resource that happens to exist.
    """
    names = module_names(short)
    haystacks = (f"openbank-{short}-service", f"openbank-{short}")
    for f in sorted(gitops.rglob("*.yaml")):
        text = read(f)
        if not any(h in text for h in haystacks):
            continue
        for doc in text.split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("Deployment", "Rollout"):
                continue
            name = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            if name and name.group(1) in names:
                return kind.group(1)
    return "Deployment"


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


def management_port_workloads(gitops: Path) -> dict[str, set[str]]:
    """{namespace: {workload names}} for every workload declaring a container port `management`.

    This is the SCRAPE CONTRACT, read off the workloads themselves. The fleet PodMonitor scrapes
    `port: management, path: /q/metrics`, so a workload that declares that port is asking to be
    scraped, and a workload that does not cannot be scraped no matter which namespace it sits in.
    Deriving the population this way is the whole point: [workload_namespaces] answers "where does
    module X run", which can only ever enumerate namespaces for modules someone already listed.

    That difference is not academic. The caller that used to drive the coverage gate iterated
    `repo.glob("openbank-*-service")` plus the money-path list, so the eight control-plane agent
    workloads — devops-agent, finops-agent, governance-auditor, docs-truth-agent,
    authz-policy-auditor, flaky-test-hunter, control-liveness-sentinel, release-steward — were
    never in its population at all. All eight declare a `management` port, none was in
    `matchNames`, and the gate printed `0 missing` about a fleet it had never looked at. A gate
    whose scope is a hand-kept list of the thing it checks reads as passing when the list is
    short, never as unchecked (CLAUDE.md).
    """
    out: dict[str, set[str]] = {}
    for f in sorted(gitops.rglob("*.yaml")):
        text = read(f)
        if "name: management" not in text:
            continue
        for doc in text.split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("Deployment", "Rollout", "StatefulSet"):
                continue
            # The port entry itself, not a mere mention: `- name: management` inside `ports:`.
            if not re.search(r"^\s+-\s+name:\s*management\s*$", doc, re.M):
                continue
            name = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            explicit = re.search(r"^\s{2}namespace:\s*(\S+)", doc, re.M)
            ns = explicit.group(1) if explicit else _nearest_kustomize_namespace(f, gitops)
            if ns:
                out.setdefault(ns, set()).add(name.group(1) if name else f.name)
    return out


def management_scraped_namespaces(gitops: Path) -> dict[str, set[str]]:
    """{namespace: {monitor names}} for every Pod/ServiceMonitor scraping `port: management`.

    The fleet PodMonitor is not the only one — `iam` (keycloak) has its own, and billing,
    document-service and statement-service each carry a per-service ServiceMonitor on the same
    port. A gate that only reads the fleet PodMonitor would report keycloak as an uncovered gap
    and get argued down, which is how a real gap ends up excluded next to a false one.

    Only endpoints on the `management` port count. A monitor scraping `redis-metrics` or
    `tcp-prometheus` in a namespace says nothing about whether that namespace's Quarkus
    `/q/metrics` reaches Prometheus.
    """
    out: dict[str, set[str]] = {}
    for f in sorted(gitops.rglob("*.yaml")):
        text = read(f)
        if "Monitor" not in text:
            continue
        for doc in text.split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("PodMonitor", "ServiceMonitor"):
                continue
            if not re.search(r"^\s+-\s+port:\s*management\s*$", doc, re.M):
                continue
            name_m = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            name = name_m.group(1) if name_m else f.name
            own = re.search(r"^\s{2}namespace:\s*(\S+)", doc, re.M)
            own_ns = own.group(1) if own else _nearest_kustomize_namespace(f, gitops)
            targets: set[str] = set()
            if re.search(r"^\s+any:\s*true\s*$", doc, re.M):
                targets.add("*")
            elif "matchNames:" in doc:
                for line in doc.split("matchNames:", 1)[1].splitlines():
                    m = re.match(r"^(\s+)-\s+(\S+)\s*$", line)
                    if m:
                        targets.add(m.group(2))
                    elif line.strip():
                        break
            elif own_ns:
                # No namespaceSelector: the operator defaults to the monitor's own namespace.
                targets.add(own_ns)
            for t in targets:
                out.setdefault(t, set()).add(name)
    return out


def _strip_comment(line: str) -> str:
    """Drop a `#` comment. Only a full-line `#` or one preceded by whitespace counts, so a value
    like `destinationPath: s3://bucket/path#frag` survives."""
    if line.lstrip().startswith("#"):
        return ""
    return re.split(r"\s#", line, maxsplit=1)[0]


def _doc_entries(doc: str) -> list[tuple[int, str, str]]:
    """(indent, key, value) for every mapping key in a single YAML document.

    A deliberately small structural reader, not a YAML parser: this module is stdlib-only (it is
    imported by scripts that must run on a bare runner), and the only questions asked of it are
    "is this document `kind: X`" and "does it declare the key path a.b.c". Comments are stripped
    first — the whole reason this exists is that a substring match cannot tell a configured key
    from prose that names it.

    A list-item key (`- name: x`) is recorded at the indent of the key itself, not the dash, so
    it nests correctly under its parent.
    """
    out: list[tuple[int, str, str]] = []
    for raw in doc.splitlines():
        line = _strip_comment(raw)
        if not line.strip():
            continue
        m = re.match(r"^(\s*)(-\s+)?([A-Za-z_][\w.\-]*)\s*:(\s.*|)$", line)
        if not m:
            continue
        indent = len(m.group(1)) + (len(m.group(2)) if m.group(2) else 0)
        out.append((indent, m.group(3), m.group(4).strip()))
    return out


def _has_key_path(entries: list[tuple[int, str, str]], path: tuple[str, ...]) -> bool:
    """True iff `entries` declares the nested mapping path, rooted at the document's top level.

    Each component must appear strictly inside the previous one's block (greater indent, before
    the block dedents), so `spec.backup.barmanObjectStore` is not satisfied by a `barmanObjectStore`
    that sits somewhere else in the document.
    """
    lo, hi, parent = 0, len(entries), -1
    for depth, key in enumerate(path):
        found = -1
        end = hi
        for i in range(lo, hi):
            indent, k, _ = entries[i]
            if indent <= parent:
                end = i
                break
            if k == key and (depth > 0 or indent == 0):
                found = i
                break
        if found < 0:
            return False
        parent = entries[found][0]
        lo, hi = found + 1, end
        for i in range(lo, hi):
            if entries[i][0] <= parent:
                hi = i
                break
    return True


def _yaml_documents(text: str) -> list[str]:
    """The documents of a possibly multi-document YAML file."""
    return re.split(r"^---\s*$", text, flags=re.M)


def cnpg_backup_configured(short: str, gitops: Path) -> bool:
    """True iff a CNPG `kind: Cluster` document for this service declares `spec.backup.barmanObjectStore`.

    Matching mirrors the prod-readiness collector's `gitops_files_for(short, "Cluster")`: when the
    service has its own `components/<short>/` directory that directory IS the scope, and only
    outside it does the service name have to appear in the document.

    The previous implementation asked `"barmanObjectStore" in text and (short in text or ...)`
    over every file under `components/`, which matched PROSE. `rules.yaml` carries the literal
    `barmanObjectStore` inside a rule DESCRIPTION, and until #3508 every service's OPA bundle
    ConfigMap embedded rules.yaml verbatim — so `components/<svc>/<svc>-opa-bundle.yaml` satisfied
    both halves for many services, and their runbooks promised on-call an RPO the cluster could
    not deliver (mcp-service, which has no CNPG cluster at all, promised "RPO target: <= 5 min").
    #3508 removed the embed and the collision went away; the TEST did not, so any future file
    under a component directory containing both strings would reproduce it. Hence: structure.
    """
    comp = gitops / "components" / short
    scoped = comp.is_dir()
    root = comp if scoped else gitops
    if not root.is_dir():
        return False
    for f in sorted(root.rglob("*.yaml")):
        text = read(f)
        if "barmanObjectStore" not in text:
            continue  # cheap prefilter — the key must be present literally to be present structurally
        for doc in _yaml_documents(text):
            if not scoped and short not in doc:
                continue
            entries = _doc_entries(doc)
            if not any(i == 0 and k == "kind" and v == "Cluster" for i, k, v in entries):
                continue
            if _has_key_path(entries, ("spec", "backup", "barmanObjectStore")):
                return True
    return False


def declared_datastore(short: str, repo: Path) -> str:
    """`primaryDatastore` from the service's governance.yaml (ADR-0071), '' when undeclared."""
    txt = read(module_dir(short, repo) / "governance.yaml")
    m = re.search(r"^primaryDatastore:\s*(.+?)\s*$", txt, re.M)
    return m.group(1).strip() if m else ""


def is_stateless(datastore: str) -> bool:
    """True when the service declares no primary datastore at all."""
    return (datastore or "").strip().lower() in ("", "none", "n/a", "—", "-")
