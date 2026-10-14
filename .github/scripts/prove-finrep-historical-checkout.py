#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate a reviewed main runner checkout; this is not cryptographic attestation.

No caller-selected revisions, origins or credentials. Trust in GitHub environment
must be established by the reviewed workflow, not by this script's JSON output.
"""

import hashlib
import json
import os
import re
import subprocess
import sys

REPOSITORY = "JiRaska/open-bank-oss"
WORKFLOW = (
    f"{REPOSITORY}/.github/workflows/finrep-historical-broker.yml@refs/heads/main"
)
CONSUMER_SHA = "4c86c6f729c27fabcf5431d27ee5270116df06b2"
PACT_PATH = "pacts/openbank-finrep-service-openbank-ledger-service.json"
BLOB = "9b245fa6464ff3c4ee1f8bd4a293131049b0fe1f"
DIGEST = "5b9c5e7ea079626460d0634a78605d24f4ab50a345168c8af3139e68b3cb7d86"
COUNT = 3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(*args):
    result = subprocess.run(["git", *args], capture_output=True, check=False)
    require(result.returncode == 0, "Git checkout proof failed")
    return result.stdout


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Pinned Pact has duplicate keys")
        result[key] = value
    return result


def prove():
    env = os.environ
    require(
        not any(key.startswith("GIT_") for key in env),
        "Git context overrides are not permitted",
    )
    require(
        env.get("GITHUB_ACTIONS") == "true"
        and env.get("GITHUB_REPOSITORY") == REPOSITORY
        and env.get("GITHUB_SERVER_URL") == "https://github.com"
        and env.get("GITHUB_REF") == "refs/heads/main"
        and env.get("GITHUB_EVENT_NAME") in {"push", "workflow_dispatch"}
        and env.get("GITHUB_WORKFLOW_REF") == WORKFLOW,
        "Reviewed main workflow context is required",
    )
    sha = env.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "Invalid main commit")
    require(
        env.get("GITHUB_WORKFLOW_SHA") == sha, "Workflow and provider commits differ"
    )
    run = env.get("GITHUB_RUN_ID", "")
    require(re.fullmatch(r"[1-9][0-9]*", run) is not None, "Invalid hosted run")
    require(
        git("rev-parse", "HEAD").decode().strip() == sha,
        "Checkout differs from main event",
    )
    git("merge-base", "--is-ancestor", sha, "refs/remotes/origin/main")
    git("merge-base", "--is-ancestor", CONSUMER_SHA, sha)
    flags = git("ls-files", "-v", "-z").split(b"\0")
    require(
        all(entry[:1] == b"H" for entry in flags if entry),
        "Tracked checkout has hidden or exceptional index entries",
    )
    require(not git("diff", "--name-only", "HEAD"), "Tracked checkout is dirty")
    require(
        not git("ls-files", "--others", "--exclude-standard", "-z"),
        "Untracked checkout inputs exist",
    )
    # PRE-BUILD only: even ignored outputs can contain executable overlays.
    # The reviewed workflow must fetch origin/main freshly and run this before
    # its first Gradle invocation, followed by an uncached clean build.
    require(
        not git("ls-files", "--others", "--ignored", "--exclude-standard", "-z"),
        "Ignored pre-build checkout inputs exist",
    )
    ref = f"{CONSUMER_SHA}:{PACT_PATH}"
    require(
        git("rev-parse", ref).decode().strip() == BLOB, "Original Pact blob differs"
    )
    require(
        git("cat-file", "-t", ref).decode().strip() == "blob",
        "Original Pact is not a blob",
    )
    raw = git("show", ref)
    require(hashlib.sha256(raw).hexdigest() == DIGEST, "Original Pact digest differs")
    try:
        pact = json.loads(raw, object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Pinned Pact JSON is invalid") from None
    require(
        isinstance(pact, dict)
        and pact.get("consumer", {}).get("name") == "openbank-finrep-service"
        and pact.get("provider", {}).get("name") == "openbank-ledger-service"
        and isinstance(pact.get("interactions"), list)
        and len(pact["interactions"]) == COUNT,
        "Original Pact pair or interaction count differs",
    )
    return {
        "proofKind": "pre-build-clean-checkout-not-cryptographic-attestation",
        "providerSha": sha,
        "consumerSha": CONSUMER_SHA,
        "originalBlob": BLOB,
        "contentSha256": DIGEST,
        "interactionCount": COUNT,
        "buildUrl": f"https://github.com/{REPOSITORY}/actions/runs/{run}",
    }


if __name__ == "__main__":
    try:
        require(len(sys.argv) == 1, "Caller-selected inputs are not permitted")
        print(json.dumps(prove(), sort_keys=True))
    except (ValueError, OSError):
        print("FINREP checkout proof rejected", file=sys.stderr)
        sys.exit(1)
