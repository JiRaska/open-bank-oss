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

## Parallel release lines: a `< X` range does not classify anything (#4716)

**Do not reach a verdict by comparing the resolved version to the advisory's bound.** When an
advisory states its affected range as a single open interval — `< 4.2.1`, `[0, 4.2.1)`, or the
prose form "fixed in 4.2.1.Final" — that bound declares no release line, and Maven ordering then
sweeps in every parallel line below it.

That is not hypothetical. `GHSA-frpp-8pwq-hjrx` says `< 4.2.1`; `4.2.1.Final` shipped 2025-12-21
and hibernate-reactive `3.4.0.Final` shipped **2026-05-27**, off a branch that already contained
the fix. The fleet's `3.4.2.Final` sorts below the bound and **has the patch**. 47 statements here
sat at `affected` on that arithmetic, with an exit criterion (`>= 4.2.1`) no Quarkus platform can
satisfy — and forcing it would have failed every service at boot (#4533, fixed by #4707). The
false negative is equally available: an older, genuinely unpatched line whose version string sorts
*above* the bound reads as safe.

The classifier refuses this case rather than guessing:

```
python3 .github/scripts/check-vex-range-reasoning.py --classify 3.4.2.Final :4.2.1
# undecidable_cross_line   (exit 2)
python3 .github/scripts/check-vex-range-reasoning.py --classify 4.1.136.Final 4.2.0:4.2.16
# unaffected               (exit 0 — a bounded interval declares its own line)
```

### What settles it

1. Read the advisory's referenced fix commit and identify its observable effects (a new method, a
   changed field, a new log message).
2. `javap -p -c` the resolved jar and check for them.
3. **Run the probe against a known-negative and a known-positive** — the last unfixed release and
   the first patched one. In #4533 the first attempt used `strings`, which fails on BSD and
   answered `0` for *every* jar including the known-fixed control; only the control row caught it.
   A probe that cannot go red proves nothing.
4. Confirm the jar's `sha256` matches the value pinned in `gradle/verification-metadata.xml`, so
   the bytes examined are the bytes shipped — **and cite that sha256 in the statement.**

Step 4 is enforced: the `vex-range-reasoning` gate fails any verdict statement making a
cross-line version argument without a sha256 that is pinned in the verification metadata. It
cannot check that you did steps 1-3, only that your conclusion is anchored to the artifact this
build actually resolves rather than to two sorted strings. Steps 1-3 remain a human's job.

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
