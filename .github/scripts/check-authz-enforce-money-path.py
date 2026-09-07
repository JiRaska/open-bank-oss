#!/usr/bin/env python3
"""Every money-path service must ENFORCE authorization in its deployed manifest (issue #8470).

WHY THIS EXISTS: a money-path service whose deployment omits `AUTHZ_ENFORCE` runs whatever its
code default says — and for 16 of the 23 `rules.yaml: money_path_services` that default is
`false` (advisory). Advisory means every `@Authorize` decision is logged and none is enforced,
silently, with a healthy pod: dropping one line from a manifest, or standing up a new
environment from the application defaults, downgrades authorization with no error anywhere.
Measured 2026-09-03 all 16 manifests do supply `true`, so the gap is latent rather than live —
but latent in the direction where the failure is invisible. The sibling gate
`authz-enforce-pdp-sidecar-parity` (#1797) checks the opposite direction (an enforcing service
must have a PDP to ask); nothing checked that a money-path service is enforcing at all.

WHAT IT CHECKS: for every service in `openbank-libs/governance/rules.yaml: money_path_services`,
    effective = the workload container's literal AUTHZ_ENFORCE env, if set;
                else the module's application.yaml `authz.enforce` default
                (`${AUTHZ_ENFORCE:x}` resolved; an absent authz block means the libs default true)
    violation iff effective is not true and the service is not in ADVISORY_ALLOWLIST,
              or no workload declaring the service's image is found at all.

THE ALLOWLIST IS THE DECISION RECORD, not a waiver bin. Three services run deliberately advisory
(#8470 problem B). An entry is only honoured while the service's manifest carries an in-file
justification naming this issue and the same target date this table carries — a bare
`value: "false"` between two other env vars reads exactly like an oversight (sanctions-service
did, for 67 days), and a date that exists only here would be the second copy of a fact, free to
drift. An allowlisted service whose manifest LOSES its justification or date is a violation, so
the exception cannot silently outlive its reasoning.

DECISION RECORD (issue #8470 acceptance criterion 1): this gate was chosen over inverting the
code default to `${AUTHZ_ENFORCE:true}` for the 16 fail-open services. Inverting makes the safe
state the default but changes local-dev and test behaviour for 16 services at once and every
`%test` profile pinning `false` would need re-checking; the gate is additive and fails the exact
change that would downgrade production (a manifest edit or a new money-path service), which is
the change CI actually sees. The fail-open code default stays, now asserted closed on the only
axis that matters — the deployed manifest.

stdlib + PyYAML via gatelib (same as check-authz-pdp-parity.py).
Usage: check-authz-enforce-money-path.py [--root .] [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

import gatelib

WORKLOAD_KINDS = {"Deployment", "Rollout"}
RULES = pathlib.Path("openbank-libs/governance/rules.yaml")
COMPONENTS = pathlib.Path("openbank-infra/gitops/components")

# Deliberately-advisory money-path services (issue #8470 B): service -> target flip date.
# The manifest must repeat BOTH the issue reference and the date (see module docstring); a date
# living only here would drift from the file a deployer actually reads.
ADVISORY_ALLOWLIST = {
    "openbank-psd2-service": "2026-10-05",
    "openbank-sanctions-service": "2026-10-05",
    "openbank-standing-order-service": "2026-10-05",
}

ISSUE_REF = "#8470"


def money_path_services(root: pathlib.Path) -> list[str]:
    data = gatelib.load_yaml(root / RULES) or {}
    services = data.get("money_path_services")
    if not isinstance(services, list) or not services:
        raise SystemExit("check-authz-enforce-money-path: rules.yaml money_path_services is missing or empty")
    return [str(s) for s in services]


def app_enforce_default(root: pathlib.Path, service_dir_name: str) -> bool | None:
    """The module's application.yaml `authz.enforce` default; None when the file is absent."""
    app_yaml = root / service_dir_name / "src" / "main" / "resources" / "application.yaml"
    if not app_yaml.exists():
        return None
    try:
        data = gatelib.load_yaml(app_yaml) or {}
    except yaml.YAMLError:
        return None
    authz = data.get("authz")
    enforce = authz.get("enforce") if isinstance(authz, dict) else None
    if enforce is None:
        return True  # no authz block ⇒ libs default is true
    text = str(enforce)
    m = re.search(r"\$\{AUTHZ_ENFORCE:(true|false)\}", text)
    if m:
        return m.group(1) == "true"
    return text.strip().lower() != "false"


