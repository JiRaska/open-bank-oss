#!/usr/bin/env python3
"""Fail if Context and Document disagree on the one bank served by this deployment.

ADR-0311: these labels are operator-set provenance, not caller-selected tenancy.
The Lending publisher is not enabled yet; add its manifest here before activation.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SOURCES = (
    ("context", "context-service.yaml", "context-service", "CONTEXT_BANK_SCOPE"),
    ("document-service", "document-service-service.yaml", "document-service", "DOCUMENT_BANK_SCOPE"),
)
SCOPE = re.compile(r"[a-z0-9][a-z0-9-]{0,63}\Z")


def declared_scope(doc: dict, container_name: str, variable: str) -> str:
    containers = doc["spec"]["template"]["spec"]["containers"]
    matches = [c for c in containers if c.get("name") == container_name]
    if len(matches) != 1:
        raise ValueError(f"expected one {container_name} container")
    env = [item for item in matches[0].get("env", []) if item.get("name") == variable]
    if len(env) != 1 or not isinstance(env[0].get("value"), str):
        raise ValueError(f"{variable} must have one explicit literal value")
    value = env[0]["value"]
    if not SCOPE.fullmatch(value):
        raise ValueError(f"{variable} has an invalid bank identifier")
    return value


def require_same(values: dict[str, str]) -> None:
    if len(set(values.values())) != 1:
        raise ValueError("deployment bank mismatch: " + ", ".join(f"{k}={v}" for k, v in values.items()))


def self_test() -> None:
    good = {"spec": {"template": {"spec": {"containers": [
        {"name": "service", "env": [{"name": "BANK", "value": "bank-a"}]}
    ]}}}}
    assert declared_scope(good, "service", "BANK") == "bank-a"
    for env in ([], [{"name": "BANK", "valueFrom": {"secretKeyRef": {"name": "x"}}}],
                [{"name": "BANK", "value": "bank-a"}, {"name": "BANK", "value": "bank-b"}],
                [{"name": "BANK", "value": "BANK A"}]):
        good["spec"]["template"]["spec"]["containers"][0]["env"] = env
        try:
            declared_scope(good, "service", "BANK")
        except ValueError:
            continue
        raise AssertionError(f"invalid environment accepted: {env}")
    require_same({"CONTEXT_BANK_SCOPE": "bank-a", "DOCUMENT_BANK_SCOPE": "bank-a"})
    try:
        require_same({"CONTEXT_BANK_SCOPE": "bank-a", "DOCUMENT_BANK_SCOPE": "bank-b"})
    except ValueError:
        pass
    else:
        raise AssertionError("mismatched bank provenance accepted")
    print("bank-provenance parity self-test: PASS")


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    values = {}
    try:
        for component, file_name, container, variable in SOURCES:
            path = ROOT / "openbank-infra/gitops/components" / component / file_name
            documents = [doc for doc in yaml.safe_load_all(path.read_text()) if isinstance(doc, dict)]
            workloads = [doc for doc in documents if doc.get("kind") in ("Deployment", "Rollout")]
            if len(workloads) != 1:
                raise ValueError(f"{path}: expected one workload")
            values[variable] = declared_scope(workloads[0], container, variable)
        require_same(values)
    except (KeyError, TypeError, ValueError, yaml.YAMLError) as exc:
        print(f"::error::deployment bank provenance parity: {exc}", file=sys.stderr)
        return 1
    print(f"SUBJECTS={len(values)}  # deployment manifests")
    print("deployment bank provenance parity: PASS — Context and Document agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
