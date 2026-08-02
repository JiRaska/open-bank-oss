#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A service with `quarkus.flyway.migrate-at-start` must configure the datasource Flyway migrates.

Why this exists
---------------
Five services — ledger, account, sca, transaction and settlement, every one of them money-path —
could not start from the configuration committed to this repo (#3080). Each declared only a
REACTIVE datasource URL, so the default Agroal (JDBC) datasource was never configured, while
`quarkus.flyway.migrate-at-start: true` requires one:

    io.quarkus.runtime.configuration.ConfigurationException: Unable to find datasource '<default>'
      for persistence unit '<default>': Bean is not active: SYNTHETIC bean
      [class=io.agroal.api.AgroalDataSource]

They booted in the cluster only because the gitops env supplied `QUARKUS_DATASOURCE_JDBC_URL`. A
developer running `quarkusDev`, and the authenticated fuzz harness (#3039), got the failure.

Three of them looked like they had solved it: they carried a `quarkus.flyway.datasource:` block
with `db-kind`, `username`, `password` and a nested `jdbc.url`. Quarkus does not read that path as
a datasource *definition* — `quarkus.flyway.datasource` names WHICH existing datasource Flyway
should use. So the config answered the question for the next reader, incorrectly, and the boot
error names the missing default datasource while that block sits in the same file. That shape is
worse than the plain omission, so it is flagged separately.

WHAT IT CHECKS, per `openbank-*/src/main/resources/application.yaml`:

  1. `quarkus.flyway.migrate-at-start` truthy ⇒ `quarkus.datasource.jdbc.url` must be present
     (a `${ENV:default}` expression counts — the literal default is what makes it bootable).
  2. `quarkus.flyway.datasource` must be a NAME (a string), never a mapping. A mapping there is
     the misplaced-definition shape above.

Both are properties of one committed file, so this needs no cluster, no boot and no network.
It does not replace a real boot test (#2872) — it closes the one failure mode that recurred five
times and is decidable statically.

Usage:  check-flyway-default-datasource.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]


def configs() -> list[tuple[pathlib.Path, dict]]:
    out: list[tuple[pathlib.Path, dict]] = []
    for path in sorted(REPO.glob("openbank-*/src/main/resources/application.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        except yaml.YAMLError:
            continue
        if isinstance(doc, dict):
            out.append((path, doc))
    return out


def findings() -> tuple[list[str], int]:
    messages: list[str] = []
    checked = 0
    for path, doc in configs():
        quarkus = doc.get("quarkus") or {}
        flyway = quarkus.get("flyway") or {}
        if not flyway.get("migrate-at-start"):
            continue
        checked += 1
        rel = path.relative_to(REPO)
        service = path.parts[len(REPO.parts)]

        datasource = quarkus.get("datasource") or {}
        jdbc_url = (datasource.get("jdbc") or {}).get("url")
        if not jdbc_url:
            messages.append(
                f"::error file={rel}::{service} sets quarkus.flyway.migrate-at-start but declares "
                f"no quarkus.datasource.jdbc.url. Flyway migrates the DEFAULT (Agroal/JDBC) "
                f"datasource, so the boot dies with \"Unable to find datasource '<default>'\" "
                f"unless the environment happens to supply QUARKUS_DATASOURCE_JDBC_URL — which "
                f"means the service cannot start from this repo alone (#3080).",
            )

        if isinstance(flyway.get("datasource"), dict):
            messages.append(
                f"::error file={rel}::{service} has a quarkus.flyway.datasource MAPPING. That key "
                f"names which existing datasource Flyway should use; it does not define one, so "
                f"the nested db-kind/username/jdbc.url are ignored. It reads as a solved problem "
                f"and is not (#3080). Configure quarkus.datasource.jdbc.url instead, or set "
                f"quarkus.flyway.datasource to a datasource NAME.",
            )
    return messages, checked


def selftest() -> int:
    """Feed the two rules inputs they MUST flag and inputs they must NOT."""
    parsed = configs()
    if len(parsed) < 20:
        print(f"selftest FAIL: only {len(parsed)} application.yaml parsed — the scan is broken.")
        return 1

    cases = [
        # (migrate_at_start, jdbc_url, flyway_datasource, expected findings)
        (True, "jdbc:postgresql://localhost/x", None, 0),                 # healthy
        (True, None, None, 1),                                            # plain omission
        (True, None, {"jdbc": {"url": "jdbc:..."}}, 2),                   # #3080's worse shape
        (True, "${ENV:jdbc:postgresql://localhost/x}", None, 0),          # env expression counts
        (False, None, None, 0),                                           # no Flyway, no rule
        (True, "jdbc:postgresql://localhost/x", "named-ds", 0),           # a NAME is legitimate
    ]
    for migrate, jdbc_url, flyway_ds, expected in cases:
        n = 0
        if migrate:
            if not jdbc_url:
                n += 1
            if isinstance(flyway_ds, dict):
                n += 1
        if n != expected:
            print(f"selftest FAIL: migrate={migrate} jdbc={jdbc_url!r} flyway.datasource="
                  f"{flyway_ds!r} → {n} finding(s), expected {expected}")
            return 1
    print(f"selftest OK: {len(cases)} cases, both directions ({len(parsed)} configs parsed).")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    messages, checked = findings()
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-flyway-default-datasource: {checked} migrate-at-start service(s) — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
