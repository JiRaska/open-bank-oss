# VEX triage store (OpenVEX)

Human-reviewed [OpenVEX](https://github.com/openvex/spec) statements per released component.
At release time, `​.github/scripts/build-release-evidence.sh` builds an OpenVEX inventory from
`trivy sbom` where **every finding is `under_investigation`** — a machine never asserts
`not_affected`, because that is a security claim that requires human judgement. The statements
in this directory are that judgement: they are **merged over** the auto inventory, and a human
verdict for a CVE wins. The merged, signed VEX is attached to the GitHub Release
(`<tag>.vex.json` + `.sig`) as part of the evidence bundle (ADR-0029 D2 / ADR-0030 D1/D4).

## File per component

`openbank-libs/governance/vex/<component>.openvex.json`, where `<component>` is the
release-please component name (the git-tag prefix), e.g. `pid-service`, `ledger-service`,
`admin-ui`. The file is a valid OpenVEX 0.2.0 document; only its `statements` are consumed
(the `products` default to the released component when omitted).

## How to triage a finding

A finding stays `under_investigation` until someone records a verdict here. The four OpenVEX
statuses and what each requires:

- `not_affected` — the vuln is present in a dependency but not exploitable here. **Must** carry a
  `justification` (one of the OpenVEX-allowed values, e.g. `vulnerable_code_not_in_execute_path`)
  and an `impact_statement`.
- `affected` — exploitable; **must** carry an `action_statement` (the remediation/mitigation).
- `fixed` — remediated in this version.
- `under_investigation` — triage pending (the default; no need to list it here).

Keep statements truthful and reviewed like code — this is a regulator-facing audit artifact.

## A single `< X` advisory range does not settle anything (issue #4716)

**Enforced** by `check-vex-open-interval-evidence.py` (gate `vex-open-interval-evidence`).

An advisory that writes its affected range as one open interval — `< X`, with no lower bound —
cannot classify a dependency that maintains **parallel release lines**. Maven/semver ordering says
`3.4.2 < 4.2.1`, so a 3.x release branched *after* the fix sorts below the bound and reads as
affected when it is not. The mirror case is worse: an older, genuinely unpatched line whose
version string sorts *above* the bound reads as safe.

This fleet has hit it twice. `GHSA-frpp-8pwq-hjrx` states `< 4.2.1`; hibernate-reactive
`4.2.1.Final` released 2025-12-21 and `3.4.0.Final` released 2026-05-27 — five months later, off a
branch that already carried the patch. The 47 statements that read the range alone landed on
`affected`, behind an exit criterion no candidate `quarkus-bom` can satisfy, and forcing
`>= 4.2.1` would have traded a non-existent DoS for a certain boot failure (#4533, PR #4707).
netty's 4.1.x-vs-4.2.x statements are the same shape.

So a statement citing a **one-sided** `< X` bound — for *any* verdict, `affected` included; only
`under_investigation` is exempt — must settle it one of two ways:

- **Artifact evidence.** Identify the advisory's fix commit and its observable effects, then
  `javap -p -c` the jar for them. **Run the probe against a known-negative and a known-positive**
  (the last unfixed release and the first patched one) so it is shown to discriminate rather than
  always answering yes — in #4533 the first attempt used `strings`, which fails on BSD and
  returned `0` for every jar including the known-fixed control. Quote the jar's `sha256`, and it
  must be the value already pinned for that artifact in `gradle/verification-metadata.xml`, so
  the bytes inspected are provably the bytes shipped. The gate checks that pairing.
- **`"resolved_version": "<x.y.z>"`** on the statement, when the fleet genuinely resolves the
  bound's own release line (`major.minor`) and plain arithmetic settles it. An OpenBank-local
  annotation, carried through into the released VEX document as provenance; JSON-LD ignores
  undefined terms, so it does not affect OpenVEX consumers.

A **two-sided** range (`>=4.2.0, <4.2.16`) names its release line and cannot sweep in a parallel
one — cite it that way and no evidence is required.

## Example

```json
{
  "@context": "https://openvex.dev/ns/v0.2.0",
  "@id": "https://open-bank.tech/vex/ledger-service",
  "author": "OpenBank Security",
  "version": 1,
  "statements": [
    {
      "vulnerability": { "name": "CVE-2024-00000" },
      "status": "not_affected",
      "justification": "vulnerable_code_not_in_execute_path",
      "impact_statement": "The affected codec is never invoked; ledger only uses the JSON path."
    }
  ]
}
```
