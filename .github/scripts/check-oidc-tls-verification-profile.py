#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: TLS certificate verification may be switched off ONLY in the %dev / %test profiles.
#
# WHY THIS EXISTS
#   `quarkus.oidc.tls.verification: none` (and the `oidc-client` twin) turns off certificate AND
#   hostname validation for the Keycloak leg. Written at the top level of application.yaml it
#   applies to every profile, %prod included. Measured 2026-09-25: 53 services carried the
#   literal at top level, 8 more the oidc-client twin, and 5 spelled it
#   `${OIDC_TLS_VERIFICATION:none}` — an env knob whose DEFAULT is the insecure value, which no
#   gitops manifest overrides. It was inert only because the in-cluster Keycloak URL is plain
#   http; the day any environment moves that leg to https, validation is silently absent, and a
#   service would accept a token-endpoint / JWKS answer from anyone able to intercept it.
#
#   It spread by copy: every new service starts from an existing application.yaml. The shared
#   default in openbank-libs (microprofile-config.properties) already said the right thing —
#   `required`, with `%dev` relaxing to `none` — and every service overrode it.
#
# WHAT IS CHECKED (three layers, because each can override the one before)
#   1. openbank-*/src/main/resources/application*.yaml — any key path ending `tls.verification`
#      (or `tls-verification`) that resolves to `none` outside a profile made only of dev/test.
#      A `${VAR:default}` value is judged by its default: that is what runs when nobody sets VAR.
#   2. openbank-*/src/main/resources/**/*.properties — the same, for un-profiled lines.
#   3. openbank-infra/gitops — a container env var named `*TLS_VERIFICATION` whose value is `none`
#      (the deployed override of 1 and 2; a gate over application.yaml alone cannot see it).
#
# Run:  python3 .github/scripts/check-oidc-tls-verification-profile.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

import yaml

import gatelib

RELAXED_PROFILES = {"dev", "test"}
KEY_RE = re.compile(r"(?:^|\.)tls[.-]verification$")
ENV_DEFAULT_RE = re.compile(r"^\$\{[^:}]+:(.*)\}$")
PROP_RE = re.compile(r"^\s*([^#!=:\s][^=:\s]*)\s*[=:]\s*(.*?)\s*$")
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet", "Job", "CronJob", "DaemonSet"}


def resolved(value) -> str:
    """The value that runs when no env var is set: `${A:${B:none}}` -> `none`."""
    v = str(value).strip()
    while (m := ENV_DEFAULT_RE.match(v)):
        v = m.group(1).strip()
    return v.lower()


def relaxed(top_key: str) -> bool:
    """True for a `%dev` / `%test` / `%dev,test` profile key — never for `%prod` or a bare key."""
    if not top_key.startswith("%"):
        return False
    return {p.strip() for p in top_key[1:].split(",")} <= RELAXED_PROFILES


def walk(node, path):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v, path + [str(k)])
    else:
        yield path, node


def yaml_findings(path: pathlib.Path, rel: str):
    doc = gatelib.load_yaml(path)
    if not isinstance(doc, dict):
        return
    for top, sub in doc.items():
        if relaxed(str(top)):
            continue
        for keys, value in walk(sub, [str(top)]):
            dotted = ".".join(keys)
            if KEY_RE.search(dotted) and resolved(value) == "none":
                yield f"{rel}: `{dotted}: {value}` disables TLS verification outside %dev/%test"


def properties_findings(path: pathlib.Path, rel: str):
    for n, line in enumerate(gatelib.read_text(path).splitlines(), 1):
        m = PROP_RE.match(line)
        if not m:
            continue
        key, value = m.groups()
        profile = key[: key.index(".")] if key.startswith("%") and "." in key else ""
        if profile and relaxed(profile):
            continue
        if KEY_RE.search(key) and resolved(value) == "none":
            yield f"{rel}:{n}: `{key}={value}` disables TLS verification outside %dev/%test"


def containers(spec: dict):
    # Deployment/Rollout/StatefulSet/Job/DaemonSet: spec.template.spec; CronJob adds jobTemplate.spec
    tmpl = (spec.get("jobTemplate") or {}).get("spec") or spec
    pod = ((tmpl.get("template") or {}).get("spec")) or {}
    return list(pod.get("containers") or []) + list(pod.get("initContainers") or [])


