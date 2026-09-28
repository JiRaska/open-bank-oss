# SPDX-License-Identifier: Apache-2.0
"""Extract manifest/image-repository keys changed by a unified git patch."""

import json
import re
import sys

DIFF_HEADER = re.compile(r"^diff --git a/(.+) b/(.+)$")
IMAGE_LINE = re.compile(r"^\s*(?:-\s*)?image:\s*(.*?)\s*$")
IMAGE_REPOSITORY = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._-]*(?::[0-9]+)?(?:/[A-Za-z0-9][A-Za-z0-9._-]*)+$"
    r"|^[A-Za-z0-9][A-Za-z0-9._-]*$"
)
TAG = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
DIGEST = re.compile(r"^[A-Za-z][A-Za-z0-9_+.-]*:[A-Fa-f0-9]+$")


class DiffError(ValueError):
    pass


def image_repository(value):
    """Return an unquoted image reference and its repository."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    if not value or any(char.isspace() for char in value) or any(char in value for char in "\"'"):
        raise DiffError("malformed image value")
    if value.count("@") > 1:
        raise DiffError("malformed image digest")
    name, separator, digest = value.partition("@")
    if separator and not DIGEST.fullmatch(digest):
        raise DiffError("malformed image digest")
    slash = name.rfind("/")
    colon = name.rfind(":")
    if colon > slash:
        repository, tag = name[:colon], name[colon + 1:]
        if not TAG.fullmatch(tag):
            raise DiffError("malformed image tag")
    else:
        repository = name
    if not IMAGE_REPOSITORY.fullmatch(repository):
        raise DiffError("image has no repository")
    # Return the normalized reference as well, for the JSON replacement record.
    return value, repository


def parse_patch(patch):
    keys = []
    current_path = None
    removed = []
    added = []
    saw_diff = False

    def finish_file():
        if current_path is None:
            return
        if len(removed) != len(added):
            raise DiffError(f"unpaired changed image line in {current_path}")
        if not removed:
            return
        if len(set(removed)) != len(removed) or len(set(added)) != len(added):
            raise DiffError(f"duplicate image key in {current_path}")
        for old, new in zip(removed, added):
            if old[1] != new[1]:
                raise DiffError(f"image repository changed in {current_path}")
            if old[0] == new[0]:
                raise DiffError(f"unchanged image reference in {current_path}")
            keys.append({
                "path": current_path,
                "repository": old[1],
                "old_image": old[0],
                "new_image": new[0],
            })

    for line in patch.splitlines():
        match = DIFF_HEADER.match(line)
        if match:
            finish_file()
            saw_diff = True
            old_path, new_path = match.groups()
            if old_path != new_path:
                raise DiffError("renamed paths are ambiguous")
            current_path = new_path
            removed, added = [], []
            continue
        if current_path is None or not line.startswith(("+", "-")) or line.startswith(("+++", "---")):
            continue
        content = line[1:]
        image = IMAGE_LINE.match(content)
        if image:
            parsed = image_repository(image.group(1))
            (added if line.startswith("+") else removed).append(parsed)

    finish_file()
    if not saw_diff or not keys:
        raise DiffError("no valid image edits found")
    if len({(item["path"], item["repository"]) for item in keys}) != len(keys):
        raise DiffError("duplicate manifest/image-repository key")
    return sorted(keys, key=lambda item: (item["path"], item["repository"]))


def main():
    json_mode = sys.argv[1:] == ["--json"]
    if sys.argv[1:] and not json_mode:
        print("deploy-pr-image-keys: unsupported arguments", file=sys.stderr)
        return 2
    try:
        keys = parse_patch(sys.stdin.read())
    except (DiffError, UnicodeError) as exc:
        print(f"deploy-pr-image-keys: {exc}", file=sys.stderr)
        return 1
    if json_mode:
        print(json.dumps(keys, separators=(",", ":")))
    else:
        for item in keys:
            print(f"{item['path']}\t{item['repository']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
