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

import gatelib

# The app container's module, taken from its image. This used to require a literal `-service`
# suffix (`openbank-([a-z0-9-]+-service)`), which is a NAMING CONVENTION standing in for the set
# of deployed modules — and five modules do not follow it. Three of those are money-path
# (openbank-sepa-payment, openbank-domestic-payment, openbank-sepa-instant, plus
# openbank-customer-edge and openbank-product-catalog), and every one carries @Authorize in
# src/main. A workload the regex did not match hit `continue`, so it was neither checked NOR
# listed as skipped: measured on origin/main, deleting the whole `opa` sidecar from the
# openbank-sepa-payment Rollout left the gate printing "40 workload(s) checked … 0
# enforce-without-PDP violation(s)" and exiting 0 — the exact #1797 defect, on a money-path
# service, invisible. The set is now DERIVED: any `openbank-<name>` image whose `<name>` is a
# real module directory in this repo. An unmatched workload is reported, never dropped.
IMAGE_SVC = re.compile(r"openbank-([a-z0-9][a-z0-9-]*)\b")

# An `openbank-*` image that is deliberately NOT a module in this repo. Declared with a reason,
# and stale in BOTH directions: an entry no longer seen in any workload is an error, so this
# cannot quietly become the place unmatched workloads go to die.
NON_MODULE_IMAGES = {
    "openbank-keycloak": "our own Keycloak build (upstream image + realm), not a Quarkus module; "
                         "it has no src/main and therefore no @Authorize surface",
}
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
    for kt in gatelib.rglob(src_main, "*.kt"):
        try:
            text = gatelib.read_text(kt)
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
        data = gatelib.load_yaml(app_yaml) or {}
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


