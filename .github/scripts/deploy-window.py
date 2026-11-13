#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Deploy window: at most one bot deploy commit PER KIND (gitops / admin-ui) lands on main per window.

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
                deploy PR of each kind once that kind's window has elapsed and nothing of that kind is armed. The caller re-runs
                supersede-deploy-prs.sh (ancestry + coverage) on it before arming.
                A successful manual producer run expedites only its matching PR.
  * `carry`   — called by auto-deploy.yml's rewrite step. Re-applies the image pins of every
                open, UNARMED older gitops deploy PR onto the new PR, so the newest PR covers
                every pending service and supersede-deploy-prs.sh closes the older ones. That
                is what turns "several pushes inside one window" into ONE commit. A pin is
                carried only while main still holds the tag that PR replaced — if main moved
                that service since, the old pin is stale and carrying it would rewind it. A pin
                is the pair tag@digest and moves as one: the kubelet pulls by digest, so a new
                tag over the old digest runs the old image under the new label. It is
                also refused when build inputs changed after that image's source commit.
  * `verify`  — before either deploy workflow arms a GitOps PR, proves every image in its diff
                still represents the current main build inputs. Unknown sources fail closed.
  * `prune`   — the producer's per-pin form of `verify` (#15977): a stale pin is reverted on
                the PR branch in one signed App commit and the fresh pins proceed; with no
                fresh pin left the PR is closed. `select` applies the same rule in the flusher.

NO LOST DEPLOY. A deferred deploy is an open PR: the same durable record the pipeline used
before this change. Nothing here closes a PR; the only closer is supersede-deploy-prs.sh, which
closes a PR only when a newer one covers every file it touches — and `carry` is what makes the
newer one cover them.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
from collections.abc import Callable
from functools import lru_cache
from typing import NamedTuple

DEFAULT_WINDOW_SECONDS = 1800
DEPLOY_PREFIXES = ("chore/gitops-auto-deploy-", "chore/admin-ui-deploy-")
DEPLOY_SUBJECTS = ("chore(gitops): auto-deploy ", "chore(admin-ui): deploy ")
PIN_RE = re.compile(r"(?P<image>[A-Za-z0-9.-]+/openbank-[A-Za-z0-9._/-]+):(?P<tag>sandbox-[A-Za-z0-9._-]+)(?:@(?P<digest>sha256:[0-9a-f]{64}))?")


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
    if producer[1] == DEPLOY_PREFIXES[0]:
        run_id = run.get("id")
        if type(run_id) is not int or run_id <= 0:
            return None
        return producer[1] + sha + f"-run{run_id}"
    return producer[1] + sha


def _kind(head: str) -> str:
    return next(x for x in DEPLOY_PREFIXES if head.startswith(x))


def flush_candidates(prs: list[dict], now: int, last_deploy: int | None | dict[str, int | None],
                     window: int, manual_head: str | None = None) -> list[dict]:
    """Deferred PRs the caller may try to arm, in order; it arms the first that supersede admits.

    Each deploy kind (gitops / admin-ui) is its own LANE: its own window, measured from its own
    last deploy commit, and its own single-armed-PR guard. One kind never waits on the other.
    2026-10-24..30 a held, then stale, gitops PR refused the shared lane on every run and
    admin-ui deployed 9 times in 10 days against 51 in the 19 before (#14238 follow-up).
    `last_deploy` is either one epoch shared by both kinds (legacy callers, self-tests) or a
    {prefix: epoch} map from last_deploy_by_kind.
    """
    # Explicit maintainer intent outranks both the timer and manual producer completion.
    # Draft/blocked repairs also defer: only closing the PR or removing the label releases it.
    if any(p.get("repair_priority") and p.get("state", "open") == "open" for p in prs):
        return []
    # An armed PR marked `stuck` (cannot merge: conflict or failed check, #14498) no longer holds
    # its kind; stuck_armed() names it so the workflow disarms it before arming a successor.
    deploy = [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES) and not (p.get("armed") and p.get("stuck"))]
    armed_kinds = {_kind(p["head"]) for p in deploy if p.get("armed")}
    pending = sorted((p for p in deploy if not p.get("armed") and _kind(p["head"]) not in armed_kinds),
                     key=lambda p: (p["created_at"], p["number"]))
    if not pending:
        return []
    if manual_head:
        manual = next((p for p in pending if p["head"] == manual_head), None)
        if manual:
            # A newer PR of the same kind may already supersede this run. Never expedite a
            # stale pin merely because its source workflow was manually dispatched.
            newest = [p for p in pending if p["head"].startswith(_kind(manual_head))][-1]
            if newest is manual:
                return [manual]

    def lane_open(kind: str) -> bool:
        last = last_deploy.get(kind) if isinstance(last_deploy, dict) else last_deploy
        return decide("flush", now, last, False, window)[0] == "ARM"

    # The kind that has waited longest goes first, and within each kind the NEWEST PR is offered:
    # it carries every older pin of its kind, so supersede-deploy-prs.sh closes the older ones.
    # Arming the oldest instead would hit supersede's STALE_KEEP refusal forever.
    kinds = []
    for p in pending:
        kind = _kind(p["head"])
        if kind not in kinds and lane_open(kind):
            kinds.append(kind)
    return [[p for p in pending if p["head"].startswith(k)][-1] for k in kinds]


def flush(prs: list[dict], now: int, last_deploy: int | None | dict[str, int | None], window: int,
          manual_head: str | None = None) -> dict | None:
    """Return the first deferred PR to try, or None. prs: [{number, head, created_at, armed}]."""
    cands = flush_candidates(prs, now, last_deploy, window, manual_head)
    return cands[0] if cands else None


