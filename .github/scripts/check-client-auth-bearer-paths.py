#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Client-certificate listeners must not turn a certificate into API identity (#12511).

Quarkus registers an mTLS authentication mechanism when the production HTTP listener
requests or requires a client certificate. Every served JAX-RS class root therefore
needs a bearer-only HTTP permission. This static gate complements, but cannot replace,
the real TLS/401/bearer listener test for each service.

Usage: check-client-auth-bearer-paths.py [--root ROOT] [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml


class StrictLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader: StrictLoader, node: yaml.MappingNode) -> dict:
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        if key in result:
            raise yaml.constructor.ConstructorError(None, None, f"duplicate YAML key {key!r}", key_node.start_mark)
        result[key] = loader.construct_object(value_node)
    return result


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)

CLIENT_AUTH = ("quarkus", "http", "ssl", "client-auth")
PERMISSION = ("quarkus", "http", "auth", "permission")
PATH = re.compile(r'^@Path\s*\(\s*"([^"\n]+)"\s*\)\s*$')
CLASS = re.compile(r'^(?:(?:public|private|internal|open|abstract|final|sealed|data)\s+)*class\s+\w+')
INTERFACE = re.compile(r'^(?:(?:public|private|internal|sealed)\s+)*interface\s+\w+')


def flatten(node: dict, prefix: tuple[str, ...] = ()) -> dict[tuple[str, ...], object]:
    """Canonicalize nested and dotted Quarkus keys; reject ambiguous duplicate leaves."""
    out: dict[tuple[str, ...], object] = {}
    for key, value in node.items():
        if not isinstance(key, str):
            # Existing unrelated YAML includes `cors: { null: ... }`. PyYAML decodes that
            # key as None; it cannot affect the two Quarkus settings this gate reads.
            if prefix[:3] == ("quarkus", "http", "auth") or prefix[:3] == ("quarkus", "http", "ssl"):
                raise ValueError(f"non-string security configuration key {key!r}")
            continue
        parts = prefix + tuple(key.split("."))
        if isinstance(value, dict):
            children = flatten(value, parts)
            if set(out).intersection(children):
                raise ValueError(f"duplicate flattened configuration key at {key!r}")
            out.update(children)
        else:
            if parts in out:
                raise ValueError(f"duplicate flattened configuration key {'.'.join(parts)}")
            out[parts] = value
    return out


def effective_prod(doc: dict) -> dict[tuple[str, ...], object]:
    if not isinstance(doc, dict):
        raise TypeError("application.yaml must be a mapping")
    base = {k: v for k, v in doc.items() if not (isinstance(k, str) and k.startswith("%"))}
    result = flatten(base)
    for key, value in doc.items():
        if isinstance(key, str) and "%prod" in [p.strip() for p in key.split(",")]:
            if not isinstance(value, dict):
                raise ValueError("%prod must be a mapping")
            result.update(flatten(value))
    return result


def class_roots(sources: dict[str, str]) -> tuple[list[tuple[str, str, int]], list[str]]:
    roots: list[tuple[str, str, int]] = []
    errors: list[str] = []
    for filename, text in sorted(sources.items()):
        pending: tuple[str, int] | None = None
        for line_no, line in enumerate(text.splitlines(), 1):
            if line.startswith((" ", "\t")):
                continue  # method-level annotation or class body
            stripped = line.strip()
            if stripped.startswith("@Path"):
                match = PATH.fullmatch(stripped)
                if not match:
                    errors.append(f"{filename}:{line_no}: class-level @Path must use one literal line")
                    pending = None
                elif pending:
                    errors.append(f"{filename}:{line_no}: multiple pending class-level @Path annotations")
                else:
                    pending = (match.group(1), line_no)
                continue
            if not pending or not stripped or stripped.startswith(("@", "//", "/*", "*")):
                continue
            if CLASS.match(stripped):
                roots.append((pending[0], filename, pending[1]))
            elif not INTERFACE.match(stripped):
                errors.append(f"{filename}:{pending[1]}: @Path is not bound to a resource class")
            pending = None
        if pending:
            errors.append(f"{filename}:{pending[1]}: @Path has no following declaration")
    return roots, errors


