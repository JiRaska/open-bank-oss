#!/usr/bin/env python3
"""Guard: the Temporal namespace list is COMPLETE, and lives somewhere ArgoCD can act on.

WHY THIS EXISTS: the Temporal namespace set is a hand-written, space-separated list. A service
whose namespace is missing from it does not fail to start and does not go red — the Temporal
worker connects to the frontend fine and only the *poll* is refused, so the failure lives
entirely in a retry loop:

    Failure in poller thread Workflow Poller taskQueue="openbank-lending-origination",
    namespace="openbank-lending": 2

The pod stays 2/2 (readiness does not touch Temporal), ArgoCD stays Synced/Healthy, and the
service simply never runs a workflow. The only observable is log volume.

THIS IS THE THIRD OCCURRENCE, which is what makes it a gate rather than another comment in the
YAML:
  * settlement — the list said the worker used `openbank-default`; it polls `openbank-settlement`,
    found by NOT_FOUND on go-live (runbook 0006).
  * campaign  — its Deployment set no `OPENBANK_TEMPORAL_NAMESPACE`, so the worker fell back to
    the bare `openbank`, which was not registered either.
  * lending   — `openbank-lending` was never added. Measured in the sandbox 2026-08-02: both the
    workflow and the activity poller retried forever, ~2,000 ERROR lines/hour, and no loan
    origination workflow could start.

Each time the LIST was the thing that drifted, never the service. So the check derives the
required set from the two places a namespace can actually come from, and compares:

  1. the gitops `OPENBANK_TEMPORAL_NAMESPACE` env on the service's Deployment/Rollout — which
     WINS, because it overrides the config default at runtime; and
  2. otherwise the `${OPENBANK_TEMPORAL_NAMESPACE:<default>}` default in the service's
     `application.yaml`.

Precedence matters and is the campaign lesson in miniature: reading only (2) reports a namespace
the service does not use, and reading only (1) misses every service that never sets the env.

WHERE THE LIST IS ALLOWED TO LIVE (issue #3507) — the second half of this gate, and the reason a
complete list was still not enough. The list used to be an inline env value on the registration
Job, which is an ArgoCD **PostSync hook**. ArgoCD does not diff hooks: they are absent from the
Application's desired-state comparison, which reports the Job's status as `None` while every
other resource reads `Synced`. So editing only that file produced NO diff, no sync operation ran,
and the hook never fired — making "add a namespace to the list", the sole edit anyone ever
performs on it, the one edit that could not trigger it. Measured after #3475: the Application sat
`Synced/Healthy` at the new revision with no Job in the namespace and `openbank-lending` still
unregistered, until a manual sync was issued by hand.

So the list must be declared on a resource ArgoCD COMPARES, and must be declared exactly once:
  * on a hook  -> fail. The registration is unreachable by the edit that needs it.
  * twice      -> fail. Two copies of a namespace list is the drift this gate exists to catch,
                  reintroduced one level up.
  * unconsumed -> fail. A ConfigMap nothing mounts or reads is a list that registers nothing —
                  the same silent no-op wearing a different hat.

WHAT IT DELIBERATELY DOES NOT DO: it does not fail on an EXTRA registered namespace. Registering
a namespace nothing polls is harmless (an idle namespace costs nothing and retention reaps it),
and several entries exist for workers that live outside this repo's `openbank-*` modules. Failing
on extras would make the gate a maintenance tax with no defect behind it.

It also cannot see the CLUSTER: whether a declared namespace actually exists in the running
Temporal is not observable from a PR runner. That half is the `temporal-namespace-reconcile`
CronJob, which registers whatever the sync path missed and then fails so KubeJobFailed carries it.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

# `namespace: ${OPENBANK_TEMPORAL_NAMESPACE:openbank-lending}` under an `openbank.temporal` block.
CONFIG_DEFAULT_RE = re.compile(
    r"namespace:\s*\$\{OPENBANK_TEMPORAL_NAMESPACE:(?P<ns>[A-Za-z0-9._-]+)\}"
)
# The env override in a gitops manifest, tolerant of quoting and of `value` on the next line.
GITOPS_ENV_RE = re.compile(
    r"""name:\s*["']?OPENBANK_TEMPORAL_NAMESPACE["']?\s*\n\s*value:\s*["']?(?P<ns>[A-Za-z0-9._-]+)["']?"""
)

