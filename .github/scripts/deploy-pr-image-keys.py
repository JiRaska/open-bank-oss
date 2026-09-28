# SPDX-License-Identifier: Apache-2.0
"""Extract manifest/image-repository keys changed by a unified git patch."""

import re
import sys

DIFF_HEADER = re.compile(r"^diff --git a/(.+) b/(.+)$")
IMAGE_LINE = re.compile(r"^\s*(?:-\s*)?image:\s*(.*?)\s*$")


class DiffError(ValueError):
    pass


def image_repository(value):
    """Return the repository part, ignoring a tag or digest."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    if not value or any(char.isspace() for char in value):
        raise DiffError("malformed image value")
    # A digest identifies the pinned artifact; the key is the repository before it.
    value = value.split("@", 1)[0]
    last_slash = value.rfind("/")
    colon = value.rfind(":")
    if colon > last_slash:
        value = value[:colon]
    if not value or value.endswith("/"):
        raise DiffError("image has no repository")
    return value


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
            if old != new:
                raise DiffError(f"image repository changed in {current_path}")
            keys.append((current_path, old))

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
            repository = image_repository(image.group(1))
            (added if line.startswith("+") else removed).append(repository)

    finish_file()
    if not saw_diff or not keys:
        raise DiffError("no valid image edits found")
    if len(set(keys)) != len(keys):
        raise DiffError("duplicate manifest/image-repository key")
    return sorted(keys)


def main():
    try:
        keys = parse_patch(sys.stdin.read())
    except (DiffError, UnicodeError) as exc:
        print(f"deploy-pr-image-keys: {exc}", file=sys.stderr)
        return 1
    for path, repository in keys:
        print(f"{path}\t{repository}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