# Only a definitive "cannot merge" counts (#14498 review): a merge conflict, or a check that
# FAILED / TIMED_OUT. CANCELLED is deliberately excluded: a superseded or concurrency-cancelled
# run is routine and would otherwise disarm a healthy deploy PR.
STUCK_CONCLUSIONS = {"FAILURE", "TIMED_OUT"}


def is_stuck(view: dict) -> bool:
    """view: `gh pr view --json mergeStateStatus,statusCheckRollup`."""
    if view.get("mergeStateStatus") == "DIRTY":
        return True
    return any(str(c.get("conclusion") or c.get("state") or "").upper() in STUCK_CONCLUSIONS
               for c in view.get("statusCheckRollup") or [])


def stuck_armed(prs: list[dict]) -> list[dict]:
    """Armed deploy PRs that cannot merge; each freezes its kind until disarmed (#14498)."""
    return [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES) and p.get("armed") and p.get("stuck")]


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
def pin_transitions(diff: str) -> list[tuple[str, str, str, str | None, str, str | None]]:
    """(file, image, old_tag, old_digest, new_tag, new_digest) for every retagged image."""
    out, cur, removed = [], None, {}
    for line in diff.splitlines():
        if line.startswith("+++ "):
            cur = line[4:].removeprefix("b/")
            removed = {}
        elif line.startswith("--- "):
            continue
        elif line.startswith("-") and cur:
            for m in PIN_RE.finditer(line):
                removed.setdefault(m["image"], (m["tag"], m["digest"]))
        elif line.startswith("+") and cur:
            for m in PIN_RE.finditer(line):
                old = removed.get(m["image"])
                if old and old[0] != m["tag"]:
                    out.append((cur, m["image"], old[0], old[1], m["tag"], m["digest"]))
    return list(dict.fromkeys(out))


def pin_identity_failures(diff: str, require_digest: bool = False) -> list[str]:
    """Pins whose tag and digest cannot name the same image, decided from the diff alone.

    A rebuild pushes a new image, so a new tag over the digest it replaced is a label moved
    without the image, and a digest dropped by a retag loses the identity the kubelet pulls.
    One tag naming two digests in one diff cannot be true of both lines. `require_digest` is
    for gitops deploy PRs, whose producer always has the pushed digest; admin-ui pins by a
    per-run tag and carries its digest as evidence on the run.
    """
    failures = []
    for rel, image, old_tag, old_digest, new_tag, new_digest in pin_transitions(diff):
        service = image.rsplit("/", 1)[-1]
        if new_digest and new_digest == old_digest:
            failures.append(f"{service}:{new_tag} in {rel} keeps the digest of {old_tag}")
        elif old_digest and not new_digest:
            failures.append(f"{service}:{new_tag} in {rel} drops the digest of {old_tag}")
    seen: dict[tuple[str, str], str | None] = {}
    for line in diff.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        for m in PIN_RE.finditer(line):
            service = m["image"].rsplit("/", 1)[-1]
            if require_digest and not m["digest"]:
                failures.append(f"{service}:{m['tag']} has no digest")
            if seen.setdefault((m["image"], m["tag"]), m["digest"]) != m["digest"]:
                failures.append(f"{service}:{m['tag']} names two digests")
    return list(dict.fromkeys(failures))


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
    refused = set(pin_identity_failures(diff, require_digest=True))
    for rel, image, old, old_digest, new, new_digest in pin_transitions(diff):
        if image in skip_images:
            continue  # this run pins it itself — the newer build wins
        service = image.rsplit("/", 1)[-1]
        if not new_digest or any(f.startswith(f"{service}:{new} ") for f in refused):
            continue  # that PR's pin is not one image; repeating it would spread the false pair
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        text = open(path, encoding="utf-8").read()
        cur = {(m["tag"], m["digest"]) for m in PIN_RE.finditer(text) if m["image"] == image}
        if cur != {(old, old_digest)}:
            continue  # main moved this service since that PR was cut: carrying would rewind it
        if not fresh(image, new):
            continue  # a newer build input changed; this image cannot be carried safely
        old_ref = f"{image}:{old}" + (f"@{old_digest}" if old_digest else "")
        open(path, "w", encoding="utf-8").write(text.replace(old_ref, f"{image}:{new}@{new_digest}"))
        carried.append(f"{image}:{new}@{new_digest} in {rel}")
    return carried


def verify_pins(diff: str, fresh: Callable[[str, str], bool],
                require_digest: bool = False) -> list[str]:
    """Return the first stale or unverifiable pin; one is enough to refuse arming."""
    identity = pin_identity_failures(diff, require_digest)
    if identity:
        return identity
    pins = pins_from_diff(diff)
    if not pins:
        return ["no image pins found in the GitOps diff"]
    for rel, image, _old, new in pins:
        if not fresh(image, new):
            return [f"{image.rsplit('/', 1)[-1]}:{new} in {rel}"]
    return []


class PinVerdict(NamedTuple):
    """Per-pin freshness of one GitOps deploy PR (#15977).

    `refuse` refuses the WHOLE PR — a false tag/digest pair, a missing digest, a diff with no
    pins — because then the diff itself cannot be trusted to name the images it deploys.
    Staleness is different: it is a fact about ONE image, so it removes only that pin.
    """
    refuse: list[str]
    stale: list[tuple]
    fresh: list[tuple]


def _pin_label(t: tuple) -> str:
    return f"{t[1].rsplit('/', 1)[-1]}:{t[4]} in {t[0]}"


