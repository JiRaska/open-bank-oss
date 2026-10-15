<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Audit anchor verification-key registry

`anchor-verification-keys.json` in this directory is the **public registry of KMS
verification keys** for the audit anchors (ADR-0031 D5, issue #12923 gap 6). It exists so
that a third party can verify OpenBank's audit anchors using **only public data**: the
anchor export plus this file. Neither AWS credentials nor access to the bank-gated
`GET /api/v1/audit/anchors/verification-key` endpoint is needed.

## Why publishing these PEMs is safe

Every entry is a **public key**. A public key *verifies* signatures; it cannot *sign*
anything, so publishing it grants no capability — the private half never leaves AWS KMS.
The same PEM is already obtainable by anyone with AWS credentials via
`aws kms get-public-key`, and by anyone with an audit-reading role via the verification-key
endpoint. The registry only removes those two gates; it does not expose anything that was
secret. (This is the opposite of the pre-cutover `local-hmac-sha256` anchors: a shared
secret could never appear here, which is exactly why those anchors are not third-party
verifiable — see runbook 0014 §5a.)

## Format

A JSON array (strict JSON — no comments, so any standard parser reads it) of entries:

```json
{
  "keyId": "arn:aws:kms:…:key/<id>",
  "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----\n",
  "algorithm": "ECDSA_P256_SHA256",
  "firstSeen": "2026-10-22",
  "source": "kms get-public-key via attestation workflow",
  "note": "…"
}
```

* `keyId` — the immutable KMS key id **recorded on the anchors** (not an alias), so the
  mapping from anchor to key survives key rotation: each anchor names the exact key
  generation that signed it.
* `algorithm` — today always `ECDSA_P256_SHA256`; any future value other than this is a
  signal to stop and read the note before trusting the entry.
* `firstSeen` — the date the key generation was first observed by the weekly attestation.
  Entries are kept after rotation; a retired generation stays listed.

## How a third party verifies with it

1. Obtain an anchor export (`GET /api/v1/audit/anchors?limit=…` with an audit-reading
   role — the evidence under test; forgery is what the tool detects).
2. For each distinct `keyId` in the export, look the entry up in this registry. If a
   generation is missing, verification of that generation is `UNVERIFIABLE`, never a pass.
3. Run the offline verifier (runbook 0014 §4), one invocation per key generation:

   ```
   python3 verify-audit-anchors.py \
       --anchors anchors.json \
       --public-key <the PEM from this registry> \
       --public-key-id <the keyId recorded on the anchors>
   ```

   Requires only Python 3 and the `cryptography` package — no AWS access, no bank
   endpoint, no OpenBank code. See runbook 0014 for what a green run proves and,
   importantly, what it does not.

## How the registry is updated

The weekly `audit-anchor-attestation` workflow (runbook 0014 §8) fetches the KMS public
key for every key generation recorded in the live export, **after** the export has
independently verified. It then compares the fetched PEMs against this registry and, on
any difference (missing generation, or a PEM that no longer matches), opens or updates
**one** PR — branch `agent/audit-anchor-key-registry-update`, commit
`chore(audit): refresh anchor verification key registry`. The PR body lists only the
affected keyId(s) and the SHA-256 fingerprint(s) of the PEM(s); it claims nothing more
than a verification-material refresh, and a human reviews and merges it. When the registry
already matches, no PR and no commit are created. `workflow_dispatch` uses the same path,
as a manual repair route.

A registry entry can therefore only appear or change via a PR — visible in the repo's
review history — and never silently: the diff itself is the announcement.

## Residual trust

The registry is committed by the same CI that runs the attestation, so a party who
distrusts the repository outright should still cross-check a PEM against
`aws kms get-public-key` when they hold AWS credentials. Note what a registry swap cannot
do, however: an attacker who replaces a registered PEM with their own key does not make
forged anchors verify — anchors signed by the real generation **fail** under the swapped
key (`INVALID SIGNATURE`), so the tamper surfaces as `TAMPERED`, not as a clean pass.
