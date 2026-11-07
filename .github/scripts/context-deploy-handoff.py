#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate the context-service Services CI handoff and partition deploy selection.

`source` emits only a verified source SHA from GITHUB_EVENT_PATH. Authentication
of the event is supplied by GitHub; this validates its complete trigger identity.
The caller must separately verify source ancestry before executing that checkout.
`select --event EVENT` partitions an existing JSON service selection; it never
adds a service the ordinary change detector did not select.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

COMPONENT = "openbank-context-service"
WORKFLOW = ".github/workflows/services-ci.yml"
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
SERVICE = re.compile(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*\Z")
EVENT = re.compile(r"[a-z][a-z0-9_]*\Z")
MAX_INPUT_BYTES = 2 * 1024 * 1024


class HandoffError(ValueError):
    pass


def full_name(value):
    return value.get("full_name") if isinstance(value, dict) else None


def verified_source(payload, repository, event_name):
    if not isinstance(repository, str) or REPO.fullmatch(repository) is None:
        raise HandoffError("GITHUB_REPOSITORY is invalid")
    if event_name != "workflow_run" or not isinstance(payload, dict):
        raise HandoffError("source requires a workflow_run event object")
    run = payload.get("workflow_run")
    if payload.get("action") != "completed" or not isinstance(run, dict):
        raise HandoffError("source requires a completed workflow_run action")
    if (run.get("path") != WORKFLOW or run.get("event") != "push"
            or run.get("head_branch") != "main" or run.get("status") != "completed"
            or run.get("conclusion") != "success"):
        raise HandoffError("source is not a successful main push Services CI run")
    if (full_name(payload.get("repository")) != repository
            or full_name(run.get("head_repository")) != repository
            or full_name(run.get("repository")) != repository):
        raise HandoffError("source repository provenance does not match")
    sha = run.get("head_sha")
    if not isinstance(sha, str) or SHA.fullmatch(sha) is None:
        raise HandoffError("source SHA must be exactly 40 lowercase hexadecimal characters")
    return sha


def select_services(services, event):
    if not isinstance(event, str) or EVENT.fullmatch(event) is None:
        raise HandoffError("event name is invalid")
    if (not isinstance(services, list) or any(not isinstance(item, str) or SERVICE.fullmatch(item) is None
                                           for item in services)):
        raise HandoffError("service selection must be an array of module names")
    if event == "push":
        return [item for item in services if item != COMPONENT]
    if event == "workflow_run":
        return [item for item in services if item == COMPONENT]
    return list(services)


def read_json(stream):
    content = stream.read(MAX_INPUT_BYTES + 1)
    if len(content) > MAX_INPUT_BYTES:
        raise HandoffError("input exceeds the bounded payload size")
    return json.loads(content)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("source")
    select = subparsers.add_parser("select")
    select.add_argument("--event", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "source":
            event_path = os.environ.get("GITHUB_EVENT_PATH")
            if not event_path:
                raise HandoffError("GITHUB_EVENT_PATH is unavailable")
            with Path(event_path).open(encoding="utf-8") as stream:
                payload = read_json(stream)
            result = verified_source(payload, os.environ.get("GITHUB_REPOSITORY", ""),
                                     os.environ.get("GITHUB_EVENT_NAME", ""))
        else:
            result = json.dumps(select_services(read_json(sys.stdin), args.event), separators=(",", ":"))
        print(result)
        return 0
    except HandoffError as error:
        print(f"context-deploy-handoff: {error}", file=sys.stderr)
    except (OSError, ValueError):
        # Never echo a supplied path, raw event data or JSON parser excerpts.
        print("context-deploy-handoff: event or selection input is unreadable or malformed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