def classify_pins(diff: str, fresh: Callable[[str, str], bool],
                  require_digest: bool = False) -> PinVerdict:
    """Split a deploy diff into refusals, stale pins and fresh pins.

    Same inputs and the same refusals as `verify_pins`, but a stale pin no longer refuses the
    others: 2026-11-13 run 43387673867 built treasury, kyc and sepa-instant, #15960 moved kyc on
    main mid-build, and the one stale kyc pin left the two fresh ones undeployed in #15977.
    """
    identity = pin_identity_failures(diff, require_digest)
    if identity:
        return PinVerdict(identity, [], [])
    pins = pin_transitions(diff)
    if not pins:
        return PinVerdict(["no image pins found in the GitOps diff"], [], [])
    stale, ok = [], []
    for t in pins:
        (ok if fresh(t[1], t[4]) else stale).append(t)
    return PinVerdict([], stale, ok)


def revert_pins(text: str, transitions: list[tuple]) -> str:
    """Put each transition's OLD tag@digest back where its new one stands; refuse a miss.

    Matched pin by pin (image, tag and digest all equal), never by substring, so reverting
    `sandbox-abc` cannot touch `sandbox-abcd`. A pin the text does not hold raises: a prune
    that silently reverted nothing would leave the stale image in the PR it claims to clean.
    """
    for _rel, image, old, old_digest, new, new_digest in transitions:
        hits = 0

        def swap(m, image=image, new=new, new_digest=new_digest, old=old, old_digest=old_digest):
            nonlocal hits
            if m["image"] == image and m["tag"] == new and m["digest"] == new_digest:
                hits += 1
                return f"{image}:{old}" + (f"@{old_digest}" if old_digest else "")
            return m.group(0)

        text = PIN_RE.sub(swap, text)
        if hits == 0:
            raise ValueError(f"{_pin_label((_rel, image, old, old_digest, new, new_digest))} not found to revert")
    return text


def _gh(*args: str, stdin: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["gh", *args], input=stdin, capture_output=True, text=True, check=False)


def prune_stale_on_branch(repo: str, number: int, stale: list[tuple], gh=_gh) -> str:
    """Revert the stale pins on the PR branch in ONE signed commit; return its oid.

    `createCommitOnBranch` with the App token is the endpoint GitHub signs (the same one
    create-pull-request's `sign-commits` uses), and `expectedHeadOid` makes it compare-and-set:
    if anything moved the branch since we read it, the write is refused rather than merged.
    """
    view = gh("pr", "view", str(number), "--repo", repo,
              "--json", "headRefName,headRefOid,state,isDraft,labels")
    meta = json.loads(view.stdout) if view.returncode == 0 else None
    if not meta or meta.get("state") != "OPEN":
        raise RuntimeError(f"#{number} not readable or not open")
    if meta.get("isDraft") or any(l.get("name") == "blocked" for l in meta.get("labels") or []):
        raise RuntimeError(f"#{number} is held (draft or blocked) - an owner decision, not pruned")
    branch, head = meta["headRefName"], meta["headRefOid"]
    by_file: dict[str, list[tuple]] = {}
    for t in stale:
        by_file.setdefault(t[0], []).append(t)
    additions = []
    for rel, ts in sorted(by_file.items()):
        got = gh("api", f"repos/{repo}/contents/{rel}?ref={head}", "--jq", ".content")
        if got.returncode != 0:
            raise RuntimeError(f"{rel} unreadable at {head[:8]} (gh rc {got.returncode})")
        text = base64.b64decode(got.stdout).decode("utf-8")
        additions.append({"path": rel, "contents": base64.b64encode(
            revert_pins(text, ts).encode("utf-8")).decode("ascii")})
    services = ", ".join(sorted({t[1].rsplit("/", 1)[-1] for t in stale}))
    payload = {
        "query": "mutation($input: CreateCommitOnBranchInput!) "
                 "{ createCommitOnBranch(input: $input) { commit { oid } } }",
        "variables": {"input": {
            "branch": {"repositoryNameWithOwner": repo, "branchName": branch},
            "expectedHeadOid": head,
            "message": {"headline": f"chore(gitops): drop stale image pins ({services})",
                        "body": "Main changed these services' build inputs after their images "
                                "were built, so deploying them would run stale code. Only these "
                                "pins are reverted; every other pin in this PR is current.\n\n"
                                + "\n".join(_pin_label(t) for t in stale)},
            "fileChanges": {"additions": additions},
        }},
    }
    done = gh("api", "graphql", "--input", "-", "--jq", ".data.createCommitOnBranch.commit.oid",
              stdin=json.dumps(payload))
    oid = done.stdout.strip()
    if done.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40}", oid):
        raise RuntimeError(f"createCommitOnBranch refused on #{number}: {done.stderr.strip() or oid}")
    return oid


