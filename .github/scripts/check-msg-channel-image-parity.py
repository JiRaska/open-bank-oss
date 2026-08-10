#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: a GitOps config that DECLARES a messaging channel must not be deployed ahead of an
# image that can SERVE it.
#
# WHY THIS EXISTS
#   openbank-card-issuance-service was in CrashLoopBackOff for the better part of an hour on
#   2026-08-02, money-path, dying at boot with:
#
#     SRMSG00071: Invalid channel configuration - the `connector` attribute must be set for
#     channel `delegation-events-in`
#
#   Both halves of the change were correct in isolation. The msg-override ConfigMap carried, at
#   config_ordinal=500:
#
#     mp.messaging.incoming.delegation-events-in.group.id=cardissuance-delegation
#     mp.messaging.incoming.delegation-events-in.auto.offset.reset=earliest
#
#   which is enough for SmallRye to consider the channel DECLARED. The `connector:` line that
#   makes it servable lives in the service's own application.yaml, inside the image — and the
#   deployed image tag still pointed at a commit from eight days earlier that had neither the
#   connector nor the consumer class. A declared channel with no connector is a hard boot
#   failure, not a degraded feature: the pod cannot start at all.
#
#   Nothing caught it. The repo was self-consistent on main (both halves committed together),
#   every gate was green, and the two halves simply reached the cluster at different times
#   because the deploy gate was blocked while the ConfigMap flowed through GitOps unimpeded.
#   That is not a code defect a repo-consistency check can see — it is an ORDERING property
#   between two files that are both in git: the ConfigMap, and the image tag pinned beside it.
#
# WHAT IT CHECKS
#   For every `mp.messaging.{incoming,outgoing}.<channel>.*` key in a GitOps ConfigMap, the
#   image tag deployed for that service must point at a commit whose application.yaml declares
#   a `connector:` for that channel. The tag is a git SHA (`sandbox-<sha>`), so this is
#   answerable offline with `git show <sha>:<service>/src/main/resources/application.yaml` —
#   which is exactly the manual check that diagnosed the outage, turned into a gate.
#
#   It deliberately does NOT check the reverse (a connector with no ConfigMap override): that
#   is the normal, safe state — channels that need no group.id override have no ConfigMap entry.
#
# Run:  python3 .github/scripts/check-msg-channel-image-parity.py [--root .]

import argparse
import base64
import binascii
import os
import pathlib
import re
import subprocess
import sys

import gatelib

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

GITOPS = "openbank-infra/gitops"
CHANNEL_RE = re.compile(r"^mp\.messaging\.(?:incoming|outgoing)\.([A-Za-z0-9_-]+)\.")
IMAGE_RE = re.compile(r"image:\s*\S*/(openbank-[a-z0-9-]+):sandbox-([0-9a-f]{7,40})")


def _git_show(root: pathlib.Path, ref: str, path: str):
    p = subprocess.run(
        ["git", "show", f"{ref}:{path}"], cwd=root, capture_output=True, text=True
    )
    return p.stdout if p.returncode == 0 else None


def _api_show(repo: str, ref: str, path: str):
    """Read a file at an arbitrary commit through the GitHub contents API."""
    p = subprocess.run(
        # -X GET is required: without it `gh api -f` sends `ref` as a POST body field and
        # the contents endpoint answers 404, which reads exactly like "that commit has no
        # such file" rather than "the request was malformed".
        ["gh", "api", "-X", "GET", f"repos/{repo}/contents/{path}", "-f", f"ref={ref}",
         "--jq", ".content"],
        capture_output=True, text=True,
    )
    if p.returncode != 0 or not p.stdout.strip():
        return None
    try:
        return base64.b64decode(p.stdout.strip()).decode("utf-8", "replace")
    except (ValueError, binascii.Error):
        return None


def git_show(root: pathlib.Path, ref: str, path: str, repo: str):
    """The file at <ref>, from the local clone if it has it, else from the GitHub API.

    Local first so `./check...` works offline against a full clone. The API fallback exists
    because CI checks out with fetch-depth: 1 and the historical commits image tags point at
    are simply not there — and, unlike most remotes, GitHub will NOT serve an arbitrary sha to
    `git fetch origin <sha>` ("couldn't find remote ref"), so deepening on demand is not
    available either. The first version of this guard passed locally against a full clone and
    failed in CI against every service; the second tried the on-demand fetch and failed the
    same way. Neither was visible without running it in a shallow clone, which is the only
    honest test for this.
    """
    out = _git_show(root, ref, path)
    if out is not None:
        return out
    return _api_show(repo, ref, path) if repo else None


