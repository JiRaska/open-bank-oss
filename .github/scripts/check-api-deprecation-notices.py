#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep deprecated paths, public notices and emitted Sunset headers consistent (#11602)."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, timedelta, timezone
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = "openbank-developer-portal/site/changelog/index.html"


@dataclass(frozen=True)
class Notice:
    path: str
    filed: date
    sunset: date
    successor: str
    service: str
    status: str
    visible: str


class NoticeParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.rows: list[Notice] = []
        self.current: dict[str, str] | None = None
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            values = dict(attrs)
            if "data-api-path" in values:
                self.current = {key: value or "" for key, value in values.items()}
                self.text = []

    def handle_data(self, data: str) -> None:
        if self.current is not None:
            self.text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag != "tr" or self.current is None:
            return
        row = self.current
        self.rows.append(Notice(
            row.get("data-api-path", ""), date.fromisoformat(row.get("data-notice-on", "")),
            date.fromisoformat(row.get("data-sunset-on", "")), row.get("data-successor", ""),
            row.get("data-service", ""), row.get("data-status", "active"),
            " ".join(self.text),
        ))
        self.current = None


def notices(html: str) -> list[Notice]:
    parser = NoticeParser()
    parser.feed(html)
    return parser.rows


def check(root: Path, baseline: dict[str, Notice] | None = None,
          today: date | None = None) -> list[str]:
    errors: list[str] = []
    policy = yaml.safe_load((root / "openbank-libs/governance/rules.yaml").read_text())[
        "api_deprecation"]
    minimum = policy["min_sunset_window_days"]
    paths = policy["deprecated_paths"]
    if not isinstance(minimum, int) or minimum < 1:
        errors.append("min_sunset_window_days must be a positive integer")
        return errors
    if not isinstance(paths, list) or any(not isinstance(p, str) or not p.startswith("/") for p in paths):
        errors.append("deprecated_paths must be a list of absolute paths")
        return errors
    if len(paths) != len(set(paths)):
        errors.append("deprecated_paths contains duplicates")

    rows = notices((root / CHANGELOG).read_text())
    active = [row for row in rows if row.status == "active"]
    if any(row.status not in {"active", "removed"} for row in rows):
        errors.append("changelog has an unknown notice status")
    if len(active) != len({row.path for row in active}):
        errors.append("changelog has duplicate active notice paths")
    by_path = {row.path: row for row in active}
    for path in sorted(set(paths) - set(by_path)):
        errors.append(f"{path}: no active developer-portal changelog notice")
    for path in sorted(set(by_path) - set(paths)):
        errors.append(f"{path}: active changelog notice is absent from deprecated_paths")

    for path in sorted(set(paths) & set(by_path)):
        row = by_path[path]
        if minimum > 0 and row.sunset - row.filed < timedelta(days=minimum):
            errors.append(f"{path}: Sunset is less than {minimum} days after notice filing")
        previous = baseline.get(path) if baseline is not None else None
        if baseline is not None and (previous is None or
                                     (previous.filed, previous.sunset, previous.successor,
                                      previous.service, previous.status) !=
                                     (row.filed, row.sunset, row.successor,
                                      row.service, row.status)):
            if row.sunset - (today or date.today()) < timedelta(days=minimum):
                errors.append(f"{path}: new/changed notice has less than {minimum} days until Sunset")
        if not row.successor.startswith("/") or not row.service.startswith("openbank-"):
            errors.append(f"{path}: successor and owning service must be explicit")
            continue
        for value in (row.path, row.filed.isoformat(), row.sunset.isoformat(), row.successor):
            if value not in row.visible:
                errors.append(f"{path}: {value} is metadata but not visible on the page")
        config = root / row.service / "src/main/resources/application.yaml"
        if not config.is_file():
            errors.append(f"{path}: owning service has no application.yaml")
            continue
        api = yaml.safe_load(config.read_text()).get("openbank", {}).get("api", {})
        if path not in api.get("deprecated-paths", []):
            errors.append(f"{path}: owning service does not emit deprecation headers for this path")
        if f"{path}=>{row.successor}" not in api.get("successor-links", []):
            errors.append(f"{path}: runtime successor differs from changelog")
        try:
            emitted = parsedate_to_datetime(api["sunset-date"])
            if emitted.tzinfo is None or emitted.astimezone(timezone.utc).date() != row.sunset:
                errors.append(f"{path}: runtime Sunset differs from changelog")
        except (KeyError, TypeError, ValueError):
            errors.append(f"{path}: owning service has no valid HTTP-date Sunset")
    return errors


