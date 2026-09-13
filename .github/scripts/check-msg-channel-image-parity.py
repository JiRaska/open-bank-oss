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
# HOW IT READS A HISTORICAL COMMIT, AND WHY THAT IS A RATE-LIMIT QUESTION (issue #6290)
#   CI checks out with fetch-depth: 1, so the commit an image tag points at is not in the
#   local clone. This gate used to read every one of them through the GitHub CONTENTS API —
#   one REST call per service with a channel override, ~21 per run, on a workflow that runs on
#   every PR and every push. In Actions those calls spend the `GITHUB_TOKEN` installation
#   quota, which GitHub documents at 1,000 requests/hour PER REPOSITORY for a repo outside
#   Enterprise Cloud, and which every concurrent run shares. So under load this gate was both a
#   large consumer of that quota and its most visible victim: it answered `HTTP 403 API rate
#   limit exceeded for installation` and rendered that as "that commit could not be read",
#   i.e. as a finding about the PR's own diff.
#
#   The earlier note here said GitHub "will NOT serve an arbitrary sha to `git fetch origin
#   <sha>`". That conclusion came from a broken probe: the image tag carries an ABBREVIATED
#   sha, and git's want-sha1 fetch requires the full 40 characters — an abbreviated one fails
#   with the identical `couldn't find remote ref`. Measured 2026-08-26 against a real
#   `--depth 1` clone of this repository: the abbreviated form fails and the full form
#   succeeds, after which `git show <sha>:<path>` reads the file locally.
#
#   So the content is now read over git transport, which is not REST-rate-limited at all. The
#   only REST call left is one abbreviated -> full sha resolution per DISTINCT deployed sha
#   (7 today, against 21 content lookups before), and it falls back to the contents API when
#   the fetch is unavailable, so an offline or restricted environment behaves as before.
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


class Unreadable:
    """A file that could not be read, carrying WHY.

    The whole point of #6290: "I read the commit and it disagrees with the config" and "a
    shared CI quota is exhausted so I read nothing" are different states, and collapsing both
    into a bare `None` is what made an infrastructure fact render as a finding about the
    author's diff. `kind` is what separates them; nothing here decides severity.
    """

    QUOTA = "quota"          # the installation API quota is exhausted — true of every PR in flight
    UNREADABLE = "unreadable"  # the commit or file genuinely could not be read

    def __init__(self, kind: str, detail: str = ""):
        self.kind = kind
        self.detail = detail.strip()

    def __repr__(self):  # pragma: no cover - diagnostics only
        return f"Unreadable({self.kind}, {self.detail[:60]!r})"


def _is_quota_exhausted(stderr: str) -> bool:
    """A rate limit and a permission denial are BOTH HTTP 403 and differ only in the message.

    Same rule as `.github/scripts/spot-kill-retry.sh`: classify on the text, never on the
    status code. A permission answer is deliberately NOT quota — retrying or waiting cannot
    fix it, and reporting it as a capacity fact would hide a real misconfiguration.
    """
    t = stderr or ""
    return (
        "API rate limit exceeded" in t
        or "secondary rate limit" in t
        or "Secondary rate limit" in t
        or "(HTTP 429)" in t
    )


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
                return Unreadable(Unreadable.UNREADABLE, "content is not valid base64 UTF-8")
        if p.returncode == 0:
            # Success but an empty body — e.g. the path is a directory, not a file. Not
            # something a retry can fix.
            return Unreadable(Unreadable.UNREADABLE, "empty body (path is not a file?)")
        last_stderr = p.stderr
        if "(HTTP 404)" in p.stderr or "Not Found" in p.stderr:
            # terminal: no such ref/file, retrying cannot help
            return Unreadable(Unreadable.UNREADABLE, p.stderr)
        if attempt < _TRANSIENT_RETRIES:
            time.sleep(_TRANSIENT_BACKOFF_SECONDS * (attempt + 1))
    kind = Unreadable.QUOTA if _is_quota_exhausted(last_stderr) else Unreadable.UNREADABLE
    sys.stderr.write(
        f"::warning::gh api contents lookup for {repo}@{ref}:{path} failed "
        f"{1 + _TRANSIENT_RETRIES} times with no HTTP 404 (last error: "
        f"{last_stderr.strip() or '(no stderr)'}) — classified as {kind}.\n"
    )
    return Unreadable(kind, last_stderr)


