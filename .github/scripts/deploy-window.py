#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Deploy window: at most one bot deploy commit lands on main per window.

WHY. `main-protection` requires PR branches to be up to date with main. Every bot deploy
commit (`chore(gitops): auto-deploy ...`, `chore(admin-ui): deploy ...`) therefore makes every
open PR `behind` and restarts its required checks. Measured over 2026-09-23T09:00Z ..
2026-09-30T09:00Z: 300 of 675 first-parent commits on main were bot deploy commits.

MECHANISM. Nothing about HOW a deploy is built, gated, opened or recorded changes. Only the
moment a deploy PR's auto-merge is ARMED does:

  * `decide`  — called by the deploy workflows right after they open their PR. ARM when the
                run is a manual `workflow_dispatch` (hotfix path: always immediate), or when no
                deploy PR is currently armed and the last deploy commit on main is at least one
                window old. Otherwise DEFER: the PR stays open, unarmed.
  * `flush`   — called by deploy-window-flush.yml on a short cron. Picks the OLDEST deferred
                deploy PR once the window has elapsed and nothing is armed. The caller re-runs
                supersede-deploy-prs.sh (ancestry + coverage) on it before arming.
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
    if event == "workflow_dispatch":
        return "ARM", "manual workflow_dispatch deploys immediately"
    if armed_open:
        return "DEFER", "a deploy PR is already armed; this one waits for the next window"
    if last_deploy is None:
        return "ARM", "no recent deploy commit on main"
    age = now - last_deploy
    if age >= window:
        return "ARM", f"last deploy commit is {age}s old (window {window}s)"
    return "DEFER", f"last deploy commit is {age}s old, window {window}s — flush arms it at {last_deploy + window}"


def flush(prs: list[dict], now: int, last_deploy: int | None, window: int) -> dict | None:
    """Return the deferred PR to arm now, or None. prs: [{number, head, created_at, armed}]."""
    deploy = [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES)]
    if any(p.get("armed") for p in deploy):
        return None
    pending = sorted((p for p in deploy if not p.get("armed")), key=lambda p: (p["created_at"], p["number"]))
    if not pending:
        return None
    verdict, _ = decide("flush", now, last_deploy, False, window)
    if verdict != "ARM":
        return None
    # The kind (gitops / admin-ui) that has waited longest goes first, and within it the NEWEST
    # PR is armed: it carries every older pin of its kind, so supersede-deploy-prs.sh closes the
    # older ones. Arming the oldest instead would hit supersede's STALE_KEEP refusal forever.
    prefix = next(x for x in DEPLOY_PREFIXES if pending[0]["head"].startswith(x))
    return [p for p in pending if p["head"].startswith(prefix)][-1]


def flush_eligible(prs: list[dict], now: int, last_deploy: int | None, window: int,
                   inspect: Callable[[dict], str]) -> dict | None:
    """Skip covered or unverifiable PRs; never arm one without positive eligibility proof."""
    remaining = list(prs)
    while pick := flush(remaining, now, last_deploy, window):
        if inspect(pick) == "eligible":
            return pick
        remaining = [pr for pr in remaining if pr["number"] != pick["number"]]
    return None


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


def pin_only_changes(diff: str) -> list[tuple[str, str, str, str]] | None:
    """Accept only paired image-tag substitutions in GitOps component YAML files."""
    changes: dict[str, dict[str, list[tuple[str, str, str]]]] = {}
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[4:].removeprefix("b/")
            if not current.startswith("openbank-infra/gitops/components/") or not current.endswith(".yaml") \
                    or ".." in current.split("/"):
                return None
            changes.setdefault(current, {"old": [], "new": []})
        elif line.startswith("--- "):
            continue
        elif line.startswith(("+", "-")):
            if current is None:
                return None
            matches = list(PIN_RE.finditer(line[1:]))
            if len(matches) != 1:
                return None
            match = matches[0]
            normalized = PIN_RE.sub(lambda m: f"{m['image']}:<tag>", line[1:])
            changes[current]["new" if line[0] == "+" else "old"].append(
                (match["image"], match["tag"], normalized))
    pins = []
    for path, sides in changes.items():
        if not sides["old"] or len(sides["old"]) != len(sides["new"]):
            return None
        for old, new in zip(sides["old"], sides["new"]):
            if old[0] != new[0] or old[2] != new[2] or old[1] == new[1]:
                return None
            pins.append((path, old[0], old[1], new[1]))
    return pins if pins and sorted(pins) == sorted(pins_from_diff(diff)) else None