def baseline_notices(root: Path, ref: str) -> dict[str, Notice]:
    valid = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", ref + "^{commit}"],
                           capture_output=True, text=True, check=False)
    if valid.returncode:
        raise ValueError(f"invalid base ref: {ref}")
    result = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{CHANGELOG}"],
                            capture_output=True, text=True, check=False)
    if result.returncode:
        # The initial changelog is absent from main; every notice in this PR is new.
        return {}
    return {row.path: row for row in notices(result.stdout) if row.status == "active"}


def parent_ref(root: Path) -> str:
    result = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "HEAD^"],
                            capture_output=True, text=True, check=False)
    return "HEAD^" if result.returncode == 0 else ""


def self_test() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        rules = root / "openbank-libs/governance/rules.yaml"
        rules.parent.mkdir(parents=True)
        rules.write_text("api_deprecation:\n  min_sunset_window_days: 180\n"
                         "  deprecated_paths: [/api/v1/products]\n")
        page = root / CHANGELOG
        page.parent.mkdir(parents=True)
        page.write_text('<tr data-api-path="/api/v1/products" data-notice-on="2026-10-05" '
                        'data-sunset-on="2027-07-01" data-successor="/api/v2/offerings" '
                        'data-service="openbank-product-catalog">'
                        '/api/v1/products 2026-10-05 2027-07-01 /api/v2/offerings</tr>')
        config = root / "openbank-product-catalog/src/main/resources/application.yaml"
        config.parent.mkdir(parents=True)
        config.write_text('openbank:\n  api:\n    deprecated-paths: [/api/v1/products]\n'
                          '    successor-links: [/api/v1/products=>/api/v2/offerings]\n'
                          '    sunset-date: "Thu, 01 Jul 2027 00:00:00 GMT"\n')
        assert not check(root, {}, date(2026, 10, 5)), "known-positive notice failed"
        page.write_text("<html><body>No notice</body></html>")
        assert any("no active" in error for error in check(root)), "missing notice passed"
        page.write_text('<tr data-api-path="/api/v1/products" data-notice-on="2026-10-05" '
                        'data-sunset-on="2027-02-10" data-successor="/api/v2/offerings" '
                        'data-service="openbank-product-catalog">'
                        '/api/v1/products 2026-10-05 2027-02-10 /api/v2/offerings</tr>')
        assert any("less than 180" in error for error in check(root, {}, date(2026, 10, 5))), \
            "short sunset window passed"
        page.write_text('<tr data-api-path="/api/v1/products" data-notice-on="2026-10-05" '
                        'data-sunset-on="2027-07-01" data-successor="/api/v2/offerings" '
                        'data-service="openbank-product-catalog">'
                        '/api/v1/products 2026-10-05 2027-07-01 /api/v2/offerings</tr>')
        assert any("new/changed notice" in error for error in check(root, {}, date(2027, 2, 1))), \
            "stale proposed notice passed"
        config.write_text(config.read_text().replace("01 Jul", "02 Jul"))
        assert any("runtime Sunset differs" in error for error in check(root)), \
            "runtime Sunset mismatch passed"
    print("self-test: ok")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--base", default="")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    try:
        base = args.base or parent_ref(args.root)
        baseline = baseline_notices(args.root, base) if base else None
        errors = check(args.root, baseline)
    except (OSError, ValueError, yaml.YAMLError) as error:
        errors = [str(error)]
    for error in errors:
        print(f"::error title=API deprecation notice::{error}")
    if errors:
        print(f"FAIL: {len(errors)} API deprecation notice defect(s)")
        return 1
    print("OK: deprecated paths have public notices, a full sunset window and matching runtime headers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
