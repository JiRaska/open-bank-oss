"""Self-heal the AWS Config recorder if it is ever left in a stopped state.

Triggered by an EventBridge rule matching the CloudTrail `StopConfigurationRecorder`
API call. A `tofu apply` that recreates `aws_config_configuration_recorder.audit`
(e.g. a provider bump, or an apply interrupted mid-recreate) stops the old recorder
as part of the destroy step; if the apply is cancelled or fails before the new
recorder's start step runs, the recorder is left off with zero audit coverage
until someone notices (a 15-hour gap on 2026-07-07, repeated 2026-07-08 during
active CI iteration on the substrate apply pipeline — each recurrence also cost
$60-80/day once restarted mid-CONTINUOUS-window before the DAILY mode re-applied).

This does not replace fixing the underlying apply reliability — it is a backstop
so a stopped recorder is a same-minute self-correction, not a multi-hour human-
detected compliance and cost gap.
"""

import os

import boto3

RECORDER_NAME = os.environ["RECORDER_NAME"]
RECORDING_FREQUENCY = os.environ.get("RECORDING_FREQUENCY", "DAILY")


def handler(event, context):
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
