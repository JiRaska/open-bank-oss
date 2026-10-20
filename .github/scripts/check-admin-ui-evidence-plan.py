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


def main(path):
    try:
        plan = json.loads(Path(path).read_text(encoding="utf-8"))
        checked = validate_plan(plan)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"Admin UI evidence plan rejected: {error}", file=sys.stderr)
        return 1
    print("Admin UI evidence plan resource actions:")
    for address, actions in checked:
        print(f"{address}: {actions}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: check-admin-ui-evidence-plan.py PLAN.json", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
