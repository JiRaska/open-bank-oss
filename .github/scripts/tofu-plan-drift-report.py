#!/usr/bin/env python3
"""Report a scheduled OpenTofu plan verdict without publishing plan contents.

The plan job has cloud read credentials and no issue write token. This script runs
in a separate job with issue write access and receives only a three-state verdict.
"""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request

ROOTS = {"platform", "substrate"}
VERDICTS = {"clean", "drift", "error"}


def classify(exit_code: int) -> str:
    # OpenTofu plan -detailed-exitcode: 0 empty, 1 error, 2 non-empty.
    return {0: "clean", 2: "drift"}.get(exit_code, "error")


def title(root: str) -> str:
    return f"OpenTofu plan needs review: {root}"


def marker(root: str, verdict: str) -> str:
    return f"<!-- tofu-plan-watch:{root} status={verdict} -->"


def body(root: str, verdict: str, run_url: str) -> str:
    summary = {
        "drift": "The scheduled read-only plan found pending changes. Review the plan and decide whether a manual apply is appropriate.",
        "error": "The scheduled read-only plan could not return a verdict. Check the probe before treating infrastructure as in sync.",
    }[verdict]
    return f"{marker(root, verdict)}\n\n{summary}\n\nEnvironment: sandbox {root}. Example run: {run_url}\n\nNo plan contents are copied into this issue."


def reconcile(client, root: str, verdict: str, run_url: str) -> str:
    if root not in ROOTS or verdict not in VERDICTS:
        raise ValueError("invalid root or verdict")
    matches = [item for item in client.find(title(root)) if item.get("title") == title(root)]
    if len(matches) > 1:
        raise RuntimeError("multiple exact-title OpenTofu plan issues; manual deduplication required")
    issue = matches[0] if matches else None
    if issue and f"tofu-plan-watch:{root} " not in (issue.get("body") or ""):
        raise RuntimeError("exact-title issue is not owned by the OpenTofu plan watch")
    if verdict == "clean":
        if issue and issue["state"] == "open":
            client.patch(issue["number"], {"state": "closed"})
            return "closed stale finding"
        return "no open finding"
    desired = body(root, verdict, run_url)
    if not issue:
        client.create({"title": title(root), "body": desired})
        return "created finding"
    old_verdict = next((v for v in VERDICTS if marker(root, v) in (issue.get("body") or "")), None)
    update = {}
    if issue["state"] != "open":
        update["state"] = "open"
    if old_verdict != verdict:
        update["body"] = desired
    if update:
        client.patch(issue["number"], update)
        return "reopened or updated finding"
    return "finding already open"


class GithubIssues:
    def __init__(self, repository: str, token: str):
        if not token:
            raise RuntimeError("issue write token unavailable")
        self.repository = repository
        self.token = token

    def request(self, method: str, path: str, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(
            f"https://api.github.com{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"GitHub issue API returned HTTP {exc.code}") from None
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError("GitHub issue API unavailable") from None

    def find(self, exact_title: str):
        query = urllib.parse.urlencode({"q": f'repo:{self.repository} is:issue in:title "{exact_title}"', "per_page": 100})
        result = self.request("GET", f"/search/issues?{query}")
        if result.get("total_count", 0) > 100:
            raise RuntimeError("too many candidate OpenTofu plan issues")
        return result["items"]

    def create(self, payload):
        return self.request("POST", f"/repos/{self.repository}/issues", payload)

    def patch(self, number: int, payload):
        return self.request("PATCH", f"/repos/{self.repository}/issues/{number}", payload)


def self_test() -> None:
    assert [classify(i) for i in (0, 2, 1, 127)] == ["clean", "drift", "error", "error"]

    class Fake:
        def __init__(self):
            self.items = []
            self.calls = []

        def find(self, _):
            return self.items

        def create(self, payload):
            self.calls.append(("create", payload))
            self.items = [{"number": 1, "state": "open", **payload}]

        def patch(self, number, payload):
            self.calls.append(("patch", payload))
            self.items[0].update(payload)

    client = Fake()
    assert reconcile(client, "platform", "clean", "https://example.invalid/1") == "no open finding"
    assert reconcile(client, "platform", "drift", "https://example.invalid/2") == "created finding"
    assert len(client.calls) == 1 and "https://example.invalid/2" in client.items[0]["body"]
    assert reconcile(client, "platform", "drift", "https://example.invalid/3") == "finding already open"
    assert len(client.calls) == 1  # daily duplicates do not update or notify
    assert reconcile(client, "platform", "error", "https://example.invalid/4") == "reopened or updated finding"
    assert "could not return a verdict" in client.items[0]["body"]
    assert reconcile(client, "platform", "clean", "https://example.invalid/5") == "closed stale finding"
    assert client.items[0]["state"] == "closed"
    assert reconcile(client, "platform", "drift", "https://example.invalid/6") == "reopened or updated finding"
    assert client.items[0]["state"] == "open"
    client.items.append({"number": 2, "title": title("platform"), "body": marker("platform", "drift"), "state": "open"})
    try:
        reconcile(client, "platform", "drift", "https://example.invalid/7")
    except RuntimeError as exc:
        assert "multiple exact-title" in str(exc)
    else:
        raise AssertionError("duplicate issue was accepted")
    print("tofu plan verdict/report self-test: OK")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--classify", type=int)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", choices=sorted(ROOTS))
    parser.add_argument("--verdict", choices=sorted(VERDICTS))
    parser.add_argument("--run-url")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.classify is not None:
        print(f"verdict={classify(args.classify)}")
    elif args.root and args.verdict and args.run_url:
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        if repository.count("/") != 1:
            raise RuntimeError("repository identity unavailable")
        result = reconcile(GithubIssues(repository, os.environ.get("GH_TOKEN", "")), args.root, args.verdict, args.run_url)
        print(result)
    else:
        parser.error("choose --self-test, --classify, or --root/--verdict/--run-url")


if __name__ == "__main__":
    main()
