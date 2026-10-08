#!/usr/bin/env python3
"""Isolate the docs convention plugin from other shared build inputs."""

import json
import sys


DOCS_PLUGIN = "build-logic/src/main/kotlin/openbank.service-docs.gradle.kts"


def classify(paths: list[str]) -> dict[str, object]:
    return {
        "docsPluginChanged": DOCS_PLUGIN in paths,
        "otherPaths": [path for path in paths if path != DOCS_PLUGIN],
    }


def self_test() -> None:
    assert classify([DOCS_PLUGIN]) == {"docsPluginChanged": True, "otherPaths": []}
    assert classify([DOCS_PLUGIN, "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts"]) == {
        "docsPluginChanged": True,
        "otherPaths": ["build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts"],
    }
    assert classify(["build-logic/src/main/kotlin/other.gradle.kts"]) == {
        "docsPluginChanged": False,
        "otherPaths": ["build-logic/src/main/kotlin/other.gradle.kts"],
    }
    assert classify(["build-logic/src/main/kotlin/openbank.service-docs.gradle.kts.bak"]) == {
        "docsPluginChanged": False,
        "otherPaths": ["build-logic/src/main/kotlin/openbank.service-docs.gradle.kts.bak"],
    }


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 1:
        print(json.dumps(classify([line for line in sys.stdin.read().splitlines() if line])))
    else:
        raise SystemExit("usage: service-ci-docs-scope.py [--self-test]")
