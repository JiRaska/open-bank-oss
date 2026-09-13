# OpenBao unseal ceremony

Use after a planned restart or when the OpenBao seal state is confirmed. Recovery shares stay in
the approved external secret manager; this runbook contains no shares.

## Pre-check

1. Create a P3 scheduled-exercise entry in the ICT incident register and name the two operators.
2. Confirm the target namespace, OpenBao health endpoint, maintenance window, and a tested backup.
3. Confirm both operators can retrieve their assigned share through the approved break-glass
   workflow. Do not copy or log the share.

## Execute

1. Operator A verifies the seal status and records the timestamp and response code.
2. Operators A and B submit their shares independently through the approved control plane.
3. Stop when the threshold is reached; never submit extra shares “to be safe”.

## Verify and evidence

Confirm the health endpoint reports unsealed, a read-only canary succeeds, and audit logs contain
the ceremony actor IDs. Record only the register ID, timestamps, result, and verification checks.
If unseal fails, reseal/restore according to the platform recovery procedure and escalate as P1/P2;
do not troubleshoot by exposing shares.
