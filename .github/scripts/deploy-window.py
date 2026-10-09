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
                deploy workflow completes and on a short recovery cron. Holds Admin UI PRs until
                #12211 proves their image provenance, then picks an eligible service deploy PR
                once the window has elapsed and nothing is armed. The caller re-runs
                supersede-deploy-prs.sh (ancestry + coverage) on it before arming.
                A successful manual producer run expedites only its matching PR.
  * `flush-reviewed` — inspects every deferred service PR against selected main before arming;
                fully covered PRs are retired, while unknown source or coverage blocks selection.
  * `retire-covered` — with the GitOps App token, rechecks main, PR state, exact head and complete
                pin coverage before commenting and closing a fully covered service PR.
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
before this change. It closes only when a newer PR covers its complete diff, or when every
proposed image pin is already on main from the same or descendant source. Unknown source,
non-pin changes and a deployment hold prevent main-coverage retirement.
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
    """Return an eligible service PR, or None. prs: [{number, head, created_at, armed}]."""
    deploy = [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES)]
    if any(p.get("armed") for p in deploy):
        return None
    # Admin UI image provenance remains unverified (#12211). Keep those PRs visible and open,
    # but never select or arm one; an older Admin UI PR must not starve service GitOps.
    pending = sorted((p for p in deploy if p["head"].startswith(DEPLOY_PREFIXES[0])
                      and not p.get("armed")), key=lambda p: (p["created_at"], p["number"]))
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
    # The newest service PR carries older service pins. Arming an older one would hit
    # supersede-deploy-prs.sh's STALE_KEEP refusal forever.
    return pending[-1]


def flush_eligible(prs: list[dict], now: int, last_deploy: int | None, window: int,
                   inspect: Callable[[dict], str], manual_head: str | None = None
                   ) -> tuple[dict | None, list[int], str]:
    """Select a proven fresh PR and report covered ones; unknown blocks selection."""
    if flush(prs, now, last_deploy, window, manual_head) is None:
        return None, [], "none"
    covered: list[int] = []
    for pr in sorted(prs, key=lambda item: (item["created_at"], item["number"])):
        if pr.get("armed") or not pr["head"].startswith(DEPLOY_PREFIXES[0]):
            continue
        verdict = inspect(pr)
        if verdict == "covered":
            covered.append(pr["number"])
        elif verdict != "eligible":
            return None, covered, "unknown"
    remaining = [pr for pr in prs if pr["number"] not in covered]
    pick = flush(remaining, now, last_deploy, window, manual_head)
    return pick, covered, "eligible" if pick else "none"


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
        for old, new in zip(sides["old"], sides["new"], strict=True):
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

    @lru_cache(maxsize=128)
    def classify(*args: str) -> str:
        result = subprocess.run(["bash", helper, *args], cwd=root, capture_output=True,
                                text=True, check=False,
                                env={**os.environ, "GITHUB_REPOSITORY": repo})
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