def select_candidate(cands: list[dict], verify: Callable[[dict], "list[str] | PinVerdict"],
                     supersede: Callable[[dict], int],
                     close_stale: Callable[[dict, list[str]], None] | None = None,
                     prune: Callable[[dict, list[tuple]], bool] | None = None) -> dict | None:
    """First candidate that passes freshness verify and supersede, or None.

    Any per-kind refusal falls through to the next kind: kinds are independent, and a refused
    PR is never armed. 2026-10-24..29 an owner hold, and from 2026-10-30 a stale audit-service
    pin, refused the gitops kind on every run while admin-ui was never tried.

    A stale pin is permanent: the check compares the image's source tree with main's, and main
    only moves forward, so it can never go green on that head. Staleness is per IMAGE, so it is
    removed per pin (#15977): with fresh pins left, `prune` reverts only the stale ones and the
    PR is armed on a later tick, once its required checks have re-run on the pruned head (the
    head this tick read is gone). With no fresh pin left, `close_stale` closes the PR instead
    of leaving it open as queue noise (2026-11-12: #15832, #15833 and #15846 sat red on exactly
    this until a human closed them). An identity failure refuses the whole PR as before, and
    an UNREADABLE diff is not evidence of staleness and never closes anything.
    """
    for c in cands:
        v = verify(c) if c["head"].startswith(DEPLOY_PREFIXES[0]) else PinVerdict([], [], [])
        if isinstance(v, list):
            v = PinVerdict(v, [], [])
        if v.refuse:
            for pin in v.refuse:
                print(f"::error::GitOps deploy PR #{c['number']} refused: {pin}", file=sys.stderr)
            if close_stale is not None and not any(r.startswith("diff of #") for r in v.refuse):
                close_stale(c, v.refuse)
        elif v.stale and not v.fresh:
            labels = [_pin_label(t) for t in v.stale]
            for pin in labels:
                print(f"::error::stale GitOps image pin in #{c['number']}: {pin}", file=sys.stderr)
            if close_stale is not None:
                close_stale(c, labels)
        elif v.stale:
            for t in v.stale:
                print(f"::warning::stale GitOps image pin in #{c['number']} pruned, the rest kept: "
                      f"{_pin_label(t)}", file=sys.stderr)
            if prune is not None and prune(c, v.stale):
                print(f"::notice::#{c['number']} pruned to {len(v.fresh)} fresh pin(s); armed on a "
                      "later tick once its checks re-run", file=sys.stderr)
        else:
            rc = supersede(c)
            if rc == 0:
                return c
            print(f"::warning::supersede refused #{c['number']} (rc {rc})", file=sys.stderr)
        print(f"::warning::deploy PR #{c['number']} not armable - trying the next deploy kind.", file=sys.stderr)
    return None


def last_deploy_from_commits(commits: list[dict]) -> int | None:
    """commits: [{message, epoch}] newest-first, as the caller reads them from main."""
    for c in commits:
        if c["message"].startswith(DEPLOY_SUBJECTS):
            return int(c["epoch"])
    return None


def last_deploy_by_kind(commits: list[dict]) -> dict[str, int | None]:
    """{deploy-branch prefix: epoch of that kind's newest deploy commit}, per-lane windows."""
    out: dict[str, int | None] = {p: None for p in DEPLOY_PREFIXES}
    for c in commits:
        for prefix, subject in zip(DEPLOY_PREFIXES, DEPLOY_SUBJECTS, strict=True):
            if out[prefix] is None and c["message"].startswith(subject):
                out[prefix] = int(c["epoch"])
    return out


