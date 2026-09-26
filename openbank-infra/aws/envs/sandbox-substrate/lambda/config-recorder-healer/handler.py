"""Restore the AWS Config recorder when recording is enabled and it is stopped.

Triggered by EventBridge for recorder stop events and periodically as a backstop.
Recording can be disabled explicitly for environments whose desired state requires it.
"""

import os

import boto3

RECORDER_NAME = os.environ["RECORDER_NAME"]
RECORDING_FREQUENCY = os.environ.get("RECORDING_FREQUENCY", "DAILY")
_RECORDING_ENABLED_VALUE = os.environ.get("RECORDING_ENABLED", "true")
if _RECORDING_ENABLED_VALUE not in {"true", "false"}:
    raise ValueError("RECORDING_ENABLED must be exactly 'true' or 'false'")
RECORDING_ENABLED = _RECORDING_ENABLED_VALUE == "true"


def handler(event, context):
    if not RECORDING_ENABLED:
        print("AWS Config recording is disabled — skipping recorder healing.")
        return {"healed": False, "reason": "recording_disabled"}

    client = boto3.client("config")

    status = client.describe_configuration_recorder_status(
        ConfigurationRecorderNames=[RECORDER_NAME]
    )["ConfigurationRecordersStatus"]

    if not status:
        print(f"No recorder named {RECORDER_NAME} found — nothing to heal.")
        return {"healed": False, "reason": "recorder_not_found"}

    if status[0]["recording"]:
        print(f"{RECORDER_NAME} is already recording — no action needed.")
        return {"healed": False, "reason": "already_recording"}

    print(f"{RECORDER_NAME} is stopped — restarting.")
    client.start_configuration_recorder(ConfigurationRecorderName=RECORDER_NAME)

    # Belt-and-braces: also re-assert the DAILY recording_mode in case the same
    # apply that stopped the recorder also dropped recordingMode (the original
    # 2026-07-06 incident — CONTINUOUS drift, not just a stopped recorder).
    recorder = client.describe_configuration_recorders(
        ConfigurationRecorderNames=[RECORDER_NAME]
    )["ConfigurationRecorders"][0]
    mode = recorder.get("recordingMode", {}).get("recordingFrequency")
    if mode != RECORDING_FREQUENCY:
        print(f"recordingMode was {mode!r}, resetting to {RECORDING_FREQUENCY!r}.")
        recorder["recordingMode"] = {"recordingFrequency": RECORDING_FREQUENCY}
        client.put_configuration_recorder(ConfigurationRecorder=recorder)

    return {"healed": True}
