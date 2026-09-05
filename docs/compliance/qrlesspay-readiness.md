<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# QRlessPay — readiness assessment: security, compliance, legal, standardisation

- **Date:** 2026-08-14 · **Status:** assessment, not an approval
- **Subject:** QRlessPay ([ADR-0095](../adr/0095-qrlesspay-ble-proximity-spayd-payments.md)) ·
  [wire spec v1](../specs/qrlesspay-v1.md) · [threat model](../threat-models/qrlesspay.md) ·
  [SDK proposal](../specs/qrlesspay-sdk.md)
- **Purpose:** answer, in one document, what a bank's security function, compliance function and
  counsel will each ask — including the questions we would rather they did not.

This is a self-assessment by the implementing team. It is **not** a substitute for the independent
reviews it recommends, and it is deliberately written to surface objections rather than to survive
them.

## 1. Executive position

For **internal deployment**, nothing structural is missing: the capability is code-complete, dormant
behind an enforced flag, and gated on four reviews that are already named in the threat model §8.
Those gates are the whole remaining distance.

For the **standard ambition** (ČBA, and beyond), the technical architecture is well suited — no
backend, no registry, a conformance suite, and a threat model that states its own limits. What is
entirely absent is the **legal and institutional layer**: DPIA, patent clearance, Bluetooth SIG
identifier allocation, trademark, and a neutral governance body. None of it is hard; all of it has
long lead times, and none of it has started.

The single most likely thing to *change the design* rather than merely delay it is the DPIA (§4).

## 2. What the design gets right, stated plainly

These are the points that will earn credit in review, and they are load-bearing:

- **No money moves in this protocol.** QRlessPay transfers a payment *proposal*. Settlement runs on
  the existing IBAN/instant rail with unchanged SCA in the payer's app. This single property is why
  most of the regulatory surface below is thin.
- **No server, no registry, no trust anchor** (spec §11). Verification uses the bytes in hand plus
  the device's own clock, radio and history. Nothing to operate, nothing to breach, nothing to
  subpoena.
- **The threat model does not oversell.** It states that identity is at parity with a QR scan,
  calls the baseline phishing-grade absent VoP, and now records that ceiling as permanent rather
  than pending. Reviewers distrust optimistic threat models far more than modest ones.
- **The IBAN is never broadcast.** It travels only in the payer-initiated GATT read.
- **Controls are enforced where they cannot be forgotten** — the RSSI gate and single-use check live
  in the protocol core, not in a screen.

## 3. Security review — expected outcome: pass, with the §8 gates as conditions

### 3.1 Already satisfied

| Concern | Position |
|---|---|
| Payload integrity | Ed25519 over `version‖sid‖nonce‖exp‖pk‖spayd`; advert↔bundle bound by `SHA-256(pk)[:2]` |
| Replay | Single-use `(sid, nonce)` per device, enforced inside `verify`, checked after the signature so a forged payload cannot burn a session |
| Wire format | Canonical CBOR, definite lengths, integer keys; the decoder rejects non-canonical integers, indefinite lengths, unknown/duplicate keys, truncation and trailing bytes before any cryptography runs |
| Cross-implementation agreement | Two independent implementations (Kotlin, Swift) produce byte-identical output and verify each other's signatures |
| Key handling | Per-session Ed25519, memory-only, regenerated per request screen |

### 3.2 Open, and genuinely blocking

1. **Independent cryptographic review** — nobody outside the implementing team has examined seed
   handling, key lifetime, or comparison behaviour. *Threat model §8.1.*
2. **Continuous CBOR fuzzing** — the decoder is new money-path code. Its strictness is unit-tested
   and has never been fuzzed. *§8.2.*
3. **ADR-0030 security review + second approval.** *§8.3.*
4. **Two concurrent `CBCentralManager` instances have never been run in this app.** Scanning is not
   an exclusive radio resource the way advertising is, so it is *expected* to work — that word is
   doing real work in this sentence and only a two-device lab run removes it. *§8.6.*
5. **Warning efficacy is unmeasured.** We wrote "a warning nobody reads is not a control either" and
   have not tested the duplicate-payment and same-name warnings with users.

### 3.3 The objection we should raise ourselves

The RSSI proximity gate is spoofable by raising transmit power, and UWB — the only cryptographic
answer — is a hardware minority and does not interoperate between Apple and Android. On *physical
targeting*, QRlessPay is weaker than aiming a camera at a visible code. The threat model says so;
this document repeats it because a reviewer who finds it themselves will assume it was hidden.

## 4. Data protection — the highest-risk area

**The advert broadcasts a first name to every device in radio range**, including people with no
relationship to either party.

