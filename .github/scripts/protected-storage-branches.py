#!/usr/bin/env python3
"""List literal branch names cloned or used as storage by GitHub workflows."""

from pathlib import Path
import re
import sys


BRANCH = r"[A-Za-z0-9][A-Za-z0-9._/-]*"
END = r"(?=\s|$|\\|#)"
PATTERNS = (
    re.compile(rf"(?<![\w-])--branch(?:=|\s+)(['\"]?)({BRANCH})\1{END}"),
    re.compile(rf"\bstorage-repo-branch(?:-coverage)?:\s*(['\"]?)({BRANCH})\1{END}"),
)


def branches_in(text: str) -> set[str]:
    refs: set[str] = set()
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        for pattern in PATTERNS:
            refs.update(match.group(2) for match in pattern.finditer(line))
    return refs


def main() -> None:
    workflow_dir = Path(sys.argv[1])
    refs: set[str] = set()
    for path in workflow_dir.glob("*.yml"):
        refs.update(branches_in(path.read_text(encoding="utf-8")))
    for ref in sorted(refs):
        print(ref)


if __name__ == "__main__":
    main()