def retire_covered(numbers: list[int], selected_main: str,
                   read_main: Callable[[], str], read_pr: Callable[[int], dict],
                   inspect: Callable[[dict], str], comment: Callable[[int, str], None],
                   close: Callable[[int], None]) -> None:
    """Close only still-unarmed service PRs whose entire pin diff is already on main."""
    def require_main() -> None:
        if read_main() != selected_main:
            raise ValueError("main moved since selection; refusing to retire a deploy PR")

    def safe_pr(number: int) -> dict:
        pr = read_pr(number)
        if (pr.get("state") != "OPEN" or pr.get("isDraft") is not False
                or pr.get("isCrossRepository") is not False or pr.get("autoMergeRequest") is not None
                or "blocked" in {label.get("name") for label in pr.get("labels", [])}
                or not pr.get("headRefName", "").startswith(DEPLOY_PREFIXES[0])
                or not re.fullmatch(r"[0-9a-f]{40}", pr.get("headRefOid", ""))):
            raise ValueError(f"#{number} changed state or carries a deployment hold")
        return pr

    for number in numbers:
        require_main()
        before = safe_pr(number)
        if inspect({"number": number, "head": before["headRefName"]}) != "covered":
            raise ValueError(f"#{number} is not fully covered by selected main")
        comment(number, f"Closed automatically: every proposed image pin is already on main "
                        f"at `{selected_main}` or a proven descendant source. The complete "
                        "GitOps diff contains only those pin substitutions; no image is being "
                        "deployed by closing this PR. See #12182.")
        require_main()
        after = safe_pr(number)
        if after["headRefOid"] != before["headRefOid"]:
            raise ValueError(f"#{number} head changed during retirement")
        if inspect({"number": number, "head": after["headRefName"]}) != "covered":
            raise ValueError(f"#{number} coverage changed during retirement")
        close(number)


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
    check("oldest Admin UI held; eligible service proceeds", pick is not None and pick["number"] == 12)
    check("Admin UI alone is held, never armed", flush([prs[1]], T, T - W, W) is None)
    prs2 = prs + [{"number": 14, "head": "chore/admin-ui-deploy-d", "created_at": "2026-09-30T10:09:00Z", "armed": False}]
    pick = flush(prs2, T, T - W, W)
    check("newer Admin UI remains held behind service", pick is not None and pick["number"] == 12)
    services = prs + [{"number": 15, "head": "chore/gitops-auto-deploy-e",
                       "created_at": "2026-09-30T10:11:00Z", "armed": False}]
    check("newest eligible service supersedes older service",
          flush(services, T, T - W, W)["number"] == 15)
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
        def run_fixture_full(prs_fixture, event_fixture):
            for name, value in (("prs", prs_fixture), ("commits", [{"message": DEPLOY_SUBJECTS[0] + "old", "epoch": T - 60}]),
                                ("event", event_fixture)):
                with open(paths[name], "w", encoding="utf-8") as out:
                    json.dump(value, out)
            result = subprocess.run([sys.executable, __file__, "flush", "--prs-json", paths["prs"],
                                     "--commits-json", paths["commits"], "--event-json", paths["event"],
                                     "--now", str(T), "--window", str(W)], capture_output=True, text=True)
            return result.returncode, result.stdout, result.stderr
        def run_fixture(prs_fixture, event_fixture):
            code, stdout, _stderr = run_fixture_full(prs_fixture, event_fixture)
            return code, stdout
        held_code, held_stdout, held_stderr = run_fixture_full(
            [prs[1], manual_pr], event)
        check("CLI reports held Admin UI while manual service proceeds",
              held_code == 0 and held_stdout == "arm=21\narm_head=" + manual_pr["head"] + "\n"
              and "Admin UI deploy PRs held until #12211" in held_stderr and "[11]" in held_stderr)
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
        check("manual Admin-UI completion does not bypass provenance hold",
              run_fixture([admin_pr], {**event, "workflow_run": {**event["workflow_run"],
                                                       "name": "Admin-UI deploy",
                                                       "path": ".github/workflows/admin-ui-deploy.yml"}})[1]
              == "arm=\narm_head=\n")
        check("manual completion never arms beside an already armed deploy PR",
              run_fixture([manual_pr, {**prs[0], "armed": True}], event)[1] == "arm=\narm_head=\n")
        check("older manual PR cannot overtake a newer PR of the same kind",
              run_fixture([manual_pr, {**manual_pr, "number": 22, "head": DEPLOY_PREFIXES[0] + "b" * 40,
                                       "created_at": "2026-09-30T10:11:00Z"}], event)[1]
              == "arm=\narm_head=\n")
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
        {"number": 102, "head": "chore/gitops-auto-deploy-next", "created_at": "b", "armed": False},
    ]
    pick, covered, verdict = flush_eligible(queued, T, T - W, W,
                                             lambda pr: stale if pr["number"] == 101 else "eligible")
    check("covered oldest is retired before later eligible PR is selected",
          pick is not None and pick["number"] == 102 and covered == [101] and verdict == "eligible")
    pick, covered, verdict = flush_eligible(queued, T, T - W, W,
                                             lambda pr: unknown if pr["number"] == 101 else "eligible")
    check("unknown oldest blocks later PR rather than silently skipping it",
          pick is None and covered == [] and verdict == "unknown")
    check("non-pin edits cannot prove coverage", pin_only_changes(pin_diff("00000000", "11111111")
          + "+replicas: 2\n") is None)

    selected = "a" * 40
    state = {"state": "OPEN", "isDraft": False, "isCrossRepository": False,
             "autoMergeRequest": None, "labels": [], "headRefName": DEPLOY_PREFIXES[0] + selected,
             "headRefOid": "b" * 40}
    actions: list[str] = []
    def read_retirement_pr(_number: int) -> dict:
        return dict(state)
    def retirement_comment(_number: int, _body: str) -> None:
        actions.append("comment")
    def retirement_close(_number: int) -> None:
        actions.append("close")
    retire_covered([101], selected, lambda: selected, read_retirement_pr,
                   lambda _pr: "covered", retirement_comment, retirement_close)
    check("covered PR is commented before close after two live proofs", actions == ["comment", "close"])
    actions.clear()
    state["labels"] = [{"name": "blocked"}]
    try:
        retire_covered([101], selected, lambda: selected, read_retirement_pr,
                       lambda _pr: "covered", retirement_comment, retirement_close)
        held_refused = False
    except ValueError:
        held_refused = True
    check("a held PR is never retired", held_refused and not actions)
    state["labels"] = []
    try:
        retire_covered([101], selected, lambda: selected, read_retirement_pr,
                       lambda _pr: "unknown", retirement_comment, retirement_close)
        unknown_refused = False
    except ValueError:
        unknown_refused = True
    check("unknown main coverage never retires a PR", unknown_refused and not actions)
    def moved_head(_number: int, _body: str) -> None:
        actions.append("comment")
        state["headRefOid"] = "c" * 40
    try:
        retire_covered([101], selected, lambda: selected, read_retirement_pr,
                       lambda _pr: "covered", moved_head, retirement_close)
        moved_refused = False
    except ValueError:
        moved_refused = True
    check("a head change after comment prevents close", moved_refused and actions == ["comment"])
    actions.clear()
    observed_main = [selected]
    def moved_main(_number: int, _body: str) -> None:
        actions.append("comment")
        observed_main[0] = "d" * 40
    try:
        retire_covered([101], selected, lambda: observed_main[0], read_retirement_pr,
                       lambda _pr: "covered", moved_main, retirement_close)
        main_refused = False
    except ValueError:
        main_refused = True
    check("main advancement after comment prevents close", main_refused and actions == ["comment"])
    workflow = os.path.join(os.path.dirname(__file__), "../workflows/deploy-window-flush.yml")
    with open(workflow, encoding="utf-8") as source:
        wiring = source.read()
    check("scheduled flusher invokes reviewed selection and guarded retirement",
          "deploy-window.py flush-reviewed" in wiring and "deploy-window.py retire-covered" in wiring
          and "deploy-window.py flush --" not in wiring)

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
    reviewed = sub.add_parser("flush-reviewed", help="live GitOps eligibility proof before selection")
    reviewed.add_argument("--prs-json", required=True)
    reviewed.add_argument("--commits-json", required=True)
    reviewed.add_argument("--event-json")
    reviewed.add_argument("--root", required=True)
    reviewed.add_argument("--repo", required=True)
    reviewed.add_argument("--selected-main", required=True)
    retire = sub.add_parser("retire-covered", help="App-token retirement of proven main-covered service PRs")
    retire.add_argument("--numbers", required=True, help="comma-separated PR numbers from flush-reviewed")
    retire.add_argument("--selected-main", required=True)
    retire.add_argument("--root", required=True)
    retire.add_argument("--repo", required=True)
    c = sub.add_parser("carry")
    c.add_argument("--root", default=".")
    c.add_argument("--skip-images", default="", help="space-separated images this run pins itself")
    v = sub.add_parser("verify")
    v.add_argument("--root", default=".")
    for p in (d, f, reviewed):
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
        prs = json.load(open(a.prs_json))
        held_admin = sorted(p["number"] for p in prs if p["head"].startswith(DEPLOY_PREFIXES[1])
                            and not p.get("armed"))
        if held_admin:
            print(f"::notice::Admin UI deploy PRs held until #12211 image provenance is verified: {held_admin}",
                  file=sys.stderr)
        pick = flush(prs, a.now, last, a.window, manual_head)
        print(f"arm={pick['number'] if pick else ''}")
        print(f"arm_head={pick['head'] if pick else ''}")
        return 0
    if a.cmd == "flush-reviewed":
        if not re.fullmatch(r"[0-9a-f]{40}", a.selected_main):
            print("::error::invalid selected main SHA", file=sys.stderr)
            return 1
        fetched = subprocess.run(["git", "rev-parse", "origin/main"], cwd=a.root,
                                 capture_output=True, text=True, check=False)
        checked_out = subprocess.run(["git", "rev-parse", "HEAD"], cwd=a.root,
                                     capture_output=True, text=True, check=False)
        if (fetched.returncode != 0 or checked_out.returncode != 0
                or fetched.stdout.strip() != a.selected_main
                or checked_out.stdout.strip() != a.selected_main):
            print("::error::checkout differs from selected main; retry on the next tick", file=sys.stderr)
            return 1
        last = last_deploy_from_commits(json.load(open(a.commits_json)))
        manual_head = manual_deploy_head(json.load(open(a.event_json))) if a.event_json else None
        pick, covered, verdict = flush_eligible(
            json.load(open(a.prs_json)), a.now, last, a.window,
            lambda pr: inspect_deferred_pr(pr, a.root, a.repo), manual_head)
        print(f"arm={pick['number'] if pick else ''}")
        print(f"arm_head={pick['head'] if pick else ''}")
        print(f"retire={','.join(map(str, covered))}")
        print(f"selection={verdict}")
        return 0
    if a.cmd == "retire-covered":
        if (not re.fullmatch(r"[0-9a-f]{40}", a.selected_main)
                or not re.fullmatch(r"[1-9][0-9]*(,[1-9][0-9]*)*", a.numbers)):
            print("::error::invalid retirement input", file=sys.stderr)
            return 1
        numbers = [int(number) for number in a.numbers.split(",")]
        if len(numbers) != len(set(numbers)):
            print("::error::duplicate retirement PR", file=sys.stderr)
            return 1

        def checked(*command: str) -> str:
            result = subprocess.run(command, cwd=a.root, capture_output=True, text=True, check=False)
            if result.returncode != 0:
                raise ValueError(f"retirement command failed: {command[0]} {command[1]}")
            return result.stdout.strip()

        def read_main() -> str:
            local = checked("git", "rev-parse", "origin/main")
            checked_out = checked("git", "rev-parse", "HEAD")
            live = checked("gh", "api", f"repos/{a.repo}/git/ref/heads/main", "--jq", ".object.sha")
            return local if local == checked_out == live else ""

        def read_pr(number: int) -> dict:
            return json.loads(checked("gh", "pr", "view", str(number), "--repo", a.repo,
                                      "--json", "state,isDraft,isCrossRepository,autoMergeRequest,labels,headRefName,headRefOid"))

        def comment(number: int, body: str) -> None:
            checked("gh", "pr", "comment", str(number), "--repo", a.repo, "--body", body)

        def close(number: int) -> None:
            checked("gh", "pr", "close", str(number), "--repo", a.repo, "--delete-branch")

        try:
            retire_covered(numbers, a.selected_main, read_main, read_pr,
                           lambda pr: inspect_deferred_pr(pr, a.root, a.repo), comment, close)
        except (ValueError, KeyError, json.JSONDecodeError) as error:
            print(f"::error::{error}", file=sys.stderr)
            return 1
        print(f"retired={','.join(map(str, numbers))}")
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
