#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tag every deployed openbank-* image `live-<digest12>` so ECR lifecycle never expires it (#14189).

The service lifecycle policy (ecr-lifecycle-policies.tf) keeps the newest N `sandbox-*` builds
and does not know what is deployed: a build ranked beyond N expires while pods still run it, and
the outage surfaces only at the next pull (ImagePullBackOff). Its priority-1 rule never expires
an image carrying a `live-` tag, and ECR does not let a lower-priority rule expire an image a
higher-priority rule matched. This script owns that tag:

  protected = every openbank-* image referenced by gitops or the ARC runner pin at HEAD, plus
              every one referenced on main within the last WINDOW_DAYS (rollback targets and a
              Rollout's previous stable revision)
  tag       = put-image of the SAME manifest under `live-<first 12 hex of digest>` (idempotent;
              works for digest pins whose tag has moved, e.g. keycloak / ci-runner)
  untag     = remove `live-*` tags whose digest is no longer protected

`live-<digest12>` rather than one moving tag per repository: several digests per repository must
be protected at once (Rollout stable + canary, rollback history), and a name derived from the
digest is stable under IMMUTABLE_WITH_EXCLUSION, where a moving tag could never be re-pointed.

Fails closed: if any HEAD reference cannot be resolved to a digest, nothing is untagged.
`--dry-run` prints the plan; `--selftest` exercises the pure parts against known cases.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

WINDOW_DAYS = 14
LIVE_PREFIX = "live-"
SCAN_PATHS = ["openbank-infra/gitops", "openbank-infra/aws/envs/sandbox-platform/arc-runners.tf"]

# The repository, then an optional :tag, then an optional @digest. The host may be literal or a
# Terraform interpolation (arc-runners.tf), so anchor on the `/openbank-` path segment instead.
REF_RE = re.compile(
    r"(?:amazonaws\.com|\})/(openbank-[a-z0-9-]+)(?::([A-Za-z0-9][A-Za-z0-9._-]{0,127}))?(?:@(sha256:[0-9a-f]{64}))?"
)
MANIFEST_TYPES = [
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.docker.distribution.manifest.v2+json",
]


def parse_refs(text: str) -> set[tuple[str, str | None, str | None]]:
    """(repository, tag, digest) for every pinned reference; bare `repo:` mentions are skipped."""
    out = set()
    for repo, tag, digest in REF_RE.findall(text):
        if tag or digest:
            out.add((repo, tag or None, digest or None))
    return out


def live_tag(digest: str) -> str:
    return LIVE_PREFIX + digest.split(":", 1)[1][:12]


def plan(protected: dict[str, set[str]], live_now: dict[str, dict[str, str]],
         all_tags: dict[str, dict[str, str]] | None = None):
    """protected: repo -> digests; live_now: repo -> {live tag: digest}. Returns (add, remove).

    A live- tag is removed only when its image carries another tag. BatchDeleteImage deletes an
    image whose LAST tag it removes, so a live-only image (a digest pin whose tag moved) keeps its
    tag rather than being deleted here; it is reported instead.
    """
    all_tags = all_tags if all_tags is not None else live_now
    add, remove = [], []
    for repo, digests in sorted(protected.items()):
        have = set(live_now.get(repo, {}).values())
        for d in sorted(digests - have):
            add.append((repo, d))
    for repo, tags in sorted(live_now.items()):
        keep = protected.get(repo, set())
        for t, d in sorted(tags.items()):
            others = [x for x, xd in all_tags.get(repo, {}).items() if xd == d and x != t]
            if d not in keep and others:
                remove.append((repo, t))
    return add, remove


def sh(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def aws(*args: str) -> dict:
    return json.loads(sh("aws", "ecr", *args, "--output", "json") or "{}")


def refs_in_window() -> set[tuple[str, str | None, str | None]]:
    refs = set()
    for path in SCAN_PATHS:
        refs |= parse_refs(sh("git", "grep", "-h", "-E", "/openbank-", "HEAD", "--", path))
    log = sh("git", "log", f"--since={WINDOW_DAYS}.days", "--first-parent", "-p", "--format=", "HEAD", "--", *SCAN_PATHS)
    refs |= parse_refs("\n".join(l for l in log.splitlines() if l[:1] in "+-"))
    return refs


def head_refs() -> set[tuple[str, str | None, str | None]]:
    refs = set()
    for path in SCAN_PATHS:
        refs |= parse_refs(sh("git", "grep", "-h", "-E", "/openbank-", "HEAD", "--", path))
    return refs


def list_tags(repo: str) -> dict[str, str]:
    """tag -> digest for every tagged image in one repository (one paginated listing)."""
    imgs = aws("describe-images", "--repository-name", repo, "--filter", "tagStatus=TAGGED")["imageDetails"]
    return {t: i["imageDigest"] for i in imgs for t in i.get("imageTags", [])}


def resolve(refs, tags_by_repo: dict[str, dict[str, str]]):
    """repo -> digests. A tag that no longer exists (expired, or never pushed) is reported."""
    out: dict[str, set[str]] = {}
    unresolved = []
    for repo, tag, digest in sorted(refs, key=lambda r: tuple(x or "" for x in r)):
        if repo not in tags_by_repo:
            unresolved.append((repo, tag, digest, "no repository"))
            continue
        if not digest:
            digest = tags_by_repo[repo].get(tag)
            if not digest:
                unresolved.append((repo, tag, None, "tag not found"))
                continue
        out.setdefault(repo, set()).add(digest)
    return out, unresolved


def live_tags(tags_by_repo: dict[str, dict[str, str]]) -> dict[str, dict[str, str]]:
    out = {}
    for repo, tags in tags_by_repo.items():
        live = {t: d for t, d in tags.items() if t.startswith(LIVE_PREFIX)}
        if live:
            out[repo] = live
    return out


def put_live(repo: str, digest: str) -> None:
    img = aws("batch-get-image", "--repository-name", repo, "--image-ids", f"imageDigest={digest}",
              "--accepted-media-types", *MANIFEST_TYPES)["images"][0]
    try:
        sh("aws", "ecr", "put-image", "--repository-name", repo, "--image-tag", live_tag(digest),
           "--image-digest", digest, "--image-manifest", img["imageManifest"],
           "--image-manifest-media-type", img["imageManifestMediaType"])
    except subprocess.CalledProcessError as e:
        if "ImageAlreadyExistsException" not in e.stderr:
            raise


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()

    existing = {r["repositoryName"] for r in aws("describe-repositories")["repositories"]
                if r["repositoryName"].startswith("openbank-")}
    head_r, window_r = head_refs(), refs_in_window()
    tags_by_repo = {repo: list_tags(repo) for repo in sorted(existing & {r[0] for r in head_r | window_r})}
    # live- tags in repositories nothing references any more must still be found to be removed
    for repo in sorted(existing - set(tags_by_repo)):
        tags_by_repo[repo] = {}

    head, head_unresolved = resolve(head_r, tags_by_repo)
    protected, _ = resolve(window_r, tags_by_repo)  # history refs may legitimately be gone
    for repo, ds in head.items():
        protected.setdefault(repo, set()).update(ds)
    current = live_tags(tags_by_repo)
    add, remove = plan(protected, current, tags_by_repo)

    for u in head_unresolved:
        print(f"::warning::HEAD reference not resolvable: {u}")
    print(f"protected digests: {sum(len(v) for v in protected.values())} in {len(protected)} repositories")
    for repo, d in add:
        print(f"tag   {repo} {live_tag(d)} -> {d}")
    if head_unresolved:
        # Fail closed: a reference we cannot see might be one we must keep.
        print(f"::warning::{len(head_unresolved)} HEAD reference(s) unresolved — untagging nothing this run")
        remove = []
    for repo, t in remove:
        print(f"untag {repo} {t}")
    if a.dry_run:
        return 0
    for repo, d in add:
        put_live(repo, d)
    for repo, t in remove:
        sh("aws", "ecr", "batch-delete-image", "--repository-name", repo, "--image-ids", f"imageTag={t}")
    return 0


def selftest() -> int:
    d1, d2 = "sha256:" + "a" * 64, "sha256:" + "b" * 64
    text = (
        "image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-ledger-service:sandbox-1234abcd\n"
        f"image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-keycloak:26.8.0-optimized@{d1}\n"
        f'runner_image = "${{x}}.dkr.ecr.${{y}}.amazonaws.com/openbank-ci-runner@{d2}"\n'
        "pattern: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-*\n"
    )
    got = parse_refs(text)
    want = {
        ("openbank-ledger-service", "sandbox-1234abcd", None),
        ("openbank-keycloak", "26.8.0-optimized", d1),
        ("openbank-ci-runner", None, d2),
    }
    assert got == want, got
    assert live_tag(d1) == "live-aaaaaaaaaaaa"
    # Known positive: an unprotected live tag is removed, a protected digest is tagged.
    add, remove = plan({"r": {d1}}, {"r": {"live-bbbbbbbbbbbb": d2}}, {"r": {"live-bbbbbbbbbbbb": d2, "sandbox-x": d2}})
    assert add == [("r", d1)] and remove == [("r", "live-bbbbbbbbbbbb")], (add, remove)
    # A live- tag that is its image's only tag is never removed (removal would delete the image).
    assert plan({"r": set()}, {"r": {"live-bbbbbbbbbbbb": d2}}, {"r": {"live-bbbbbbbbbbbb": d2}}) == ([], [])
    # Known negative: already tagged and protected -> nothing to do.
    assert plan({"r": {d1}}, {"r": {live_tag(d1): d1}}) == ([], [])
    print("SUBJECTS=5")
    print("ecr-live-tags selftest: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