def iter_workloads(components: pathlib.Path):
    for path in gatelib.rglob(components, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except yaml.YAMLError:
            continue
        for doc in docs:
            if isinstance(doc, dict) and doc.get("kind") in WORKLOAD_KINDS:
                yield path, doc


def pod_containers(workload: dict) -> list[dict]:
    spec = workload.get("spec") or {}
    template = spec.get("template") or {}
    pod_spec = (template.get("spec") or {}) if isinstance(template, dict) else {}
    return [c for c in (pod_spec.get("containers") or []) if isinstance(c, dict)]


def env_value(container: dict, name: str) -> str | None:
    for env in container.get("env") or []:
        if isinstance(env, dict) and env.get("name") == name:
            v = env.get("value")
            return None if v is None else str(v)
    return None


def manifest_enforce(root: pathlib.Path, service_dir_name: str) -> tuple[bool | None, pathlib.Path | None]:
    """The AUTHZ_ENFORCE value the service's deployed manifest supplies.

    (None, path) when a workload for the service exists but sets no literal value (a valueFrom
    is not a literal and cannot be reasoned about — the app default then applies);
    (None, None) when no workload declares the service's image at all.
    """
    found_path: pathlib.Path | None = None
    for path, workload in iter_workloads(root / COMPONENTS):
        for container in pod_containers(workload):
            image = str(container.get("image") or "")
            if not re.search(rf"(^|[/@:]){re.escape(service_dir_name)}([:@]|$)", image):
                continue
            found_path = path
            value = env_value(container, "AUTHZ_ENFORCE")
            if value is not None:
                return value.strip().lower() == "true", path
    return None, found_path


def manifest_has_justification(path: pathlib.Path, target_date: str) -> bool:
    """The manifest must carry the issue reference AND the same target date as the allowlist."""
    try:
        text = gatelib.read_text(path)
    except OSError:
        return False
    return ISSUE_REF in text and target_date in text


def self_test() -> int:
    import tempfile

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        (root / RULES.parent).mkdir(parents=True)
        (root / RULES).write_text("money_path_services:\n  - openbank-ok-service\n  - openbank-open-service\n"
                                  "  - openbank-psd2-service\n")
        (root / COMPONENTS / "x").mkdir(parents=True)

        def svc(name, app=None):
            d = root / name / "src" / "main" / "resources"
            d.mkdir(parents=True, exist_ok=True)
            if app is not None:
                (d / "application.yaml").write_text(app)

        def deploy(name, env_value_literal=None, raw_extra=""):
            env = ""
            if env_value_literal is not None:
                env = f'          - name: AUTHZ_ENFORCE\n            value: "{env_value_literal}"\n'
            (root / COMPONENTS / "x" / f"{name}.yaml").write_text(
                f"{raw_extra}kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n"
                f"        - name: app\n          image: registry/{name}:sandbox-1\n"
                f"          env:\n{env}")

        # Enforcing via the manifest: fine even with a fail-open code default.
        svc("openbank-ok-service", app='authz:\n  enforce: "${AUTHZ_ENFORCE:false}"\n')
        deploy("openbank-ok-service", "true")
        # Advisory with NO manifest env and a fail-open default: the latent #8470 A defect —
        # this is the case that must flag.
        svc("openbank-open-service", app='authz:\n  enforce: "${AUTHZ_ENFORCE:false}"\n')
        deploy("openbank-open-service")
        # Allowlisted advisory: honoured only while the manifest repeats the issue and the date.
        svc("openbank-psd2-service", app="authz:\n  enforce: true\n")
        deploy("openbank-psd2-service", "false",
               raw_extra="# Advisory while decision logs accumulate (#8470, flip target 2026-10-05).\n")

        violations, checked, missing = run(root)
        case("one latent fail-open deployment flags", [v[0] for v in violations], ["openbank-open-service"])
        case("all three services were checked", checked, 3)
        case("nothing is missing a workload", missing, [])

        # The same allowlisted service WITHOUT the justification in its manifest must flag —
        # otherwise the allowlist quietly becomes a permanent waiver.
        deploy("openbank-psd2-service", "false")
        violations, _, _ = run(root)
        case("allowlist without in-file justification flags",
             sorted(v[0] for v in violations), ["openbank-open-service", "openbank-psd2-service"])

        # A money-path service with NO workload at all is reported missing, never skipped.
        (root / COMPONENTS / "x" / "openbank-ok-service.yaml").unlink()
        violations, _, missing = run(root)
        case("a service with no manifest is missing", missing, ["openbank-ok-service"])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: authz-enforce money-path gate is falsifiable (6 cases)")
    return 0


def run(root: pathlib.Path) -> tuple[list[tuple[str, str]], int, list[str]]:
    violations: list[tuple[str, str]] = []
    missing: list[str] = []
    services = money_path_services(root)
    for service in services:
        manifest_value, path = manifest_enforce(root, service)
        if path is None:
            missing.append(service)
            continue
        effective = manifest_value if manifest_value is not None else app_enforce_default(root, service)
        if effective is True:
            continue
        target = ADVISORY_ALLOWLIST.get(service)
        if target and manifest_has_justification(path, target):
            continue
        reason = "manifest supplies AUTHZ_ENFORCE=false" if manifest_value is False else \
                 "no manifest value and the code default is advisory"
        if target:
            reason += f" (allowlisted until {target} but the manifest lacks the {ISSUE_REF} justification + date)"
        violations.append((service, reason))
    return violations, len(services), missing


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    violations, checked, missing = run(root)
    gatelib.subjects(checked, "money_path_services entries examined")
    for service in missing:
        print(f"::warning::money-path service {service} has no Deployment/Rollout manifest to assert AUTHZ_ENFORCE on")
    for service, reason in violations:
        print(f"::error::{service}: money-path authorization is NOT enforced — {reason}. "
              f"Either supply AUTHZ_ENFORCE=true in the manifest or record a dated, in-file advisory "
              f"justification ({ISSUE_REF}).")
    if violations or missing:
        print(f"authz-enforce money-path: {len(violations)} advisory/unenforced violation(s), "
              f"{len(missing)} service(s) without a manifest, {checked} checked")
        return 1 if args.enforce else 0
    print(f"authz-enforce money-path: OK — all {checked} money-path services enforce authorization "
          f"or carry a dated, justified advisory exception")
    return 0


if __name__ == "__main__":
    sys.exit(main())
