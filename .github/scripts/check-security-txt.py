#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every published security.txt must send a reporter somewhere they can actually read (#9880).

WHY THIS EXISTS
---------------
Measured 2026-09-13: the security.txt that https://open-bank.tech actually serves (byte-identical
to openbank-infra/web/landing/.well-known/security.txt) and the admin-ui copy both said

    Policy: https://github.com/JiRaska/openbank-app/blob/main/docs/VULNERABILITY-DISCLOSURE.md

and that repository is PRIVATE — the URL answers 404 to anyone who is not its owner, which is
every external security researcher. The developer-portal copy's Policy pointed at the
security.txt itself. So the one machine-readable pointer RFC 9116 gives a reporter led nowhere,
on all three hosts, while the real policy (SECURITY.md, public) existed the whole time.

Nothing noticed because nothing looks: the file validates, a scanner sees a well-formed Policy
field, and the 404 only happens in the browser of the person trying to report something.

WHAT IT CHECKS (fully decidable, offline)
-----------------------------------------
For every `.well-known/security.txt` in the tree:
  * `Contact:` is present;
  * `Expires:` parses as ISO-8601 and is in the future (RFC 9116 §2.5.5 — a stale file is
    treated as untrustworthy by scanners);
  * `Policy:` is present, is NOT the file's own Canonical URL (a policy that is the contact file
    is no policy), and points INTO THIS PUBLIC REPOSITORY at a file that exists in the tree.

The last rule is deliberately narrower than "some reachable URL". Reachability is a network
fact this gate cannot establish, and the defect above was a URL that *looked* reachable. A path
this repository contains is a claim the gate can verify, and this repository is public.

Usage:  check-security-txt.py [--root .] [--live]
        check-security-txt.py --self-test
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

REPO_BLOB = re.compile(r"^https://github\.com/JiRaska/open-bank-oss/blob/main/(?P<path>[^?#]+)$")
MAX_LIVE_BYTES = 64 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def fetch_live(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "openbank-security-txt-watch/1"})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=10) as response:
        if response.status != 200:
            raise ValueError(f"HTTP {response.status}")
        body = response.read(MAX_LIVE_BYTES + 1)
        if len(body) > MAX_LIVE_BYTES:
            raise ValueError("response exceeds size limit")
        return body


def live_problem(path: pathlib.Path, root: pathlib.Path, fetch=fetch_live) -> str | None:
    rel = path.relative_to(root).as_posix()
    source = path.read_bytes()
    try:
        canonicals = fields(source.decode("utf-8")).get("canonical", [])
    except UnicodeDecodeError:
        return f"{rel}: tracked security.txt is not UTF-8"
    if len(canonicals) != 1:
        return f"{rel}: expected exactly one Canonical field for live verification"
    url = canonicals[0]
    try:
        parsed = urllib.parse.urlsplit(url)
        valid = (parsed.scheme == "https" and parsed.hostname and not parsed.username
                 and not parsed.password and not parsed.port and not parsed.query
                 and not parsed.fragment and parsed.path == "/.well-known/security.txt")
    except ValueError:
        valid = False
    if not valid:
        return f"{rel}: Canonical is not an HTTPS /.well-known/security.txt URL"
    try:
        published = fetch(url)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        # Never print the URL, response, or exception text: the check only needs to
        # identify which tracked copy requires attention.
        return f"{rel}: published security.txt could not be read ({type(exc).__name__})"
    if published != source:
        return f"{rel}: published security.txt differs from the tracked source"
    return None


