<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Runbook 0014 — Independently verifying the audit anchors (ADR-0031 D5)

**Audience:** an external auditor, a regulator, or an incident responder who must decide whether
OpenBank's audit log was tampered with — **without taking OpenBank's word for it**.

**Issue:** #5838. **Tool:** `.github/scripts/verify-audit-anchors.py`.

---

## 1. Why `GET /api/v1/audit/anchors/verify` is not the answer

audit-service already exposes an endpoint that walks the hash chain, re-checks every anchor
signature and returns `INTACT`/`BROKEN`. It is a genuinely useful operational control and it is
**not independent verification**, for one structural reason:

> The component rendering the verdict is the component whose tampering the anchor exists to detect.

Anyone able to rewrite `audit_entries` is equally able to serve a hand-written `INTACT`, and the
endpoint reads the same database and uses the same signer bean. Its green is evidence about a
*healthy* system, and says nothing under the threat model that motivated anchoring in the first
place. The same applies to `GET /api/v1/audit/integrity`: an attacker who rewrites every row
recomputes a self-consistent chain, which is precisely the gap anchors were added to close.

So the verification has to be done **outside**, on public material, by code that is not ours.

## 2. What the anchor actually is

- A row in `audit_anchor` (migration `V6`), captured hourly by `AuditAnchorService.captureScheduled`.
- It attests a **checkpoint over the chain head**: `lastEntryId`, `lastRecordHash`,
  `chainedCount`, `chainStatus`, `signedAt`.
- `anchorDigest` = SHA-256 over those five fields pipe-joined (`AuditAnchor.digest`).
- `signature` = that digest signed by an **asymmetric AWS KMS `ECC_NIST_P256` key**
  (`SigningAlgorithmSpec.ECDSA_SHA_256`, `MessageType.RAW`), under the audit-service Pod Identity,
  which holds only `Sign` / `Verify` / `GetPublicKey`. The private key never enters the workload.
- Deployment (`openbank-infra/gitops/components/audit/audit-service.yaml`) sets
  `AUDIT_ANCHOR_SIGNER=kms` and `AUDIT_ANCHOR_SIGNING_REQUIRED=true` — so a signer failure
  **aborts capture** rather than storing an unsigned row that would later read as a checkpoint.

## 3. What you need — and where each piece comes from

| Input | Source | Do you have to trust OpenBank for it? |
|---|---|---|
| Anchor export | `GET /api/v1/audit/anchors?limit=200` (`ROLE_AUDITOR`) | It is the *evidence under test*; forgery is what the tool detects |
| Public key (PEM) | **AWS KMS `GetPublicKey` on the key id recorded in the anchor** — or `GET /api/v1/audit/anchors/verification-key?keyId=…` | **No.** Fetch it from KMS yourself; that is the point |
| Entry export (optional) | `audit_entries` rows for the attested range | It is the *log under test* |

Fetch the key independently whenever you can:

```
aws kms get-public-key --key-id <keyId recorded on the anchor> \
  --query PublicKey --output text | base64 -d > kms-pub.der
openssl pkey -pubin -inform DER -in kms-pub.der -out kms-pub.pem
```

The anchors record the **immutable KMS key id returned by `Sign`**, not the alias, so the check
stays correct across key rotation: each anchor names the exact key generation that signed it.

## 4. Run it

```
python3 .github/scripts/verify-audit-anchors.py \
    --anchors anchors.json \
    --public-key kms-pub.pem \
    --public-key-id <the keyId recorded on the anchors> \
    --entries entries.json       # optional but see §6
```

Requires only Python 3 and the `cryptography` package. It imports **no OpenBank code** — the two
canonical forms are re-implemented from their specification, so a producer that silently changes
one is rejected here instead of being mirrored. Both sides are pinned to the same literal test
vectors by `OfflineVerifierConformanceTest` (Kotlin) and the verifier's `--self-test` (Python);
change either form and exactly one side goes red.

### Outcomes are four, not two

| Exit | Verdict | Meaning |
|---|---|---|
| `0` | `VERIFIED` | Every anchor recomputed to its stored digest and verified under the public key |
| `2` | `TAMPERED` | Something was rejected; the message names what and where |
| `3` | `UNVERIFIABLE` | Nothing to verify, or no usable key for that generation — **never** report this as a pass |
| `4` | `INPUT_ERROR` | Bad arguments or a malformed export |