TEMPORAL_DIR = "openbank-infra/gitops/components/temporal"
NAMESPACE_SOURCE = f"{TEMPORAL_DIR}/temporal-namespace-config.yaml"
GITOPS_COMPONENTS = "openbank-infra/gitops/components"

HOOK_ANNOTATION_PREFIX = "argocd.argoproj.io/hook"
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet", "Job", "CronJob"}


def strip_yaml_comments(text: str) -> str:
    """A `#` comment naming a namespace must not satisfy the check — the repo has been bitten by a
    guard matching the prose about the thing instead of the thing. (The declaration scan below
    parses YAML, where comments are dropped for free; this is for the regex-based scans.)"""
    return "\n".join(line if (h := line.find("#")) < 0 else line[:h] for line in text.splitlines())


def _docs(path: pathlib.Path) -> list[dict]:
    try:
        loaded = yaml.safe_load_all(path.read_text(encoding="utf-8", errors="replace"))
        return [d for d in loaded if isinstance(d, dict)]
    except yaml.YAMLError:
        # A templated or otherwise unparseable manifest cannot carry a declaration we can read;
        # it is skipped, not treated as clean — the "no declaration at all" branch is fatal.
        return []


def _pod_specs(doc: dict) -> list[dict]:
    spec = doc.get("spec") or {}
    if doc.get("kind") == "CronJob":
        template = ((spec.get("jobTemplate") or {}).get("spec") or {}).get("template") or {}
    else:
        template = spec.get("template") or {}
    pod = template.get("spec")
    return [pod] if isinstance(pod, dict) else []


def _containers(pod: dict) -> list[dict]:
    out = []
    for key in ("initContainers", "containers"):
        for container in pod.get(key) or []:
            if isinstance(container, dict):
                out.append(container)
    return out


def namespace_declarations(root: pathlib.Path) -> list[dict]:
    """Every place under the temporal component that states a literal NAMESPACES value.

    Returns one record per declaration: where it is, what it says, whether the resource carrying
    it is an ArgoCD hook (and therefore never diffed), and — for a ConfigMap — its name, so the
    caller can check something actually reads it.
    """
    found: list[dict] = []
    directory = root / TEMPORAL_DIR
    if not directory.is_dir():
        return found
    for path in sorted(directory.glob("*.yaml")):
        rel = path.relative_to(root).as_posix()
        for doc in _docs(path):
            metadata = doc.get("metadata") or {}
            annotations = metadata.get("annotations") or {}
            record = {
                "file": rel,
                "kind": doc.get("kind"),
                "name": metadata.get("name"),
                "hook": any(str(k).startswith(HOOK_ANNOTATION_PREFIX) for k in annotations),
            }
            if doc.get("kind") == "ConfigMap":
                value = (doc.get("data") or {}).get("NAMESPACES")
                if isinstance(value, str):
                    found.append({**record, "namespaces": set(value.split())})
            for pod in _pod_specs(doc):
                for container in _containers(pod):
                    for env in container.get("env") or []:
                        if not isinstance(env, dict) or env.get("name") != "NAMESPACES":
                            continue
                        if isinstance(env.get("value"), str):
                            found.append({**record, "namespaces": set(env["value"].split())})
    return found