def self_test() -> int:
    """Falsify the annotation reader, the enforce-default resolver and the workload walker.

    What this prevents: a service whose code calls @Authorize while its deployed pod has no
    OPA sidecar. The interceptor then FAILS CLOSED — every authorized call 403s — and the
    failure is total but reads as an authorization bug rather than a missing container. The
    reverse gap is quieter still: enforce defaulting to false means the interceptor decides
    nothing, so a service can look protected in code and be open in production.

    Every branch below has a way of being wrong that yields the SAFE-LOOKING answer, which is
    why the fixtures assert both directions of each.
    """
    import tempfile

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)

        def svc(name, kt=None, app=None):
            d = root / name / "src" / "main"
            (d / "kotlin").mkdir(parents=True, exist_ok=True)
            (d / "resources").mkdir(parents=True, exist_ok=True)
            if kt is not None:
                (d / "kotlin" / "R.kt").write_text(kt)
            if app is not None:
                (d / "resources" / "application.yaml").write_text(app)

        # --- @Authorize detection ---------------------------------------------------------
        svc("openbank-a", kt='@Authorize\nfun a() {}\n')
        case("a real @Authorize is found", has_authorize_annotation(root, "openbank-a"), True)

        # PROSE. The KDoc explaining the annotation must not read as a use — every service
        # that adopts it carries such a comment, so a stripper failure marks the whole fleet
        # as annotated and the gate then demands a sidecar everywhere.
        svc("openbank-b", kt='/** Uses @Authorize on write paths. */\nfun b() {}\n')
        case("@Authorize in a KDoc is not a use", has_authorize_annotation(root, "openbank-b"), False)
        svc("openbank-c", kt='// @Authorize\nfun c() {}\n')
        case("@Authorize in a line comment is not a use", has_authorize_annotation(root, "openbank-c"), False)

        # A module with no sources at all is not annotated — and must not raise.
        case("a module with no src/main is not annotated",
             has_authorize_annotation(root, "openbank-missing"), False)

        # --- enforce default --------------------------------------------------------------
        # NO authz block means the libs default applies, which is TRUE. Reading that as false
        # would silently excuse every service from needing a sidecar.
        svc("openbank-d", kt="fun d() {}\n", app="quarkus:\n  http:\n    port: 8080\n")
        case("no authz block defaults to enforce=true", app_enforce_default(root, "openbank-d"), True)
        svc("openbank-e", kt="fun e() {}\n", app="authz:\n  enforce: false\n")
        case("an explicit false is false", app_enforce_default(root, "openbank-e"), False)
        svc("openbank-f", kt="fun f() {}\n", app='authz:\n  enforce: "${AUTHZ_ENFORCE:true}"\n')
        case("the env-default idiom resolves to its default (true)",
             app_enforce_default(root, "openbank-f"), True)
        svc("openbank-g", kt="fun g() {}\n", app='authz:\n  enforce: "${AUTHZ_ENFORCE:false}"\n')
        case("the env-default idiom resolves to its default (false)",
             app_enforce_default(root, "openbank-g"), False)
        # No application.yaml at all is UNKNOWN, not false — a missing file must not be
        # reported as a deliberate opt-out.
        case("a module with no application.yaml is unknown",
             app_enforce_default(root, "openbank-missing"), None)

    # --- workload walking ------------------------------------------------------------------
    dep = {"kind": "Deployment", "spec": {"template": {"spec": {"containers": [
        {"name": "app", "env": [{"name": "AUTHZ_ENFORCE", "value": "true"}]},
        {"name": "opa"},
    ]}}}}
    names = [c["name"] for c in pod_containers(dep)]
    case("both containers are found", names, ["app", "opa"])
    case("an env value is read", env_value(pod_containers(dep)[0], "AUTHZ_ENFORCE"), "true")
    # A NAME with no value (valueFrom) is not a literal value — returning "" or the name would
    # make a secret-sourced flag look like a literal "false"/"true".
    case("a valueFrom env has no literal value",
         env_value({"env": [{"name": "X", "valueFrom": {"secretKeyRef": {"name": "s", "key": "k"}}}]}, "X"), None)
    case("an absent env is None", env_value({"env": []}, "X"), None)
    # A non-workload doc must yield no containers rather than raising.
    case("a doc with no template yields no containers", pod_containers({"kind": "Service"}), [])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: authz PDP parity is falsifiable (14 cases)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".", help="repo root (positional, default '.')")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    components = root / "openbank-infra" / "gitops" / "components"
    if not components.exists():
        print(f"check-authz-pdp-parity: no components dir at {components} — nothing to check")
        return 0

    violations: list[tuple[str, str, str]] = []
    checked = 0
    unannotated: list[str] = []
    unresolved: list[tuple[str, str]] = []
    seen_non_module: set[str] = set()

    for path, workload in iter_workloads(components):
        containers = pod_containers(workload)
        if not containers:
            continue
        # The app container is the one whose image names a module directory that exists here.
        app, svc_full = None, None
        for c in containers:
            m = IMAGE_SVC.search(str(c.get("image", "")))
            if m and (root / f"openbank-{m.group(1)}").is_dir():
                app, svc_full = c, m.group(1)
                break
        if app is None:
            # Not a repo-module workload (an upstream image, a sidecar-only pod). Record any
            # `openbank-*` image we could not resolve, so a renamed or new module surfaces as an
            # UNRESOLVED line rather than vanishing the way the five above did.
            for c in containers:
                m = IMAGE_SVC.search(str(c.get("image", "")))
                if m:
                    image = f"openbank-{m.group(1)}"
                    if image in NON_MODULE_IMAGES:
                        seen_non_module.add(image)
                    else:
                        unresolved.append((image, str(path.relative_to(root))))
            continue
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
    # An `openbank-*` image with no module directory is a THIRD state next to checked and
    # skipped. It is not silently dropped, because that is exactly how five modules — three of
    # them money-path — stayed outside this gate while it printed a clean count.
    # The staleness half only means anything over the REAL tree. `--root` is also pointed at
    # synthetic fixture trees by check_authz_pdp_parity_test.py, where every declared entry is
    # legitimately absent; reporting it there turns the falsification suite red about nothing.
    # `openbank-libs` is the marker: no fixture builds one.
    real_tree = (root / "openbank-libs").is_dir()
    stale_declared = sorted(set(NON_MODULE_IMAGES) - seen_non_module) if real_tree else []
    for image in stale_declared:
        print(
            f"::{'error' if args.enforce else 'warning'}::authz-pdp-parity: NON_MODULE_IMAGES "
            f"declares `{image}` ({NON_MODULE_IMAGES[image]}) but no workload runs it any more — "
            f"remove the entry, or the exclusion outlives the thing it excused."
        )

    for image, rel in sorted(set(unresolved)):
        print(
            f"::{'error' if args.enforce else 'warning'} file={rel}::authz-pdp-parity: workload runs image `{image}` but no such "
            f"module directory exists in this repo, so its @Authorize surface cannot be read. "
            f"Rename the module or the image so the two agree."
        )

    print(
        f"check-authz-pdp-parity: {checked} workload(s) checked; "
        f"{len(unannotated)} skipped (no @Authorize in src/main: "
        f"{', '.join(sorted(set(unannotated))) or 'none'}); "
        f"{len(set(unresolved))} unresolved image(s); "
        f"{len(stale_declared)} stale NON_MODULE_IMAGES entr(ies); "
        f"{len(violations)} enforce-without-PDP violation(s)."
    )
    return 1 if ((violations or unresolved or stale_declared) and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
