#!/usr/bin/env python3
"""Fail closed unless a saved OpenTofu plan only mutates approved evidence resources."""

import json
import sys
from pathlib import Path


ALLOWED = {
    "aws_iam_role.admin_ui_evidence",
    "aws_iam_role_policy.admin_ui_evidence",
    "aws_s3_bucket_policy.test_evidence",
}
NON_MUTATING = {"no-op", "read"}
MUTATING_ALLOWED = {"create", "update"}


def validate_plan(plan):
    if not isinstance(plan, dict):
        raise ValueError("plan root must be an object")
    resources = plan.get("resource_changes")
    if not isinstance(resources, list):
        raise ValueError("plan is missing a resource_changes array")

    checked = []
    for index, resource in enumerate(resources):
        if not isinstance(resource, dict):
            raise ValueError(f"resource_changes[{index}] must be an object")
        address = resource.get("address")
        mode = resource.get("mode")
        change = resource.get("change")
        if not isinstance(address, str) or not address:
            raise ValueError(f"resource_changes[{index}] has no valid address")
        if not isinstance(mode, str) or mode not in {"managed", "data"}:
            raise ValueError(f"{address}: invalid or missing resource mode")
        if not isinstance(change, dict):
            raise ValueError(f"{address}: missing change object")
        actions = change.get("actions")
        if not isinstance(actions, list) or not actions or any(not isinstance(action, str) for action in actions):
            raise ValueError(f"{address}: invalid or missing action list")

        action_set = set(actions)
        if action_set & {"delete", "forget"}:
            raise ValueError(f"{address}: destructive action rejected ({','.join(actions)})")
        if action_set <= NON_MUTATING:
            checked.append((address, "+".join(actions)))
            continue
        if mode == "data":
            raise ValueError(f"{address}: data resource has unexpected action ({','.join(actions)})")
        if address not in ALLOWED:
            raise ValueError(f"{address}: managed change is outside the admin-ui-evidence allowlist")
        if not action_set <= MUTATING_ALLOWED or len(actions) != 1:
            raise ValueError(f"{address}: unsupported action sequence ({','.join(actions)})")
        checked.append((address, "+".join(actions)))
    return checked


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def validate_ui_stream(source):
    """Check the official plan UI stream without decoding unrelated prior state.

    The producer must run plan -json -out=... successfully. Its completed summary
    must account for every planned mutation; a truncated/error stream fails closed.
    https://opentofu.org/docs/internals/machine-readable-ui/
    """
    events = [json.loads(line, object_pairs_hook=unique_object)
              for line in source.splitlines() if line.strip()]
    if not events or not isinstance(events[0], dict) or events[0].get("type") != "version":
        raise ValueError("missing initial UI version")
    version = events[0].get("ui")
    if not isinstance(version, str) or not version.startswith("1.") or not version[2:].isdigit():
        raise ValueError("unsupported UI version")
    resources = []
    addresses = set()
    counts = {"add": 0, "change": 0, "remove": 0}
    summary = None
    passive = {"log", "diagnostic", "resource_drift", "outputs", "refresh_start", "refresh_complete"}
    actions = {"noop": "no-op", "read": "read", "create": "create", "update": "update"}
    for index, event in enumerate(events):
        if not isinstance(event, dict):
            raise ValueError("UI event must be an object")
        kind = event.get("type")
        if event.get("@level") == "error" or (
            kind == "diagnostic" and event.get("diagnostic", {}).get("severity") == "error"
        ):
            raise ValueError("plan contains an error diagnostic")
        if kind == "version":
            if index != 0:
                raise ValueError("duplicate UI version")
        elif kind == "planned_change":
            if summary is not None:
                raise ValueError("planned change after summary")
            change = event.get("change")
            if not isinstance(change, dict) or not isinstance(change.get("resource"), dict):
                raise ValueError("invalid planned change")
            address = change["resource"].get("addr")
            action = change.get("action")
            if action not in actions or change.get("previous_resource") is not None or change.get("importing") is not None:
                raise ValueError("unsupported planned action or resource move/import")
            if not isinstance(address, str) or not address or address in addresses:
                raise ValueError("invalid or duplicate planned resource")
            addresses.add(address)
            resources.append({"address": address, "mode": "data" if address.startswith("data.") else "managed",
                              "change": {"actions": [actions[action]]}})
            if action == "create":
                counts["add"] += 1
            elif action == "update":
                counts["change"] += 1
        elif kind == "change_summary":
            if summary is not None:
                raise ValueError("duplicate change summary")
            summary = event.get("changes")
            if not isinstance(summary, dict) or summary.get("operation") != "plan":
                raise ValueError("missing plan summary")
            if set(summary) - {"operation", "add", "change", "remove", "import", "forget"}:
                raise ValueError("unknown summary operation")
            for key in ("add", "change", "remove", "import", "forget"):
                value = summary.get(key, 0 if key in {"import", "forget"} else None)
                if type(value) is not int or value != counts.get(key, 0):
                    raise ValueError("summary does not match approved planned actions")
        elif kind not in passive:
            raise ValueError(f"unsupported UI event: {kind}")
    if summary is None:
        raise ValueError("missing completed plan summary")
    return validate_plan({"resource_changes": resources})


def main(path, ui_stream=False):
    try:
        source = Path(path).read_text(encoding="utf-8")
        checked = validate_ui_stream(source) if ui_stream else validate_plan(
            json.loads(source, object_pairs_hook=unique_object)
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError, AttributeError) as error:
        print(f"Admin UI evidence plan rejected: {error}", file=sys.stderr)
        return 1
    print("Admin UI evidence plan resource actions:")
    for address, actions in checked:
        print(f"{address}: {actions}")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    ui_stream = len(args) == 2 and args[0] == "--ui-stream"
    if len(args) != 1 and not ui_stream:
        print("usage: check-admin-ui-evidence-plan.py [--ui-stream] PLAN.json", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(args[-1], ui_stream))
