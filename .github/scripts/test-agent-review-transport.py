#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.
"""Exercise the workflow's actual CLI invocation without calling a model."""

import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class ReviewTransportTest(unittest.TestCase):
    def invoke(self, payload):
        workflow = yaml.safe_load((ROOT / ".github/workflows/agent-review.yml").read_text())
        commands = [
            line.strip()
            for step in workflow["jobs"]["review"]["steps"]
            for line in step.get("run", "").replace("\\\n", " ").splitlines()
            if line.strip().startswith("claude -p ")
        ]
        self.assertEqual(len(commands), 1)
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            prompt = directory / "prompt.txt"
            output = directory / "review.raw"
            prompt.write_bytes(payload)
            cli = directory / "claude"
            cli.write_text(
                "#!/usr/bin/env python3\n"
                "import sys, json, hashlib\n"
                "data = sys.stdin.buffer.read()\n"
                "print(json.dumps({'argv': sys.argv[1:], 'size': len(data), "
                "'sha256': hashlib.sha256(data).hexdigest()}))\n"
            )
            cli.chmod(0o755)
            command = commands[0].replace("/tmp/prompt.txt", shlex.quote(str(prompt)))
            command = command.replace("/tmp/review.raw", shlex.quote(str(output)))
            env = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", "-c", command], env=env, input=b"", capture_output=True, timeout=20
            )
            self.assertEqual(result.returncode, 0, output.read_text() if output.exists() else result.stderr)
            return json.loads(output.read_text())

    def test_prompt_larger_than_argument_limit_arrives_unchanged(self):
        line = "Bankovní test: '$()' `quoted` \\\n".encode()
        payload = line * (2 * os.sysconf("SC_ARG_MAX") // len(line) + 1)
        received = self.invoke(payload)
        self.assertEqual(received["size"], len(payload))
        self.assertEqual(received["sha256"], hashlib.sha256(payload).hexdigest())

    def test_no_tools_and_stream_output_are_preserved(self):
        args = self.invoke(b"small review prompt\n")["argv"]
        self.assertEqual(
            args,
            ["-p", "--model", "sonnet", "--max-turns", "2", "--max-budget-usd", "0.50",
             "--tools", "", "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}',
             "--setting-sources", "", "--no-session-persistence",
             "--output-format", "stream-json", "--verbose"],
        )


if __name__ == "__main__":
    unittest.main()