def paths(value: object) -> list[str] | None:
    if isinstance(value, str):
        items = [p.strip() for p in value.split(",")]
    elif isinstance(value, list) and all(isinstance(p, str) for p in value):
        items = [p.strip() for p in value]
    else:
        return None
    return items if items and all(p.startswith("/") and p for p in items) else None


def covers(pattern: str, root: str) -> bool:
    """Accept only segment-boundary prefix globs that cover descendants too."""
    if not pattern.endswith("/*") or "*" in pattern[:-1] or "{" in pattern or "}" in pattern:
        return False
    prefix = pattern[:-2]
    return root.startswith(prefix + "/") if prefix else root.startswith("/")


def overlaps(pattern: str, root: str) -> bool:
    if pattern == root or covers(pattern, root):
        return True
    if "*" in pattern or "{" in pattern or "}" in pattern:
        if not pattern.endswith("/*") or "*" in pattern[:-1] or "{" in pattern or "}" in pattern:
            return True  # cannot prove a competing permission disjoint: fail closed
        pattern = pattern[:-2]
        wildcard = True
    else:
        wildcard = False
    route = [part for part in root.strip("/").split("/") if part]
    candidate = [part for part in pattern.strip("/").split("/") if part]
    shared = min(len(route), len(candidate))
    same = all(a == b or (a.startswith("{") and a.endswith("}")) for a, b in zip(route[:shared], candidate[:shared]))
    return same and (wildcard or len(candidate) >= len(route))


def findings_for(doc: dict, sources: dict[str, str]) -> tuple[bool, list[str]]:
    conf = effective_prod(doc)
    client_auth = conf.get(CLIENT_AUTH)
    if client_auth is None or str(client_auth).strip().lower() == "none":
        return False, []
    if not isinstance(client_auth, str) or client_auth.strip().lower() not in {"required", "request"}:
        return True, [f"production client-auth value {client_auth!r} cannot be proven safe"]
    roots, errors = class_roots(sources)
    if not roots:
        errors.append("client-auth service has no parsed class-level @Path roots")
    permissions: dict[str, dict[str, object]] = {}
    for key, value in conf.items():
        if key[: len(PERMISSION)] != PERMISSION:
            continue
        suffix = key[len(PERMISSION) :]
        if len(suffix) < 2 or suffix[-1] not in {"paths", "policy", "auth-mechanism", "methods"}:
            errors.append(f"unrecognized HTTP permission key {'.'.join(key)}")
            continue
        permissions.setdefault(".".join(suffix[:-1]), {})[suffix[-1]] = value
    for root, filename, line in roots:
        cover = []
        for name, permission in permissions.items():
            patterns = paths(permission.get("paths"))
            if patterns is None:
                errors.append(f"permission {name}: missing/ambiguous paths")
                continue
            mechanism = permission.get("auth-mechanism")
            safe = mechanism == "bearer" and permission.get("policy") in {"permit", "authenticated"} and "methods" not in permission
            if safe and any(covers(p, root) for p in patterns):
                cover.append(name)
            elif not safe and any(overlaps(p, root) for p in patterns):
                errors.append(f"{filename}:{line}: {root} overlaps non-bearer permission {name}")
        if not cover:
            errors.append(f"{filename}:{line}: {root} lacks a bearer-only permission covering descendants")
    return True, errors


