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
import time

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


_TRANSIENT_RETRIES = 2
_TRANSIENT_BACKOFF_SECONDS = 1.0


def _api_show(repo: str, ref: str, path: str):
    """Read a file at an arbitrary commit through the GitHub contents API.

    A 404 ("No commit found for the ref ..." or "Not Found") is terminal: the ref genuinely
    has no such file, or does not exist on this remote, and retrying cannot change that.
    Anything else — a rate limit (403/429), a 5xx, or a bare network failure with no HTTP
    status in `gh`'s stderr at all — is retried with a short backoff before giving up, because
    those are exactly the failures a single high-concurrency CI window produces and self-heal
    within seconds. Measured live on #5270: the identical lookup for the identical commit
    failed once during a 240-runs-in-10-minutes window and succeeded 6 minutes later with
    nothing about the commit having changed — a transient failure was misreported as "that
    commit could not be read", which reads exactly like a genuinely unreachable one.
    """
    last_stderr = ""
    for attempt in range(1 + _TRANSIENT_RETRIES):
        p = subprocess.run(
            # -X GET is required: without it `gh api -f` sends `ref` as a POST body field and
            # the contents endpoint answers 404, which reads exactly like "that commit has no
            # such file" rather than "the request was malformed".
            ["gh", "api", "-X", "GET", f"repos/{repo}/contents/{path}", "-f", f"ref={ref}",
             "--jq", ".content"],
            capture_output=True, text=True,
        )
        if p.returncode == 0 and p.stdout.strip():
            try:
                return base64.b64decode(p.stdout.strip()).decode("utf-8", "replace")
            except (ValueError, binascii.Error):
                return None
        if p.returncode == 0:
            # Success but an empty body — e.g. the path is a directory, not a file. Not
            # something a retry can fix.
            return None
        last_stderr = p.stderr
        if "(HTTP 404)" in p.stderr or "Not Found" in p.stderr:
            return None  # terminal: no such ref/file, retrying cannot help
        if attempt < _TRANSIENT_RETRIES:
            time.sleep(_TRANSIENT_BACKOFF_SECONDS * (attempt + 1))
    sys.stderr.write(
        f"::warning::gh api contents lookup for {repo}@{ref}:{path} failed "
        f"{1 + _TRANSIENT_RETRIES} times with no HTTP 404 (last error: "
        f"{last_stderr.strip() or '(no stderr)'}) — treating as unreadable after retrying "
        f"transient failures.\n"
    )
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

    retry_cases = _self_test_api_show_retry()
    bad_cases.extend(retry_cases)

    if bad_cases:
        print("\n::error::self-test FAILED: " + "; ".join(bad_cases))
        return 1
    print("\nself-test: the scanner distinguishes a servable channel from a declared-only one.")
    return 0


def _self_test_api_show_retry():
    """`_api_show` must retry a transient failure and give up immediately on a real 404.

    Regression cover for #5270: a single non-404 `gh api` failure (rate limit, 5xx, a bare
    network error) must not be reported the same way as a ref that genuinely does not exist.
    """
    import unittest.mock as mock

    bad = []
    orig_sleep = time.sleep
    time.sleep = lambda _s: None  # keep the self-test fast; retry COUNT is what's asserted
    try:
        transient = mock.Mock(returncode=1, stdout="", stderr="gh: … (HTTP 503)")
        ok = mock.Mock(returncode=0, stdout="aGVsbG8=\n", stderr="")
        with mock.patch("subprocess.run", side_effect=[transient, transient, ok]) as m:
            out = _api_show("owner/repo", "deadbeef", "some/path")
            if out != "hello":
                bad.append(f"a transient failure that later succeeds should return the "
                            f"content, got {out!r}")
            if m.call_count != 3:
                bad.append(f"expected 3 calls (2 transient + 1 success), got {m.call_count}")

        not_found = mock.Mock(returncode=1, stdout="", stderr="gh: No commit found (HTTP 404)")
        with mock.patch("subprocess.run", side_effect=[not_found, transient, ok]) as m:
            out = _api_show("owner/repo", "0000000", "some/path")
            if out is not None:
                bad.append(f"a 404 must be terminal (no retry), got {out!r}")
            if m.call_count != 1:
                bad.append(f"a 404 must not be retried, got {m.call_count} call(s)")

        always_transient = mock.Mock(returncode=1, stdout="", stderr="gh: … (HTTP 429)")
        with mock.patch("subprocess.run", return_value=always_transient) as m:
            out = _api_show("owner/repo", "deadbeef", "some/path")
            if out is not None:
                bad.append(f"exhausting all retries must still return None, got {out!r}")
            if m.call_count != 1 + _TRANSIENT_RETRIES:
                bad.append(
                    f"expected {1 + _TRANSIENT_RETRIES} attempts before giving up, "
                    f"got {m.call_count}"
                )
    finally:
        time.sleep = orig_sleep

    for msg in bad:
        print(f"  BAD {msg}")
    if not bad:
        print("  ok  a transient gh-api failure is retried; a real 404 is not")
    return bad


if __name__ == "__main__":
    sys.exit(main())
