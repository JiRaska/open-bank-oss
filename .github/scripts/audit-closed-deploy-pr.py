# SPDX-License-Identifier: Apache-2.0
"""Audit image pins in a closed, unmerged bot deploy PR against current main.

Consumes the conservative --json output from deploy-pr-image-keys.py. An image is
covered only by the exact same pin or by a pin built from a proven descendant
commit. Unknown is never treated as covered. This does not authorize deployment.
"""

import argparse
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

PARSER_PATH = Path(__file__).with_name("deploy-pr-image-keys.py")
spec = importlib.util.spec_from_file_location("deploy_pr_image_keys", PARSER_PATH)
image_keys = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image_keys)

SANDBOX_TAG = re.compile(r"^sandbox-([0-9a-f]{8,40})(?:-run[1-9][0-9]*)?$")
MANIFEST_ROOT = Path("openbank-infra/gitops/components")


def commit_of_image(image):
    """Return the source commit prefix, or None for nonstandard pins."""
    value = image.split("@", 1)[0]
    last_slash = value.rfind("/")
    colon = value.rfind(":")
    if colon <= last_slash:
        return None
    match = SANDBOX_TAG.fullmatch(value[colon + 1:])
    return match.group(1) if match else None


def current_image(root, path, repository):
    """Find exactly one image for this key in the trusted main checkout."""
    relative = Path(path)
    if relative.is_absolute() or ".." in relative.parts or not relative.is_relative_to(MANIFEST_ROOT):
        raise ValueError(f"path outside GitOps components: {path}")
    manifest = root / relative
    values = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = image_keys.IMAGE_LINE.match(line)
        if not match:
            continue
        value, parsed_repository = image_keys.image_repository(match.group(1))
        if parsed_repository == repository:
            values.append(value)
    if len(values) != 1:
        raise ValueError(f"expected one current pin for {path} / {repository}, found {len(values)}")
    return values[0]


def full_commit(root, prefix):
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--verify", "--quiet", f"{prefix}^{{commit}}"],
        capture_output=True, text=True, check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def is_ancestor(root, old, new):
    result = subprocess.run(
        ["git", "-C", str(root), "merge-base", "--is-ancestor", old, new],
        capture_output=True, text=True, check=False,
    )
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise RuntimeError("git merge-base could not classify image source commits")


def classify_pin(root, proposed, current):
    if proposed == current:
        return "covered"
    proposed_prefix = commit_of_image(proposed)
    current_prefix = commit_of_image(current)
    if proposed_prefix is None or current_prefix is None:
        return "unknown"
    proposed_sha = full_commit(root, proposed_prefix)
    current_sha = full_commit(root, current_prefix)
    if proposed_sha is None or current_sha is None:
        return "unknown"
    if proposed_sha == current_sha:
        # A different tag/digest for one source commit may be a rebuilt artifact.
        return "unknown"
    if is_ancestor(root, proposed_sha, current_sha):
        return "covered"
    if is_ancestor(root, current_sha, proposed_sha):
        return "gap"
    return "unknown"


def audit(root, records, successors=()):
    if not isinstance(records, list) or not records:
        raise ValueError("closed PR has no unambiguous image pin replacements")
    results = []
    seen = set()
    for record in records:
        path = record["path"]
        repository = record["repository"]
        proposed = record["new_image"]
        if image_keys.image_repository(proposed)[1] != repository:
            raise ValueError("proposed image does not match its repository key")
        key = (path, repository)
        if key in seen:
            raise ValueError("duplicate manifest/image key")
        seen.add(key)
        try:
            current = current_image(root, path, repository)
            status = classify_pin(root, proposed, current)
        except (OSError, ValueError, RuntimeError):
            current = None
            status = "unknown"
        successor_pr = None
        if status != "covered":
            for successor in successors:
                if successor.get("mergeable") != "MERGEABLE" or current is None:
                    continue
                matching = [pin for pin in successor["pins"]
                            if (pin["path"], pin["repository"]) == key]
                if len(matching) != 1:
                    continue
                if matching[0]["old_image"] != current:
                    continue
                try:
                    successor_verdict = classify_pin(root, proposed, matching[0]["new_image"])
                except RuntimeError:
                    successor_verdict = "unknown"
                if successor_verdict == "covered":
                    status = "pending-successor"
                    successor_pr = successor["pr"]
                    break
        results.append({"path": path, "repository": repository, "proposed": proposed,
                        "current": current, "status": status, "successor_pr": successor_pr})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("records", type=Path, help="JSON records from deploy-pr-image-keys.py --json")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--successors", type=Path, help="JSON list of open bot PR pin records")
    args = parser.parse_args()
    try:
        records = json.loads(args.records.read_text(encoding="utf-8"))
        successors = json.loads(args.successors.read_text(encoding="utf-8")) if args.successors else []
        results = audit(args.root, records, successors)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"audit-closed-deploy-pr: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(results, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
