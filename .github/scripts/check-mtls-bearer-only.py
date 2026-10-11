#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A listener that asks for client certificates must not accept one as a login (#12511).

With `quarkus.http.ssl.client-auth` set to `required` or `request`, Quarkus also registers
its mTLS authentication MECHANISM: any certificate the trust store accepts becomes an
authenticated SecurityIdentity. A workload holding a fleet-CA certificate and no bearer is then
"logged in" on 8443. Treasury measured it (#12506): 403 from authz where 401 was due.

Rule: in every profile where client-auth resolves to required/request, some
`quarkus.http.auth.permission.<name>` resolving in that profile must set
`auth-mechanism: bearer`, and its `paths` must cover the class-level `@Path` root of every
JAX-RS resource class in the module's src/main (REST-client interfaces are not served and do
not count). The certificate then stays transport authentication; the OIDC bearer is the only
identity a route sees. Behaviour is proven per service by a ListenerAuthParityConformance IT.

Baseline: .github/scripts/mtls-bearer-only-baseline.txt (module names, ratchet-only). A listed
module that now passes is reported, so the list can only shrink.

Usage: check-mtls-bearer-only.py [--enforce] [--self-test] [ROOT]
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

import yaml

CLIENT_AUTH = "quarkus.http.ssl.client-auth"
PERMISSION = "quarkus.http.auth.permission"
BASELINE = ".github/scripts/mtls-bearer-only-baseline.txt"


def _flatten(node, prefix=""):
    out = {}
    if isinstance(node, dict):
        for k, v in node.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            out.update(_flatten(v, key))
    else:
        out[prefix] = node
    return out


def profiles(doc) -> dict:
    """profile name (None = default) -> flat config as Quarkus resolves it in that profile."""
    if not isinstance(doc, dict):
        return {}
    base = _flatten({k: v for k, v in doc.items() if not str(k).startswith("%")})
    out = {None: base}
    for k, v in doc.items():
        if isinstance(k, str) and k.startswith("%") and isinstance(v, dict):
            for name in k[1:].split(","):
                merged = dict(out.get(name.strip(), base))
                merged.update(_flatten(v))
                out[name.strip()] = merged
    return out


def _covers(pattern: str, path: str) -> bool:
    pattern = _normalize_pattern(pattern)
    if pattern in ("/*", "*"):
        return True
    if pattern.endswith("/*"):
        stem = pattern[:-2]
        return path == stem or path.startswith(stem + "/")
    return path == pattern


def _normalize_pattern(pattern: str) -> str:
    """Quarkus `/public*` means `/public` and its descendants, like `/public/*`."""
    pattern = pattern.strip()
    if pattern.endswith("*") and pattern not in ("*", "/*") and not pattern.endswith("/*"):
        return pattern[:-1].rstrip("/") + "/*"
    return pattern


def _matches_permission(pattern: str, path: str, *, conservative: bool = False) -> bool:
    """Match one concrete path; unknown wildcard syntax is never trusted for bearer coverage."""
    pattern = _normalize_pattern(pattern)
    # An exact slashless path also matches its slash-suffixed request, but an
    # exact slash-suffixed path does not match the slashless request.
    if "*" not in pattern:
        return pattern == path or (not pattern.endswith("/") and path == pattern + "/")
    parts = pattern.strip("/").split("/")
    actual = path.strip("/").split("/")
    for i, part in enumerate(parts):
        if part == "*" and i == len(parts) - 1:
            return True
        if i >= len(actual):
            return False
        if part == "*" or part == actual[i]:
            continue
        if conservative and "*" in part:
            continue
        return False
    return len(parts) == len(actual)


def bearer_paths(flat: dict) -> list[str]:
    names = {k[len(PERMISSION) + 1:].split(".")[0] for k in flat if k.startswith(PERMISSION + ".")}
    out = []
    for n in sorted(names):
        mech = str(flat.get(f"{PERMISSION}.{n}.auth-mechanism", "")).strip().lower()
        # A GET-only bearer permission does not protect POST/PUT on the same resource root.
        # Class-level @Path inspection cannot prove which methods each resource serves, so
        # only a permission applying to every method can establish root-wide coverage.
        methods = str(flat.get(f"{PERMISSION}.{n}.methods", "")).strip()
        if mech == "bearer" and not methods and str(flat.get(f"{PERMISSION}.{n}.enabled", "true")).lower() != "false":
            out += [p for p in str(flat.get(f"{PERMISSION}.{n}.paths", "")).split(",") if p.strip()]
    return out


def _permission_paths(flat: dict, name: str) -> list[str]:
    return [p.strip() for p in str(flat.get(f"{PERMISSION}.{name}.paths", "")).split(",") if p.strip()]


