#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Deploy window: at most one bot deploy commit lands on main per window.

WHY. `main-protection` requires PR branches to be up to date with main. Every bot deploy
commit (`chore(gitops): auto-deploy ...`, `chore(admin-ui): deploy ...`) therefore makes every
open PR `behind` and restarts its required checks. Measured over 2026-09-23T09:00Z ..
2026-09-30T09:00Z: 300 of 675 first-parent commits on main were bot deploy commits.

MECHANISM. Nothing about HOW a deploy is built, gated, opened or recorded changes. Only the
moment a deploy PR's auto-merge is ARMED does:

  * `decide`  — pure decision retained for self-tests and manual diagnosis. Deploy producers
                never arm directly, because independent reads can both see no armed PR.
  * `flush`   — the sole auto-merge writer, called by deploy-window-flush.yml after either
                deploy workflow completes and on a short recovery cron. Picks the OLDEST deferred
                deploy PR once the window has elapsed and nothing is armed. The caller re-runs
                supersede-deploy-prs.sh (ancestry + coverage) on it before arming.
                A successful manual producer run expedites only its matching PR.
  * `carry`   — called by auto-deploy.yml's rewrite step. Re-applies the image pins of every
                open, UNARMED older gitops deploy PR onto the new PR, so the newest PR covers
                every pending service and supersede-deploy-prs.sh closes the older ones. That
                is what turns "several pushes inside one window" into ONE commit. A pin is
                carried only while main still holds the tag that PR replaced — if main moved
                that service since, the old pin is stale and carrying it would rewind it. It is
                also refused when build inputs changed after that image's source commit.
  * `verify`  — before either deploy workflow arms a GitOps PR, proves every image in its diff
                still represents the current main build inputs. Unknown sources fail closed.

NO LOST DEPLOY. A deferred deploy is an open PR: the same durable record the pipeline used
before this change. Nothing here closes a PR; the only closer is supersede-deploy-prs.sh, which
closes a PR only when a newer one covers every file it touches — and `carry` is what makes the
newer one cover them.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Callable
from functools import lru_cache

DEFAULT_WINDOW_SECONDS = 1800
DEPLOY_PREFIXES = ("chore/gitops-auto-deploy-", "chore/admin-ui-deploy-")
DEPLOY_SUBJECTS = ("chore(gitops): auto-deploy ", "chore(admin-ui): deploy ")
PIN_RE = re.compile(r"(?P<image>[A-Za-z0-9.-]+/openbank-[A-Za-z0-9._/-]+):(?P<tag>sandbox-[A-Za-z0-9._-]+)")


def decide(event: str, now: int, last_deploy: int | None, armed_open: bool, window: int) -> tuple[str, str]:
    if armed_open:
        return "DEFER", "a deploy PR is already armed; this one waits for the next window"
    if event == "workflow_dispatch":
        return "ARM", "manual workflow_dispatch deploys immediately"
    if last_deploy is None:
        return "ARM", "no recent deploy commit on main"
    age = now - last_deploy
    if age >= window:
        return "ARM", f"last deploy commit is {age}s old (window {window}s)"
    return "DEFER", f"last deploy commit is {age}s old, window {window}s — flush arms it at {last_deploy + window}"


def manual_deploy_head(event: dict) -> str | None:
    """Identify only a successful manual producer run from GitHub's workflow_run event."""
    run = event.get("workflow_run") or {}
    producers = {
        "Auto deploy": (".github/workflows/auto-deploy.yml", DEPLOY_PREFIXES[0]),
        "Admin-UI deploy": (".github/workflows/admin-ui-deploy.yml", DEPLOY_PREFIXES[1]),
    }
    producer = producers.get(run.get("name"))
    # Match the exact canonical file path; a different workflow can reuse the display name.
    # Auto deploy also supports workflow_dispatch from a recovery ref.
    path = run.get("path")
    sha = run.get("head_sha") or ""
    repository = (event.get("repository") or {}).get("full_name")
    if (event.get("action") != "completed" or run.get("event") != "workflow_dispatch"
            or run.get("conclusion") != "success" or not producer or path != producer[0]
            or not repository
            or not re.fullmatch(r"[0-9a-f]{40}", sha)
            or (run.get("head_repository") or {}).get("full_name") != repository):
        return None
    return producer[1] + sha