def gitops_findings(root: pathlib.Path):
    count = 0
    findings = []
    base = root / "openbank-infra/gitops"
    for p in gatelib.rglob(base, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(p)
        except yaml.YAMLError:
            continue
        count += 1
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") not in WORKLOAD_KINDS:
                continue
            for c in containers(d.get("spec") or {}):
                for e in c.get("env") or []:
                    name = str((e or {}).get("name", ""))
                    if name.endswith("TLS_VERIFICATION") and resolved((e or {}).get("value", "")) == "none":
                        findings.append(f"{p.relative_to(root)}: env {name}=none on "
                                        f"{(d.get('metadata') or {}).get('name')} disables TLS verification")
    return count, findings


def evaluate(root: pathlib.Path):
    subjects = 0
    findings: list[str] = []
    for p in sorted(root.glob("openbank-*/src/main/resources/application*.yaml")):
        subjects += 1
        findings += yaml_findings(p, str(p.relative_to(root)))
    for p in sorted(root.glob("openbank-*/src/main/resources/**/*.properties")):
        subjects += 1
        findings += properties_findings(p, str(p.relative_to(root)))
    n, f = gitops_findings(root)
    return subjects + n, findings + f


def self_test() -> int:
    import tempfile

    fails: list[str] = []
    cases = {
        # name: (application.yaml text, must_flag)
        "toplevel": ("quarkus:\n  oidc:\n    tls:\n      verification: none\n", True),
        "client": ("quarkus:\n  oidc-client:\n    tls:\n      verification: none\n", True),
        "envdefault": ("quarkus:\n  oidc:\n    tls:\n      verification: ${OIDC_TLS_VERIFICATION:none}\n", True),
        "nested-env": ("quarkus:\n  oidc:\n    tls:\n      verification: ${A:${B:none}}\n", True),
        "prod": ('"%prod":\n  quarkus:\n    oidc:\n      tls:\n        verification: none\n', True),
        "devprod": ('"%dev,prod":\n  quarkus:\n    oidc:\n      tls:\n        verification: none\n', True),
        "flat": ("quarkus.oidc.tls.verification: none\n", True),
        "upper": ("quarkus:\n  oidc:\n    tls:\n      verification: NONE\n", True),
        "dev": ('"%dev":\n  quarkus:\n    oidc:\n      tls:\n        verification: none\n', False),
        "test": ('"%test":\n  quarkus:\n    oidc-client:\n      tls:\n        verification: none\n', False),
        "devtest": ('"%dev,test":\n  quarkus:\n    oidc:\n      tls:\n        verification: none\n', False),
        "required": ("quarkus:\n  oidc:\n    tls:\n      verification: ${OIDC_TLS_VERIFICATION:required}\n", False),
        "unrelated": ("quarkus:\n  hibernate-orm:\n    verification: none\n", False),
    }
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        for name, (text, _) in cases.items():
            res = root / f"openbank-{name}-service/src/main/resources"
            res.mkdir(parents=True)
            (res / "application.yaml").write_text(text)
        props = root / "openbank-libs/src/main/resources/META-INF"
        props.mkdir(parents=True)
        (props / "microprofile-config.properties").write_text(
            "quarkus.oidc.tls.verification=required\n%dev.quarkus.oidc.tls.verification=none\n")
        badprops = root / "openbank-badprops/src/main/resources"
        badprops.mkdir(parents=True)
        (badprops / "application.properties").write_text("quarkus.oidc-client.tls.verification = none\n")
        gitops = root / "openbank-infra/gitops/components"
        gitops.mkdir(parents=True)
        wl = ("kind: {kind}\nmetadata:\n  name: {n}\nspec:\n  template:\n    spec:\n      containers:\n"
              "        - name: app\n          env:\n            - name: {e}\n              value: {v}\n")
        (gitops / "bad.yaml").write_text(wl.format(kind="Rollout", n="bad-svc", e="OIDC_TLS_VERIFICATION", v="none"))
        (gitops / "good.yaml").write_text(wl.format(kind="Deployment", n="good-svc", e="OIDC_TLS_VERIFICATION", v="required"))

        subjects, findings = evaluate(root)
        text = "\n".join(findings)
        for name, (_, must) in cases.items():
            hit = f"openbank-{name}-service/" in text
            if hit != must:
                fails.append(f"{name}: expected finding={must}, got {hit}")
        if "openbank-libs/" in text:
            fails.append("a %dev-profiled properties line must not be flagged")
        if "openbank-badprops/" not in text:
            fails.append("an un-profiled properties line with none must be flagged")
        if "bad-svc" not in text:
            fails.append("a gitops env TLS_VERIFICATION=none must be flagged")
        if "good-svc" in text:
            fails.append("a gitops env TLS_VERIFICATION=required must not be flagged")
        want = len(cases) + 2 + 2
        if subjects != want:
            fails.append(f"subject count {subjects}, expected {want}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: oidc-tls-verification-profile is falsifiable ({len(cases) + 4} cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    subjects, findings = evaluate(root)
    gatelib.subjects(subjects, "application yaml/properties + gitops manifests")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"oidc-tls-verification-profile: {len(findings)} finding(s). Put `verification: none` "
              f'under "%dev": (or "%test": where a test needs it), never at the top level or %prod.')
        return 1
    print("oidc-tls-verification-profile: TLS verification is relaxed only in %dev/%test.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