def fields(text: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#") or ":" not in line:
            continue
        key, _, value = line.partition(":")
        out.setdefault(key.strip().lower(), []).append(value.strip())
    return out


def problems(path: pathlib.Path, root: pathlib.Path, now: dt.datetime) -> list[str]:
    f = fields(path.read_text(encoding="utf-8", errors="replace"))
    rel = path.relative_to(root).as_posix()
    out: list[str] = []
    if not f.get("contact"):
        out.append(f"{rel}: no Contact: field")
    expires = (f.get("expires") or [None])[0]
    if not expires:
        out.append(f"{rel}: no Expires: field")
    else:
        try:
            when = dt.datetime.fromisoformat(expires.replace("Z", "+00:00"))
            if when.tzinfo is None:
                when = when.replace(tzinfo=dt.timezone.utc)
            if when <= now:
                out.append(f"{rel}: Expires {expires} is in the past — renew the file")
        except ValueError:
            out.append(f"{rel}: Expires {expires!r} is not ISO-8601")
    policy = (f.get("policy") or [None])[0]
    canonical = (f.get("canonical") or [None])[0]
    if not policy:
        out.append(f"{rel}: no Policy: field")
    elif canonical and policy == canonical:
        out.append(f"{rel}: Policy points at this security.txt itself — that is a contact file, not a policy")
    else:
        m = REPO_BLOB.match(policy)
        if not m:
            out.append(
                f"{rel}: Policy {policy} does not point into this public repository. A URL this gate "
                f"cannot resolve is exactly how all three hosts published a policy that answered 404 "
                f"(#9880) — link a document under https://github.com/JiRaska/open-bank-oss/blob/main/"
            )
        elif not (root / m.group("path")).is_file():
            out.append(f"{rel}: Policy names {m.group('path')}, which does not exist in this tree")
    return out


def scan(root: pathlib.Path, now: dt.datetime) -> tuple[int, list[str]]:
    files = sorted(p for p in root.rglob(".well-known/security.txt")
                   if "node_modules" not in p.parts and ".git" not in p.parts)
    found: list[str] = []
    for p in files:
        found.extend(problems(p, root, now))
    return len(files), found


def scan_live(root: pathlib.Path) -> tuple[int, list[str]]:
    files = sorted(p for p in root.rglob(".well-known/security.txt")
                   if "node_modules" not in p.parts and ".git" not in p.parts)
    found = [problem for p in files if (problem := live_problem(p, root))]
    return len(files), found


def self_test() -> int:
    now = dt.datetime(2026, 9, 13, tzinfo=dt.timezone.utc)
    good = ("Contact: mailto:security@example.org\nExpires: 2027-06-11T00:00:00.000Z\n"
            "Policy: https://github.com/JiRaska/open-bank-oss/blob/main/SECURITY.md\n"
            "Canonical: https://example.org/.well-known/security.txt\n")
    cases = {
        "good": (good, []),
        "private-repo policy (the #9880 defect)": (
            good.replace("open-bank-oss/blob/main/SECURITY.md", "openbank-app/blob/main/docs/VULNERABILITY-DISCLOSURE.md"),
            ["does not point into this public repository"]),
        "policy is itself": (
            good.replace("Policy: https://github.com/JiRaska/open-bank-oss/blob/main/SECURITY.md",
                         "Policy: https://example.org/.well-known/security.txt"),
            ["Policy points at this security.txt itself"]),
        "policy names a missing file": (
            good.replace("SECURITY.md", "docs/NOPE.md"), ["does not exist in this tree"]),
        "expired": (good.replace("2027-06-11", "2026-01-01"), ["in the past"]),
        "no contact": (good.replace("Contact: mailto:security@example.org\n", ""), ["no Contact"]),
    }
    bad = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "SECURITY.md").write_text("policy\n")
        for i, (name, (body, want)) in enumerate(cases.items()):
            d = root / f"site{i}" / ".well-known"
            d.mkdir(parents=True)
            (d / "security.txt").write_text(body)
            got = problems(d / "security.txt", root, now)
            ok = (not want and not got) or (want and len(got) == len(want) and all(w in g for w, g in zip(want, got, strict=True)))
            print(f"  {'ok ' if ok else 'BAD'} {name}: {got}")
            bad += 0 if ok else 1
        live_file = root / "site0" / ".well-known" / "security.txt"
        for name, fetch, expected in [
            ("published copy matches", lambda _: good.encode(), None),
            ("stale published copy", lambda _: b"Policy: stale\n", "differs from the tracked source"),
            ("published copy unavailable", lambda _: (_ for _ in ()).throw(OSError("hidden address")),
             "could not be read"),
        ]:
            got = live_problem(live_file, root, fetch=fetch)
            ok = (got is None) if expected is None else (got is not None and expected in got and "hidden address" not in got)
            print(f"  {'ok ' if ok else 'BAD'} {name}")
            bad += 0 if ok else 1
    print(f"SUBJECTS={len(cases) + 3}")
    print("security.txt self-test: " + ("PASS" if not bad else f"FAIL ({bad})"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--live", action="store_true", help="compare public Canonical URLs to the tracked copies")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    count, found = scan(root, dt.datetime.now(dt.timezone.utc))
    if args.live and not found:
        _, live_found = scan_live(root)
        found.extend(live_found)
    print(f"SUBJECTS={count}")
    for p in found:
        print(f"::error::{p}")
    print(f"check-security-txt: {count} security.txt file(s), {len(found)} problem(s)"
          + (" (including published copies)" if args.live else ""))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
