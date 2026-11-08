# SPDX-License-Identifier: Apache-2.0
"""Synthetic-only checks for the credential action's output/control boundary."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

PRELOAD = Path(__file__).with_name("private-credential-action-output.cjs")


class PrivateCredentialOutputTest(unittest.TestCase):
    def run_node(self, source, missing=None, *, spaced_preload=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            preload = PRELOAD
            if spaced_preload:
                preload = root / "directory with spaces" / PRELOAD.name
                preload.parent.mkdir()
                preload.write_bytes(PRELOAD.read_bytes())
            env = dict(os.environ, RUNNER_TEMP=tmp, NODE_OPTIONS="--require=" + json.dumps(str(preload)))
            for name in ("GITHUB_ENV", "GITHUB_STATE", "GITHUB_OUTPUT"):
                env[name] = str(root / name)
                Path(env[name]).touch()
            if missing:
                env.pop(missing, None)
            script = root / "synthetic.cjs"
            script.write_text(source)
            result = subprocess.run(["node", str(script)],
                                    env=env, capture_output=True, timeout=20, check=False)
            logs = list(root.glob("private-credential-action-*/diagnostics.log"))
            for log in logs:
                self.assertEqual(log.stat().st_mode & 0o777, 0o600)
                self.assertEqual(log.parent.stat().st_mode & 0o777, 0o700)
            private = b"".join(log.read_bytes() for log in logs)
            controls = {name: (root / name).read_text()
                        for name in ("GITHUB_ENV", "GITHUB_STATE", "GITHUB_OUTPUT")}
            return result, private, controls

    def test_both_diagnostics_streams_are_private_including_unterminated_output(self):
        result, private, _ = self.run_node(
            'console.log("SYNTHETIC_ROLE_ID"); console.error("SYNTHETIC_ERROR");'
            'process.stdout.write("SYNTHETIC_TAIL");')
        self.assertEqual(result.returncode, 0)
        for value in (b"SYNTHETIC_ROLE_ID", b"SYNTHETIC_ERROR", b"SYNTHETIC_TAIL"):
            self.assertNotIn(value, result.stdout + result.stderr)
            self.assertIn(value, private)

    def test_masking_commands_are_forwarded_without_retaining_their_values(self):
        result, private, _ = self.run_node(
            'process.stdout.write("::add-mask::SYNTHETIC_SECRET%0Avalue\\n");')
        self.assertEqual(result.returncode, 0)
        self.assertIn(b"::add-mask::SYNTHETIC_SECRET%0Avalue\n", result.stdout)
        self.assertNotIn(b"SYNTHETIC_SECRET", private)

    def test_split_commands_and_write_callbacks_are_preserved(self):
        result, private, controls = self.run_node(
            'const fs=require("node:fs"); process.stdout.write("::add-ma");'
            'process.stdout.write("sk::SYNTHETIC_SECRET\\n",()=>'
            'fs.appendFileSync(process.env.GITHUB_STATE,"callback=true\\n"));')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.count(b"::add-mask::SYNTHETIC_SECRET\n"), 1)
        self.assertNotIn(b"SYNTHETIC_SECRET", private)
        self.assertEqual(controls["GITHUB_STATE"], "callback=true\n")

    def test_environment_state_and_output_files_are_untouched(self):
        result, _, controls = self.run_node(
            'const fs=require("node:fs"); for(const name of '
            '["GITHUB_ENV","GITHUB_STATE","GITHUB_OUTPUT"]) '
            'fs.appendFileSync(process.env[name],"synthetic=value\\n");')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(set(controls.values()), {"synthetic=value\n"})

    def test_failures_do_not_publish_diagnostics_or_error_annotations(self):
        result, private, _ = self.run_node(
            'console.log("::error::SYNTHETIC_ROLE_ID"); process.exitCode=1;')
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b"SYNTHETIC_ROLE_ID", result.stdout + result.stderr)
        self.assertIn(b"SYNTHETIC_ROLE_ID", private)
        self.assertIn(b"::error::Credential action failed", result.stdout)

    def test_incomplete_masking_command_fails_without_recording_credentials(self):
        result, private, _ = self.run_node('process.stdout.write("::add-mask::SYNTHETIC_SECRET");')
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b"SYNTHETIC_SECRET", result.stdout + result.stderr + private)

    def test_missing_control_file_refuses_before_running_the_action(self):
        result, _, _ = self.run_node('console.log("MUST_NOT_RUN");', missing="GITHUB_STATE")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b"MUST_NOT_RUN", result.stdout + result.stderr)

    def test_legacy_control_protocol_is_rejected_without_exposing_its_payload(self):
        result, _, _ = self.run_node('process.stdout.write("::save-state name=synthetic::SYNTHETIC_ROLE_ID\\n");')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(b"SYNTHETIC_ROLE_ID", result.stdout + result.stderr)

    def test_unicode_diagnostic_chunks_are_preserved_privately(self):
        result, private, _ = self.run_node(
            'const bytes=Buffer.from("žluťoučký\\n");'
            'process.stderr.write(bytes.subarray(0,1)); process.stderr.write(bytes.subarray(1));')
        self.assertEqual(result.returncode, 0)
        self.assertIn("žluťoučký".encode(), private)
        self.assertNotIn("žluťoučký".encode(), result.stdout + result.stderr)

    def test_preload_option_quotes_workspace_paths_with_spaces(self):
        result, private, _ = self.run_node('console.log("SYNTHETIC_ROLE_ID");', spaced_preload=True)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn(b"SYNTHETIC_ROLE_ID", result.stdout + result.stderr)
        self.assertIn(b"SYNTHETIC_ROLE_ID", private)

    def test_preload_is_scoped_to_the_two_pinned_credential_actions(self):
        source = (PRELOAD.parents[1] / "workflows/platform-tofu.yml").read_text()
        self.assertEqual(source.count("NODE_OPTIONS:"), 2)
        parts = source.split("      - name: Configure AWS credentials")
        self.assertEqual(len(parts), 3)
        for part in parts[1:]:
            step = part.split("      - name:", 1)[0]
            self.assertIn("uses: aws-actions/configure-aws-credentials@e1253824e5c10ff9df46874f81ed3ec929e19cfd", step)
            self.assertIn('--require="${{ github.workspace }}/.github/scripts/private-credential-action-output.cjs"', step)


if __name__ == "__main__":
    unittest.main()