def _specificity(pattern: str) -> tuple[tuple[int, int], ...]:
    """Order path segments as Quarkus does: an earlier literal beats a wildcard."""
    parts = _normalize_pattern(pattern).strip("/").split("/")
    score = tuple((int(part != "*"), len(part.replace("*", ""))) for part in parts)
    # A trailing wildcard also matches zero segments. For that common request,
    # /api/v1 is more specific than /api/v1/* despite having fewer segments.
    return score if parts[-1] == "*" else score + ((2, int(pattern.endswith("/"))),)


def _overlap_witness(pattern: str, root: str, probe: str) -> str | None:
    """Find a possible served path under root matched by a Quarkus permission pattern.

    A middle `*` occupies one segment; a trailing `*` may consume the rest.
    Unknown partial-segment wildcard syntax remains possible (fail closed).
    """
    pattern = _normalize_pattern(pattern)
    if "*" not in pattern:
        if _matches_permission(pattern, root):
            return root
        return pattern if pattern.startswith(root.rstrip("/") + "/") else None
    parts = pattern.strip("/").split("/")
    root_parts = root.strip("/").split("/")
    candidate = []
    for i, part in enumerate(parts):
        if part == "*" and i == len(parts) - 1:
            candidate.extend(root_parts[i:] or [probe])
            return "/" + "/".join(candidate)
        if i < len(root_parts):
            if part != root_parts[i] and "*" not in part:
                return None
            candidate.append(root_parts[i])
        else:
            candidate.append(probe if "*" in part else part)
    if len(root_parts) > len(parts):
        return None  # An exact or middle-wildcard pattern does not match descendants.
    return "/" + "/".join(candidate)


def overriding_permissions(flat: dict, roots: list[str], bearer: list[str]) -> list[str]:
    """Find a more-specific unshared permission that could defeat broad bearer-only coverage."""
    names = {k[len(PERMISSION) + 1:].split(".")[0] for k in flat if k.startswith(PERMISSION + ".")}
    probe = "listener-auth-probe"
    while any(probe in path for path in bearer):
        probe += "x"
    out = []
    for name in sorted(names):
        key = f"{PERMISSION}.{name}."
        if str(flat.get(key + "enabled", "true")).lower() == "false":
            continue
        if str(flat.get(key + "shared", "false")).lower() == "true":
            continue
        if str(flat.get(key + "auth-mechanism", "")).strip().lower() == "bearer":
            continue
        # An unconditional deny does not authenticate a certificate or reach the API.
        if str(flat.get(key + "policy", "")).strip().lower() == "deny":
            continue
        for pattern in _permission_paths(flat, name):
            methods = str(flat.get(key + "methods", "")).strip()
            for root in roots:
                witness = _overlap_witness(pattern, root, probe)
                if witness is None:
                    continue
                # An exact bearer exception can protect the root while leaving its
                # descendants under the less-specific, certificate-accepting rule.
                candidates = (witness, witness.rstrip("/") + "/" + probe)
                for path in candidates:
                    if not _matches_permission(pattern, path, conservative=True):
                        continue
                    matching = [_specificity(b) for b in bearer if _matches_permission(b, path)]
                    winner = max(matching) if matching else None
                    if (winner is None or _specificity(pattern) > winner or
                            (methods and _specificity(pattern) == winner)):
                        out.append(f"{name} ({pattern})")
                        break
                if f"{name} ({pattern})" in out:
                    break
    return out


_PATH = re.compile(r'^\s*@(?:jakarta\.ws\.rs\.)?Path\(\s*"([^"]*)"\s*\)')
_DECL = re.compile(r"^\s*(?:(?:public|internal|private|protected|open|abstract|data|final|override|suspend|inline|sealed|enum|annotation|operator)\s+)*(class|interface|object|fun|val|var)\b")


