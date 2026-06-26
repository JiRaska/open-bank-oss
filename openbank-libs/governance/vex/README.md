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
