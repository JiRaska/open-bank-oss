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
from pathlib import Path

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


def tracked_inputs():
    inputs = {}
    entries = git("ls-files", "--stage", "-z").split(b"\0")
    for entry in entries:
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, blob, stage = metadata.split()
        require(stage == b"0" and mode in {b"100644", b"100755", b"120000"}, "Unsupported tracked input")
        path = Path(os.fsdecode(name))
        require(path.is_symlink() == (mode == b"120000"), "Tracked input type differs")
        if mode != b"120000":
            require(bool(path.stat().st_mode & 0o111) == (mode == b"100755"), "Tracked executable mode differs")
        raw = os.fsencode(os.readlink(path)) if mode == b"120000" else path.read_bytes()
        require(re.fullmatch(b"[0-9a-f]{40}", blob) is not None, "Unsupported Git object identity")
        framed = b"blob " + str(len(raw)).encode() + b"\0" + raw
        require(hashlib.sha1(framed).hexdigest() == blob.decode(), "Tracked input bytes differ from committed blob")
        inputs[os.fsdecode(name)] = {
            "mode": mode.decode(),
            "blob": blob.decode(),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
    return inputs


def prove(post_build=False):
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
    require(env.get("GITHUB_RUN_ATTEMPT") == "1", "Historical publication reruns are not permitted")
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
    untracked = git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    # PRE-BUILD only: even ignored outputs can contain executable overlays.
    # The reviewed workflow must fetch origin/main freshly and run this before
    # its first Gradle invocation, followed by an uncached clean build.
    ignored = git("ls-files", "--others", "--ignored", "--exclude-standard", "-z").split(b"\0")
    inputs = tracked_inputs()
    if post_build:
        modules = {name.split("/")[0] for name in inputs if name.endswith("/build.gradle.kts")}

        def generated(name):
            parts = Path(os.fsdecode(name)).parts
            return parts[0] in {"build", ".gradle"} or (
                len(parts) > 1 and parts[0] in modules and parts[1] == "build"
            )

        require(all(generated(name) for name in untracked + ignored if name), "Unexpected post-build overlay")
    else:
        require(not any(untracked), "Untracked checkout inputs exist")
        require(not any(ignored), "Ignored pre-build checkout inputs exist")
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
    result = {
        "proofKind": "pre-build-clean-checkout-not-cryptographic-attestation",
        "providerSha": sha,
        "consumerSha": CONSUMER_SHA,
        "originalBlob": BLOB,
        "contentSha256": DIGEST,
        "interactionCount": COUNT,
        "buildUrl": f"https://github.com/{REPOSITORY}/actions/runs/{run}",
        "trackedInputCount": len(inputs),
        "trackedInputManifestSha256": hashlib.sha256(
            json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
    }
    if post_build:
        proof_path = Path(env.get("FINREP_CHECKOUT_PROOF", ""))
        require(proof_path.is_absolute(), "Pre-build proof path is required")
        original = json.loads(proof_path.read_bytes(), object_pairs_hook=unique_object)
        require(original == result, "Source identity changed since pre-build proof")
        result["proofKind"] = "historical-finrep-main-postbuild-checkout"
    return result


if __name__ == "__main__":
    try:
        require(sys.argv[1:] in [[], ["--post-build"]], "Caller-selected inputs are not permitted")
        print(json.dumps(prove(post_build=sys.argv[1:] == ["--post-build"]), sort_keys=True))
    except (ValueError, OSError):
        print("FINREP checkout proof rejected", file=sys.stderr)
        sys.exit(1)
