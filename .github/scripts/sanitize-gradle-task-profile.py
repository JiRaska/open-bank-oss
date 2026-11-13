#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Retain only allowlisted task timings from Gradle 9.8.1's local profile.

ProfileReportRenderer's tab4 has Task/Duration/Result columns, project total
rows, and task rows marked indentPath. All other tables and free text are
discarded. Unknown schema, paths or outcomes produce an incomplete diagnostic,
never partial task data. Display durations are rounded by Gradle, and parallel
task durations do not describe a critical path or add up to build wall time.
"""
import argparse
from decimal import Decimal
from html.parser import HTMLParser
import json
from pathlib import Path
import re

VERSION = "9.8.1"
TASK_PATH = re.compile(r"(?::[A-Za-z0-9_-]+)+", re.ASCII)
DURATION = re.compile(r"(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(\d+(?:\.\d{1,3})?)s", re.ASCII)
OUTCOMES = {"": "work_performed", "Did No Work": "no_work", "UP-TO-DATE": "up_to_date",
            "FROM-CACHE": "from_cache", "NO-SOURCE": "no_source", "SKIPPED": "skipped"}


def milliseconds(value):
    match = DURATION.fullmatch(value)
    if not match:
        raise ValueError("duration")
    days, hours, minutes, seconds = match.groups()
    result = (int(days or 0) * 86400 + int(hours or 0) * 3600 + int(minutes or 0) * 60) * 1000
    result += int(Decimal(seconds) * 1000)
    if result > 7 * 86400 * 1000:
        raise ValueError("duration bound")
    return result


class TaskTable(HTMLParser):
    CHILDREN = {"div": {"h2", "table"}, "h2": set(), "table": {"thead", "tr"},
                "thead": {"tr"}, "tr": {"th", "td"}, "th": set(), "td": set()}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.sections = self.tables = 0
        self.heading = ""
        self.rows = []
        self.cells = None
        self.text = ""

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if not self.stack:
            if tag == "div" and attributes.get("id") == "tab4":
                self.sections += 1
                self.stack = ["div"]
            return
        if tag not in self.CHILDREN[self.stack[-1]]:
            raise ValueError("table structure")
        self.stack.append(tag)
        if tag == "table":
            self.tables += 1
        elif tag == "tr":
            self.cells = []
        elif tag in ("td", "th"):
            self.cell = (tag, attributes.get("class", ""))
            self.text = ""

    def handle_endtag(self, tag):
        if not self.stack:
            return
        if self.stack[-1] != tag:
            raise ValueError("unclosed table")
        if tag in ("td", "th"):
            self.cells.append((*self.cell, self.text.strip()))
        elif tag == "tr":
            self.rows.append(self.cells)
            if len(self.rows) > 20000:
                raise ValueError("row bound")
        self.stack.pop()

    def handle_data(self, data):
        if not self.stack:
            return
        if self.stack[-1] in ("td", "th"):
            self.text += data
            if len(self.text) > 512:
                raise ValueError("cell bound")
        elif self.stack[-1] == "h2":
            self.heading += data
        elif data.strip():
            raise ValueError("unexpected table text")

    def tasks(self):
        if (self.stack or self.sections != 1 or self.tables != 1
                or self.heading.strip() != "Task Execution" or not self.rows
                or self.rows[0] != [("th", "", "Task"), ("th", "numeric", "Duration"), ("th", "", "Result")]):
            raise ValueError("missing task table")
        tasks, seen = [], set()
        for row in self.rows[1:]:
            if len(row) != 3 or [cell[:2] for cell in row][1:] != [("td", "numeric"), ("td", "")]:
                raise ValueError("row schema")
            first, duration, outcome = (cell[2] for cell in row)
            elapsed = milliseconds(duration)
            if row[0][:2] == ("td", "") and outcome == "(total)":
                if first != ":" and not TASK_PATH.fullmatch(first):
                    raise ValueError("project path")
                continue
            if (row[0][:2] != ("td", "indentPath") or not TASK_PATH.fullmatch(first)
                    or first in seen or outcome not in OUTCOMES):
                raise ValueError("task schema")
            seen.add(first)
            # Empty native status means did-work, including a task that failed.
            tasks.append({"path": first, "durationMs": elapsed, "outcome": OUTCOMES[outcome]})
        if not tasks:
            raise ValueError("empty task table")
        return tasks


def report(directory, wrapper):
    result = {"schemaVersion": 1, "gradleVersion": VERSION,
              "scope": "available_profile_task_durations", "criticalPathAvailable": False,
              "precision": "gradle_profile_display_rounding", "status": "incomplete",
              "reasonEnum": "invalid_profile", "profiles": []}
    try:
        if not re.search(r"^distributionUrl=.*?/gradle-9\.8\.1-bin\.zip$", wrapper.read_text(), re.MULTILINE):
            result["reasonEnum"] = "unsupported_gradle"
            return result
        files = sorted(directory.glob("profile-*.html"))
        if not files:
            result["reasonEnum"] = "profile_missing"
            return result
        if len(files) > 8:
            return result
        profiles = []
        for path in files:
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
                return result
            parser = TaskTable()
            parser.feed(path.read_text(encoding="utf-8"))
            parser.close()
            profiles.append({"tasks": parser.tasks()})
        result.update(status="parsed", reasonEnum="parsed", profiles=profiles)
    except (OSError, UnicodeError):
        result["reasonEnum"] = "profile_unreadable"
    except (ValueError, KeyError, IndexError):
        pass
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--wrapper", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = report(args.profiles, args.wrapper)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.out.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, indent=2) + "\n")
    temporary.replace(args.out)
    print("Gradle task profile: " + result["reasonEnum"])


if __name__ == "__main__":
    main()
