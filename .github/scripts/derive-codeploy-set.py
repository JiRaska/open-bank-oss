#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
r"""Derive the CO-DEPLOY SET behind a can-i-deploy block (issue #1985).

THE PROBLEM THIS SOLVES
`can-i-deploy --pacticipant <svc> --to-environment sandbox` asks one question about one
service: is THIS version compatible with what is CURRENTLY DEPLOYED alongside it. That
question has no answer when two services in the same run must move together — each is
blocked by the other's not-yet-deployed version, and no ordering of single-service
deploys resolves it. Neither service is broken: the pair verifies fine at one frozen main
SHA. Only the deployed state is stale, and it is stale in both directions at once.

WHAT ACTUALLY HAPPENS TODAY, and why this is a safety issue rather than a latency one.
The gate names the blocked services (#1420) and names the KIND of block (#2549), but
nothing names the SET, so a human reads the broker matrix by hand and then dispatches a
co-deploy that bypasses the per-service gate. That bypass was used three times in one
week, all on the money path: #1979 (sepa-payment/transaction/fraud), #2057
(swift/transaction), #2059 (domestic-payment/sepa-payment). The recurring hand-driven
bypass IS the risk — each one establishes contract safety by archaeology instead of by
the gate.

WHAT THIS DOES
Reads the can-i-deploy CLI output already captured per blocked service and derives, as a
pure function of that text, the connected components of blocked services that reference
each other. A component of size > 1 is a co-deploy set: those services cannot converge
one at a time and must be offered to the broker together.

It also reports the other shape — a blocked service whose counterpart is NOT part of this
run at all. That one is NOT a co-deploy set: nothing in this run can move the counterpart,
so the honest message is that the block waits on a service outside the run.

WHAT THIS DOES NOT DO
It does not deploy, does not call the broker, and does not weaken the gate: every service
it names is still blocked. It turns "3 services blocked, work out why" into "these 3
services must go together, here is the command", which is the difference between a
supported mode and an undocumented bypass.

WHY A SEPARATE SCRIPT
The same reason as classify-can-i-deploy-block.sh: inline in auto-deploy.yml this is
unreachable by any test, and graph logic that silently produces the wrong set would put a
confident label on a guess — here it is a pure function of its stdin, so
test-derive-codeploy-set.sh can drive every branch.

Usage:
  derive-codeploy-set.py <changed-services-space-separated> < blocks

  stdin is a stream of records, one per blocked service:
      ===SERVICE <name>
      <that service's verbatim can-i-deploy CLI output>

  <changed-services> is every service in THIS run (deployable or not), so a counterpart
  can be told apart from one outside the run.

Prints, one finding per line, TAB-separated. Two record types:
    CODEPLOY\t<svc> <svc> ...        a mutually-blocking set that must deploy together
    EXTERNAL\t<svc>\t<counterpart>   blocked on a service this run cannot move

Always exits 0 — this is a reporter, not a gate.

stdlib-only, and python3 rather than bash on purpose: bash 3.2 (macOS, the machine a
contributor runs the unit test on) has no associative arrays, so a bash implementation
runs ONLY in CI. A graph script that cannot be exercised where it is written is the
unfalsified-gate shape this repo has already paid for.
"""

from __future__ import annotations

import re
import sys

SERVICE_TOKEN = re.compile(r"openbank-[a-z0-9][a-z0-9-]*")

# Only the per-pair explanation lines name a counterpart. The "Computer says no" banner
# names nobody, and the CONSUMER|PROVIDER matrix table TRUNCATES names to the column width
# ("openbank-transacti"), so parsing it invents services that do not exist and would name a
# co-deploy set no dispatch can satisfy. Read the prose lines only.
PAIR_LINE = ("There is no verified pact", "The verification for the pact between")


def derive(changed: set[str], stream) -> list[str]:
    blocked: list[str] = []
    edges: dict[str, set[str]] = {}
    external: set[tuple[str, str]] = set()

    svc: str | None = None
    for raw in stream:
        line = raw.rstrip("\n")
        if line.startswith("===SERVICE "):
            svc = line[len("===SERVICE "):].strip()
            if svc not in blocked:
                blocked.append(svc)
                edges.setdefault(svc, set())
            continue
        if svc is None or not any(m in line for m in PAIR_LINE):
            continue
        for token in sorted(set(SERVICE_TOKEN.findall(line))):
            if token == svc:
                continue
            if token in changed:
                edges.setdefault(svc, set()).add(token)
                edges.setdefault(token, set()).add(svc)
            else:
                external.add((svc, token))

    findings: list[str] = []
    seen: set[str] = set()
    for root in blocked:
        if root in seen:
            continue
        component = [root]
        seen.add(root)
        i = 0
        while i < len(component):
            node = component[i]
            i += 1
            for peer in sorted(edges.get(node, ())):
                # Only BLOCKED services can be part of a co-deploy set: a counterpart that
                # is in this run and already deployable needs nothing done to it.
                if peer in blocked and peer not in seen:
                    seen.add(peer)
                    component.append(peer)
        if len(component) > 1:
            findings.append("CODEPLOY\t" + " ".join(sorted(component)))

    for svc_name, counterpart in sorted(external):
        findings.append(f"EXTERNAL\t{svc_name}\t{counterpart}")
    return findings


def main() -> int:
    changed = set(sys.argv[1:])
    for finding in derive(changed, sys.stdin):
        print(finding)
    return 0


if __name__ == "__main__":
    sys.exit(main())
