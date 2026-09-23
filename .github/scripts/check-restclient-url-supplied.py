#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A REST-client URL whose only fallback is localhost must be supplied by the deployment.

WHAT HAPPENS

`application.yaml` writes `quarkus.rest-client.<key>.url: ${SOME_URL:http://localhost:NNNN}`. That
fallback is right for a laptop and wrong in every pod: if the service's gitops manifest never sets
`SOME_URL`, the deployed client calls its own pod on localhost, gets `Connection refused`, and the
caller's error handling decides what that looks like. For aml-service's party-resolution sweep it
looked like nothing at all: the lookup returned null, the sweep logged a warning per case and left
the case unresolved, forever. The pod stayed Ready, and no test could see it because tests do not
read gitops.

THE CHECK

For every `openbank-*` service with an `application.yaml`, every top-level (profile-less)
`quarkus.rest-client.<key>.url` of the form `${ENV:http://localhost...}` or `${ENV:http://127.0.0.1...}`
needs `- name: ENV` in the env of the service's own container in gitops (the manifest whose image is
`openbank-<svc>:`). A service that has no gitops manifest is not deployed and is skipped. Existing
gaps are baselined in `restclient-url-supplied-baseline.txt`; a NEW gap fails, and a baseline line
that no longer matches a finding fails too (debt paid: delete the line).
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
BASELINE = pathlib.Path(__file__).with_name("restclient-url-supplied-baseline.txt")
LOCAL_FALLBACK = re.compile(r"^\$\{([A-Z0-9_]+):https?://(localhost|127\.0\.0\.1)[:/]")


def _clients(doc) -> dict:
    return ((doc or {}).get("quarkus") or {}).get("rest-client") or {}


def local_url_envs(app_yaml: pathlib.Path) -> dict[str, str]:
    """rest-client key -> env var name, for URLs whose fallback is localhost.

    A `%prod` profile that sets that client's url itself supplies it, so it is not a finding.
    """
    doc = yaml.safe_load(app_yaml.read_text(encoding="utf-8")) or {}
    prod = _clients(doc.get("%prod"))
    out: dict[str, str] = {}
    for key, cfg in _clients(doc).items():
        if not isinstance(cfg, dict):
            continue
        if isinstance(prod.get(key), dict) and prod[key].get("url"):
            continue
        m = LOCAL_FALLBACK.match(str(cfg.get("url", "")))
        if m:
            out[str(key)] = m.group(1)
    return out


def direct_env(key: str) -> str:
    """The env var SmallRye maps straight onto quarkus.rest-client.<key>.url."""
    return "QUARKUS_REST_CLIENT_" + re.sub(r"[^A-Za-z0-9]", "_", key).upper() + "_URL"


def container_envs(gitops: pathlib.Path, svc: str) -> set[str] | None:
    """Env names on the container running openbank-<svc>, or None if no manifest deploys it."""
    found: set[str] | None = None
    for f in gitops.glob("components/**/*.yaml"):
        text = f.read_text(encoding="utf-8", errors="ignore")
        if f"/{svc}:" not in text:
            continue
        for doc in yaml.safe_load_all(text):
            for c in _containers(doc):
                if f"/{svc}:" in str(c.get("image", "")):
                    found = (found or set()) | {e.get("name") for e in c.get("env") or [] if isinstance(e, dict)}
    return found


def _containers(doc):
    if not isinstance(doc, dict):
        return []
    spec = ((doc.get("spec") or {}).get("template") or {}).get("spec") or {}
    return [c for c in spec.get("containers") or [] if isinstance(c, dict)]


EXAMINED = [0]


def scan(root: pathlib.Path) -> list[str]:
    findings = []
    for app in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        svc = app.parts[len(root.parts)]
        wanted = local_url_envs(app)
        if not wanted:
            continue
        envs = container_envs(root / "openbank-infra/gitops", svc)
        if envs is None:
            continue
        for key, env in sorted(wanted.items()):
            EXAMINED[0] += 1
            if env not in envs and direct_env(key) not in envs:
                findings.append(f"{svc}: quarkus.rest-client.{key}.url falls back to localhost and gitops never sets {env}")
    return findings


def self_test() -> int:
    import tempfile

    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        res = root / "openbank-demo-service/src/main/resources"
        res.mkdir(parents=True)
        (res / "application.yaml").write_text(
            "quarkus:\n  rest-client:\n    a:\n      url: ${A_URL:http://localhost:1}\n"
            "    b:\n      url: ${B_URL:http://b.ns.svc:1}\n",
        )
        comp = root / "openbank-infra/gitops/components/demo"
        comp.mkdir(parents=True)
        manifest = comp / "demo.yaml"
        dep = (
            "kind: Deployment\nspec:\n  template:\n    spec:\n      containers:\n"
            "        - name: demo\n          image: reg/openbank-demo-service:x\n          env:\n{env}"
        )
        manifest.write_text(dep.format(env="            - name: OTHER\n              value: x\n"))
        got = scan(root)
        ok &= got == ["openbank-demo-service: quarkus.rest-client.a.url falls back to localhost and gitops never sets A_URL"]
        print(f"  [{'ok' if ok else 'FAIL'}] unset env with a localhost fallback is flagged; an in-cluster fallback is not")
        manifest.write_text(dep.format(env="            - name: A_URL\n              value: http://a\n"))
        good = scan(root) == []
        ok &= good
        print(f"  [{'ok' if good else 'FAIL'}] the env set on the service's own container clears it")
        manifest.unlink()
        gone = scan(root) == []
        ok &= gone
        print(f"  [{'ok' if gone else 'FAIL'}] a service with no gitops manifest is not deployed and is skipped")
    print("self-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings = scan(REPO)
    baseline = {ln.strip() for ln in BASELINE.read_text().splitlines() if ln.strip() and not ln.startswith("#")}
    new = [f for f in findings if f not in baseline]
    paid = sorted(baseline - set(findings))
    print(f"SUBJECTS={EXAMINED[0]}  # localhost-fallback rest-client URLs of deployed services examined")
    print(f"{len(findings)} not supplied by gitops ({len(findings) - len(new)} baselined)")
    for f in new:
        print(f"::error::{f}")
    for p in paid:
        print(f"::error::baseline line no longer matches a finding (debt paid - delete it): {p}")
    return 1 if new or paid else 0


if __name__ == "__main__":
    sys.exit(main())
