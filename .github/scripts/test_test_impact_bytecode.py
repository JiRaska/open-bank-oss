#!/usr/bin/env python3
"""Falsify unsafe source claims from stale and misidentified JVM class files."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from datetime import datetime, timezone

from test_impact_bytecode import direct_bytecode_mapping


def write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)


def run() -> None:
    with tempfile.TemporaryDirectory() as directory:
        service = Path(directory) / "openbank-example-service"
        main = service / "build/classes/java/main"
        tests = service / "build/classes/java/test"
        source = service / "src/main/java/example/Foo.java"
        test_source = service / "src/test/java/example/FooTest.java"
        write(source, "package example; public class Foo { public static class Inner { public static int value() { return 1; } } }\n")
        write(test_source, "package example; public class FooTest { public int test() { return Foo.Inner.value(); } }\n")
        main.mkdir(parents=True)
        tests.mkdir(parents=True)
        subprocess.run(["javac", "-d", str(main), str(source)], check=True)
        subprocess.run(["javac", "-cp", str(main), "-d", str(tests), str(test_source)], check=True)
        case = {"fingerprint": "0" * 24, "kind": "unit", "classname": "example.FooTest",
                "testDefinitionPath": "src/test/java/example/FooTest.java"}

        observed = direct_bytecode_mapping(service, [case])
        assert observed["mappingState"] == "unknown" and observed["selectionState"] == "unavailable"
        assert observed["evidenceState"] == "unverified-bytecode-references"
        assert observed["coverage"] == {"observedTests": 1, "testsWithUnverifiedRefs": 1, "unknownTests": 0}
        edge = next(ref for ref in observed["references"][0]["directClassRefs"]
                    if ref["referencedClass"] == "example/Foo$Inner")
        assert len(edge["productionClassSha256"]) == len(edge["testClassSha256"]) == 64
        assert "src/main" not in json.dumps(observed)

        spec = importlib.util.spec_from_file_location("run_evidence", Path(__file__).with_name("collect-test-run-evidence.py"))
        assert spec is not None and spec.loader is not None
        collector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(collector)
        envelope = {
            "schemaVersion": 1,
            "run": {"id": "1", "attempt": 1, "commit": "1234567", "branch": "main",
                    "workflow": "CI", "url": "", "observedAt": datetime.now(timezone.utc).isoformat()},
            "component": "openbank-example-service", "suites": [], "coverage": None,
            "testInfrastructure": {"declared": [], "observed": []},
            "testCases": [{**case, "name": "test", "state": "passed", "durationMs": 1}],
            "testImpact": observed,
        }
        collector.validate_envelope(envelope, service)
        for altered in (
            {**observed, "mappingState": "partial"},
            {**observed, "selectionState": "selected"},
            {**observed, "references": [{**observed["references"][0],
                "directClassRefs": [{**edge, "testClassSha256": "0" * 64}]}]},
        ):
            try:
                collector.validate_envelope({**envelope, "testImpact": altered}, service)
            except ValueError:
                pass
            else:
                raise AssertionError("invented mapping or class hash was accepted")
        try:
            collector.validate_envelope(envelope)
        except ValueError:
            pass
        else:
            raise AssertionError("v2 class reference was accepted without the build tree")

        # Falsification: old bytecode can have a NEWER mtime than changed source.
        source.write_text(source.read_text().replace("return 1", "return 2"))
        class_file = main / "example/Foo$Inner.class"
        fresh_time = source.stat().st_mtime_ns + 1_000_000_000
        os.utime(class_file, ns=(fresh_time, fresh_time))
        stale = direct_bytecode_mapping(service, [case])
        assert stale["mappingState"] == "unknown"
        assert stale["evidenceState"] == "unverified-bytecode-references"
        assert stale["references"][0]["directClassRefs"]  # useful bytes, not verified source

        # Wrong this_class at a plausible path must not become an edge.
        original_main = class_file.read_bytes()
        class_file.write_bytes((main / "example/Foo.class").read_bytes())
        wrong_main = direct_bytecode_mapping(service, [case])
        assert all(ref["referencedClass"] != "example/Foo$Inner"
                   for ref in wrong_main["references"][0]["directClassRefs"])
        class_file.write_bytes(original_main)
        test_class = tests / "example/FooTest.class"
        original_test = test_class.read_bytes()
        test_class.write_bytes((main / "example/Foo.class").read_bytes())
        assert direct_bytecode_mapping(service, [case])["coverage"]["testsWithUnverifiedRefs"] == 0
        test_class.write_bytes(original_test)

        test_class.unlink()
        assert direct_bytecode_mapping(service, [case])["coverage"]["testsWithUnverifiedRefs"] == 0
        outside = Path(directory) / "outside.class"
        outside.write_bytes(original_test)
        test_class.symlink_to(outside)
        assert direct_bytecode_mapping(service, [case])["coverage"]["testsWithUnverifiedRefs"] == 0
        print("test-impact bytecode self-test: stale-newer bytes stay unverified; class identity, absent and outside paths fail closed")


if __name__ == "__main__":
    run()
