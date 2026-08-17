#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: a service that PRESENTS an OIDC token must be able to mint one IN THE CLUSTER.
#
# WHY THIS EXISTS
#   An outbound REST client annotated `@RegisterProvider(OidcClientRequestReactiveFilter::class)`
#   attaches a bearer token minted by the DEFAULT OidcClient — `quarkus.oidc-client.*`. That is a
#   different extension from `quarkus.oidc.*`, which is the inbound resource-server side that
#   VALIDATES tokens arriving here. The two blocks carry the same three field names
#   (auth-server-url, client-id, credentials.secret) and differ by one hyphen.
#
#   With no `quarkus.oidc-client`, nothing errors at build time and nothing errors at boot. The
#   filter has no client to mint from, the request leaves unauthenticated, and the callee answers
#   401. The status code is the tell and is easy to misread: 401 means no token AT ALL, where a
#   role problem would be 403 — so the obvious hypothesis ("the service account is missing a
#   role") sends you into Keycloak, which is the wrong place entirely.
#
#   Measured 2026-08-16: 32 modules registered the filter and 27 configured the client.
#   openbank-ledger-service's daily FX revaluation had failed `Received: 'Unauthorized, status
#   code 401'` on every run it ever made — `openbank_workflow_success_recorded{workflow=
#   "ledger-fx-revaluation"} = 0` — and the only thing that noticed was FxFixingAgeAbsent, an
#   `absent()` alert on a gauge the run never got far enough to register.
#   openbank-settlement-service, money-path, had neither block at all.
#
# THE INVARIANT IS ABOUT THE DEPLOYED VALUE, NOT THE SPELLING
#   Every service defaults auth-server-url to localhost so local dev works. In a pod, localhost
#   is the pod itself (`quarkus.http.port` is 8080), so an unsubstituted default does not fail
#   loudly either — it POSTs the token request to this very service, gets a 404, and the outbound
#   call goes out bare. Identical symptom to the missing block.
#
#   So the check is: whatever env vars the value interpolates must actually be set on this
#   module's deployed workload. The fleet spells that three different ways and all three are
#   correct — QUARKUS_OIDC_AUTH_SERVER_URL (26 services, reusing the inbound URL), KEYCLOAK_URL
#   (campaign), and a literal + QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL override (balance-service).
#   Checking for any ONE of those names would have failed 16 correct services; the first draft of
#   this gate did exactly that, and only a live `kubectl get deploy` disproved it. Resolving the
#   interpolation instead needs no list and admits any future spelling.
#
#   Nothing else could catch this. A unit test mocks the port; a @QuarkusTest supplies its own
#   security context; a consumer pact's mock server answers whatever is asked and never inspects
#   the Authorization header. The defect exists only between a real client and a real provider.
#
# Run:  python3 .github/scripts/check-oidc-client-configured.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

import yaml

import gatelib

FILTER_RE = re.compile(r"@RegisterProvider\s*\(\s*OidcClientRequest(?:Reactive)?Filter::class")
# `  oidc-client:` at quarkus-child depth, then its auth-server-url, ignoring deeper occurrences
# (`quarkus.rest-client.<x>.oidc-client.enabled`, and the `%test` profile's disable block).
OIDC_CLIENT_URL_RE = re.compile(r"^  oidc-client:\s*$\n(?:^ {4}.*$\n)*?^ {4}auth-server-url:\s*(.+)$", re.M)
OIDC_CLIENT_KEY_RE = re.compile(r"^  oidc-client:\s*$", re.M)
ENV_REF_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)")
OVERRIDE_ENV = "QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL"
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet"}

# Library modules ship the filter for consumers to register; they have no application.yaml and no
# workload of their own. Derived from the name rather than enumerated per module.
LIBRARY_PREFIXES = ("openbank-libs",)


def modules_registering_filter(root: pathlib.Path):
    """Modules with at least one @RegisterProvider(OidcClientRequest*Filter) in main sources."""
    found = {}
    for f in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        if not FILTER_RE.search(f.read_text()):
            continue
        module = f.relative_to(root).parts[0]
        if module.startswith(LIBRARY_PREFIXES):
            continue
        found.setdefault(module, []).append(str(f.relative_to(root)))
    return found