def self_test() -> int:
    good = yaml.load('"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n      auth:\n        permission:\n          api:\n            paths: /api/*\n            policy: permit\n            auth-mechanism: bearer\n', Loader=StrictLoader)
    source = {"Resource.kt": '@Path("/api/v1/things")\nclass ThingsResource\n'}
    cases = [
        ("required bearer covers root", good, source, False),
        ("request listener is covered", {"%prod": {"quarkus.http.ssl.client-auth": "request", "quarkus.http.auth.permission.api.paths": ["/api/*"], "quarkus.http.auth.permission.api.policy": "authenticated", "quarkus.http.auth.permission.api.auth-mechanism": "bearer"}}, source, False),
        ("inherited base permission", {"quarkus.http.auth.permission.api.paths": "/api/*", "quarkus.http.auth.permission.api.policy": "permit", "quarkus.http.auth.permission.api.auth-mechanism": "bearer", "%prod": {"quarkus.http.ssl.client-auth": "required"}}, source, False),
        ("missing bearer", {"%prod": {"quarkus.http.ssl.client-auth": "required"}}, source, True),
        ("basic mechanism", {"%prod": {"quarkus.http.ssl.client-auth": "required", "quarkus.http.auth.permission.api.paths": "/api/*", "quarkus.http.auth.permission.api.policy": "permit", "quarkus.http.auth.permission.api.auth-mechanism": "basic"}}, source, True),
        ("exact root misses descendants", {"%prod": {"quarkus.http.ssl.client-auth": "required", "quarkus.http.auth.permission.api.paths": "/api/v1/things", "quarkus.http.auth.permission.api.policy": "permit", "quarkus.http.auth.permission.api.auth-mechanism": "bearer"}}, source, True),
        ("second root uncovered", good, {**source, "Other.kt": '@Path("/internal/items")\nclass OtherResource\n'}, True),
        ("client interface does not count", good, {"Client.kt": '@Path("/other")\ninterface OutboundClient\n', **source}, False),
        ("more-specific non-bearer overlap", {"%prod": {**good["%prod"], "quarkus.http.auth.permission.open.paths": "/api/v1/things/public/*", "quarkus.http.auth.permission.open.policy": "permit"}}, source, True),
        ("path-parameter overlap", {"%prod": {**good["%prod"], "quarkus.http.auth.permission.open.paths": "/api/v1/accounts/123/authorizations/*", "quarkus.http.auth.permission.open.policy": "permit"}}, {"Resource.kt": '@Path("/api/v1/accounts/{accountId}/authorizations")\nclass Resource\n'}, True),
        ("unresolved client-auth", {"%prod": {"quarkus.http.ssl.client-auth": "${CLIENT_AUTH:required}"}}, source, True),
        ("zero resource roots", good, {"Client.kt": '@Path("/api/v1/things")\ninterface OutboundClient\n'}, True),
    ]
    failed = []
    for label, doc, src, expected_bad in cases:
        _, findings = findings_for(doc, src)
        if bool(findings) != expected_bad:
            failed.append(label)
    try:
        yaml.load('quarkus:\n  http: {}\n  http: {}\n', Loader=StrictLoader)
        failed.append("duplicate YAML key")
    except yaml.YAMLError:
        pass
    print(f"client-auth-bearer-paths self-test: {len(cases) + 1 - len(failed)}/{len(cases) + 1} passed")
    for label in failed:
        print(f"FAIL: {label}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    applications = sorted(args.root.glob("openbank-*/src/main/resources/application.yaml"))
    findings: list[str] = []
    subjects = 0
    for app in applications:
        service = app.parents[3]
        try:
            doc = yaml.load(app.read_text(encoding="utf-8"), Loader=StrictLoader)
            sources = {str(p.relative_to(service)): p.read_text(encoding="utf-8") for p in service.glob("src/main/kotlin/**/*.kt")}
            for p in service.glob("src/main/java/**/*.java"):
                if "@Path" in p.read_text(encoding="utf-8"):
                    raise ValueError(f"Java @Path source is not inventoried: {p.relative_to(service)}")
            active, bad = findings_for(doc, sources)
            subjects += int(active)
            findings.extend(f"{app.relative_to(args.root)}: {b}" for b in bad)
        except (OSError, TypeError, ValueError, yaml.YAMLError) as exc:
            findings.append(f"{app.relative_to(args.root)}: cannot verify: {exc}")
    print(f"SUBJECTS={subjects}")
    print(f"client-auth-bearer-paths: {len(applications)} YAML files, {subjects} client-auth subjects, {len(findings)} findings")
    for finding in findings:
        print(f"::{ 'error' if args.enforce else 'warning' }::{finding}")
    if not applications or not subjects:
        print("::error::no application.yaml or client-auth subject found; refusing to report clean")
        return 1
    return 1 if args.enforce and findings else 0


if __name__ == "__main__":
    sys.exit(main())
