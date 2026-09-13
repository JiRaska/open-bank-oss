#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Flyway must have a datasource to migrate — and committed migrations must have something running them.

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
  3. A service that commits `src/main/resources/db/migration/*.sql` must have SOMETHING that runs
     them — `quarkus.flyway.migrate-at-start` here, or `QUARKUS_FLYWAY_MIGRATE_AT_START` in its own
     GitOps manifest (psd2-service's deliberate, documented shape). security-scanner had neither,
     so its deployed database held zero tables and no flyway_schema_history, and every write
     answered `relation "security_outbox" does not exist (42P01)` as a 500 — while the pod stayed
     Ready, because health probes never touch the schema. The inverse of rule 1: that one asks
     "you migrate, but against what?", this one asks "you wrote migrations, but does anything run
     them?".

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

import gatelib

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


def gitops_migrates(service: str) -> bool:
    """True when the service's own GitOps manifest exports QUARKUS_FLYWAY_MIGRATE_AT_START.

    psd2-service does exactly that, deliberately and with a comment explaining it, and
    migrates fine in production — so it must not be flagged. What rule 3 is looking for is
    "does ANYTHING run these migrations", not "is the switch in one particular file".
    """
    component = REPO / "openbank-infra" / "gitops" / "components" / service.removeprefix("openbank-")
    if not component.is_dir():
        return False
    return any(
        "QUARKUS_FLYWAY_MIGRATE_AT_START" in f.read_text(encoding="utf-8", errors="ignore")
        for f in component.rglob("*.yaml")
    )


def migration_rule(service: str, rel: object, quarkus: dict, has_migrations: bool,
                   env_migrates: bool = False) -> list[str]:
    """Rule 3: committed migrations ⇒ Flyway must be told to run them.

    security-scanner shipped `db/migration/V2__create_security_outbox.sql` and V3, had
    quarkus-flyway as a build dependency, and never set `migrate-at-start`. It defaults to
    false, no deployment env set it either, and so the deployed database had ZERO tables and
    no `flyway_schema_history` at all. Every write answered
    `ERROR: relation "security_outbox" does not exist (42P01)` as a 500.

    Nothing could see it. The pod is Ready (health probes do not touch the schema), the
    migrations are present and correct in the repo, and the service's own tests are
    container-free. It is the exact inverse of the rule above — that one asks "you migrate, but
    against what?", this one asks "you wrote migrations, but does anything run them?" — and it
    is decidable from the same committed file plus a directory listing.
    """
    if not has_migrations:
        return []
    if (quarkus.get("flyway") or {}).get("migrate-at-start") or env_migrates:
        return []
    return [
        f"::error file={rel}::{service} commits Flyway migrations under "
        f"src/main/resources/db/migration, but nothing runs them: neither "
        f"quarkus.flyway.migrate-at-start in this file nor QUARKUS_FLYWAY_MIGRATE_AT_START in "
        f"its GitOps manifest. The switch defaults to FALSE, so the schema is never created — "
        f"the service starts, passes its health probes, and 500s on the first query with "
        f"'relation \"<table>\" does not exist'. Set quarkus.flyway.migrate-at-start: true "
        f"(and declare quarkus.datasource.jdbc.url with it — see the rule above).",
    ]


def findings() -> tuple[list[str], int, int]:
    """(messages, migrate-at-start services, application.yaml parsed).

    The third value is the corpus. `checked` is the migrate-at-start SUBSET and a scope
    collapse takes both, so only the parsed count can tell 'no service misconfigures Flyway'
    from 'no service was read'."""
    messages: list[str] = []
    checked = 0
    parsed = configs()
    for path, doc in parsed:
        quarkus = doc.get("quarkus") or {}
        flyway = quarkus.get("flyway") or {}
        rel = path.relative_to(REPO)
        service = path.parts[len(REPO.parts)]

        migrations = path.parent / "db" / "migration"
        messages += migration_rule(
            service, rel, quarkus,
            has_migrations=any(migrations.glob("*.sql")) if migrations.is_dir() else False,
            env_migrates=gitops_migrates(service),
        )

        if not flyway.get("migrate-at-start"):
            continue
        checked += 1

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
    return messages, checked, len(parsed)


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
    # Rule 3 is exercised through the REAL function rather than a re-derivation of it. (The
    # case table above predates this and re-implements rules 1-2 inline; that second copy is
    # the drift risk this repo warns about, and is worth collapsing separately.)
    migration_cases = [
        # (has_migrations, migrate-at-start value, gitops env, expected findings)
        (True, True, False, 0),    # migrations, and the config runs them
        (True, None, True, 0),     # migrations, and the GitOps manifest runs them (psd2's shape)
        (True, None, False, 1),    # migrations nobody runs — the security-scanner defect
        (True, False, False, 1),   # explicitly disabled is the same outcome
        (False, None, False, 0),   # no migrations, no rule
    ]
    for has_migrations, migrate, env_migrates, expected in migration_cases:
        quarkus = {} if migrate is None else {"flyway": {"migrate-at-start": migrate}}
        got = len(migration_rule("svc", "svc/application.yaml", quarkus, has_migrations, env_migrates))
        if got != expected:
            print(f"selftest FAIL: migrations={has_migrations} migrate-at-start={migrate!r} "
                  f"gitops={env_migrates} → {got} finding(s), expected {expected}")
            return 1
    # The GitOps lookup itself must be exercised, or the rule above is only ever tested with a
    # hand-passed boolean: psd2-service must resolve True, and a service with no component dir False.
    if not gitops_migrates("openbank-psd2-service"):
        print("selftest FAIL: gitops_migrates(openbank-psd2-service) is False — the lookup is broken.")
        return 1
    if gitops_migrates("openbank-not-a-real-service"):
        print("selftest FAIL: gitops_migrates matched a nonexistent service.")
        return 1

    print(f"selftest OK: {len(cases) + len(migration_cases)} cases, both directions "
          f"({len(parsed)} configs parsed).")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    messages, checked, parsed = findings()
    gatelib.subjects(parsed, "service application.yaml parsed")
    for line in messages:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not messages else f"{len(messages)} finding(s) above."
    print(f"check-flyway-default-datasource: {checked} migrate-at-start service(s) — {verdict}")
    return 1 if messages and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