def oidc_client_url(root: pathlib.Path, module: str):
    """(configured?, raw auth-server-url or None) for this module's default OidcClient."""
    p = root / module / "src/main/resources/application.yaml"
    if not p.is_file():
        return False, None
    text = p.read_text()
    if not OIDC_CLIENT_KEY_RE.search(text):
        return False, None
    m = OIDC_CLIENT_URL_RE.search(text)
    return True, (m.group(1).strip() if m else None)


def workload_env(root: pathlib.Path):
    """{workload name: set of env var names it sets}, over every gitops manifest.

    Indexed by workload rather than by file because eleven payments services share one file, so a
    per-file answer would let one service's env vouch for another's.
    """
    index = {}
    for p in sorted((root / "openbank-infra/gitops").rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(p.read_text()))
        except yaml.YAMLError:
            continue
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") not in WORKLOAD_KINDS:
                continue
            name = (d.get("metadata") or {}).get("name")
            spec = ((d.get("spec") or {}).get("template") or {}).get("spec") or {}
            names = {e.get("name") for c in spec.get("containers") or [] for e in c.get("env") or []}
            if name:
                index.setdefault(name, set()).update(names)
    return index


def self_test() -> int:
    import tempfile

    fails: list[str] = []
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        gitops = root / "openbank-infra/gitops/components"
        gitops.mkdir(parents=True)

        def service(name, *, filter_registered=True, url=None, env=(), reactive=True, workload=None):
            kt = root / f"openbank-{name}-service/src/main/kotlin/com/openbank/{name}"
            kt.mkdir(parents=True)
            variant = "OidcClientRequestReactiveFilter" if reactive else "OidcClientRequestFilter"
            (kt / "C.kt").write_text(
                f"@RegisterProvider({variant}::class)\ninterface C\n" if filter_registered else "interface C\n")
            res = root / f"openbank-{name}-service/src/main/resources"
            res.mkdir(parents=True)
            cfg = "quarkus:\n  oidc:\n    auth-server-url: ${INBOUND:http://localhost:8080}\n"
            if url is not None:
                # a deeper `oidc-client` key must NOT be mistaken for the real one
                cfg += (f"  oidc-client:\n    auth-server-url: {url}\n    client-id: openbank-services\n"
                        "  rest-client:\n    x:\n      oidc-client:\n        enabled: false\n")
            (res / "application.yaml").write_text(cfg)
            wl = workload or f"{name}-service"
            envs = "".join(f"            - name: {e}\n              value: v\n" for e in env)
            gitops.joinpath(f"{name}.yaml").write_text(
                "kind: Deployment\nmetadata:\n  name: " + wl +
                "\nspec:\n  template:\n    spec:\n      containers:\n        - name: app\n          env:\n"
                + (envs or "            - name: UNRELATED\n              value: v\n"))

        service("good", url="${KC_URL:http://localhost:8080}/realms/openbank", env=["KC_URL"])
        service("altspelling", url="${OTHER_URL:http://localhost:8080}/realms/x", env=["OTHER_URL"])
        service("override", url="http://localhost:8080/realms/openbank", env=[OVERRIDE_ENV])
        service("noblock", url=None, env=["KC_URL"])                                  # DEFECT 1
        service("bareliteral", url="http://localhost:8080/realms/openbank", env=[])   # DEFECT 2
        service("unsetvar", url="${MISSING:http://localhost:8080}/realms/x", env=[])  # DEFECT 3
        service("blocking", url="${KC_URL:http://localhost:8080}/x", env=["KC_URL"], reactive=False)
        service("nofilter", filter_registered=False, url=None, env=[])
        service("noworkload", url="${KC_URL:http://localhost:8080}/x", env=["KC_URL"], workload="other-name")

        lib = root / "openbank-libs-runtime/src/main/kotlin/com/openbank/libs"
        lib.mkdir(parents=True)
        (lib / "L.kt").write_text("@RegisterProvider(OidcClientRequestReactiveFilter::class)\ninterface L\n")

        mods = modules_registering_filter(root)
        want = {f"openbank-{n}-service" for n in
                ("good", "altspelling", "override", "noblock", "bareliteral", "unsetvar", "blocking", "noworkload")}
        if set(mods) != want:
            fails.append(f"corpus wrong: {sorted(mods)} — libs must be excluded, nofilter out of "
                         f"scope, the non-reactive filter variant in scope")

        # The deeper rest-client `oidc-client:` key must not be picked up as the block's URL.
        ok, url = oidc_client_url(root, "openbank-good-service")
        if not ok or url != "${KC_URL:http://localhost:8080}/realms/openbank":
            fails.append(f"url extraction wrong for good: configured={ok} url={url!r}")
        if oidc_client_url(root, "openbank-noblock-service") != (False, None):
            fails.append("a module with no oidc-client block must read as unconfigured")

        env_index = workload_env(root)
        if env_index.get("good-service") != {"KC_URL"}:
            fails.append(f"workload env index wrong: {env_index.get('good-service')}")

        found = dict(evaluate(root, mods, env_index))
        for m, must_fail in (("good", False), ("altspelling", False), ("override", False),
                             ("blocking", False), ("noblock", True), ("bareliteral", True),
                             ("unsetvar", True), ("noworkload", True)):
            key = f"openbank-{m}-service"
            if (key in found) != must_fail:
                fails.append(f"{key}: expected finding={must_fail}, got {found.get(key, 'none')!r}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: oidc-client-configured is falsifiable (15 cases)")
    return 0