def configmap_consumers(root: pathlib.Path, name: str) -> list[str]:
    """Workloads under the temporal component that read the named ConfigMap (env ref or volume)."""
    consumers: list[str] = []
    directory = root / TEMPORAL_DIR
    if not directory.is_dir():
        return consumers
    for path in sorted(directory.glob("*.yaml")):
        for doc in _docs(path):
            if doc.get("kind") not in WORKLOAD_KINDS:
                continue
            for pod in _pod_specs(doc):
                referenced = False
                for volume in pod.get("volumes") or []:
                    if isinstance(volume, dict) and (volume.get("configMap") or {}).get("name") == name:
                        referenced = True
                for container in _containers(pod):
                    for env in container.get("env") or []:
                        if not isinstance(env, dict):
                            continue
                        ref = (env.get("valueFrom") or {}).get("configMapKeyRef") or {}
                        if ref.get("name") == name:
                            referenced = True
                    for source in container.get("envFrom") or []:
                        if isinstance(source, dict) and (source.get("configMapRef") or {}).get("name") == name:
                            referenced = True
                if referenced:
                    consumers.append(f"{doc.get('kind')}/{(doc.get('metadata') or {}).get('name')}")
    return consumers


def registered_namespaces(root: pathlib.Path) -> set[str] | None:
    """The declared namespace set, or None when the declaration is missing or unreachable.

    Every None path prints its own ::error first — "the input is not what this check assumes" is a
    different verdict from "the fleet is clean", and the repo has paid for conflating them.
    """
    declarations = namespace_declarations(root)
    if not declarations:
        print(
            f"::error file={NAMESPACE_SOURCE}::no NAMESPACES declaration found anywhere under "
            f"{TEMPORAL_DIR}/ — the check's own input is missing or reshaped, which is not the "
            "same as the fleet being clean. Investigate before trusting this run."
        )
        return None

    if len(declarations) > 1:
        where = ", ".join(f"{d['file']} ({d['kind']}/{d['name']})" for d in declarations)
        print(
            f"::error file={NAMESPACE_SOURCE}::NAMESPACES is declared {len(declarations)} times "
            f"({where}). Two copies of the Temporal namespace list is exactly the drift this gate "
            "exists to catch, one level up: the copies are free to disagree and nothing else would "
            "notice. Declare it once, in the ConfigMap, and have every consumer read it from there."
        )
        return None

    declaration = declarations[0]
    if declaration["hook"]:
        print(
            f"::error file={declaration['file']}::NAMESPACES is declared on an ArgoCD HOOK "
            f"({declaration['kind']}/{declaration['name']}). ArgoCD does not diff hook resources — "
            "they are excluded from the Application's desired-state comparison — so editing this "
            "file produces no diff, no sync operation runs, and the registration never fires. That "
            "makes 'add a namespace to the list', the only edit this resource ever receives, the "
            "one edit that cannot trigger it (issue #3507). Move the list to a non-hook resource "
            f"({NAMESPACE_SOURCE}) and reference it from the hook."
        )
        return None

    if declaration["kind"] == "ConfigMap":
        consumers = configmap_consumers(root, declaration["name"])
        if not consumers:
            print(
                f"::error file={declaration['file']}::the ConfigMap "
                f"`{declaration['name']}` declares NAMESPACES but no workload under "
                f"{TEMPORAL_DIR}/ reads it (no configMapKeyRef, envFrom or volume). A list nothing "
                "consumes registers nothing — a silent no-op that reads exactly like a working "
                "mechanism, which is the failure class this gate was written for."
            )
            return None

    return declaration["namespaces"]


def gitops_env_overrides(root: pathlib.Path) -> dict[str, str]:
    """Map component-directory name -> namespace, for every manifest setting the env explicitly."""
    overrides: dict[str, str] = {}
    components = root / GITOPS_COMPONENTS
    if not components.is_dir():
        return overrides
    for manifest in sorted(components.rglob("*.yaml")):
        body = strip_yaml_comments(manifest.read_text(encoding="utf-8", errors="replace"))
        for match in GITOPS_ENV_RE.finditer(body):
            overrides[manifest.stem] = match.group("ns")
    return overrides


