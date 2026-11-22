#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ENFORCED half of the MCP red-team pack (ADR-0383 D7, #16617 layer 4): the corpus, the rendered
# fixture the JUnit test commits, evals/mcp-redteam/scenarios.json and the generated promptfoo
# files must agree. Needs no model, so it can block today. The model verdict is a separate,
# ADVISORY gate (run-mcp-redteam-evals.py) until a real recording exists — kept in its own script
# so each gate's producer has exactly one mode in rules.yaml.
#
# Run: python3 .github/scripts/check-mcp-redteam-fixture-integrity.py [--self-test]
import importlib.util
import pathlib
import sys

_spec = importlib.util.spec_from_file_location(
    "mcp_redteam", pathlib.Path(__file__).with_name("run-mcp-redteam-evals.py"))
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

if __name__ == "__main__":
    sys.exit(_mod.self_test() if "--self-test" in sys.argv[1:] else _mod.fixture_integrity())