def flush(prs: list[dict], now: int, last_deploy: int | None, window: int,
          manual_head: str | None = None) -> dict | None:
    """Return the deferred PR to arm now, or None. prs: [{number, head, created_at, armed}]."""
    deploy = [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES)]
    if any(p.get("armed") for p in deploy):
        return None
    pending = sorted((p for p in deploy if not p.get("armed")), key=lambda p: (p["created_at"], p["number"]))
    if not pending:
        return None
    if manual_head:
        manual = next((p for p in pending if p["head"] == manual_head), None)
        if manual:
            # A newer PR of the same kind may already supersede this run. Never expedite a
            # stale pin merely because its source workflow was manually dispatched.
            kind = next(x for x in DEPLOY_PREFIXES if manual_head.startswith(x))
            newest = [p for p in pending if p["head"].startswith(kind)][-1]
            if newest is manual:
                return manual
    verdict, _ = decide("flush", now, last_deploy, False, window)
    if verdict != "ARM":
        return None
    # The kind (gitops / admin-ui) that has waited longest goes first, and within it the NEWEST
    # PR is armed: it carries every older pin of its kind, so supersede-deploy-prs.sh closes the
    # older ones. Arming the oldest instead would hit supersede's STALE_KEEP refusal forever.
    prefix = next(x for x in DEPLOY_PREFIXES if pending[0]["head"].startswith(x))
    return [p for p in pending if p["head"].startswith(prefix)][-1]


def pins_from_diff(diff: str) -> list[tuple[str, str, str, str]]:
    """(file, image, old_tag, new_tag) for every image pin a unified diff changes."""
    out, cur, removed = [], None, {}
    for line in diff.splitlines():
        if line.startswith("+++ "):
            cur = line[4:].removeprefix("b/")
            removed = {}
        elif line.startswith("--- "):
            continue
        elif line.startswith("-") and cur:
            for m in PIN_RE.finditer(line):
                removed.setdefault(m["image"], m["tag"])
        elif line.startswith("+") and cur:
            for m in PIN_RE.finditer(line):
                old = removed.get(m["image"])
                if old and old != m["tag"]:
                    out.append((cur, m["image"], old, m["tag"]))
    return list(dict.fromkeys(out))


@lru_cache(maxsize=128)
def global_build_inputs_unchanged(root: str, source: str) -> bool:
    """Cheap rejection shared by every service built from the same source commit."""
    result = subprocess.run(
        ["git", "diff", "--quiet", source, "HEAD", "--", "build-logic", "gradle", "config"],
        cwd=root, capture_output=True, check=False,
    )
    return result.returncode == 0