def required_namespaces(root: pathlib.Path) -> dict[str, str]:
    """Map service module name -> the namespace its worker will actually poll."""
    overrides = gitops_env_overrides(root)
    required: dict[str, str] = {}
    for config in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        service = config.parts[-5]
        match = CONFIG_DEFAULT_RE.search(strip_yaml_comments(config.read_text(encoding="utf-8", errors="replace")))
        if not match:
            continue
        # The gitops env overrides the config default at runtime, so it wins when present. Match
        # loosely on the module name because manifest filenames are not uniformly derived from it
        # (`campaign-service.yaml` for `openbank-campaign-service`, `lending-service.yaml`, ...).
        stem = service.removeprefix("openbank-")
        override = overrides.get(stem) or overrides.get(stem.removesuffix("-service"))
        required[service] = override or match.group("ns")
    return required


def check(root: pathlib.Path) -> int:
    registered = registered_namespaces(root)
    if registered is None:
        return 1

    required = required_namespaces(root)
    if not required:
        print(
            "::error::check-temporal-namespace-registration found NO service declaring a Temporal "
            "namespace — the derivation is broken, not the fleet."
        )
        return 1

    missing = {svc: ns for svc, ns in sorted(required.items()) if ns not in registered}
    for service, namespace in missing.items():
        print(
            f"::error file={NAMESPACE_SOURCE}::{service} polls Temporal namespace `{namespace}`, "
            "which the registration ConfigMap does not declare. The worker will retry forever "
            "against a namespace that does not exist — the pod stays Ready, ArgoCD stays Healthy, "
            "no workflow ever runs, and the only symptom is log volume. Add it to NAMESPACES."
        )

    verdict = "clean." if not missing else f"{len(missing)} MISSING above."
    print(
        f"check-temporal-namespace-registration: {len(required)} service(s) poll a Temporal "
        f"namespace, {len(registered)} registered — {verdict}"
    )
    return 1 if missing else 0


