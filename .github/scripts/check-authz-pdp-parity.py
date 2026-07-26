#!/usr/bin/env python3
"""authz-enforce ⇒ OPA PDP sidecar parity guard (issue #1797).

WHY THIS EXISTS: eight live services enforced `@Authorize` (`authz.enforce` defaults true) while
their gitops manifest declared no `opa` sidecar. AuthorizeInterceptor fails closed when the PDP at
localhost:8181 is unreachable, so EVERY `@Authorize` endpoint on those services was bricked — it
could only ever error, for any caller — violating the interceptor's own invariant ("Advisory must
never brick an endpoint when no sidecar is deployed yet"). It stayed invisible because those
endpoints simply were not exercised in sandbox, until ADR-0179 added party.merge and someone called
it (HTTP 422/503 "policy decision point unavailable"). party-service was fixed per-service (#1798);
this guard closes the general case so a service can never again enforce authz with no PDP to ask.

WHAT IT CHECKS: for every workload (Deployment / argoproj Rollout) under
openbank-infra/gitops/components/** whose app container runs an `openbank-<svc>-service` image:
  annotated = the service module's src/main Kotlin carries >= 1 real `@Authorize` annotation
              (comments stripped first — see below)
  enforces = (app container's AUTHZ_ENFORCE env, if set) else (the service's application.yaml
             `authz.enforce` default: `${AUTHZ_ENFORCE:true}` or an absent `authz:` block ⇒ true;
             `${AUTHZ_ENFORCE:false}` ⇒ false — the libs default is true)
  has_pdp  = that SAME pod spec has a container named `opa`
  violation iff annotated AND enforces AND NOT has_pdp
The opa check is per-workload, not per-file, so a shared manifest (payments-services.yaml holds
several Rollouts, each with its own opa sidecar) is handled correctly.

WHY `annotated` IS PART OF THE PREDICATE (issue #2228). Without it the guard reasons purely from
the manifest and the `AUTHZ_ENFORCE` default, and flags a service that has nothing to fail closed:
finrep-service and onboarding-service carry ZERO `@Authorize` between them, yet were both reported
as violations. Nothing on those services was bricked and no sidecar would have fixed anything —
wiring one is ceremony, not a fix. That matters because the guard's count is read as "how much of
#1797 is left": a count that can never reach 0 by wiring is a count people stop trusting. The
converse defect is the one this predicate now also closes and is the reason it stays: a service can
declare `AUTHZ_ENFORCE=false` and be counted clean forever while growing `@Authorize` annotations
that will brick the day someone flips the flag. The count now means what its name implies.

The count reaching 0 on `main` today is NOT evidence this predicate works: #2403 set
`AUTHZ_ENFORCE=false` on both finrep and onboarding manifests, so the old manifest-only reasoning
also reports 0. The fix is therefore purely preventative, and its falsification lives in
check_authz_pdp_parity_test.py — which feeds it a service that MUST flag and one that MUST NOT.

COMMENTS ARE STRIPPED BEFORE MATCHING, and the stripper mirrors the fact that Kotlin block comments
NEST (`/* /* */ */` closes once, not twice). A guard over source text that matches raw text flags
the very prose that explains the bug it exists to catch — this file's own KDoc writes `@Authorize`
several times, and a KDoc on a resource saying "this used to carry @Authorize" is not an annotation.

KNOWN LIMITATION: this checks the MANIFEST, not the live pod. standing-order-service's manifest
declares the sidecar but the running pod lacks it (manifest-vs-live drift) — a manifest guard cannot
see that. Live-drift is a separate detector's job; this guard's contract is "the declared manifest
is self-consistent".

ADVISORY: findings are ::warning:: annotations; exits 0 unless invoked with --enforce. It has
graduated: ci.yml invokes it with `--enforce`, and the fleet carries no violations.

There is NO rule for this gate in rules.yaml, and there never has been. Earlier text here cited an
`authz_pdp_parity.target_enforce_date` key as the graduation deadline; that key does not exist, so
this gate is invisible to check-gate-graduation.sh (ADR-0144) and had no deadline at all —
it only read as though it did. Do not re-add the citation without adding the rule: the governance
half is tracked in #2392, which covers the whole class of CI gates with no rules.yaml entry.

stdlib + PyYAML (already installed for check-governance-lineage.py / check-slo-registry.py).
Usage: check-authz-pdp-parity.py [--root .] [--enforce]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

IMAGE_SVC = re.compile(r"openbank-([a-z0-9-]+-service)\b")
WORKLOAD_KINDS = {"Deployment", "Rollout"}

# `@Authorize` as an annotation use-site: the identifier must not be part of a longer word
# (`@AuthorizeSomething`), and an import line is not a use.
AUTHORIZE_USE = re.compile(r"@Authorize\b(?!\s*\w*\s*=)")


def strip_kotlin_comments(src: str) -> str:
    """Remove Kotlin line and block comments, leaving string literals intact.

    Kotlin block comments NEST — `/* /* */ */` is ONE comment — so a naive scan to the first
    `*/` closes early and leaks the tail of a KDoc back into the matched text. Raw (`\"\"\"`)
    and normal string literals are preserved so a literal containing `//` or `/*` cannot
    swallow real code.
    """
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        if depth:
            if src.startswith("/*", i):
                depth += 1
                i += 2
            elif src.startswith("*/", i):
                depth -= 1
                i += 2
            else:
                # Keep newlines so line numbers and blank-line structure survive.
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append(src[i:j])
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def has_authorize_annotation(root: pathlib.Path, service_dir_name: str) -> bool:
    """True iff the module's src/main Kotlin carries at least one real `@Authorize` use.

    Comments are stripped first (see strip_kotlin_comments): this file's own KDoc, and the
    KDoc of any resource explaining the annotation, must not read as an annotation.
    """
    src_main = root / service_dir_name / "src" / "main"
    if not src_main.exists():
        return False
    for kt in src_main.rglob("*.kt"):
        try:
            text = kt.read_text(encoding="utf-8")
        except OSError:
            continue
        if "@Authorize" not in text:  # cheap pre-filter; the stripper is the authority
            continue
        if AUTHORIZE_USE.search(strip_kotlin_comments(text)):
            return True
    return False


def app_enforce_default(root: pathlib.Path, service_dir_name: str) -> bool | None:
    """The service's application.yaml `authz.enforce` default, resolving the `${AUTHZ_ENFORCE:x}`
    idiom. Returns True/False, or None if the service module has no application.yaml at all."""
    app_yaml = root / service_dir_name / "src" / "main" / "resources" / "application.yaml"
    if not app_yaml.exists():
        return None
    try:
        data = yaml.safe_load(app_yaml.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError:
        return None
    enforce = (((data.get("authz") or {}) if isinstance(data.get("authz"), dict) else {}).get("enforce"))
    if enforce is None:
        return True  # no authz block ⇒ libs default is true
    text = str(enforce)
    # `${AUTHZ_ENFORCE:false}` ⇒ default false; `${AUTHZ_ENFORCE:true}` or a bare true ⇒ true.
    m = re.search(r"\$\{AUTHZ_ENFORCE:(true|false)\}", text)
    if m:
        return m.group(1) == "true"
    return text.strip().lower() != "false"


def env_value(container: dict, name: str) -> str | None:
    for env in container.get("env") or []:
        if env.get("name") == name:
            v = env.get("value")
            return None if v is None else str(v)
    return None


def iter_workloads(components: pathlib.Path):
    for path in sorted(components.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".", help="repo root (positional, default '.')")
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    components = root / "openbank-infra" / "gitops" / "components"
    if not components.exists():
        print(f"check-authz-pdp-parity: no components dir at {components} — nothing to check")
        return 0

    violations: list[tuple[str, str, str]] = []
    checked = 0
    unannotated: list[str] = []

    for path, workload in iter_workloads(components):
        containers = pod_containers(workload)
        if not containers:
            continue
        # The app container is the one running an openbank-<svc>-service image.
        app = next((c for c in containers if IMAGE_SVC.search(str(c.get("image", "")))), None)
        if app is None:
            continue
        svc_full = IMAGE_SVC.search(str(app.get("image", ""))).group(1)  # e.g. "kyc-service"
        service_dir = f"openbank-{svc_full}"
        checked += 1

        has_pdp = any(c.get("name") == "opa" for c in containers)
        env_enforce = env_value(app, "AUTHZ_ENFORCE")
        if env_enforce is not None:
            enforces = env_enforce.strip().lower() == "true"
        else:
            default = app_enforce_default(root, service_dir)
            enforces = bool(default)  # None (no app.yaml) ⇒ treat as not-enforcing, can't assert

        # A service with no `@Authorize` has nothing that can fail closed, so a missing PDP
        # sidecar is not a defect there — it is the correct absence (#2228).
        annotated = has_authorize_annotation(root, service_dir)
        if not annotated:
            unannotated.append(svc_full)
            continue

        if enforces and not has_pdp:
            rel = path.relative_to(root)
            violations.append((svc_full, str(rel), "env" if env_enforce is not None else "app-default"))

    for svc, rel, source in sorted(violations):
        level = "error" if args.enforce else "warning"
        print(
            f"::{level} file={rel}::authz-pdp-parity: {svc} enforces authz ({source}) but its workload "
            f"declares no `opa` PDP sidecar — every @Authorize endpoint fails closed (HTTP 503/422, "
            f"issue #1797). Deploy the sidecar (see components/party/party-service.yaml) or set "
            f"AUTHZ_ENFORCE=false until it is wired."
        )

    # Print the skipped set, so "0 violations" is never mistaken for "everything was examined" —
    # the reader can see exactly which workloads the annotation predicate took out of scope.
    print(
        f"check-authz-pdp-parity: {checked} workload(s) checked; "
        f"{len(unannotated)} skipped (no @Authorize in src/main: "
        f"{', '.join(sorted(set(unannotated))) or 'none'}); "
        f"{len(violations)} enforce-without-PDP violation(s)."
    )
    return 1 if (violations and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
