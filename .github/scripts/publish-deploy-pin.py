#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR-0358: write one component's pin to deploy/sandbox as a signed GitHub App commit.
#
#   deploy   pin a freshly built image (--image-source-sha, --tag, --digest); manifests are
#            copied from the current main tip
#   repin    keep the pinned image, move the manifests to the current main tip (a push to
#            main that touched components/<component>/)
#   rollback pin an older image from an earlier pin.json (--tag, --digest,
#            --image-source-sha), manifests still from the main tip
#
# The candidate commit is built locally and run through check-deploy-pin-branch.py's
# check_commit BEFORE anything is written. Only then is the same tree sent through the
# GraphQL createCommitOnBranch mutation, which GitHub signs for an App installation token,
# with expectedHeadOid = the parent the check ran against — so a concurrent writer makes the
# mutation fail instead of silently interleaving, and we re-read and re-check. An older build
# never overwrites a newer pin: the ancestry rule in check_commit rejects it and the run
# ends as a no-op with a notice, not a failure (the newer pin is already the right answer).

import argparse
import base64
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("deploy_pin", HERE / "check-deploy-pin-branch.py")
pins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pins)

BRANCH = "deploy/sandbox"
MUTATION = """
mutation($input: CreateCommitOnBranchInput!) {
  createCommitOnBranch(input: $input) { commit { oid signature { isValid state } } }
}"""


def git(*args, text=True):
    return pins.git(".", *args, text=text)


def graphql(token, query, variables):
    request = urllib.request.Request(
        "https://api.github.com/graphql",
        data=json.dumps({"query": query, "variables": variables}).encode(),
        headers={"Authorization": f"bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.load(response)
    return body


def candidate_commit(parent, component, files):
    """Build the candidate tree on top of `parent` in a throwaway index; return its commit sha."""
    with tempfile.TemporaryDirectory() as tmp:
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(tmp) / "index")}

        def g(*args, stdin=None):
            return subprocess.run(["git", *args], env=env, input=stdin, capture_output=True,
                                  check=True).stdout.decode().strip()

        g("read-tree", parent)
        prefix = f"{pins.PINS_DIR}/{component}/"
        stale = [p for p in g("ls-files", prefix).splitlines() if p[len(prefix):] not in files]
        if stale:
            g("rm", "--cached", "-q", *stale)
        for rel, data in files.items():
            blob = g("hash-object", "-w", "--stdin", stdin=data)
            g("update-index", "--add", "--cacheinfo", f"100644,{blob},{prefix}{rel}")
        tree = g("write-tree")
        commit = subprocess.run(
            ["git", "-c", "user.name=deploy-pin-check", "-c", "user.email=deploy-pin-check@invalid",
             "commit-tree", tree, "-p", parent, "-m", "candidate"],
            capture_output=True, check=True).stdout.decode().strip()
        return commit, stale


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["deploy", "repin", "rollback"])
    parser.add_argument("--component", required=True)
    parser.add_argument("--image-source-sha")
    parser.add_argument("--tag")
    parser.add_argument("--digest")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--dry-run", action="store_true", help="check the candidate, write nothing")
    args = parser.parse_args()
    token = os.environ.get("DEPLOY_PIN_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not args.dry_run and (not token or not repo):
        print("::error::DEPLOY_PIN_TOKEN (GitHub App installation token) and GITHUB_REPOSITORY are "
              "required; a deploy pin is only ever written as a signed App commit")
        return 1

    for attempt in range(1, 4):
        git("fetch", "--no-tags", "origin", f"+refs/heads/main:refs/remotes/origin/main",
            f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}")
        main_sha = git("rev-parse", "origin/main").strip()
        parent = git("rev-parse", f"origin/{BRANCH}").strip()
        current = pins.read_pin(".", parent, args.component)
        if args.mode == "repin":
            if current is None:
                print(f"::error::no pin for {args.component} on {BRANCH} to re-pin")
                return 1
            image_src, tag, digest, rollback = (current["image_source_sha"], current["tag"],
                                                current["digest"], current["rollback"])
        else:
            if not (args.image_source_sha and args.tag and args.digest):
                print("::error::--image-source-sha, --tag and --digest are required")
                return 1
            image_src, tag, digest = args.image_source_sha, args.tag, args.digest
            rollback = args.mode == "rollback"
        try:
            files = pins.render(".", args.component, main_sha, image_src, tag, digest, rollback)
        except pins.PinError as error:
            print(f"::error::cannot render the pin: {error}")
            return 1
        candidate, stale = candidate_commit(parent, args.component, files)
        if git("rev-parse", f"{candidate}^{{tree}}") == git("rev-parse", f"{parent}^{{tree}}"):
            print(f"{args.component} on {BRANCH} already pins exactly this; nothing to do")
            return 0
        try:
            pins.check_commit(".", candidate, parent, "origin/main")
        except pins.PinError as error:
            if "moves backwards" in str(error):
                print(f"::notice::{BRANCH} already pins a newer {args.component}: {error}. Nothing written.")
                return 0
            print(f"::error::deploy-pin-branch-contract rejects this pin: {error}")
            return 1
        if args.dry_run:
            print(f"dry run: candidate {candidate[:12]} passes the contract; nothing written")
            return 0

        subject = {"deploy": "deploy", "repin": "re-pin manifests", "rollback": "rollback"}[args.mode]
        message = {
            "headline": f"chore({args.component}): {subject} {tag}",
            "body": (f"image_source_sha {image_src}\nmanifest_sha {main_sha}\ndigest {digest}\n"
                     f"{args.run_url}\n\nADR-0358 deploy pin, written by admin-ui-deploy.yml."),
        }
        prefix = f"{pins.PINS_DIR}/{args.component}/"
        variables = {"input": {
            "branch": {"repositoryNameWithOwner": repo, "branchName": BRANCH},
            "expectedHeadOid": parent,
            "message": message,
            "fileChanges": {
                "additions": [{"path": prefix + p, "contents": base64.b64encode(d).decode()}
                              for p, d in sorted(files.items())],
                "deletions": [{"path": p} for p in stale],
            },
        }}
        body = graphql(token, MUTATION, variables)
        if body.get("errors"):
            text = json.dumps(body["errors"])
            if "expected" in text.lower() and "head" in text.lower() and attempt < 3:
                print(f"::notice::{BRANCH} moved while pinning (attempt {attempt}); re-reading and re-checking")
                time.sleep(3 * attempt)
                continue
            print(f"::error::createCommitOnBranch failed: {text}")
            return 1
        commit = body["data"]["createCommitOnBranch"]["commit"]
        signature = commit.get("signature") or {}
        if not signature.get("isValid"):
            print(f"::error::pin commit {commit['oid']} is not signed/verified ({signature}); the "
                  f"{BRANCH} ruleset requires signatures. Is DEPLOY_PIN_TOKEN an App installation token?")
            return 1
        print(f"pinned {args.component} {tag} ({digest}) at {BRANCH}@{commit['oid']}, manifests "
              f"from main@{main_sha[:12]}")
        out = os.environ.get("GITHUB_OUTPUT")
        if out:
            with open(out, "a") as handle:
                handle.write(f"pin_commit={commit['oid']}\n")
        return 0
    print(f"::error::{BRANCH} kept moving; gave up after 3 attempts")
    return 1


if __name__ == "__main__":
    sys.exit(main())
