"""Unit tests for the environment-gated AWS Config recorder healer."""

import importlib.util
import os
import re
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

HANDLER = (
    Path(__file__).resolve().parents[1]
    / "aws/envs/sandbox-substrate/lambda/config-recorder-healer/handler.py"
)


class ConfigRecorderHealerTest(unittest.TestCase):
    def load_handler(self, enabled=None):
        fake_boto3 = types.ModuleType("boto3")
        fake_client = Mock()
        fake_client.describe_configuration_recorder_status.return_value = {
            "ConfigurationRecordersStatus": [{"recording": False}]
        }
        fake_client.describe_configuration_recorders.return_value = {
            "ConfigurationRecorders": [
                {"name": "audit", "recordingMode": {"recordingFrequency": "DAILY"}}
            ]
        }
        fake_boto3.client = Mock(return_value=fake_client)
        environment = {"RECORDER_NAME": "audit"}
        if enabled is not None:
            environment["RECORDING_ENABLED"] = enabled

        spec = importlib.util.spec_from_file_location("config_recorder_healer_under_test", HANDLER)
        module = importlib.util.module_from_spec(spec)
        with patch.dict(os.environ, environment, clear=True), patch.dict(
            sys.modules, {"boto3": fake_boto3}
        ):
            spec.loader.exec_module(module)
        return module, fake_boto3, fake_client

    def test_disabled_returns_before_creating_aws_client(self):
        module, boto3, _client = self.load_handler("false")

        self.assertEqual(
            module.handler({}, None),
            {"healed": False, "reason": "recording_disabled"},
        )
        boto3.client.assert_not_called()

    def test_default_enabled_restarts_stopped_recorder_and_preserves_frequency(self):
        module, boto3, client = self.load_handler()

        self.assertEqual(module.handler({}, None), {"healed": True})
        boto3.client.assert_called_once_with("config")
        client.start_configuration_recorder.assert_called_once_with(
            ConfigurationRecorderName="audit"
        )
        client.put_configuration_recorder.assert_not_called()

    def test_enabled_restarts_stopped_recorder_and_repairs_frequency_drift(self):
        module, _boto3, client = self.load_handler("true")
        recorder = {"name": "audit", "recordingMode": {"recordingFrequency": "CONTINUOUS"}}
        client.describe_configuration_recorders.return_value = {
            "ConfigurationRecorders": [recorder]
        }

        self.assertEqual(module.handler({}, None), {"healed": True})
        client.start_configuration_recorder.assert_called_once_with(
            ConfigurationRecorderName="audit"
        )
        client.put_configuration_recorder.assert_called_once_with(
            ConfigurationRecorder={
                "name": "audit",
                "recordingMode": {"recordingFrequency": "DAILY"},
            }
        )

    def test_enabled_already_recording_does_not_restart(self):
        module, _boto3, client = self.load_handler("true")
        client.describe_configuration_recorder_status.return_value = {
            "ConfigurationRecordersStatus": [{"recording": True}]
        }

        self.assertEqual(
            module.handler({}, None),
            {"healed": False, "reason": "already_recording"},
        )
        client.start_configuration_recorder.assert_not_called()

    def test_missing_recorder_is_not_started(self):
        module, _boto3, client = self.load_handler("true")
        client.describe_configuration_recorder_status.return_value = {
            "ConfigurationRecordersStatus": []
        }

        self.assertEqual(
            module.handler({}, None),
            {"healed": False, "reason": "recorder_not_found"},
        )
        client.start_configuration_recorder.assert_not_called()

    def test_invalid_enablement_value_fails_closed_as_configuration_error(self):
        for value in ("yes", "TRUE", ""):
            with self.subTest(value=value), self.assertRaisesRegex(
                ValueError, "RECORDING_ENABLED must be exactly"
            ):
                self.load_handler(value)

    def test_sandbox_desired_state_wires_flag_and_both_event_rules(self):
        infra = Path(__file__).resolve().parents[1]
        main_tf = (infra / "aws/envs/sandbox-substrate/main.tf").read_text()
        selfheal_tf = (infra / "aws/envs/sandbox-substrate/config-recorder-selfheal.tf").read_text()

        self.assertRegex(
            main_tf,
            r"(?m)^\s*config_recording_enabled\s*=\s*(?:true|false)\s*$",
        )
        self.assertRegex(
            main_tf,
            r"config_recording_enabled\s*=\s*local\.config_recording_enabled",
        )
        self.assertRegex(
            selfheal_tf,
            r"RECORDING_ENABLED\s*=\s*tostring\(local\.config_recording_enabled\)",
        )
        for rule_name in ("config_recorder_stopped", "config_recorder_healer_schedule"):
            match = re.search(
                rf'resource\s+"aws_cloudwatch_event_rule"\s+"{rule_name}"\s*\{{',
                selfheal_tf,
            )
            self.assertIsNotNone(match, f"missing EventBridge rule {rule_name}")
            next_resource = re.search(r"\nresource\s+", selfheal_tf[match.end() :])
            end = match.end() + next_resource.start() if next_resource else len(selfheal_tf)
            resource_block = selfheal_tf[match.start() : end]
            self.assertRegex(
                resource_block,
                r'(?m)^\s*state\s*=\s*local\.config_recording_enabled\s*\?\s*"ENABLED"\s*:\s*"DISABLED"\s*$',
            )


if __name__ == "__main__":
    unittest.main()