def classify_against_main(diff: str, read_main: Callable[[str], str | None],
                          classify_source: Callable[[str, str], str],
                          classify_files: Callable[[str, str], str]) -> str:
    """Return covered, eligible, or unknown using the existing ancestry and coverage verdicts."""
    pins = pin_only_changes(diff)
    if not pins:
        return "unknown"
    main_files = {}
    relations = []
    for path, image, _old, proposed in pins:
        if path not in main_files:
            main_files[path] = read_main(path)
        content = main_files[path]
        if content is None:
            return "unknown"
        current = {m["tag"] for m in PIN_RE.finditer(content) if m["image"] == image}
        if len(current) != 1:
            return "unknown"
        main_tag = current.pop()
        if main_tag == proposed:
            relations.append("covered")
            continue
        proposed_sha = re.fullmatch(r"sandbox-([0-9a-f]{8})", proposed)
        main_sha = re.fullmatch(r"sandbox-([0-9a-f]{8})", main_tag)
        if not proposed_sha or not main_sha:
            return "unknown"
        verdict = classify_source(main_sha[1], proposed_sha[1])
        if verdict == "CLOSE":
            relations.append("covered")
        elif verdict == "STALE_KEEP":
            relations.append("eligible")
        else:
            return "unknown"
    paths = "\n".join(sorted(main_files))
    if classify_files(paths, "\n".join(sorted({pin[0] for pin in pins}))) != "CLOSE":
        return "unknown"
    if all(relation == "covered" for relation in relations):
        return "covered"
    if all(relation == "eligible" for relation in relations):
        return "eligible"
    # A partly covered diff could rewind the covered pins while applying its other pins.
    return "unknown"


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


def inspect_deferred_pr(pr: dict, root: str, repo: str) -> str:
    """Read-only eligibility proof for the flusher's chosen GitOps candidate."""
    if not pr["head"].startswith(DEPLOY_PREFIXES[0]):
        return "eligible"  # Admin UI has its own source gate, unchanged here.
    diff = subprocess.run(["gh", "pr", "diff", str(pr["number"]), "--repo", repo],
                          cwd=root, capture_output=True, text=True, check=False)
    if diff.returncode != 0:
        return "unknown"
    helper = os.path.join(root, ".github/scripts/supersede-deploy-prs.sh")

    def classify(*args: str) -> str:
        result = subprocess.run(["bash", helper, *args], cwd=root, capture_output=True,
                                text=True, check=False)
        return result.stdout.strip() if result.returncode == 0 else "SKIP"

    def read_main(path: str) -> str | None:
        result = subprocess.run(["git", "show", f"origin/main:{path}"], cwd=root,
                                capture_output=True, text=True, check=False)
        return result.stdout if result.returncode == 0 else None

    relation = classify_against_main(diff.stdout, read_main,
                                     lambda current, proposed: classify("--classify", current, proposed),
                                     lambda current, proposed: classify("--classify-coverage", current, proposed))
    if relation != "eligible":
        return relation
    return "eligible" if not verify_pins(diff.stdout,
                                         lambda image, tag: image_source_is_current(root, image, tag)) else "unknown"


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
    # 3. manual dispatch is never delayed
    check("workflow_dispatch -> ARM even inside the window", decide("workflow_dispatch", T, T - 1, True, W)[0] == "ARM")
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
    check("last deploy read from main's subjects",
          last_deploy_from_commits([{"message": "feat(x): y", "epoch": 9},
                                    {"message": "chore(admin-ui): deploy sandbox-1-run2", "epoch": 7}]) == 7)

    # A covered oldest GitOps PR must not block a later eligible deferred PR (#12182).
    reg = "registry.invalid"
    path = "openbank-infra/gitops/components/example/example-service.yaml"
    def pin_diff(old: str, new: str) -> str:
        return (f"--- a/{path}\n+++ b/{path}\n@@ -1 +1 @@\n"
                f"-image: {reg}/openbank-example-service:sandbox-{old}\n"
                f"+image: {reg}/openbank-example-service:sandbox-{new}\n")
    main_text = f"image: {reg}/openbank-example-service:sandbox-22222222\n"
    files = lambda main, pr: "CLOSE" if set(pr.splitlines()) <= set(main.splitlines()) else "SKIP"
    relation = lambda main, proposed: {
        ("22222222", "11111111"): "CLOSE",
        ("22222222", "33333333"): "STALE_KEEP",
    }.get((main, proposed), "SKIP")
    stale = classify_against_main(pin_diff("00000000", "11111111"),
                                  lambda _: main_text, relation, files)
    eligible = classify_against_main(pin_diff("00000000", "33333333"),
                                     lambda _: main_text, relation, files)
    unknown = classify_against_main(pin_diff("00000000", "44444444"),
                                    lambda _: main_text, relation, files)
    check("main descendant pin covers stale deferred PR", stale == "covered")
    check("proposed descendant pin remains eligible", eligible == "eligible")
    check("unknown ancestry never proves coverage or eligibility", unknown == "unknown")
    check("unknown file coverage never proves a stale PR safe to skip",
          classify_against_main(pin_diff("00000000", "11111111"),
                                lambda _: main_text, relation, lambda _a, _b: "SKIP") == "unknown")
    queued = [
        {"number": 101, "head": "chore/gitops-auto-deploy-old", "created_at": "a", "armed": False},
        {"number": 102, "head": "chore/admin-ui-deploy-next", "created_at": "b", "armed": False},
    ]
    pick = flush_eligible(queued, T, T - W, W,
                          lambda pr: stale if pr["number"] == 101 else "eligible")
    check("covered oldest is skipped and later eligible PR is selected", pick is not None and pick["number"] == 102)
    check("unknown oldest stays unarmed", flush_eligible(queued[:1], T, T - W, W,
          lambda _: unknown) is None)
    check("non-pin edits cannot prove coverage", pin_only_changes(pin_diff("00000000", "11111111")
          + "+replicas: 2\n") is None)

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
        pick = flush(json.load(open(a.prs_json)), a.now, last, a.window)
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
