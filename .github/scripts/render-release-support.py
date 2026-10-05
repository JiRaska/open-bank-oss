#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render a release's signed evidence support date into its public release page (#9881).

The evidence bundle remains the sole data source. This command emits only the GitHub
release PATCH body; the workflow publishes it after attaching the signed bundle.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path
from urllib.parse import quote

START = "<!-- openbank-support-period:start -->"
END = "<!-- openbank-support-period:end -->"
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
TAG_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def support_block(evidence: dict, repository: str) -> str:
    tag = evidence.get("tag")
    support = evidence.get("support")
    if not isinstance(tag, str) or not TAG_RE.fullmatch(tag):
        raise ValueError("evidence has no safe release tag")
    if not REPO_RE.fullmatch(repository):
        raise ValueError("invalid repository identity")
    if not isinstance(support, dict):
        raise TypeError("evidence has no support block")
    policy = support.get("policy")
    released_on = support.get("released_on")
    if not isinstance(released_on, str):
        raise TypeError("support release date is missing")
    dt.date.fromisoformat(released_on)
    if policy == "production":
        end = support.get("end_of_support")
        if support.get("open_ended") is not False or not isinstance(end, str):
            raise ValueError("production support has no determined end date")
        dt.date.fromisoformat(end)
        statement = f"Supported through **{end}** (five years from the release date)."
    elif policy == "beta":
        floor = support.get("end_of_support_floor")
        if (
            support.get("open_ended") is not True
            or not isinstance(floor, str)
            or "end_of_support" in support
        ):
            raise ValueError(
                "beta support must have an open-ended floor, not an invented end date"
            )
        dt.date.fromisoformat(floor)
        statement = (
            f"Supported at least through **{floor}**. The final end-of-support date also depends "
            "on when a superseding release is published (see SECURITY.md)."
        )
    else:
        raise ValueError("unknown support policy")
    asset = f"https://github.com/{repository}/releases/download/{quote(tag)}/{quote(tag)}.evidence.json"
    return (
        f"{START}\n"
        "### Support period\n\n"
        f"{statement}\n\n"
        f"[Signed release evidence]({asset}) · [Signature]({asset}.sig)\n"
        f"{END}"
    )


def update_body(release: dict, evidence: dict, repository: str) -> dict[str, str]:
    if release.get("tag_name") != evidence.get("tag"):
        raise ValueError("release tag and evidence tag differ")
    body = release.get("body")
    if body is None:
        body = ""
    if not isinstance(body, str):
        raise TypeError("release body is not text")
    block = support_block(evidence, repository)
    if body.count(START) != body.count(END) or body.count(START) > 1:
        raise ValueError("release body contains malformed support markers")
    if START in body:
        begin, remainder = body.split(START, 1)
        _, finish = remainder.split(END, 1)
        body = begin.rstrip() + "\n\n" + block + finish
    else:
        body = body.rstrip() + "\n\n" + block if body.strip() else block
    return {"body": body}


def self_test() -> int:
    release = {"tag_name": "fixture-v1.2.3", "body": "## Changes\n\nExisting notes.\n"}
    evidence = {
        "tag": "fixture-v1.2.3",
        "support": {
            "policy": "production",
            "released_on": "2026-09-13",
            "end_of_support": "2031-09-13",
            "open_ended": False,
        },
    }
    original = update_body(release, evidence, "owner/repo")["body"]
    if "Existing notes." not in original or "2031-09-13" not in original:
        raise AssertionError(
            "production support date is not visible alongside the notes"
        )
    if (
        update_body({**release, "body": original}, evidence, "owner/repo")["body"]
        != original
    ):
        raise AssertionError(
            "publishing the same evidence twice changes the release body"
        )
    beta = {
        "tag": "fixture-v0.9.0",
        "support": {
            "policy": "beta",
            "released_on": "2026-09-13",
            "end_of_support_floor": "2027-09-13",
            "open_ended": True,
        },
    }
    beta_body = update_body({"tag_name": beta["tag"], "body": ""}, beta, "owner/repo")[
        "body"
    ]
    if (
        "at least through **2027-09-13**" not in beta_body
        or "final end-of-support date" not in beta_body
    ):
        raise AssertionError("beta floor was rendered as a final date")
    for bad_release, bad_evidence in (
        (release, {**evidence, "tag": "wrong-v1.2.3"}),
        (release, {**evidence, "support": {}}),
        ({**release, "body": START}, evidence),
        (
            release,
            {**evidence, "support": {**evidence["support"], "end_of_support": "bad"}},
        ),
    ):
        try:
            update_body(bad_release, bad_evidence, "owner/repo")
        except (ValueError, TypeError):
            continue
        raise AssertionError("invalid support input was accepted")
    workflow = (
        Path(__file__).resolve().parents[2] / ".github/workflows/release-please.yml"
    ).read_text()
    upload = workflow.find("for f in $assets; do")
    render = workflow.find("python3 .github/scripts/render-release-support.py")
    publish = workflow.find("curl -fsSL -X PATCH")
    if not (0 <= upload < render < publish):
        raise AssertionError(
            "release pipeline does not publish support after signed evidence upload"
        )
    print("release support rendering self-test: PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("evidence", type=Path, nargs="?")
    parser.add_argument("--repository")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.evidence or not args.repository:
        parser.error("evidence and --repository are required")
    evidence = json.loads(args.evidence.read_text())
    release = json.load(sys.stdin)
    print(json.dumps(update_body(release, evidence, args.repository)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