# ── self-test ─────────────────────────────────────────────────────────────────────────────────
def prune_command(repo: str, number: int, diff: str, require_digest: bool,
                  fresh: Callable[[str, str], bool], gh=_gh, prune=prune_stale_on_branch) -> int:
    """The producer's per-pin gate: 0 all fresh, 1 refused whole, 3 pruned, 4 every pin stale.

    Exit 3 mirrors can-i-deploy's UnresolvedServicesError (#15955): the batch proceeds without
    the members that failed, and says which. After pruning, the PR is read and classified again
    and must come back all-fresh; anything else refuses, so a prune that did not take can never
    be mistaken for one that did.
    """
    def read() -> PinVerdict | None:
        got = gh("pr", "diff", str(number), "--repo", repo)
        if got.returncode != 0:
            print(f"::error::diff of #{number} unreadable (gh rc {got.returncode})", file=sys.stderr)
            return None
        return classify_pins(got.stdout, fresh, require_digest)

    v = classify_pins(diff, fresh, require_digest)
    for r in v.refuse:
        print(f"::error::GitOps deploy PR #{number} refused: {r}", file=sys.stderr)
    if v.refuse:
        return 1
    for t in v.stale:
        print(f"::warning::stale GitOps image pin: {_pin_label(t)}", file=sys.stderr)
    if not v.stale:
        return 0
    if not v.fresh:
        print(f"::error::every image pin in #{number} is stale - nothing left to deploy", file=sys.stderr)
        return 4
    try:
        oid = prune(repo, number, v.stale)
    except (RuntimeError, ValueError) as e:
        print(f"::error::could not prune #{number}: {e}", file=sys.stderr)
        return 1
    after = read()
    if after is None or after.refuse or after.stale or not after.fresh:
        print(f"::error::#{number} at {oid[:8]} is not all-fresh after pruning - refusing", file=sys.stderr)
        return 1
    for t in v.stale:
        print(f"pruned={_pin_label(t)}")
    print(f"::notice::#{number} pruned at {oid[:8]}; {len(after.fresh)} fresh pin(s) proceed", file=sys.stderr)
    return 3


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
    # Head-of-line blocking (2026-10-24..29): an owner hold refused the gitops kind on every run
    # and admin-ui was never tried. A refused kind must leave the other kind as the next candidate.
    cands = flush_candidates(prs2, T, T - W, W)
    check("flush offers every kind, longest-waiting first, newest within each",
          [p["number"] for p in cands] == [14, 12])
    check("candidates are empty while the window is closed", flush_candidates(prs2, T, T - 60, W) == [])
    check("flush() is the head of flush_candidates()", flush(prs2, T, T - W, W) is cands[0])
    cand_g = {"number": 12, "head": DEPLOY_PREFIXES[0] + "b"}
    cand_a = {"number": 11, "head": DEPLOY_PREFIXES[1] + "a"}
    tried = []
    def sup(rc_by_number):
        return lambda c: (tried.append(c["number"]), rc_by_number.get(c["number"], 0))[1]
    check("stale gitops pin falls through to admin-ui (never runs supersede on the stale PR)",
          select_candidate([cand_g, cand_a], lambda c: ["openbank-audit-service:sandbox-x"], sup({}))
          is cand_a and tried == [11])
    check("owner-hold refusal falls through to admin-ui",
          select_candidate([cand_g, cand_a], lambda c: [], sup({12: 3})) is cand_a)
    check("any supersede refusal falls through (rollback rc 1)",
          select_candidate([cand_g, cand_a], lambda c: [], sup({12: 1})) is cand_a)
    closed = []
    def closer(c, why):
        closed.append(c["number"])
    select_candidate([cand_g, cand_a], lambda c: ["openbank-audit-service:sandbox-x"], sup({}), closer)
    check("a stale gitops pin closes that PR", closed == [12])
    closed.clear()
    select_candidate([cand_g, cand_a], lambda c: [f"diff of #{c['number']} unreadable (gh rc 1)"], sup({}), closer)
    check("an unreadable diff closes nothing", closed == [])
    closed.clear()
    select_candidate([cand_g, cand_a], lambda c: [], sup({12: 3}), closer)
    check("a supersede refusal (owner hold) closes nothing", closed == [])
    check("every kind refused -> nothing armed",
          select_candidate([cand_g, cand_a], lambda c: ["x"], sup({11: 1})) is None)
    check("first armable candidate wins, later kinds untouched",
          select_candidate([cand_a, cand_g], lambda c: ["x"], sup({})) is cand_a)
    # Independent lanes: a kind's armed PR or recent deploy never holds the OTHER kind.
    g_armed = {"number": 40, "head": DEPLOY_PREFIXES[0] + "x", "created_at": "2026-09-30T09:00:00Z", "armed": True}
    check("armed gitops PR does not block admin-ui (lanes are independent)",
          [p["number"] for p in flush_candidates(prs2 + [g_armed], T, T - W, W)] == [14])
    lanes = {DEPLOY_PREFIXES[0]: T - 60, DEPLOY_PREFIXES[1]: T - W}
    check("recent gitops deploy closes only the gitops window",
          [p["number"] for p in flush_candidates(prs2, T, lanes, W)] == [14])
    check("each lane is measured from its own last deploy commit",
          last_deploy_by_kind([{"message": "chore(gitops): auto-deploy x", "epoch": 9},
                               {"message": "fix(a): y", "epoch": 8},
                               {"message": "chore(admin-ui): deploy z", "epoch": 7},
                               {"message": "chore(gitops): auto-deploy w", "epoch": 6}])
          == {DEPLOY_PREFIXES[0]: 9, DEPLOY_PREFIXES[1]: 7})
    a_armed = {"number": 41, "head": DEPLOY_PREFIXES[1] + "y", "created_at": "2026-09-30T09:00:00Z", "armed": True}
    check("a second PR of an ARMED kind is never offered",
          [p["number"] for p in flush_candidates(prs2 + [a_armed], T, T - W, W)] == [12])
    check("flush never arms a second PR of a kind while one of that kind is armed",
          flush([prs[1], {"number": 13, "head": "chore/admin-ui-deploy-c", "created_at": "z", "armed": True}],
                T, None, W) is None)
    stuck = {"number": 13, "head": "chore/admin-ui-deploy-c", "created_at": "z", "armed": True, "stuck": True}
    check("a stuck armed PR does not freeze the flush (#14498)",
          (flush(prs + [stuck], T, T - W, W) or {}).get("number") == 11)
    check("a stuck armed PR is named for disarming", [p["number"] for p in stuck_armed(prs + [stuck])] == [13])
    roll = lambda *cs: {"mergeStateStatus": "BLOCKED", "statusCheckRollup": [{"conclusion": c} for c in cs]}
    check("a merge conflict is stuck", is_stuck({"mergeStateStatus": "DIRTY", "statusCheckRollup": []}))
    check("a FAILURE check is stuck", is_stuck(roll("SUCCESS", "FAILURE")))
    check("a TIMED_OUT check is stuck", is_stuck(roll("TIMED_OUT")))
    check("a single CANCELLED check is NOT stuck", not is_stuck(roll("SUCCESS", "CANCELLED")))
    check("pending / green checks are NOT stuck", not is_stuck(roll(None, "SUCCESS")))
    check("a failed commit status (state) is stuck",
          is_stuck({"mergeStateStatus": "BLOCKED", "statusCheckRollup": [{"state": "FAILURE"}]}))
    check("a healthy armed PR is never named for disarming",
          stuck_armed(prs + [{**stuck, "stuck": False}]) == [])
    repair = {"number": 30, "head": "fix/repair", "created_at": "a", "armed": False,
              "state": "open", "repair_priority": True, "draft": True, "blocked": True}
    check("explicit open draft/blocked repair defers elapsed timer",
          flush(prs + [repair], T, T - W, W) is None)
    check("explicit repair defers matching manual producer completion",
          flush(prs + [repair], T, None, W, prs[0]["head"]) is None)
    check("closed marked repair releases oldest eligible deploy kind",
          flush(prs + [{**repair, "state": "closed"}], T, T - W, W)["number"] == 11)
    check("removing repair marker releases oldest eligible deploy kind",
          flush(prs + [{**repair, "repair_priority": False}], T, T - W, W)["number"] == 11)
    check("unmarked draft/blocked feature does not defer deploy",
          flush(prs + [{**repair, "repair_priority": False}], T, None, W)["number"] == 11)
    # Exercise the same CLI and event file that the serialized workflow uses. An unrelated
    # older PR must not steal a manual run's exemption, and routine events keep the window.
    with tempfile.TemporaryDirectory() as d:
        sha = "a" * 40
        manual_pr = {"number": 21, "head": DEPLOY_PREFIXES[0] + sha + "-run101",
                     "created_at": "2026-09-30T10:10:00Z", "armed": False}
        event = {"action": "completed", "repository": {"full_name": "example/open-bank"},
                 "workflow_run": {"name": "Auto deploy", "event": "workflow_dispatch",
                                  "conclusion": "success", "head_sha": sha, "id": 101,
                                  "path": ".github/workflows/auto-deploy.yml", "head_branch": "main",
                                  "head_repository": {"full_name": "example/open-bank"}}}
        paths = {name: os.path.join(d, name + ".json") for name in ("prs", "commits", "event")}
        def run_fixture(prs_fixture, event_fixture):
            for name, value in (("prs", prs_fixture), ("commits", [{"message": s + "old", "epoch": T - 60} for s in DEPLOY_SUBJECTS]),
                                ("event", event_fixture)):
                with open(paths[name], "w", encoding="utf-8") as out:
                    json.dump(value, out)
            result = subprocess.run([sys.executable, __file__, "flush", "--prs-json", paths["prs"],
                                     "--commits-json", paths["commits"], "--event-json", paths["event"],
                                     "--now", str(T), "--window", str(W)], capture_output=True, text=True)
            # The arm/arm_head lines are asserted exactly; the candidates line separately below.
            return result.returncode, "".join(l for l in result.stdout.splitlines(True)
                                              if not l.startswith("candidates="))
        def fixture_candidates(prs_fixture, event_fixture):
            run_fixture(prs_fixture, event_fixture)
            result = subprocess.run([sys.executable, __file__, "flush", "--prs-json", paths["prs"],
                                     "--commits-json", paths["commits"], "--event-json", paths["event"],
                                     "--now", str(T + W), "--window", str(W)], capture_output=True, text=True)
            return [l for l in result.stdout.splitlines() if l.startswith("candidates=")]
        check("CLI emits both kinds as candidates so a held kind cannot starve the other",
              fixture_candidates(prs, {}) == ["candidates=11:chore/admin-ui-deploy-a 12:chore/gitops-auto-deploy-b"])
        check("manual producer completion expedites only its matching PR inside the window",
              run_fixture(prs + [manual_pr], event) == (0, "arm=21\narm_head=" + manual_pr["head"] + "\n"))
        sibling = {**manual_pr, "number": 22, "head": DEPLOY_PREFIXES[0] + sha + "-run102"}
        check("same-source producer completion cannot expedite another run's candidate",
              run_fixture([sibling], event) == (0, "arm=\narm_head=\n"))
        check("same-source second producer maps to its own candidate",
              run_fixture([sibling], {**event, "workflow_run": {**event["workflow_run"], "id": 102}})
              == (0, "arm=22\narm_head=" + sibling["head"] + "\n"))
        for bad_id in (None, 0, -1, True, "101"):
            check(f"invalid producer run identity {bad_id!r} cannot expedite",
                  manual_deploy_head({**event, "workflow_run": {**event["workflow_run"], "id": bad_id}}) is None)
        check("CLI manual producer completion waits for explicit repair priority",
              run_fixture(prs + [manual_pr, repair], event) == (0, "arm=\narm_head=\n"))
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
    dg = lambda c: f"@sha256:{c * 64}"
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
            f"--- a/g/{s}.yaml\n+++ b/g/{s}.yaml\n@@ -1 +1 @@\n-image: {reg}/openbank-{s}:{o}\n+image: {reg}/openbank-{s}:sandbox-111{dg('1')}\n"
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
        check("carry moves tag and digest together",
              carry(d, older, set(), lambda image, tag: True) != []
              and f"openbank-billing:sandbox-111{dg('1')}\n" in read("billing"))
        # The shape of #14905: a new tag over the digest of the tag it replaced.
        def hunk(old, new):
            return f"--- a/g/aml.yaml\n+++ b/g/aml.yaml\n@@ -1 +1 @@\n-image: {reg}/openbank-aml:{old}\n+image: {reg}/openbank-aml:{new}\n"
        retag = hunk(f"sandbox-aaa{dg('a')}", f"sandbox-bbb{dg('a')}")
        rebuilt = hunk(f"sandbox-aaa{dg('a')}", f"sandbox-bbb{dg('b')}")
        always = lambda image, tag: True
        check("a new tag over the old digest is refused", len(verify_pins(retag, always)) == 1)
        check("a new tag with its own digest passes", verify_pins(rebuilt, always) == [])
        check("a retag that drops the digest is refused",
              len(verify_pins(hunk(f"sandbox-aaa{dg('a')}", "sandbox-bbb"), always)) == 1)
        check("a gitops pin without a digest is refused",
              verify_pins(hunk("sandbox-aaa", "sandbox-bbb"), always, require_digest=True) != [])
        check("a per-run tag without a digest stays legal for admin-ui",
              verify_pins(hunk("sandbox-aaa-run1", "sandbox-bbb-run2"), always) == [])
        check("one tag naming two digests is refused",
              pin_identity_failures(f"+++ b/g/a.yaml\n+image: {reg}/openbank-aml:sandbox-b{dg('a')}\n"
                                    f"+image: {reg}/openbank-aml:sandbox-b{dg('b')}\n") != [])
        aml = os.path.join(d, "g", "aml.yaml")
        open(aml, "w").write(f"image: {reg}/openbank-aml:sandbox-aaa{dg('a')}\n")
        check("carry refuses a donor whose pin is a false pair",
              carry(d, retag, set(), always) == [] and "sandbox-aaa" in open(aml).read())
        check("carry refuses a donor pin with no digest",
              carry(d, hunk(f"sandbox-aaa{dg('a')}", "sandbox-bbb"), set(), always) == [])
        open(aml, "w").write(f"image: {reg}/openbank-aml:sandbox-aaa{dg('c')}\n")
        check("carry treats a moved digest under the same tag as moved",
              carry(d, rebuilt, set(), always) == [])
        open(aml, "w").write(f"image: {reg}/openbank-aml:sandbox-aaa{dg('a')}\n")
        check("carry replaces the whole old reference, leaving no stale digest",
              carry(d, rebuilt, set(), always) != []
              and open(aml).read() == f"image: {reg}/openbank-aml:sandbox-bbb{dg('b')}\n")
        check("verify refuses a diff without image pins", verify_pins("", lambda image, tag: True) != [])

        # 6. #15977: kyc went stale mid-build; treasury and sepa-instant must still deploy.
        def pin_hunk(svc, old, new):
            return (f"--- a/g/{svc}.yaml\n+++ b/g/{svc}.yaml\n@@ -1 +1 @@\n"
                    f"-image: {reg}/openbank-{svc}:{old}\n+image: {reg}/openbank-{svc}:{new}\n")
        mixed = "".join(pin_hunk(s, f"sandbox-old{dg(o)}", f"sandbox-03f9ba58{dg(n)}")
                        for s, o, n in (("treasury", "1", "2"), ("kyc", "3", "4"), ("sepa-instant", "5", "6")))
        only_kyc_stale = lambda image, tag: not image.endswith("/openbank-kyc")
        v = classify_pins(mixed, only_kyc_stale, require_digest=True)
        check("mixed fresh + stale: only the stale pin is stale",
              not v.refuse and [t[1].rsplit("/", 1)[-1] for t in v.stale] == ["openbank-kyc"]
              and len(v.fresh) == 2)
        pruned_diff = "".join(pin_hunk(s, f"sandbox-old{dg(o)}", f"sandbox-03f9ba58{dg(n)}")
                              for s, o, n in (("treasury", "1", "2"), ("sepa-instant", "5", "6")))
        kyc_text = f"a: {reg}/openbank-kyc:sandbox-03f9ba58{dg('4')}\nb: {reg}/openbank-kyc:sandbox-03f9ba58a{dg('4')}\n"
        check("revert_pins restores the old tag@digest of exactly that pin",
              revert_pins(kyc_text, v.stale) ==
              f"a: {reg}/openbank-kyc:sandbox-old{dg('3')}\nb: {reg}/openbank-kyc:sandbox-03f9ba58a{dg('4')}\n")
        try:
            revert_pins("nothing here\n", v.stale)
            check("revert_pins refuses a pin the file does not hold", False)
        except ValueError:
            check("revert_pins refuses a pin the file does not hold", True)

        def fake_gh(after):
            return lambda *args, stdin=None: subprocess.CompletedProcess(args, 0, after, "")
        pruned_with = []
        def fake_prune(repo, number, stale):
            pruned_with.append([t[1].rsplit("/", 1)[-1] for t in stale])
            return "a" * 40
        rc = prune_command("o/r", 15977, mixed, True, only_kyc_stale, fake_gh(pruned_diff), fake_prune)
        check("producer: mixed fresh + stale prunes ONLY the stale pin and proceeds (exit 3)",
              rc == 3 and pruned_with == [["openbank-kyc"]])
        pruned_with.clear()
        rc = prune_command("o/r", 15977, mixed, True, lambda image, tag: False, fake_gh(mixed), fake_prune)
        check("producer: every pin stale -> close the PR (exit 4), nothing pruned",
              rc == 4 and pruned_with == [])
        retagged = mixed + pin_hunk("aml", f"sandbox-aaa{dg('a')}", f"sandbox-bbb{dg('a')}")
        rc = prune_command("o/r", 15977, retagged, True, only_kyc_stale, fake_gh(retagged), fake_prune)
        check("producer: an identity failure still refuses the WHOLE PR (exit 1), nothing pruned",
              rc == 1 and pruned_with == [])
        rc = prune_command("o/r", 15977, mixed, True, only_kyc_stale, fake_gh(mixed), fake_prune)
        check("producer: a prune that did not take refuses (exit 1)", rc == 1)
        check("producer: all fresh -> exit 0, nothing pruned",
              prune_command("o/r", 15977, mixed, True, always, fake_gh(mixed), fake_prune) == 0
              and pruned_with == [["openbank-kyc"]])

        closed.clear(); tried.clear(); pruned = []
        def pruner(c, stale):
            pruned.append((c["number"], [t[1].rsplit("/", 1)[-1] for t in stale]))
            return True
        got_pick = select_candidate([cand_g, cand_a], lambda c: classify_pins(mixed, only_kyc_stale, True),
                                    sup({}), closer, pruner)
        check("flusher: mixed fresh + stale prunes only the stale pin, closes nothing, arms the next kind",
              pruned == [(12, ["openbank-kyc"])] and closed == [] and got_pick is cand_a and tried == [11])
        pruned.clear()
        select_candidate([cand_g, cand_a], lambda c: classify_pins(mixed, lambda i, t: False, True),
                         sup({}), closer, pruner)
        check("flusher: every pin stale closes the PR, prunes nothing", closed == [12] and pruned == [])
        closed.clear()
        select_candidate([cand_g, cand_a], lambda c: classify_pins(retagged, only_kyc_stale, True),
                         sup({}), closer, pruner)
        check("flusher: an identity failure refuses the whole PR, prunes nothing",
              closed == [12] and pruned == [])

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
    sub.add_parser("stuck", help="stdin: gh pr view --json mergeStateStatus,statusCheckRollup; prints true/false")
    sel = sub.add_parser("select", help="arm-candidate fall-through: verify + supersede per kind")
    sel.add_argument("--root", default=".")
    sel.add_argument("--candidates", required=True, help="space-separated number:head pairs from flush")
    v = sub.add_parser("verify")
    v.add_argument("--root", default=".")
    v.add_argument("--require-digest", action="store_true")
    pr = sub.add_parser("prune", help="stdin: the PR diff. Per-pin freshness; reverts only stale pins. "
                        "exit 0 all fresh, 1 refused whole, 3 stale pins pruned, 4 every pin stale")
    pr.add_argument("--root", default=".")
    pr.add_argument("--number", type=int, required=True)
    pr.add_argument("--require-digest", action="store_true")
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
        last = last_deploy_by_kind(json.load(open(a.commits_json)))
        manual_head = manual_deploy_head(json.load(open(a.event_json))) if a.event_json else None
        prs = json.load(open(a.prs_json))
        stuck = stuck_armed(prs)
        for p in stuck:
            print(f"::warning::deploy window: armed deploy PR #{p['number']} cannot merge; disarming it so its kind is not frozen (#14498)", file=sys.stderr)
        cands = flush_candidates(prs, a.now, last, a.window, manual_head)
        print(f"arm={cands[0]['number'] if cands else ''}")
        print(f"arm_head={cands[0]['head'] if cands else ''}")
        # number:head pairs in order; the workflow falls through to the next on a hold refusal.
        if stuck:
            print(f"disarm={' '.join(str(p['number']) for p in stuck)}")
        print("candidates=" + " ".join(f"{p['number']}:{p['head']}" for p in cands))
        return 0
    if a.cmd == "carry":
        for line in carry(a.root, sys.stdin.read(), set(a.skip_images.split()),
                          lambda image, tag: image_source_is_current(a.root, image, tag)):
            print(f"  [carried] {line}")
        return 0
    if a.cmd == "stuck":
        print("true" if is_stuck(json.load(sys.stdin)) else "false")
        return 0
    if a.cmd == "select":
        repo = os.environ["GITHUB_REPOSITORY"]
        cands = []
        for pair in a.candidates.split():
            number, _, head = pair.partition(":")
            if not number.isdigit() or not head.startswith(DEPLOY_PREFIXES):
                print(f"::error::unexpected deploy candidate {pair}", file=sys.stderr)
                return 1
            cands.append({"number": int(number), "head": head})

        def verify(c):
            # An unreadable diff refuses THIS kind only; raising here would abort the
            # fall-through and re-create the head-of-line block this command exists to remove.
            read = subprocess.run(["gh", "pr", "diff", str(c["number"]), "--repo", repo],
                                  capture_output=True, text=True)
            if read.returncode != 0:
                return [f"diff of #{c['number']} unreadable (gh rc {read.returncode})"]
            diff = read.stdout
            return classify_pins(diff, lambda image, tag: image_source_is_current(a.root, image, tag),
                                 require_digest=True)

        def prune(c, stale):
            try:
                oid = prune_stale_on_branch(repo, c["number"], stale)
            except (RuntimeError, ValueError) as e:
                print(f"::error::could not prune #{c['number']}: {e}", file=sys.stderr)
                return False
            print(f"::notice::pruned #{c['number']} at {oid[:8]}", file=sys.stderr)
            return True

        def supersede(c):
            prefix = next(x for x in DEPLOY_PREFIXES if c["head"].startswith(x))
            # stdout -> stderr: this command's own stdout is the step's GITHUB_OUTPUT.
            return subprocess.run(["bash", ".github/scripts/supersede-deploy-prs.sh", prefix, str(c["number"])],
                                  stdout=sys.stderr).returncode

        def close_stale(c, why):
            # A held PR (draft, or labelled `blocked`) is an owner's decision: leave it open.
            view = subprocess.run(["gh", "pr", "view", str(c["number"]), "--repo", repo,
                                   "--json", "isDraft,labels,state"], capture_output=True, text=True)
            try:
                meta = json.loads(view.stdout) if view.returncode == 0 else None
            except ValueError:
                meta = None
            if not meta or meta.get("state") != "OPEN" or meta.get("isDraft") \
                    or any(l.get("name") == "blocked" for l in meta.get("labels") or []):
                print(f"::notice::stale #{c['number']} left open (held, closed, or unreadable)", file=sys.stderr)
                return
            body = ("Closing: this deploy PR's image pin no longer matches main's source "
                    f"({why[0]}), so it can never pass 'Verify exact-head GitOps image source'. "
                    "The next build of the service on main opens a fresh pin.")
            done = subprocess.run(["gh", "pr", "close", str(c["number"]), "--repo", repo,
                                   "--comment", body], stdout=sys.stderr)
            print(f"::notice::closed stale deploy PR #{c['number']} (rc {done.returncode})", file=sys.stderr)

        pick = select_candidate(cands, verify, supersede, close_stale, prune)
        if pick is None:
            print("::error::every deploy kind was refused - nothing armed.", file=sys.stderr)
            return 1
        print(f"arm={pick['number']}")
        return 0
    if a.cmd == "prune":
        return prune_command(os.environ["GITHUB_REPOSITORY"], a.number, sys.stdin.read(), a.require_digest,
                             lambda image, tag: image_source_is_current(a.root, image, tag))
    if a.cmd == "verify":
        stale = verify_pins(sys.stdin.read(),
                            lambda image, tag: image_source_is_current(a.root, image, tag),
                            require_digest=a.require_digest)
        for pin in stale:
            print(f"::error::stale GitOps image pin: {pin}", file=sys.stderr)
        return 1 if stale else 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