def self_test() -> int:
    """Feed the checker the shapes it must get right, including several it must NOT flag."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / TEMPORAL_DIR).mkdir(parents=True)
        (root / GITOPS_COMPONENTS / "camp").mkdir(parents=True)
        source = root / NAMESPACE_SOURCE
        consumer = root / TEMPORAL_DIR / "temporal-namespace-registration.yaml"

        def write_registration(namespaces: str, *, trailer: str = "") -> None:
            """The shipped shape: a plain ConfigMap holding the list, read by a hook Job."""
            source.write_text(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n"
                "  name: temporal-namespace-registration\n  namespace: temporal\n"
                f'data:\n  NAMESPACES: "{namespaces}"\n  RETENTION: "168h"\n{trailer}'
            )

        def write_consumer() -> None:
            consumer.write_text(
                "apiVersion: batch/v1\nkind: Job\nmetadata:\n"
                "  name: temporal-namespace-registration\n  namespace: temporal\n"
                "  annotations:\n    argocd.argoproj.io/hook: PostSync\n"
                "spec:\n  template:\n    spec:\n      containers:\n"
                "        - name: register\n          image: admin-tools\n          env:\n"
                "            - name: NAMESPACES\n              valueFrom:\n"
                "                configMapKeyRef:\n"
                "                  name: temporal-namespace-registration\n"
                "                  key: NAMESPACES\n"
            )

        def write_service(name: str, default_ns: str) -> None:
            resources = root / name / "src" / "main" / "resources"
            resources.mkdir(parents=True)
            (resources / "application.yaml").write_text(
                "openbank:\n  temporal:\n"
                f"    namespace: ${{OPENBANK_TEMPORAL_NAMESPACE:{default_ns}}}\n"
                "    task-queue: q\n"
            )

        write_service("openbank-ok-service", "openbank-ok")
        write_service("openbank-lending-service", "openbank-lending")
        # Config default is the bare `openbank`, but gitops overrides it — the campaign shape.
        write_service("openbank-camp-service", "openbank")
        (root / GITOPS_COMPONENTS / "camp" / "camp.yaml").write_text(
            "env:\n  - name: OPENBANK_TEMPORAL_NAMESPACE\n    value: openbank-camp\n"
        )
        write_consumer()

        failures = []

        write_registration("openbank-ok openbank-camp")
        if check(root) == 0:
            failures.append("did NOT flag a service whose namespace is unregistered")

        write_registration("openbank-ok openbank-camp openbank-lending")
        if check(root) != 0:
            failures.append("flagged a fully-registered fleet")

        # The override must WIN: registering the bare config default is not enough.
        write_registration("openbank-ok openbank-lending openbank")
        if check(root) == 0:
            failures.append("honoured the config default over the gitops env override")

        # An extra registered namespace nothing polls is deliberately fine.
        write_registration("openbank-ok openbank-camp openbank-lending openbank-unused")
        if check(root) != 0:
            failures.append("flagged an EXTRA registered namespace, which is harmless by design")

        # A namespace named only in a comment must not satisfy the check.
        write_registration(
            "openbank-ok openbank-camp", trailer="# TODO: register openbank-lending here\n"
        )
        if check(root) == 0:
            failures.append("was satisfied by a namespace appearing only in a COMMENT")

        # ---- issue #3507: WHERE the list lives ------------------------------------------------
        complete = "openbank-ok openbank-camp openbank-lending"
        write_registration(complete)

        # (a) A complete list carried on a hook resource is unreachable by the edit that needs it.
        source_backup = source.read_text()
        consumer_backup = consumer.read_text()
        source.write_text(
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: unrelated\n  namespace: temporal\n"
            "data:\n  OTHER: x\n"
        )
        consumer.write_text(
            "apiVersion: batch/v1\nkind: Job\nmetadata:\n"
            "  name: temporal-namespace-registration\n  namespace: temporal\n"
            "  annotations:\n    argocd.argoproj.io/hook: PostSync\n"
            "spec:\n  template:\n    spec:\n      containers:\n"
            "        - name: register\n          image: admin-tools\n          env:\n"
            f'            - name: NAMESPACES\n              value: "{complete}"\n'
        )
        if check(root) == 0:
            failures.append("accepted a COMPLETE list declared on an ArgoCD hook (issue #3507)")

        # (b) The same list on a non-hook Job is fine — the rule is about the hook, not about Jobs.
        consumer.write_text(consumer.read_text().replace("  annotations:\n    argocd.argoproj.io/hook: PostSync\n", ""))
        if check(root) != 0:
            failures.append("flagged a list on a NON-hook workload, which is legal")

        # (c) Two declarations must fail even when both agree — copies drift later, not now.
        source.write_text(source_backup)
        if check(root) == 0:
            failures.append("accepted TWO declarations of NAMESPACES")

        # (d) A ConfigMap nothing reads registers nothing.
        consumer.write_text(
            "apiVersion: batch/v1\nkind: Job\nmetadata:\n  name: unrelated\n  namespace: temporal\n"
            "spec:\n  template:\n    spec:\n      containers:\n"
            "        - name: x\n          image: y\n"
        )
        if check(root) == 0:
            failures.append("accepted a NAMESPACES ConfigMap that no workload consumes")

        # (e) And the shipped shape — ConfigMap + hook Job reading it — must pass.
        consumer.write_text(consumer_backup)
        if check(root) != 0:
            failures.append("flagged the shipped shape: a ConfigMap read by a hook Job")

        for message in failures:
            print(f"::error::self-test FAILED — the checker {message}.")
        print("check-temporal-namespace-registration --self-test: " + ("clean." if not failures else "FAILED."))
        return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root to scan")
    parser.add_argument("--self-test", action="store_true", help="prove the checker's RED is reachable")
    args = parser.parse_args()
    return self_test() if args.self_test else check(pathlib.Path(args.root))


if __name__ == "__main__":
    sys.exit(main())
