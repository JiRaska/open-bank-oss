# SPDX-License-Identifier: Apache-2.0
"""Only exclusively Admin UI changes can omit JVM consumer regeneration."""
import sys


def jvm_required(paths):
    if not paths:
        return True
    for path in paths:
        parts = path.split("/")
        if (not path.startswith("openbank-admin-ui/") or len(parts) < 2
                or any(part in ("", ".", "..") for part in parts)
                or any(ord(character) < 32 for character in path)):
            return True
    return False


def main():
    raw = sys.stdin.buffer.read()
    # The caller must use git diff --name-only -z. Malformed inventory is never
    # an authorization to skip checks, including undecodable filenames.
    try:
        if not raw or not raw.endswith(b"\0"):
            required = True
        else:
            required = jvm_required(raw[:-1].decode("utf-8").split("\0"))
    except UnicodeDecodeError:
        required = True
    print("true" if required else "false")


if __name__ == "__main__":
    main()