def evaluate(root: pathlib.Path, modules, env_index):
    """Yield (module, finding) for every module that cannot mint a token in the cluster."""
    for module, sites in sorted(modules.items()):
        where = ", ".join(sites[:2]) + (", …" if len(sites) > 2 else "")
        configured, url = oidc_client_url(root, module)
        if not configured:
            yield module, (
                f"{module} registers OidcClientRequest*Filter ({where}) but its application.yaml "
                f"declares no `quarkus.oidc-client` block. The filter has no client to mint from, "
                f"so every outbound call leaves WITHOUT a token and the callee answers 401 — not "
                f"403, because there is no token to be missing a role."
            )
            continue

        workload = module.removeprefix("openbank-")
        env = env_index.get(workload)
        if env is None:
            yield module, (
                f"{module} configures `quarkus.oidc-client`, but no gitops workload is named "
                f"`{workload}`, so this guard cannot tell whether the deployed pod resolves the "
                f"auth-server-url — UNCHECKED, not clean. Name the workload after the module or "
                f"teach this script the mapping."
            )
            continue
        if OVERRIDE_ENV in env:
            continue

        refs = set(ENV_REF_RE.findall(url or ""))
        if not refs:
            yield module, (
                f"{module}'s `quarkus.oidc-client.auth-server-url` is the literal {url!r} and its "
                f"workload sets neither an interpolated variable nor {OVERRIDE_ENV}. In a pod "
                f"localhost:8080 is the service ITSELF, so the token request 404s and the outbound "
                f"call goes out bare — same 401 as having no block at all."
            )
            continue
        missing = sorted(r for r in refs if r not in env)
        if missing:
            yield module, (
                f"{module}'s `quarkus.oidc-client.auth-server-url` interpolates {', '.join(missing)}, "
                f"which the `{workload}` workload does not set, so it falls back to its localhost "
                f"default — in a pod that is the service itself. Set the variable, or "
                f"{OVERRIDE_ENV}."
            )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    modules = modules_registering_filter(root)
    findings = [f for _, f in evaluate(root, modules, workload_env(root))]

    gatelib.subjects(len(modules), "modules registering an OIDC client filter")

    if findings:
        for f in findings:
            sys.stderr.write(f"::error title=OIDC client configured::{f}\n")
        sys.stderr.write(f"::error::check-oidc-client-configured: {len(findings)} module(s) cannot mint the token they present.\n")
        return 1

    print(f"oidc-client parity: {len(modules)} module(s) register an outbound OIDC filter; every one "
          f"declares quarkus.oidc-client and its deployed workload resolves the auth-server-url "
          f"away from localhost.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
