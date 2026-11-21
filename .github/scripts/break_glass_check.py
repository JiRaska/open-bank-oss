#!/usr/bin/env python3
"""Owner break-glass for the `merge-train` required status (ADR-0343 amendment, #17006).

When the merge train itself is broken it cannot post `merge-train`, and every PR — including the
one that fixes the train — is unmergeable. This script lets the REPOSITORY OWNER post that one
status on one PR head, after re-deriving, live, everything the ruleset would otherwise demand:

  * the dispatcher is the repository owner;
  * the PR is open, not a draft, targets main, and has native auto-merge armed;
  * its head is not behind live main;
  * EVERY other required context of the rules that apply to main (read live from
    GET /rules/branches/main, never hardcoded) is `success` on THAT head, counting only check
    runs from the integration the ruleset pins the context to;
  * the PR does not remove or loosen a `deploy_holds` entry, nor edit `money_path_services`,
    in openbank-libs/governance/rules.yaml.

`.github/` paths are deliberately ALLOWED: the break-glass exists to land a fix to the train, and
that fix lives under .github/. What stays refused is anything whose purpose is to lift a control
the train is not the guardian of — a deploy hold (a release freeze a human placed) or the
money-path list (which decides who needs two approvals and a threat model). A PR that does that
must wait for a working train.

The status is posted with the same App the ruleset pins `merge-train` to; every use leaves a job
summary, a PR comment and a comment on the standing tracking issue, for post-use review.

Subcommands:
  run --repo R --pr N --actor A --owner O --reason-env VAR   evaluate, then post + audit
  --self-test                                                must-pass and must-FAIL cases
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

STATUS_CONTEXT_NAME = "merge-train"   # not a STATUS_CONTEXT declaration: the train owns that name
RULES_PATH = "openbank-libs/governance/rules.yaml"
PROTECTED_BLOCKS_NO_REMOVAL = ("deploy_holds",)
PROTECTED_BLOCKS_NO_CHANGE = ("money_path_services",)
TRACKING_TITLE = "merge-train break-glass log"
TRACKING_LABEL = "governance"
PR_RE = re.compile(r"^[0-9]{1,6}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
REASON_MAX = 500


# ----------------------------------------------------------------------------- pure logic
def top_level_block(text: str | None, key: str) -> list[str]:
    """Lines of the top-level YAML mapping `key:` (up to the next top-level key), comments dropped."""
    if text is None:
        return []
    out, inside = [], False
    for line in text.splitlines():
        if re.match(rf"^{re.escape(key)}\s*:", line):
            inside = True
            out.append(line.split("#", 1)[0].rstrip())
            continue
        if inside:
            if line and not line[0].isspace() and not line.startswith("#"):
                break
            s = line.split("#", 1)[0].rstrip()
            if s.strip():
                out.append(s)
    return out


def rules_refusals(base_text: str | None, head_text: str | None) -> list[str]:
    why = []
    for key in PROTECTED_BLOCKS_NO_REMOVAL:
        base, head = top_level_block(base_text, key), top_level_block(head_text, key)
        gone = [ln for ln in base if ln not in head]
        if gone:
            why.append(f"rules.yaml `{key}` loses {len(gone)} line(s) — a hold is lifted by review, never by break-glass")
    for key in PROTECTED_BLOCKS_NO_CHANGE:
        if top_level_block(base_text, key) != top_level_block(head_text, key):
            why.append(f"rules.yaml `{key}` changes — money-path classification is never break-glassed")
    return why


def evaluate(f: dict) -> list[str]:
    """Every reason to refuse. Empty list = may post. `f` is the live facts dict."""
    why = []
    if not f.get("actor") or f.get("actor") != f.get("owner"):
        why.append(f"actor {f.get('actor')!r} is not the repository owner")
    pr = f.get("pr") or {}
    if pr.get("state") != "open":
        why.append(f"PR is {pr.get('state')!r}, not open")
    if pr.get("draft") is not False:
        why.append("PR is a draft (or draft state unknown)")
    if pr.get("base") != "main":
        why.append(f"PR base is {pr.get('base')!r}, not main")
    if not pr.get("auto_merge"):
        why.append("native auto-merge is not armed")
    head = pr.get("head") or ""
    if not SHA_RE.fullmatch(head):
        why.append(f"head {head!r} is not a full sha")
    behind = f.get("behind_by")
    if type(behind) is not int or behind != 0:
        why.append(f"head is not up to date with main (behind_by={behind!r})")
    required = f.get("required") or {}
    others = {c: i for c, i in required.items() if c != STATUS_CONTEXT_NAME}
    if STATUS_CONTEXT_NAME not in required:
        why.append("`merge-train` is not a required context on main — break-glass has nothing to unblock")
    if not others:
        why.append("no other required contexts resolved — refusing to judge green")
    runs = f.get("check_runs") or []
    for ctx, integ in sorted(others.items()):
        mine = [r for r in runs if r.get("name") == ctx and (integ is None or r.get("app_id") == integ)]
        if not mine:
            why.append(f"required context never reported on head: {ctx}")
            continue
        latest = max(mine, key=lambda r: r.get("id", 0))
        if latest.get("status") != "completed":
            why.append(f"required context pending: {ctx}")
        elif latest.get("conclusion") != "success":
            why.append(f"required context {latest.get('conclusion')}: {ctx}")
    why += rules_refusals(f.get("rules_base"), f.get("rules_head"))
    reason = f.get("reason") or ""
    if not reason.strip():
        why.append("reason is empty")
    elif len(reason) > REASON_MAX:
        why.append(f"reason longer than {REASON_MAX} characters")
    return why


def description(actor: str, reason: str) -> str:
    flat = " ".join(reason.split())
    return f"break-glass by {actor}: {flat[:60]}"[:140]


# ----------------------------------------------------------------------------- live facts
class Api:
    def __init__(self, repo: str, token: str):
        self.repo, self.token = repo, token

    def call(self, method: str, path: str, body: dict | None = None, raw: bool = False):
        url = path if path.startswith("https://") else f"https://api.github.com{path}"
        req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/vnd.github.raw" if raw else "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = r.read()
                return r.status, (data.decode() if raw else (json.loads(data) if data else None))
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")

    def get(self, path: str, raw: bool = False):
        code, data = self.call("GET", path, raw=raw)
        if code != 200:
            raise SystemExit(f"::error::GET {path} -> {code}: {str(data)[:300]}")
        return data

    def pages(self, path: str, key: str | None = None):
        out, page = [], 1
        while True:
            sep = "&" if "?" in path else "?"
            data = self.get(f"{path}{sep}per_page=100&page={page}")
            items = data[key] if key else data
            out += items
            if len(items) < 100:
                return out
            page += 1


def gather(api: Api, pr_number: int, actor: str, owner: str, reason: str) -> dict:
    r = f"/repos/{api.repo}"
    p = api.get(f"{r}/pulls/{pr_number}")
    head = p["head"]["sha"]
    pr = {"state": p["state"], "draft": p["draft"], "base": p["base"]["ref"], "head": head,
          "auto_merge": bool(p.get("auto_merge"))}
    required: dict[str, int | None] = {}
    for rule in api.pages(f"{r}/rules/branches/main"):
        if rule.get("type") == "required_status_checks":
            for c in rule["parameters"]["required_status_checks"]:
                required[c["context"]] = c.get("integration_id")
    cmp = api.get(f"{r}/compare/main...{head}")
    runs = [{"id": x["id"], "name": x["name"], "status": x["status"], "conclusion": x["conclusion"],
             "app_id": (x.get("app") or {}).get("id")}
            for x in api.pages(f"{r}/commits/{head}/check-runs?filter=latest", key="check_runs")]
    files = {x["filename"] for x in api.pages(f"{r}/pulls/{pr_number}/files")}
    rules_base = rules_head = None
    if RULES_PATH in files:
        main_sha = api.get(f"{r}/commits/main")["sha"]
        q = urllib.parse.quote(RULES_PATH)
        rules_base = api.get(f"{r}/contents/{q}?ref={main_sha}", raw=True)
        code, rules_head = api.call("GET", f"{r}/contents/{q}?ref={head}", raw=True)
        if code != 200:
            rules_head = ""          # deleted at head: every protected line is gone
    return {"actor": actor, "owner": owner, "pr": pr, "behind_by": cmp.get("behind_by"),
            "required": required, "check_runs": runs, "rules_base": rules_base,
            "rules_head": rules_head, "reason": reason}


def audit(api: Api, pr_number: int, head: str, actor: str, reason: str, run_url: str) -> None:
    r = f"/repos/{api.repo}"
    text = (f"**merge-train break-glass** used by @{actor} on #{pr_number} at `{head}`.\n\n"
            f"Reason (verbatim, untrusted text):\n\n```text\n{reason.replace('```', '` ` `')}\n```\n\n"
            f"Run: {run_url}\n\nPost-use review: confirm the train was genuinely unable to post, "
            f"and that the fix landed (ADR-0343, break-glass amendment).")
    api.call("POST", f"{r}/issues/{pr_number}/comments", {"body": text})
    q = urllib.parse.quote(f'repo:{api.repo} is:issue is:open in:title "{TRACKING_TITLE}"')
    hits = [i for i in api.get(f"/search/issues?q={q}")["items"] if i["title"] == TRACKING_TITLE]
    if hits:
        n = hits[0]["number"]
    else:
        code, data = api.call("POST", f"{r}/issues", {
            "title": TRACKING_TITLE, "labels": [TRACKING_LABEL],
            "body": "Every use of the merge-train break-glass workflow is appended here for after-the-fact "
                    "review (ADR-0343 amendment). Close an entry's review by replying under it."})
        if code != 201:
            raise SystemExit(f"::error::could not open tracking issue -> {code}: {str(data)[:300]}")
        n = data["number"]
    api.call("POST", f"{r}/issues/{n}/comments", {"body": text})
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text + f"\n\nTracking issue: #{n}\n")


def cmd_run(a) -> int:
    if not PR_RE.fullmatch(a.pr or ""):
        raise SystemExit("::error::pr must match ^[0-9]{1,6}$")
    reason = os.environ.get(a.reason_env, "")
    api = Api(a.repo, os.environ["GH_TOKEN"])
    facts = gather(api, int(a.pr), a.actor, a.owner, reason)
    why = evaluate(facts)
    if why:
        for w in why:
            print(f"::error::refused: {w}")
        return 1
    head = facts["pr"]["head"]
    again = gather(api, int(a.pr), a.actor, a.owner, reason)   # the head must not move under us
    if again["pr"]["head"] != head or evaluate(again):
        print("::error::refused: PR changed between evaluation and posting")
        return 1
    code, data = api.call("POST", f"/repos/{a.repo}/statuses/{head}", {
        "state": "success", "context": STATUS_CONTEXT_NAME,
        "description": description(a.actor, reason), "target_url": a.run_url})
    if code != 201:
        raise SystemExit(f"::error::POST status -> {code}: {str(data)[:300]}")
    print(f"posted {STATUS_CONTEXT_NAME}=success on {head}")
    audit(api, int(a.pr), head, a.actor, reason, a.run_url)
    return 0


# ----------------------------------------------------------------------------- self-test
def _good() -> dict:
    h = "a" * 40
    req = {"all-green": 15368, "Gitleaks": 15368, "merge-train": 4315266}
    runs = [{"id": 1, "name": "all-green", "status": "completed", "conclusion": "success", "app_id": 15368},
            {"id": 2, "name": "Gitleaks", "status": "completed", "conclusion": "success", "app_id": 15368}]
    rules = "x: 1\ndeploy_holds:\n  - service: a\n    until: z\nmoney_path_services:\n  - ledger\ny: 2\n"
    return {"actor": "own", "owner": "own", "reason": "train broken, fix in this PR",
            "pr": {"state": "open", "draft": False, "base": "main", "head": h, "auto_merge": True},
            "behind_by": 0, "required": req, "check_runs": runs, "rules_base": rules, "rules_head": rules}


def self_test(sabotage_context_check: bool = False) -> int:
    global evaluate
    real = evaluate
    if sabotage_context_check:          # proves the suite can see the context check's absence
        def evaluate(f):                # noqa: E306
            g = dict(f, check_runs=[{"id": 9, "name": c, "status": "completed", "conclusion": "success",
                                     "app_id": i} for c, i in (f.get("required") or {}).items()])
            return real(g)
    fails = 0

    def case(name, mut, want_pass, needle=None):
        nonlocal fails
        f = _good()
        mut(f)
        why = evaluate(f)
        ok = (not why) if want_pass else (bool(why) and (needle is None or any(needle in w for w in why)))
        print(f"{'PASS' if ok else 'FAIL'}: {name}{'' if ok else f' -> {why}'}")
        fails += 0 if ok else 1

    def red(f): f["check_runs"][1]["conclusion"] = "failure"
    def missing(f): f["check_runs"].pop()
    def pending(f): f["check_runs"][0].update(status="in_progress", conclusion=None)
    def wrong_app(f): f["check_runs"][1]["app_id"] = 999
    def hold_removed(f): f["rules_head"] = f["rules_base"].replace("  - service: a\n    until: z\n", "")
    def money(f): f["rules_head"] = f["rules_base"].replace("  - ledger\n", "")

    case("good facts post", lambda f: None, True)
    case("hold ADDED is allowed", lambda f: f.update(rules_head=f["rules_base"].replace("money_path", "  - service: b\nmoney_path")), True)
    case("non-owner actor refused", lambda f: f.update(actor="someone"), False, "not the repository owner")
    case("missing required context refused", missing, False, "never reported")
    case("red context refused", red, False, "failure: Gitleaks")
    case("pending context refused", pending, False, "pending")
    case("same-name run from another app ignored", wrong_app, False, "never reported")
    case("stale head refused", lambda f: f.update(behind_by=3), False, "not up to date")
    case("unknown behind_by refused", lambda f: f.update(behind_by=None), False, "not up to date")
    case("draft refused", lambda f: f["pr"].update(draft=True), False, "draft")
    case("closed refused", lambda f: f["pr"].update(state="closed"), False, "not open")
    case("non-main base refused", lambda f: f["pr"].update(base="feature"), False, "not main")
    case("not armed refused", lambda f: f["pr"].update(auto_merge=False), False, "auto-merge")
    case("deploy_holds removal refused", hold_removed, False, "deploy_holds")
    case("rules.yaml deleted refused", lambda f: f.update(rules_head=""), False, "deploy_holds")
    case("money_path_services change refused", money, False, "money_path_services")
    case("no required contexts refused", lambda f: f.update(required={"merge-train": 1}), False, "no other required")
    case("empty reason refused", lambda f: f.update(reason="  "), False, "reason is empty")
    case("long reason refused", lambda f: f.update(reason="x" * 501), False, "longer than")
    d = description("own", "line one\nline two " + "y" * 100)
    ok = d.startswith("break-glass by own: line one line two") and "\n" not in d and len(d) <= 140
    print(f"{'PASS' if ok else 'FAIL'}: description is single-line and bounded")
    fails += 0 if ok else 1
    ok = all(PR_RE.fullmatch(x) for x in ("1", "17006")) and not any(PR_RE.fullmatch(x) for x in ("", "1234567", "1;rm", "-1"))
    print(f"{'PASS' if ok else 'FAIL'}: pr input pattern")
    fails += 0 if ok else 1
    evaluate = real
    print(f"self-test: {fails} failure(s)")
    return 1 if fails else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test("--sabotage-context-check" in sys.argv)
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    for k in ("--repo", "--pr", "--actor", "--owner", "--reason-env", "--run-url"):
        r.add_argument(k, required=True)
    return cmd_run(ap.parse_args())


if __name__ == "__main__":
    sys.exit(main())