`UNVERIFIABLE` is deliberately its own exit code. An absent anchor set, an unsigned anchor, and an
HMAC-keyed anchor all land here, because a shared secret cannot by construction be verified by a
third party. This repo has already paid once for letting a skipped path share a result with a
successful one (`PushResult.skipped()`, #4348) — do not re-collapse them in a wrapper script.

## 5. What a rejection looks like

The tool names the finding rather than returning a bare status. Its `--self-test` constructs each
of these and asserts the rejection, so these paths are exercised on every CI run:

| Tampering | Rejection |
|---|---|
| Entry field edited in place | `ENTRY ALTERED at position N` |
| Entry deleted | `CHAIN LINK BROKEN at position N` |
| Two entries re-ordered | `CHAIN LINK BROKEN at position N` |
| Range truncated below an anchor | `ATTESTED HEAD IS MISSING FROM THE LOG` / `CHAIN LENGTH GAP` |
| Anchor row itself edited | `ANCHOR DIGEST MISMATCH` |
| Signature forged under another key | `INVALID SIGNATURE` |
| Log re-anchored over a shortened range | `ANCHOR SEQUENCE WENT BACKWARDS` |

Note why the DB rules do not make these impossible: `no_update_audit` / `no_delete_audit` are
`DO INSTEAD NOTHING` rules, so an `UPDATE` affects zero rows **and reports success**. They stop
casual mutation; they are not a control, because anything with rights to drop the rule leaves no
error behind. The chain plus the anchor are what make such a change *detectable*.

## 5a. Two findings you WILL hit on a real export, and what each means

Verified against the live audit database on 2026-08-21 (1212 anchors, read-only):

**Most anchors here are still HMAC-signed, and they are `UNVERIFIABLE`, not verified.** The KMS
cutover is recent; anchors captured before it carry `keyId=local-hmac-sha256`. A shared secret
cannot produce a signature a public key can check, so the tool declines to rule on them. It is
important that it declines rather than rejects: feeding an HMAC tag to an ECDSA verify fails, and
an earlier cut of this tool consequently reported *the entire pre-cutover history as forged*. A
false accusation of tampering is the most expensive wrong answer available here, so the key id is
inspected before any verification is attempted, and a mismatched key **generation** is treated the
same way. Practical consequence for a reader: **the independently verifiable window starts at the
KMS cutover**, and anything earlier rests on the operator's custody of the HMAC secret.

**Some anchors attest `chainStatus=BROKEN`.** This is not a forged anchor — the digest recomputes
and (for KMS anchors) the signature verifies. It is the producer faithfully recording that its own
`verifyChain()` walk was not intact *at capture time*. On this platform that population is bounded
(2026-07-09 to 2026-08-05) and corresponds to the pre-#3586 legacy hash segment: rows hashed in a
canonical form whose digits `timestamptz` truncated, which can never be recomputed (#3505). Those
rows are unverifiable rather than tampered with, and the walk was later corrected to count them
separately — which is why the `BROKEN` anchors stop. Do not read a `BROKEN` anchor as proof of
tampering, and do not read it as noise either: check whether the affected range is the known legacy
segment (`hash_version IS NULL` on `audit_entries`) before concluding either way.

## 6. What this does NOT establish — state this in any report

Three gaps are real, and a partial control described as full independence is worse than none:

1. **Absence of an anchor cannot be rejected.** The tool verifies the anchors it is given. A
   producer that never captured one for a period, or dropped a range before exporting, presents
   nothing to reject. Cross-check the anchor cadence against `openbank_workflow_last_success_age_seconds`
   for `audit-anchor-capture` and against the expected hourly interval — a missing hour is a
   finding, and only the *reader* can raise it.
2. **`signedAt` is the producer's own claim.** There is no external time source in the loop, so an
   anchor cannot prove *when* it was signed, only *what* it attests. A backdated history signed
   with a live key still verifies. Closing this needs an RFC 3161 timestamping authority or a
   public transparency log — **neither exists in this platform today**.
3. **Without `--entries`, only the anchors are checked.** A valid signature proves the checkpoint
   is authentic; it does not prove the *log* still matches it. The tool says so explicitly in its
   output rather than letting a signature-only run read as a full verification.

Consequently ADR-0031 **D5 remains 🟡 Partial**. The asymmetric signer and this verifier are the
part that is real; independent *proof of completeness and of time* is not, and the public control
score must not advance on the strength of this runbook.

## 7. Falsifying the tool itself

```
python3 .github/scripts/verify-audit-anchors.py --self-test
```

Ten cases: one clean range that must be **accepted** (ten rejections prove nothing if the tool
rejects everything), seven distinct tamperings that must be rejected with a naming message, and
two absence cases that must produce `UNVERIFIABLE` rather than either other verdict. Enforced in
CI as gate `audit-anchor-offline-verifier-unit-test` with a `min_subjects: 10` floor, so a suite
that quietly stopped constructing cases cannot read as a pass on the ones left.