def channels_with_connector(app_yaml: str):
    """Channel names whose application.yaml block declares a `connector:`.

    Parsed as YAML, not scanned line-wise. The first draft walked lines tracking indentation
    and got it wrong in both directions — its own self-test caught that, which is the entire
    reason the self-test feeds it a file where one sibling channel has a connector and the
    other does not.

    Quarkus profile blocks (`"%test":`, `"%prod":`) are walked too: a channel is servable if
    ANY profile declares its connector, and being generous here is the safe direction — this
    guard exists to catch a channel nothing can serve, not to police which profile serves it.
    """
    try:
        doc = yaml.safe_load(app_yaml) or {}
    except yaml.YAMLError:
        return None  # caller decides; an unparseable file is not "no channels"

    found = set()

    def walk(node):
        if not isinstance(node, dict):
            return
        msg = ((node.get("mp") or {}).get("messaging") or {}) if isinstance(node.get("mp"), dict) else {}
        for direction in ("incoming", "outgoing"):
            block = msg.get(direction) or {}
            if isinstance(block, dict):
                for ch, cfg in block.items():
                    if isinstance(cfg, dict) and str(cfg.get("connector") or "").strip():
                        found.add(str(ch))
        for v in node.values():
            walk(v)

    walk(doc)
    return found


def declared_channels(properties_text: str):
    """Channel names a properties override declares, ignoring comments.

    `#`-prefixed lines are skipped: the ConfigMaps carry long rationale comments that mention
    example keys, and the first draft happily extracted a channel called `x` from prose for
    six different services.
    """
    out = set()
    for line in properties_text.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        m = CHANNEL_RE.match(s)
        if m:
            out.add(m.group(1))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    if args.self_test:
        return self_test()

    repo = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss")
    gitops = root / GITOPS
    if not gitops.is_dir():
        sys.stderr.write(f"::error::{GITOPS} not found — refusing to report success\n")
        return 2

    # service -> deployed image sha, and service -> {channels declared in a ConfigMap}
    images, declared = {}, {}
    for f in gatelib.rglob(gitops, "*.yaml"):
        text = f.read_text(errors="replace")
        for svc, sha in IMAGE_RE.findall(text):
            images[svc] = sha
        if "mp.messaging." in text:
            # Attribute the override to the service whose labels the manifest carries.
            m = re.search(r"app\.kubernetes\.io/name:\s*([a-z0-9-]+)", text)
            svc = f"openbank-{m.group(1)}" if m else None
            chans = declared_channels(text)
            if svc and chans:
                declared.setdefault(svc, set()).update(chans)

    if not images:
        sys.stderr.write(
            "::error::found no `image: .../openbank-*:sandbox-<sha>` pins under "
            f"{GITOPS} — this guard would then be checking nothing. If the tag format "
            "changed, update IMAGE_RE in the same commit.\n"
        )
        return 2

    errors, checked = [], 0
    for svc, channels in sorted(declared.items()):
        sha = images.get(svc)
        if not sha:
            continue  # config with no image pinned here — nothing this guard can compare
        app = git_show(root, sha, f"{svc}/src/main/resources/application.yaml", repo)
        if app is None:
            errors.append(
                f"{svc}: the deployed image is sandbox-{sha}, and that commit could not be "
                f"read even after fetching it, so this channel cannot be checked. Either the tag "
                f"does not correspond to a commit on this remote, or the service has no "
                f"application.yaml there. Unverifiable is not the same as fine."
            )
            continue
        servable = channels_with_connector(app)
        if servable is None:
            errors.append(
                f"{svc}: application.yaml at sandbox-{sha} is not parseable as YAML, so this "
                f"guard cannot tell a servable channel from a declared-only one. Unparseable "
                f"is not clean."
            )
            continue
        for ch in sorted(channels):
            checked += 1
            if ch not in servable:
                errors.append(
                    f"{svc}: GitOps config declares messaging channel `{ch}`, but the deployed "
                    f"image (sandbox-{sha}) has no `connector:` for it in application.yaml. "
                    f"SmallRye treats a configured channel with no connector as a HARD BOOT "
                    f"FAILURE (SRMSG00071), so this does not degrade the service — it stops it "
                    f"starting. Deploy an image built from a commit that declares the channel "
                    f"BEFORE, or with, the config that names it."
                )

    if errors:
        for e in errors:
            sys.stderr.write(f"::error title=Messaging channel/image parity::{e}\n")
        return 1

    print(
        f"messaging channel/image parity: {checked} channel override(s) across "
        f"{len(declared)} service(s); every one is servable by the image actually deployed."
    )
    return 0


def self_test() -> int:
    """Feed the connector scanner what it must accept and what it must flag."""
    good = """
mp:
  messaging:
    incoming:
      party-events-in:
        connector: smallrye-kafka
        topic: openbank.party.events
      delegation-events-in:
        connector: smallrye-kafka
        topic: openbank.delegation.events
"""
    bad = """
mp:
  messaging:
    incoming:
      party-events-in:
        connector: smallrye-kafka
        topic: openbank.party.events
      delegation-events-in:
        topic: openbank.delegation.events
"""
    bad_cases = []
    g = channels_with_connector(good)
    b = channels_with_connector(bad)
    checks = [
        ("both channels servable when both declare a connector",
         {"party-events-in", "delegation-events-in"} <= g),
        ("the connector-less channel is NOT reported servable",
         "delegation-events-in" not in b),
        ("its sibling still is (the scan is per channel, not per file)",
         "party-events-in" in b),
    ]
    for why, ok in checks:
        print(f"  {'ok ' if ok else 'BAD'} {why}")
        if not ok:
            bad_cases.append(why)
    if bad_cases:
        print("\n::error::self-test FAILED: " + "; ".join(bad_cases))
        return 1
    print("\nself-test: the scanner distinguishes a servable channel from a declared-only one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
