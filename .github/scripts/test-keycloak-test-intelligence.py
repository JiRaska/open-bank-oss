#!/usr/bin/env python3
"""Versioned Keycloak runtime evidence remains visible without widening v1."""

import importlib.util
import json
import tempfile
from pathlib import Path


script = Path(__file__).with_name("collect-test-run-evidence.py")
spec = importlib.util.spec_from_file_location("collector", script)
assert spec and spec.loader
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def main() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        service = Path(temporary) / "openbank-example-service"
        test_source = service / "src/test/kotlin/IssuerIT.kt"
        test_source.parent.mkdir(parents=True)
        test_source.write_text("@QuarkusTestResource(ScaOidcTestResource::class, restrictToAnnotatedClass = true)\n")
        assert collector.declared_infrastructure(service, 1) == []
        assert collector.declared_infrastructure(service, 2) == ["keycloak"]
        runtime = service / "build/test-intelligence/runtime"
        runtime.mkdir(parents=True)
        first = "11111111-1111-4111-8111-111111111111"
        second = "22222222-2222-4222-8222-222222222222"
        rows = [
            {"schemaVersion": 1, "resource": "keycloak", "image": "quay.io/keycloak/keycloak:27.0.0", "lifecycle": "started", "observedAt": "2026-08-22T21:10:01Z", "resourceScopeId": first},
            {"schemaVersion": 1, "resource": "keycloak", "image": "quay.io/keycloak/keycloak:27.0.0", "lifecycle": "started", "observedAt": "2026-08-22T21:10:20Z", "resourceScopeId": second},
            {"schemaVersion": 1, "resource": "keycloak", "image": "quay.io/keycloak/keycloak:27.0.0", "lifecycle": "stopped", "observedAt": "2026-08-22T21:11:01Z", "resourceScopeId": first},
            {"schemaVersion": 1, "resource": "keycloak", "image": "quay.io/keycloak/keycloak:27.0.0", "lifecycle": "stopped", "observedAt": "2026-08-22T21:11:20Z", "resourceScopeId": second},
            {"schemaVersion": 1, "resource": "opa", "image": "openpolicyagent/opa:1.4.2", "lifecycle": "started", "observedAt": "2026-08-22T21:10:01Z"},
            {"schemaVersion": 1, "resource": "keycloak", "image": "keycloak:27.0.0", "lifecycle": "started", "observedAt": "bad"},
        ]
        (runtime / "testcontainers.jsonl").write_text("\n".join(map(json.dumps, rows)) + "\n")
        # The daemon and recorder see the same first issuer. Keep the readiness
        # event while preserving the independent second issuer's opaque scope.
        (runtime / "docker-events.jsonl").write_text(json.dumps({
            "image": "quay.io/keycloak/keycloak:27.0.0", "containerId": "internal-id",
            "lifecycle": "start", "observedAtUnix": 1787433001,
        }) + "\n")
        assert collector.observations(service, 1) == []
        observed = collector.observations(service, 2)
        assert len([row for row in observed if row["lifecycle"] == "started"]) == 2, observed
        assert len([row for row in observed if row["lifecycle"] == "stopped"]) == 2, observed
        assert {row["resourceScopeId"] for row in observed} == {first, second}
        assert all(row["image"] == "keycloak:27.0.0" for row in observed)
        assert all("containerId" not in row for row in observed)
        envelope = {
            "schemaVersion": 2, "component": "openbank-example-service",
            "run": {"id": "1", "attempt": 1, "commit": "abcdef012345", "branch": "main",
                    "workflow": "Services CI", "url": "", "observedAt": "2026-08-22T21:12:00Z"},
            "suites": [], "coverage": None,
            "testInfrastructure": {"declared": ["keycloak"], "observed": observed},
        }
        collector.validate_envelope(envelope)
        for version in (1, 3):
            envelope["schemaVersion"] = version
            try:
                collector.validate_envelope(envelope)
            except ValueError:
                pass
            else:
                raise AssertionError(f"unsupported envelope accepted as v{version}")
    print("Keycloak runtime v2 compatibility: passed")


if __name__ == "__main__":
    main()
