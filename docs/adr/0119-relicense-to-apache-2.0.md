# 119. Relicense the platform from MPL-2.0 to Apache-2.0

Date: 2026-06-27
Status: Accepted

Supersedes [ADR-0012](0012-mpl-license-and-dco.md) (MPL-2.0 + DCO).

## Context

[ADR-0012](0012-mpl-license-and-dco.md) chose **MPL-2.0** — file-level weak copyleft — as the platform
licence, to protect against pure-extraction forks while still allowing operators to combine OpenBank with
proprietary code in *new* files. In practice that protection has cost more adoption than it has bought:

- **Enterprise/bank legal review friction.** Many banks and large IT shops have blanket policies that
  treat *any* copyleft (including file-level MPL) as a review trigger. For a reference implementation whose
  whole value proposition is "deploy it, read it, trust it", that friction works against the mission.
- **Ecosystem default.** The cloud-native / Kotlin / Quarkus / Apache-Foundation ecosystem OpenBank lives
  in is overwhelmingly Apache-2.0. Matching the ecosystem default removes a class of compatibility
  questions for downstream integrators.
- **The anti-extraction moat was already moved.** The part of OpenBank actually intended for
  commercialization — the AI agent **runtime** — is AGPL-3.0 + commercial in a separate repo with a CLA
  ([ADR-0031](0031-ai-agent-governance-and-operations.md) D8). The copyleft moat lives *there*, not in the
  repo-wide baseline. MPL on the open core was therefore protecting against a threat the open core does not
  actually need protection from.
- **Patent grant is preserved.** Apache-2.0 carries an explicit patent grant and a patent-retaliation
  termination clause, so the defensive-patent goal of ADR-0012 is retained.

The trade-off accepted: downstream may now relicense their *changes* to OpenBank files (no file-level
copyleft). For a reference implementation optimising for adoption and trust, that is the intended outcome.

## Decision

**Relicense the entire platform repository from MPL-2.0 to Apache License 2.0.**

- Root `LICENSE` is replaced with the full Apache-2.0 text.
- Every source file's SPDX header changes to `SPDX-License-Identifier: Apache-2.0` and the header URL to
  `https://www.apache.org/licenses/LICENSE-2.0` (~2,200 files; mechanical, see
  `scripts/add-license-headers.sh`). OpenAPI/AsyncAPI `info.license` blocks change `name` to `Apache-2.0`.
- **Contribution mechanism is unchanged: DCO v1.1** (`git commit -s`). Apache-2.0 + DCO is a standard,
  well-understood pairing; no CLA is introduced for the platform.
- **The agent-runtime carve-out is unchanged.** The AGPL-3.0 + commercial agent runtime stays in its own
  repo/module with its own LICENSE + CLA. `rules.yaml`'s `license_denylist` carve-out is retained; its
  rationale wording moves from "incompatible with MPL-2.0" to "would contaminate the Apache-2.0 tree".
  AGPL consuming the Apache-2.0 `ModelProvider` port is one-directional and remains acceptable.
- Third-party facts are **not** rewritten: OpenBao is genuinely MPL-2.0 (runbook 0002, `openbao.yaml`),
  and transitive dependency licences in lockfiles are left as declared by their authors.

### Authority to relicense

MPL-2.0 → Apache-2.0 is a relicensing of existing contributions, which requires the consent of the
copyright holders. At this stage all in-tree contributions are held by the project owner / OpenBank
contributors under DCO with no third-party-assigned copyright that withholds consent; the relicensing is
therefore within the project's authority. Any future external contributions are made under Apache-2.0 via
the DCO sign-off on each commit.

## Consequences

**Positive**
- Removes the #1 legal-review blocker for bank/enterprise adoption.
- Aligns with the surrounding OSS ecosystem default; fewer downstream compatibility questions.
- Retains the explicit patent grant + retaliation clause.
- No new contributor friction — DCO stays.

**Negative**
- No file-level copyleft: downstream forks may keep their modifications closed. Accepted as the intended
  trade-off for a reference implementation.
- One-time relicensing churn touches ~2,200 files (header-only, non-functional).

**Neutral / unchanged**
- Agent-runtime AGPL-3.0 + commercial open-core model (ADR-0031 D8) is untouched.
- DCO, governance gates, and `version.txt`/release-please mechanics are unaffected — this is a licence
  metadata change, not a behaviour change.

## References

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- SPDX licence identifiers: https://spdx.org/licenses/Apache-2.0.html
- Developer Certificate of Origin: https://developercertificate.org/
- Superseded decision: [ADR-0012](0012-mpl-license-and-dco.md)
- Agent-runtime licensing carve-out: [ADR-0031](0031-ai-agent-governance-and-operations.md) D8