def image_source_is_current(root: str, image: str, tag: str) -> bool:
    """Only carry an image when its source still builds the same service artifact."""
    service = image.rsplit("/", 1)[-1]
    match = re.fullmatch(r"sandbox-([0-9a-f]{8})", tag)
    if not service.startswith("openbank-") or not match:
        return False
    source = subprocess.run(
        ["git", "rev-parse", "--verify", f"{match[1]}^{{commit}}"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    if source.returncode != 0:
        return False
    if not global_build_inputs_unchanged(root, source.stdout.strip()):
        return False
    equivalent = subprocess.run(
        ["bash", ".github/scripts/pact-version-tree-equivalent.sh", service,
         source.stdout.strip(), "HEAD"],
        cwd=root, capture_output=True, text=True, check=False,
    )
    return equivalent.returncode == 0


def carry(root: str, diff: str, skip_images: set[str],
          fresh: Callable[[str, str], bool]) -> list[str]:
    """Apply an older PR's pins only when main and its image source remain equivalent."""
    carried = []
    for rel, image, old, new in pins_from_diff(diff):
        if image in skip_images:
            continue  # this run pins it itself — the newer build wins
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        text = open(path, encoding="utf-8").read()
        cur = {m["tag"] for m in PIN_RE.finditer(text) if m["image"] == image}
        if cur != {old}:
            continue  # main moved this service since that PR was cut: carrying would rewind it
        if not fresh(image, new):
            continue  # a newer build input changed; this image cannot be carried safely
        open(path, "w", encoding="utf-8").write(text.replace(f"{image}:{old}", f"{image}:{new}"))
        carried.append(f"{image}:{new} in {rel}")
    return carried


def verify_pins(diff: str, fresh: Callable[[str, str], bool]) -> list[str]:
    """Return the first stale or unverifiable pin; one is enough to refuse arming."""
    pins = pins_from_diff(diff)
    if not pins:
        return ["no image pins found in the GitOps diff"]
    for rel, image, _old, new in pins:
        if not fresh(image, new):
            return [f"{image.rsplit('/', 1)[-1]}:{new} in {rel}"]
    return []


def last_deploy_from_commits(commits: list[dict]) -> int | None:
    """commits: [{message, epoch}] newest-first, as the caller reads them from main."""
    for c in commits:
        if c["message"].startswith(DEPLOY_SUBJECTS):
            return int(c["epoch"])
    return None


# ── self-test ─────────────────────────────────────────────────────────────────────────────────
def self_test() -> int:
    fails = []

    def check(name, cond):
        print(f"self-test {'OK  ' if cond else 'FAIL'} {name}")
        if not cond:
            fails.append(name)

    W, T = 1800, 1_000_000
    # 1. single push, quiet main: armed immediately (no added latency for sparse traffic)
    check("single push after a quiet window -> ARM", decide("push", T, T - 4000, False, W)[0] == "ARM")
    # 2. second push inside the window: deferred, then flushed at the window
    check("push inside the window -> DEFER", decide("push", T, T - 60, False, W)[0] == "DEFER")
    check("push while another deploy PR is armed -> DEFER", decide("push", T, T - 9000, True, W)[0] == "DEFER")
    # 3. manual dispatch bypasses the time window, but never the single armed-PR guard
    check("workflow_dispatch -> ARM inside the window", decide("workflow_dispatch", T, T - 1, False, W)[0] == "ARM")
    check("workflow_dispatch waits behind an armed PR", decide("workflow_dispatch", T, T - 1, True, W)[0] == "DEFER")
    # 4. nothing pending -> no commit
    check("flush with nothing pending -> no PR", flush([], T, T - 9000, W) is None)
    check("flush ignores non-deploy PRs",
          flush([{"number": 9, "head": "feat/x", "created_at": "a", "armed": False}], T, None, W) is None)
    prs = [
        {"number": 12, "head": "chore/gitops-auto-deploy-b", "created_at": "2026-09-30T10:05:00Z", "armed": False},
        {"number": 11, "head": "chore/admin-ui-deploy-a", "created_at": "2026-09-30T10:01:00Z", "armed": False},
    ]
    check("flush inside the window -> nothing yet", flush(prs, T, T - 60, W) is None)
    pick = flush(prs, T, T - W, W)
    check("flush at the window -> the longest-waiting kind first", pick is not None and pick["number"] == 11)
    prs2 = prs + [{"number": 14, "head": "chore/admin-ui-deploy-d", "created_at": "2026-09-30T10:09:00Z", "armed": False}]
    pick = flush(prs2, T, T - W, W)
    check("flush arms the NEWEST PR of that kind (it supersedes the older)", pick is not None and pick["number"] == 14)
    check("flush never arms a second PR while one is armed",
          flush(prs + [{"number": 13, "head": "chore/admin-ui-deploy-c", "created_at": "z", "armed": True}], T, None, W) is None)
    # Exercise the same CLI and event file that the serialized workflow uses. An unrelated
    # older PR must not steal a manual run's exemption, and routine events keep the window.
    with tempfile.TemporaryDirectory() as d:
        sha = "a" * 40
        manual_pr = {"number": 21, "head": DEPLOY_PREFIXES[0] + sha,
                     "created_at": "2026-09-30T10:10:00Z", "armed": False}
        event = {"action": "completed", "repository": {"full_name": "example/open-bank"},
                 "workflow_run": {"name": "Auto deploy", "event": "workflow_dispatch",
                                  "conclusion": "success", "head_sha": sha,
                                  "path": ".github/workflows/auto-deploy.yml", "head_branch": "main",
                                  "head_repository": {"full_name": "example/open-bank"}}}
        paths = {name: os.path.join(d, name + ".json") for name in ("prs", "commits", "event")}
        def run_fixture(prs_fixture, event_fixture):
            for name, value in (("prs", prs_fixture), ("commits", [{"message": DEPLOY_SUBJECTS[0] + "old", "epoch": T - 60}]),
                                ("event", event_fixture)):
                with open(paths[name], "w", encoding="utf-8") as out:
                    json.dump(value, out)
            result = subprocess.run([sys.executable, __file__, "flush", "--prs-json", paths["prs"],
                                     "--commits-json", paths["commits"], "--event-json", paths["event"],
                                     "--now", str(T), "--window", str(W)], capture_output=True, text=True)
            return result.returncode, result.stdout
        check("manual producer completion expedites only its matching PR inside the window",
              run_fixture(prs + [manual_pr], event) == (0, "arm=21\narm_head=" + manual_pr["head"] + "\n"))
        check("manual producer completion cannot expedite an unrelated PR",
              run_fixture(prs, event) == (0, "arm=\narm_head=\n"))
        check("scheduled producer completion remains inside the window",
              run_fixture([manual_pr], {**event, "workflow_run": {**event["workflow_run"], "event": "schedule"}})[1]
              == "arm=\narm_head=\n")
        check("failed manual producer completion remains inside the window",
              run_fixture([manual_pr], {**event, "workflow_run": {**event["workflow_run"], "conclusion": "failure"}})[1]
              == "arm=\narm_head=\n")
        check("another repository's manual completion remains inside the window",
              run_fixture([manual_pr], {**event, "workflow_run": {**event["workflow_run"],
                                                               "head_repository": {"full_name": "other/open-bank"}}})[1]
              == "arm=\narm_head=\n")
        check("same-name manual completion from another workflow remains inside the window",
              run_fixture([manual_pr], {**event, "workflow_run": {**event["workflow_run"],
                                                               "path": ".github/workflows/other.yml"}})[1]
              == "arm=\narm_head=\n")
        check("manual recovery-ref completion expedites its matching PR",
              run_fixture([manual_pr], {**event, "workflow_run": {**event["workflow_run"],
                                                               "head_branch": "recovery"}})[1]
              == "arm=21\narm_head=" + manual_pr["head"] + "\n")
        admin_pr = {**manual_pr, "head": DEPLOY_PREFIXES[1] + sha}
        check("manual Admin-UI completion expedites its own PR",
              run_fixture([admin_pr], {**event, "workflow_run": {**event["workflow_run"],
                                                       "name": "Admin-UI deploy",
                                                       "path": ".github/workflows/admin-ui-deploy.yml"}})[1]
              == "arm=21\narm_head=" + admin_pr["head"] + "\n")
        check("manual completion never arms beside an already armed deploy PR",
              run_fixture([manual_pr, {**prs[0], "armed": True}], event)[1] == "arm=\narm_head=\n")
        check("older manual PR cannot overtake a newer PR of the same kind",
              run_fixture([manual_pr, {**manual_pr, "number": 22, "head": DEPLOY_PREFIXES[0] + "b" * 40,
                                       "created_at": "2026-09-30T10:11:00Z"}], event)[1]
              == "arm=\narm_head=\n")
    check("last deploy read from main's subjects",
          last_deploy_from_commits([{"message": "feat(x): y", "epoch": 9},
                                    {"message": "chore(admin-ui): deploy sandbox-1-run2", "epoch": 7}]) == 7)

    # 5. two pushes in one window -> ONE PR pins both (carry), and a moved service is not rewound
    reg = "123.dkr.ecr.eu-north-1.amazonaws.com"
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "g"))
        for svc, tag in (("billing", "sandbox-aaa"), ("lending", "sandbox-bbb"), ("party", "sandbox-ccc")):
            with open(os.path.join(d, "g", f"{svc}.yaml"), "w") as f:
                f.write(f"image: {reg}/openbank-{svc}:{tag}\n")
        # This run (push 2) already pinned lending to sandbox-222.
        p = os.path.join(d, "g", "lending.yaml")
        open(p, "w").write(f"image: {reg}/openbank-lending:sandbox-222\n")
        # Older deferred PR (push 1) pinned billing + lending + party to sandbox-111.
        # party has since moved on main (sandbox-ccc -> sandbox-999 by another merged deploy).
        open(os.path.join(d, "g", "party.yaml"), "w").write(f"image: {reg}/openbank-party:sandbox-999\n")
        older = "".join(
            f"--- a/g/{s}.yaml\n+++ b/g/{s}.yaml\n@@ -1 +1 @@\n-image: {reg}/openbank-{s}:{o}\n+image: {reg}/openbank-{s}:sandbox-111\n"
            for s, o in (("billing", "sandbox-aaa"), ("lending", "sandbox-bbb"), ("party", "sandbox-ccc"))
        )
        got = carry(d, older, {f"{reg}/openbank-lending"}, lambda image, tag: True)
        read = lambda s: open(os.path.join(d, "g", f"{s}.yaml")).read()
        check("two pushes in one window -> newest PR pins BOTH (billing carried)", "openbank-billing:sandbox-111" in read("billing"))
        check("carry never overrides this run's own newer pin", "openbank-lending:sandbox-222" in read("lending"))
        check("carry never rewinds a service main moved since", "openbank-party:sandbox-999" in read("party"))
        check("carry reports exactly what it applied", len(got) == 1 and "billing" in got[0])
        # Negative control: without carry the newest PR would NOT cover billing, so
        # supersede-deploy-prs.sh (coverage gate) would leave the older PR open -> two commits.
        check("carry is idempotent (second run changes nothing)",
              carry(d, older, {f"{reg}/openbank-lending"}, lambda image, tag: True) == [])
        open(os.path.join(d, "g", "billing.yaml"), "w").write(
            f"image: {reg}/openbank-billing:sandbox-aaa\n")
        check("carry rejects an image whose source predates changed build inputs",
              carry(d, older, set(), lambda image, tag: False) == []
              and "sandbox-aaa" in read("billing"))
        check("verify refuses stale GitOps pins", len(verify_pins(older, lambda image, tag: False)) == 1)
        check("verify accepts current GitOps pins", verify_pins(older, lambda image, tag: True) == [])
        check("verify refuses a diff without image pins", verify_pins("", lambda image, tag: True) != [])

    if fails:
        print(f"self-test: FAILED ({len(fails)})")
        return 1
    print("self-test: all cases OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    d = sub.add_parser("decide")
    d.add_argument("--event", required=True)
    d.add_argument("--armed-open", choices=["true", "false"], required=True)
    d.add_argument("--commits-json", required=True, help="file: [{message, epoch}] newest-first from main")
    f = sub.add_parser("flush")
    f.add_argument("--prs-json", required=True, help="file: [{number, head, created_at, armed}]")
    f.add_argument("--commits-json", required=True)
    f.add_argument("--event-json", help="GitHub's workflow_run event payload for manual deploy intent")
    c = sub.add_parser("carry")
    c.add_argument("--root", default=".")
    c.add_argument("--skip-images", default="", help="space-separated images this run pins itself")
    v = sub.add_parser("verify")
    v.add_argument("--root", default=".")
    for p in (d, f):
        p.add_argument("--now", type=int, required=True)
        p.add_argument("--window", type=int, default=int(os.environ.get("DEPLOY_WINDOW_SECONDS", DEFAULT_WINDOW_SECONDS)))
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.cmd == "decide":
        last = last_deploy_from_commits(json.load(open(a.commits_json)))
        verdict, why = decide(a.event, a.now, last, a.armed_open == "true", a.window)
        print(f"decision={verdict}")
        print(f"::notice::deploy window: {verdict} — {why}", file=sys.stderr)
        return 0
    if a.cmd == "flush":
        last = last_deploy_from_commits(json.load(open(a.commits_json)))
        manual_head = manual_deploy_head(json.load(open(a.event_json))) if a.event_json else None
        pick = flush(json.load(open(a.prs_json)), a.now, last, a.window, manual_head)
        print(f"arm={pick['number'] if pick else ''}")
        print(f"arm_head={pick['head'] if pick else ''}")
        return 0
    if a.cmd == "carry":
        for line in carry(a.root, sys.stdin.read(), set(a.skip_images.split()),
                          lambda image, tag: image_source_is_current(a.root, image, tag)):
            print(f"  [carried] {line}")
        return 0
    if a.cmd == "verify":
        stale = verify_pins(sys.stdin.read(),
                            lambda image, tag: image_source_is_current(a.root, image, tag))
        for pin in stale:
            print(f"::error::stale GitOps image pin: {pin}", file=sys.stderr)
        return 1 if stale else 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
