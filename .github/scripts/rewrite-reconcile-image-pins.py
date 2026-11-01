#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rewrite sandbox GitOps image references to this run's pushed digests."""

from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def rewrite(
    root: Path, registry: str, tags_json: str, digests_json: str,
    *, publication_context: tuple[str, str, str] | None = None,
) -> dict[str, int]:
    tags = json.loads(tags_json, object_pairs_hook=_unique_object)
    digests = json.loads(digests_json, object_pairs_hook=_unique_object)
    if not isinstance(tags, dict) or not isinstance(digests, dict) or set(tags) != set(digests):
        raise ValueError("tag and digest maps must cover exactly the same services")

    publication = None
    if publication_context is not None:
        event, reconcile, source = publication_context
        if event == "schedule" or (event == "workflow_dispatch" and reconcile == "true"):
            if not re.fullmatch(r"[0-9a-f]{40}", source):
                raise ValueError("invalid immutable publication source")
            # Fixed repository helper, never executable code selected by CLI/environment.
            spec = importlib.util.spec_from_file_location(
                "deploy_publication_race", Path(__file__).with_name("deploy-publication-race.py")
            )
            assert spec is not None and spec.loader is not None
            publication = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(publication)

    gitops = root / "openbank-infra/gitops/components"
    if not gitops.is_dir():
        raise ValueError(f"GitOps component directory is missing: {gitops}")

    manifests = sorted((*gitops.rglob("*.yaml"), *gitops.rglob("*.yml")))
    original_contents = {
        manifest: manifest.read_text(encoding="utf-8") for manifest in manifests
    }
    updated_contents = dict(original_contents)
    counts: dict[str, int] = {}
    for service, tag in tags.items():
        if not isinstance(service, str) or not re.fullmatch(r"openbank-[a-z0-9-]+", service):
            raise ValueError(f"invalid service name: {service!r}")
        if not isinstance(tag, str) or not re.fullmatch(r"sandbox-[A-Za-z0-9._-]+", tag):
            raise ValueError(f"invalid sandbox image tag for {service}")
        digest = digests[service]
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError(f"invalid pushed image digest for {service}")

        image = f"{registry}/{service}"
        pattern = re.compile(
            r"(?m)^(?P<prefix>[ \t]*image:[ \t]*)(?P<quote>[\"']?)"
            + re.escape(image)
            + r":(?P<tag>sandbox-[A-Za-z0-9._-]+)(?:@(?P<digest>sha256:[0-9a-f]{64}))?"
            + r"(?P=quote)(?P<suffix>[ \t]*(?:\#.*)?)$"
        )

        count = 0
        image_field = re.compile(r"^[ \t]*image:[ \t]*[\"']?" + re.escape(image) + ":")
        for manifest, original in updated_contents.items():
            initial_tag = None
            if publication is not None and (
                sum(bool(image_field.match(line)) for line in original.splitlines())
                != len(list(pattern.finditer(original)))
            ):
                raise ValueError(f"malformed selected image reference: {manifest.relative_to(root)}")
            if publication is not None and pattern.search(original):
                relative = manifest.relative_to(root).as_posix()
                initial = publication.git('show', f'{source}:{relative}', root=root).decode('utf-8')
                # Use the writer's exact parser for both revisions. Ambiguous initial pins
                # cannot be assigned a single prior identity by taking only the first one.
                if (sum(bool(image_field.match(line)) for line in initial.splitlines())
                        != len(list(pattern.finditer(initial)))):
                    raise ValueError(f"malformed selected image reference: {relative}")
                initial_refs = {(m['tag'], m['digest']) for m in pattern.finditer(initial)}
                if len(initial_refs) != 1:
                    raise ValueError(f"missing or ambiguous initial image reference: {relative}")
                initial_tag = next(iter(initial_refs))[0]

            def replace_image(
                match: re.Match[str], *, image: str = image, tag: str = tag,
                digest: str = digest, initial_tag: str | None = initial_tag,
            ) -> str:
                if publication is not None:
                    verdict = publication.decision(
                        event, reconcile, initial_tag, match['tag'], tag, source, root=root
                    )
                    if verdict == 'preserve':
                        return match.group(0)
                    if verdict != 'publish':
                        raise ValueError("unrecognized concurrent publication decision")
                return (
                    f"{match.group('prefix')}{match.group('quote')}"
                    f"{image}:{tag}@{digest}{match.group('quote')}{match.group('suffix')}"
                )

            updated, changed = pattern.subn(replace_image, original)
            if changed:
                updated_contents[manifest] = updated
                count += changed
        if count == 0:
            raise ValueError(
                f"no GitOps sandbox image reference found for selected service {service}"
            )
        counts[service] = count
    for manifest, updated in updated_contents.items():
        if updated != original_contents[manifest]:
            manifest.write_text(updated, encoding="utf-8")
    return counts


def main() -> int:
    if len(sys.argv) not in (5, 9) or (len(sys.argv) == 9 and sys.argv[5] != '--preserve-concurrent'):
        print(f"usage: {sys.argv[0]} <root> <registry> <tags-json> <digests-json> "
              "[--preserve-concurrent <event> <reconcile> <source-sha>]", file=sys.stderr)
        return 2
    try:
        counts = rewrite(Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4],
                         publication_context=tuple(sys.argv[6:9]) if len(sys.argv) == 9 else None)
    except (OSError, ValueError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"reconcile image pin rewrite rejected: {exc}", file=sys.stderr)
        return 1
    for service, count in counts.items():
        if len(sys.argv) == 9:
            print(f"[{service}] processed {count} manifest image(s): exact digest or proven concurrent pin")
        else:
            print(f"[{service}] pinned {count} manifest image(s) by exact digest")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