_FULL_SHA_CACHE: dict = {}
_FETCHED: set = set()


def _resolve_full_sha(root: pathlib.Path, repo: str, ref: str):
    """Abbreviated sha -> full 40-char sha. ONE REST call per DISTINCT sha, memoised.

    git's want-sha1 fetch takes nothing shorter than 40 characters, and an image tag carries
    an abbreviation, so this resolution is unavoidable — but it is per sha, not per service,
    which is what turns 21 calls a run into 7.
    """
    if len(ref) == 40 and all(c in "0123456789abcdef" for c in ref.lower()):
        return ref
    if ref in _FULL_SHA_CACHE:
        return _FULL_SHA_CACHE[ref]
    # A full clone can answer this with no network at all.
    p = subprocess.run(["git", "rev-parse", f"{ref}^{{commit}}"], cwd=root,
                       capture_output=True, text=True)
    if p.returncode == 0 and len(p.stdout.strip()) == 40:
        _FULL_SHA_CACHE[ref] = p.stdout.strip()
        return _FULL_SHA_CACHE[ref]
    if not repo:
        return None
    p = subprocess.run(["gh", "api", f"repos/{repo}/commits/{ref}", "--jq", ".sha"],
                       capture_output=True, text=True)
    if p.returncode == 0 and len(p.stdout.strip()) == 40:
        _FULL_SHA_CACHE[ref] = p.stdout.strip()
        return _FULL_SHA_CACHE[ref]
    if _is_quota_exhausted(p.stderr):
        # Do NOT memoise: the quota resets, and caching the failure would make one unlucky
        # moment permanent for the rest of the run.
        return Unreadable(Unreadable.QUOTA, p.stderr)
    _FULL_SHA_CACHE[ref] = None
    return None


def _fetch_commit(root: pathlib.Path, full_sha: str) -> bool:
    """Bring one commit into a shallow clone over git transport (no REST quota spent)."""
    if full_sha in _FETCHED:
        return True
    p = subprocess.run(["git", "fetch", "--depth", "1", "--quiet", "origin", full_sha],
                       cwd=root, capture_output=True, text=True)
    if p.returncode == 0:
        _FETCHED.add(full_sha)
        return True
    return False


