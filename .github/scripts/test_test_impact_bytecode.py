#!/usr/bin/env python3
"""Independent positive and falsification fixtures for partial test-impact edges."""

from __future__ import annotations

import json
import importlib.util
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

        mapped = direct_bytecode_mapping(service, [case])
        assert mapped["mappingState"] == "partial", mapped
        assert mapped["coverage"] == {"observedTests": 1, "testsWithDirectEdges": 1, "unknownTests": 0}
        edge = next(item for item in mapped["mappings"][0]["edges"] if item["productionClass"] == "example/Foo$Inner")
        assert edge["sourcePath"] == "src/main/java/example/Foo.java", edge
        assert len(edge["sourceSha256"]) == len(edge["productionClassSha256"]) == len(edge["testClassSha256"]) == 64
        assert "Foo.Inner.value" not in json.dumps(mapped)  # no source contents or invocation data

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
            "testImpact": mapped,
        }
        collector.validate_envelope(envelope, service)
        for altered in (
            {**mapped, "mappingState": "mapped"},
            {**mapped, "mappings": [{**mapped["mappings"][0], "edges": [{**edge, "sourcePath": "../outside.java"}]}]},
            {**mapped, "mappings": [{**mapped["mappings"][0], "edges": [{**edge, "sourceSha256": "0" * 64}]}]},
        ):
            try:
                collector.validate_envelope({**envelope, "testImpact": altered}, service)
            except ValueError:
                pass
            else:
                raise AssertionError("invented test-impact claim was accepted")
        try:
            collector.validate_envelope(envelope)
        except ValueError:
            pass
        else:
            raise AssertionError("v2 mapping was accepted without the source tree")

        # A same-name source edit after compilation cannot retain an old edge.
        original_test = test_source.read_text()
        test_source.write_text(original_test + "// changed after compile\n")
        class_mtime = (tests / "example/FooTest.class").stat().st_mtime_ns
        os.utime(test_source, ns=(class_mtime + 1_000_000_000, class_mtime + 1_000_000_000))
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        test_source.write_text(original_test)
        subprocess.run(["javac", "-cp", str(main), "-d", str(tests), str(test_source)], check=True)

        original_main = source.read_text()
        source.write_text(original_main + "// changed after compile\n")
        class_mtime = (main / "example/Foo$Inner.class").stat().st_mtime_ns
        os.utime(source, ns=(class_mtime + 1_000_000_000, class_mtime + 1_000_000_000))
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        source.write_text(original_main)
        subprocess.run(["javac", "-d", str(main), str(source)], check=True)

        renamed = service / "src/main/java/example/Renamed.java"
        source.rename(renamed)
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        renamed.rename(source)

        test_source.rename(service / "src/test/java/example/RenamedTest.java")
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        (service / "src/test/java/example/RenamedTest.java").rename(test_source)

        # A test class whose SourceFile names another declaration cannot inherit
        # the original case's mapping, even when it references the same production class.
        wrong_definition = {**case, "testDefinitionPath": "src/test/java/example/OtherTest.java"}
        write(service / "src/test/java/example/OtherTest.java", "package example; class OtherTest {}\n")
        assert direct_bytecode_mapping(service, [wrong_definition])["mappingState"] == "unknown"

        compiled = tests / "example/FooTest.class"
        compiled.unlink()
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        subprocess.run(["javac", "-cp", str(main), "-d", str(tests), str(test_source)], check=True)

        # A symlink into a different tree can never become a retained production path.
        source.unlink()
        outside = Path(directory) / "outside.java"
        outside.write_text("public class Outside {}\n")
        source.symlink_to(outside)
        assert direct_bytecode_mapping(service, [case])["mappingState"] == "unknown"
        print("test-impact bytecode self-test: nested edge, stale/missing/renamed/outside paths and forged claims proven")


if __name__ == "__main__":
    run()
