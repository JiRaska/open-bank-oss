#!/usr/bin/env python3
"""Guard: every Temporal namespace a service actually polls must be in the registration Job.

WHY THIS EXISTS: `temporal-namespace-registration.yaml` carries a hand-written, space-separated
`NAMESPACES` list. A service whose namespace is missing from it does not fail to start and does
not go red — the Temporal worker connects to the frontend fine and only the *poll* is refused,
so the failure lives entirely in a retry loop:

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

WHAT IT DELIBERATELY DOES NOT DO: it does not fail on an EXTRA registered namespace. Registering
a namespace nothing polls is harmless (an idle namespace costs nothing and retention reaps it),
and several entries exist for workers that live outside this repo's `openbank-*` modules. Failing
on extras would make the gate a maintenance tax with no defect behind it.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

# `namespace: ${OPENBANK_TEMPORAL_NAMESPACE:openbank-lending}` under an `openbank.temporal` block.
CONFIG_DEFAULT_RE = re.compile(
    r"namespace:\s*\$\{OPENBANK_TEMPORAL_NAMESPACE:(?P<ns>[A-Za-z0-9._-]+)\}"
)
# The env override in a gitops manifest, tolerant of quoting and of `value` on the next line.
GITOPS_ENV_RE = re.compile(
    r"""name:\s*["']?OPENBANK_TEMPORAL_NAMESPACE["']?\s*\n\s*value:\s*["']?(?P<ns>[A-Za-z0-9._-]+)["']?"""
)
NAMESPACES_RE = re.compile(r"""name:\s*NAMESPACES\s*\n\s*value:\s*["'](?P<list>[^"']*)["']""")

REGISTRATION = "openbank-infra/gitops/components/temporal/temporal-namespace-registration.yaml"
GITOPS_COMPONENTS = "openbank-infra/gitops/components"


def strip_yaml_comments(text: str) -> str:
    """A `#` comment naming a namespace must not satisfy the check — the repo has been bitten by a
    guard matching the prose about the thing instead of the thing."""
    return "\n".join(line if (h := line.find("#")) < 0 else line[:h] for line in text.splitlines())


def registered_namespaces(root: pathlib.Path) -> set[str] | None:
    path = root / REGISTRATION
    if not path.is_file():
        return None
    match = NAMESPACES_RE.search(strip_yaml_comments(path.read_text(encoding="utf-8", errors="replace")))
    return set(match.group("list").split()) if match else None


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
        print(
            f"::error file={REGISTRATION}::could not read the NAMESPACES list from the Temporal "
            "registration Job — the check's own input is missing or reshaped, which is not the "
            "same as the fleet being clean. Investigate before trusting this run."
        )
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
            f"::error file={REGISTRATION}::{service} polls Temporal namespace `{namespace}`, which "
            "the registration Job does not create. The worker will retry forever against a "
            "namespace that does not exist — the pod stays Ready, ArgoCD stays Healthy, no "
            "workflow ever runs, and the only symptom is log volume. Add it to NAMESPACES."
        )

    verdict = "clean." if not missing else f"{len(missing)} MISSING above."
    print(
        f"check-temporal-namespace-registration: {len(required)} service(s) poll a Temporal "
        f"namespace, {len(registered)} registered — {verdict}"
    )
    return 1 if missing else 0


def self_test() -> int:
    """Feed the checker the three shapes it must get right, including one it must NOT flag."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / GITOPS_COMPONENTS / "temporal").mkdir(parents=True)
        (root / GITOPS_COMPONENTS / "camp").mkdir(parents=True)

        def write_registration(namespaces: str) -> None:
            (root / REGISTRATION).write_text(
                "spec:\n  env:\n    - name: NAMESPACES\n"
                f'      value: "{namespaces}"\n'
                '    - name: RETENTION\n      value: "168h"\n'
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
        write_registration("openbank-ok openbank-camp")
        (root / REGISTRATION).write_text(
            (root / REGISTRATION).read_text() + "\n# TODO: register openbank-lending here\n"
        )
        if check(root) == 0:
            failures.append("was satisfied by a namespace appearing only in a COMMENT")

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