def git_show(root: pathlib.Path, ref: str, path: str, repo: str):
    """The file at <ref>: local clone, else a git fetch of that one commit, else the API.

    Order matters and is the #6290 fix. Local first so `./check...` works offline against a
    full clone. Then a `git fetch --depth 1 origin <full-sha>` — measured to work against this
    remote, contrary to the note this function used to carry — because git transport spends no
    REST quota, and this gate reading ~21 files a run was the single largest identified
    consumer of a 1,000/hour repository budget shared by every concurrent run. The contents API
    remains as the fallback, so nothing that worked before stops working.

    Returns the file's text, or an `Unreadable` saying WHY there is none.
    """
    out = _git_show(root, ref, path)
    if out is not None:
        return out

    full = _resolve_full_sha(root, repo, ref)
    if isinstance(full, Unreadable):
        return full
    if full and _fetch_commit(root, full):
        out = _git_show(root, full, path)
        if out is not None:
            return out
        # The commit is present and simply has no such file. Terminal, and free to state.
        return Unreadable(Unreadable.UNREADABLE,
                          f"commit {full[:9]} fetched, but it has no {path}")

    if not repo:
        return Unreadable(Unreadable.UNREADABLE, "no GITHUB_REPOSITORY and not in the clone")
    return _api_show(repo, ref, path)


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

    errors, quota_blocked, checked = [], [], 0
    for svc, channels in sorted(declared.items()):
        sha = images.get(svc)
        if not sha:
            continue  # config with no image pinned here — nothing this guard can compare
        app = git_show(root, sha, f"{svc}/src/main/resources/application.yaml", repo)
        if isinstance(app, Unreadable):
            if app.kind == Unreadable.QUOTA:
                # NOT a finding about this PR. See the QUOTA branch below.
                quota_blocked.append(f"{svc} @ sandbox-{sha}")
            else:
                errors.append(
                    f"{svc}: the deployed image is sandbox-{sha}, and that commit could not be "
                    f"read even after fetching it, so this channel cannot be checked. Either "
                    f"the tag does not correspond to a commit on this remote, or the service "
                    f"has no application.yaml there ({app.detail or 'no detail'}). "
                    f"Unverifiable is not the same as fine."
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

    # The QUOTA branch is reported FIRST and separately, and it never borrows the parity
    # gate's wording. #6290: a shared installation quota being exhausted is a fact about CI
    # capacity that is true of every PR in flight; rendering it as "that commit could not be
    # read" sent authors to debug their own diff. It still is not a pass — it exits non-zero —
    # but the first line names the real cause, and it is distinguishable from a real finding by
    # the title, the text and the absence of any service/channel verdict.
    if quota_blocked:
        sys.stderr.write(
            "::error title=CI API quota exhausted — NOT a finding about this PR::"
            "The GitHub installation API quota is exhausted, so this gate read nothing and "
            "reached no verdict about your diff. In Actions this quota is shared by every "
            "concurrent run in the repository (GitHub documents GITHUB_TOKEN at 1,000 "
            "requests/hour per repository outside Enterprise Cloud), so it is a function of "
            "fleet-wide CI load, not of your change. Your own token being healthy proves "
            "nothing. Re-run this job once the queue has drained; if it recurs on a quiet "
            "queue, that is a real regression and issue #6290 is the thread. "
            f"Could not resolve: {'; '.join(quota_blocked)}.\n"
        )
        # Reported even when a genuine finding also exists, so a real defect is never hidden
        # behind an infrastructure notice.
        for e in errors:
            sys.stderr.write(f"::error title=Messaging channel/image parity::{e}\n")
        return 1

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
            if not (isinstance(out, Unreadable) and out.kind == Unreadable.UNREADABLE):
                bad.append(f"a 404 must be terminal and UNREADABLE (not quota), got {out!r}")
            if m.call_count != 1:
                bad.append(f"a 404 must not be retried, got {m.call_count} call(s)")

        always_transient = mock.Mock(returncode=1, stdout="", stderr="gh: … (HTTP 429)")
        with mock.patch("subprocess.run", return_value=always_transient) as m:
            out = _api_show("owner/repo", "deadbeef", "some/path")
            if not isinstance(out, Unreadable):
                bad.append(f"exhausting all retries must return an Unreadable, got {out!r}")
            if m.call_count != 1 + _TRANSIENT_RETRIES:
                bad.append(
                    f"expected {1 + _TRANSIENT_RETRIES} attempts before giving up, "
                    f"got {m.call_count}"
                )

        # #6290, the distinction the whole change exists for. A rate limit and a permission
        # denial are both HTTP 403; only the text separates them, and calling a permission
        # answer "capacity" would hide a real misconfiguration.
        rate_limited = mock.Mock(
            returncode=1, stdout="",
            stderr="gh: API rate limit exceeded for installation ... (HTTP 403)")
        with mock.patch("subprocess.run", return_value=rate_limited):
            out = _api_show("owner/repo", "deadbeef", "some/path")
            if not (isinstance(out, Unreadable) and out.kind == Unreadable.QUOTA):
                bad.append(f"an installation rate limit must classify as QUOTA, got {out!r}")

        denied = mock.Mock(
            returncode=1, stdout="",
            stderr="gh: Resource not accessible by integration (HTTP 403)")
        with mock.patch("subprocess.run", return_value=denied):
            out = _api_show("owner/repo", "deadbeef", "some/path")
            if not (isinstance(out, Unreadable) and out.kind == Unreadable.UNREADABLE):
                bad.append(
                    f"a PERMISSION 403 must NOT be reported as a quota fact, got {out!r}")

    finally:
        time.sleep = orig_sleep

    for msg in bad:
        print(f"  BAD {msg}")
    if not bad:
        print("  ok  a transient gh-api failure is retried; a real 404 is not")
        print("  ok  an installation rate limit is QUOTA; a permission 403 is not")
    return bad


if __name__ == "__main__":
    sys.exit(main())