def resource_roots(module: str) -> list[str]:
    """Class-level @Path values of served JAX-RS resources (not REST-client interfaces)."""
    roots = []
    for f in glob.glob(os.path.join(module, "src/main/kotlin/**/*.kt"), recursive=True):
        with open(f, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
        for i, line in enumerate(lines):
            m = _PATH.match(line)
            if not m:
                continue
            for nxt in lines[i + 1:]:
                d = _DECL.match(nxt)
                if d:
                    if d.group(1) in ("class", "object"):
                        p = m.group(1)
                        roots.append(p if p.startswith("/") else "/" + p)
                    break
    return sorted(set(roots))


def findings_for(doc, roots: list[str]) -> list[str]:
    out = []
    for name, flat in profiles(doc).items():
        ca = str(flat.get(CLIENT_AUTH, "")).strip().lower()
        if ca not in ("required", "request"):
            continue
        patterns = bearer_paths(flat)
        label = f"profile {name or '(default)'}"
        if not patterns:
            out.append(f"{label}: client-auth={ca} but no unrestricted permission sets auth-mechanism: bearer — "
                       "a trusted client certificate alone authenticates (#12511)")
            continue
        uncovered = [r for r in roots if not any(_covers(p, r) for p in patterns)]
        if uncovered:
            out.append(f"{label}: bearer-only paths {patterns} do not cover resource roots {uncovered}")
        overridden = overriding_permissions(flat, roots, patterns)
        if overridden:
            out.append(f"{label}: more-specific non-bearer permissions can override bearer-only coverage: "
                       f"{overridden}")
    return out


def scan(root: str):
    files = sorted(glob.glob(os.path.join(root, "openbank-*/src/main/resources/application.yaml")))
    result = {}
    for f in files:
        module = f.split(os.sep + "src" + os.sep)[0]
        with open(f, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
        result[os.path.relpath(module, root)] = findings_for(doc, resource_roots(module))
    return files, result


def self_test() -> int:
    vuln = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n')
    request = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: request\n')
    fixed = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n          bearer-only:\n            paths: /api/*\n"
        "            policy: permit\n            auth-mechanism: bearer\n"
    )
    inherited = yaml.safe_load(
        "quarkus:\n  http:\n    auth:\n      permission:\n        b:\n          paths: /api/*,/ap2/*\n"
        "          auth-mechanism: bearer\n"
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
    )
    wrong_mech = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n          x:\n            paths: /api/*\n            auth-mechanism: mtls\n"
    )
    get_only = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n          x:\n            paths: /api/*\n"
        "            methods: GET\n            auth-mechanism: bearer\n"
    )
    overridden = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          narrower:\n            paths: /api/v1/accounts/*\n            policy: authenticated\n"
    )
    unrelated = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          other:\n            paths: /status/*\n            policy: authenticated\n"
    )
    method_override = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          get-only:\n            paths: /api/*\n            methods: GET\n            policy: authenticated\n"
    )
    middle_wildcard = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          admin:\n            paths: /api/*/admin\n            policy: authenticated\n"
    )
    exact_override = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/v1/*\n            auth-mechanism: bearer\n"
        "          exact:\n            paths: /api/v1\n            policy: authenticated\n"
    )
    nested_bearer = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          broad:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          narrow:\n            paths: /api/v1/secure/*\n            auth-mechanism: bearer\n"
        "          middle:\n            paths: /api/v1/*\n            policy: authenticated\n"
    )
    exact_bearer = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          broad:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          exact:\n            paths: /api/v1/secure\n            auth-mechanism: bearer\n"
        "          middle:\n            paths: /api/v1/*\n            policy: authenticated\n"
    )
    trailing_slash_bearer = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          broad:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          slash-only:\n            paths: /api/v1/\n            auth-mechanism: bearer\n"
        "          exact:\n            paths: /api/v1\n            policy: authenticated\n"
    )
    trailing_slash_override = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          broad:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          slashless:\n            paths: /api/v1\n            auth-mechanism: bearer\n"
        "          slash:\n            paths: /api/v1/\n            policy: authenticated\n"
    )
    slashless_override = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          broad:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          exact:\n            paths: /api/v1\n            policy: authenticated\n"
    )
    attached_wildcard = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n'
        "      auth:\n        permission:\n"
        "          bearer-only:\n            paths: /api/*\n            auth-mechanism: bearer\n"
        "          attached:\n            paths: /api/v1*\n            policy: authenticated\n"
    )
    none = yaml.safe_load("quarkus:\n  http:\n    port: 8080\n")
    api = ["/api/v1/sca"]
    checks = [
        ("client-auth required without bearer-only flagged", len(findings_for(vuln, api)) == 1),
        ("client-auth request without bearer-only flagged", len(findings_for(request, api)) == 1),
        ("#12506 pattern passes", findings_for(fixed, api) == []),
        ("bearer-only not covering /ap2 root flagged", len(findings_for(fixed, ["/ap2/verify"])) == 1),
        ("inherited multi-path bearer-only passes", findings_for(inherited, ["/ap2/verify", "/api/v1/x"]) == []),
        ("auth-mechanism other than bearer flagged", len(findings_for(wrong_mech, api)) == 1),
        ("GET-only bearer permission does not protect POST", len(findings_for(get_only, api)) == 1),
        ("more-specific non-bearer path defeats broad bearer-only", len(findings_for(overridden, ["/api/v1/accounts"])) == 1),
        ("method-specific non-bearer path defeats same-path bearer-only",
         len(findings_for(method_override, ["/api/v1/accounts"])) == 1),
        ("middle wildcard shadows a broad bearer permission under a served root",
         len(findings_for(middle_wildcard, ["/api/v1"])) == 1),
        ("middle wildcard on another branch does not overlap a served root",
         findings_for(middle_wildcard, ["/api/v1/accounts"]) == []),
        ("exact non-bearer path wins over a trailing bearer wildcard at zero segments",
         len(findings_for(exact_override, ["/api/v1"])) == 1),
        ("most-specific nested bearer defeats middle non-bearer for root and descendants",
         findings_for(nested_bearer, ["/api/v1/secure"]) == []),
        ("middle non-bearer still wins outside the nested bearer branch",
         len(findings_for(nested_bearer, ["/api/v1/other"])) == 1),
        ("exact bearer exception leaves a descendant exposed to middle non-bearer",
         len(findings_for(exact_bearer, ["/api/v1/secure"])) == 1),
        ("trailing-slash bearer does not protect the slashless exact path",
         len(findings_for(trailing_slash_bearer, ["/api/v1"])) == 1),
        ("trailing-slash non-bearer wins over slashless bearer on slash request",
         len(findings_for(trailing_slash_override, ["/api/v1/"])) == 1),
        ("slashless non-bearer also shadows broad bearer on slash request",
         len(findings_for(slashless_override, ["/api/v1/"])) == 1),
        ("slashless non-bearer does not overlap another root",
         findings_for(slashless_override, ["/api/v2/"]) == []),
        ("exact trailing-slash path does not match the slashless request",
         not _matches_permission("/api/v1/", "/api/v1")),
        ("exact trailing-slash path matches its own request",
         _matches_permission("/api/v1/", "/api/v1/")),
        ("exact slashless path matches its trailing-slash request",
         _matches_permission("/api/v1", "/api/v1/")),
        ("attached trailing wildcard shadows broad bearer on a served descendant",
         len(findings_for(attached_wildcard, ["/api/v1/secure"])) == 1),
        ("non-bearer permission outside served API does not defeat bearer-only",
         findings_for(unrelated, ["/api/v1/accounts"]) == []),
        ("no client-auth passes", findings_for(none, api) == []),
        ("/api/* does not cover /apix", not _covers("/api/*", "/apix")),
    ]
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "src/main/kotlin/x")
        os.makedirs(src)
        with open(os.path.join(src, "R.kt"), "w") as fh:
            fh.write('@Path("/api/v1/r")\n@ApplicationScoped\nclass R {\n  @GET\n  @Path("/{id}")\n  fun get() = 1\n}\n'
                     '@RegisterRestClient\n@Path("/api/v1/remote")\ninterface C\n')
        checks.append(("resource class root found, client interface and method paths ignored",
                       resource_roots(d) == ["/api/v1/r"]))
    import contextlib
    import io
    for bad in ("--selftest", "--enfroce", "--enfor"):
        with contextlib.redirect_stderr(io.StringIO()):
            try:
                parse_args([bad])
            except SystemExit as exc:
                rejected = exc.code == 2
            else:
                rejected = False
        checks.append((f"unknown option {bad} rejected", rejected))
    parsed = parse_args(["--enforce", "fixture-root"])
    checks.append(("enforcement and explicit root preserved", parsed.enforce and parsed.root == "fixture-root"))
    ok = True
    for label, passed in checks:
        print(f"  {'PASS' if passed else 'FAIL'}: {label}")
        ok &= passed
    return 0 if ok else 1


def parse_args(argv: list[str]):
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("root", nargs="?", default=".")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    enforce = args.enforce
    root = args.root
    files, result = scan(root)
    baseline_file = os.path.join(root, BASELINE)
    baseline = set()
    if os.path.exists(baseline_file):
        with open(baseline_file, encoding="utf-8") as fh:
            baseline = {ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")}
    new = {m: f for m, f in result.items() if f and m not in baseline}
    stale = sorted(m for m in baseline if not result.get(m))
    n_flagged = sum(1 for f in result.values() if f)
    print(f"SUBJECTS={len(files)}")
    print(f"mtls-bearer-only: {len(files)} application.yaml subjects, {n_flagged} flagged, "
          f"{len(baseline)} baselined, {len(new)} new, {len(stale)} stale baseline entries")
    for m, fs in sorted(new.items()):
        for f in fs:
            print(f"  NEW {m}: {f}")
    for m in stale:
        print(f"  STALE baseline entry {m}: compliant (or gone) — delete it from {BASELINE}")
    if not files:
        print("no subjects found — refusing to report clean")
        return 1
    return 1 if (enforce and (new or stale)) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