- Lawful basis for payer and payee is performance of the requested payment. **It does not extend to
  bystanders**, who are not party to anything and cannot object to a radio broadcast they cannot
  see.
- The mitigating facts are real: user-initiated, screen-open-only, ephemeral rotating identifiers,
  platform private MAC, first name only, no IBAN, and no more than a name badge or a printed QR
  card already discloses.
- **Expected outcome:** defensible, but a formal DPIA is required and will plausibly land on
  **initials-only as the default** rather than as an opt-out. Plan for that rather than defending
  the current default.
- **Also required, and not yet done:** retention and record position for the payer-side history
  (device-local, never transmitted — likely out of scope for controller obligations, but that must
  be *concluded*, not assumed), and transparency copy explaining what is broadcast and when.

**European Accessibility Act** (applicable to banking services since June 2025): the warnings and
any SAS comparison must work under VoiceOver/TalkBack. Unaddressed today, and it will block an
internal release independently of anything above.

## 5. Payments compliance — expected outcome: pass

| Question | Position |
|---|---|
| New payment service under PSD2? | No — a proposal is transferred; the payment is an ordinary credit transfer initiated in the payer's own app. **Counsel must confirm.** |
| SCA | Unchanged. Mandatory, in the payer's app, after explicit confirmation. |
| Dynamic linking (PSD2 RTS Art. 5) | Satisfied: amount and payee shown and authorised come from the signed bundle the payer verified. |
| AML / sanctions screening | Unchanged — screening happens on the rail, not here. |
| VoP (EU Instant Payments Regulation) | Applies to euro/SEPA only. **Not deployed for CZK domestic**, so it must be treated as absent on that rail. |
| New ČNB licence or notification | Most likely none. **Counsel must confirm** — this is a legal conclusion, not an engineering one. |

**The open item compliance will press on: misdirected-payment liability.** If a payer pays the
wrong "Jiří", who bears it? Our position is parity with a QR scan — the payer confirmed name and
masked IBAN. Two things must become formal rather than implicit:

1. The decision to **warn rather than block** on duplicate payments and ambiguous names is a
   deliberate acceptance of residual risk, currently recorded only in a pull request. It needs
   business sign-off.
2. Customer-facing terms should state the payer's confirmation is the authorising act.

## 6. Legal, IP and standardisation — the least developed area

1. **Bluetooth SIG identifiers.** The three 128-bit UUIDs (service, `bundle`, `sas`) are frozen
   normative values — no registry applies to the 128-bit space, so nothing is pending there. The
   16-bit alias `0xF0B2` is still unallocated — effectively squatting on a Bluetooth
   SIG-administered space. Immaterial for a pilot; mandatory before third parties implement.
   Requires SIG membership and fees, and the decision taken here is to defer that request until a
   pilot with a second bank is real (see [wire spec §1](../specs/qrlesspay-v1.md#1-identifiers)).
2. **Patents.** Proximity payments is densely patented. A standard offered to other banks needs a
   freedom-to-operate search and an IPR policy (royalty-free or FRAND commitment from
   contributors). No bank's counsel will approve adoption without one.
3. **Trademark.** "QRlessPay" is unregistered. File before publication, or someone else can.
4. **Licensing hygiene.** Apache-2.0 throughout, contributor terms (DCO or CLA) decided before the
   first external contribution — not after.
5. **Governance.** Neither EPC nor ČBA adopts one bank's repository. A standard needs neutral
   ownership of the specification, a change-control process, and a decision on whether conformance
   is **self-declared** (publish suite output) or **certified** (someone operates the certification).
   That question is the difference between an open profile and a standard, and it is the point at
   which this needs an institution rather than a repository.

## 7. Recommended sequence

Ordered so that work which can *change the design* happens before work that builds on it.

1. **DPIA** and **independent cryptographic review** — either can alter the design; everything else
   is cheaper afterwards.
2. **Two-device lab run** (dual `CBCentralManager`, per-device peripheral-role compatibility) and
   **CBOR fuzzing in CI** — both are prerequisites for §8 and neither needs anyone external.
3. **Decisions**: SAS default-on, high-value threshold, initials-only default, accessibility.
4. **Bluetooth SIG allocation** and **trademark filing** — start early, long lead times, cheap.
5. **ADR-0030 second approval**, then pilot.
6. **Patent search** and **governance model** — only once a second institution is genuinely
   interested; doing this speculatively burns money on an option nobody has taken up.

## 8. What this document is not

It is not an approval, and no section of it should be cited as one. The reviews in §3.2 and §4 are
the approval; this only argues that they are the right ones and that nothing else is hiding behind
them.
