# 12. MPL-2.0 licence + Developer Certificate of Origin

Date: 2026-05-26
Status: Superseded by [ADR-0123](0123-relicense-to-apache-2.0.md) on 2026-06-27 (amended 2026-05-30 — see Amendment)
Delivery-Status: N/A

> **Superseded.** The platform was relicensed from MPL-2.0 to **Apache-2.0** on 2026-06-27 — see
> [ADR-0123](0123-relicense-to-apache-2.0.md). The DCO contribution mechanism and the AGPL-3.0 +
> commercial carve-out for the agent runtime (Amendment below, ADR-0031 D8) both carry over unchanged;
> only the permissive baseline changed from MPL-2.0 to Apache-2.0. This record is kept for history.

## Context

OpenBank will be released as open source. The choice of licence shapes who adopts the project and how. The major candidates considered:

- **Apache-2.0** — maximally permissive, patent grant, no copyleft. Easiest commercial adoption; downstream may close their forks entirely.
- **MIT** — minimally permissive, no patent grant. Liked for simplicity; weak protection for the project against patent threats.
- **MPL-2.0** — file-level weak copyleft. Modifications to MPL-licensed files must be released under MPL; new files combined with MPL code may be proprietary. Industry-tested in Mozilla, Eclipse, Hashicorp's pre-BSL era.
- **AGPL-3.0** — strong network copyleft. Forces SaaS operators to release modifications. Hostile to commercial adopters; reduces enterprise interest.
- **BSL / SSPL** — source-available, not OSI-approved open source. Tempts vendors but fragments community.

We need the licence to:
1. Enable widespread adoption by banks, fintechs, and OSS contributors.
2. Protect against pure-extraction commercial forks that contribute nothing back.
3. Avoid AGPL's hostility to enterprise IT teams (who often have policies against AGPL).
4. Include a patent grant (defensive against patent trolling).
5. Allow operators to combine OpenBank with their proprietary code without disclosure burden.

We also need a contribution provenance mechanism. Two options:
- **CLA (Contributor License Agreement)** — contributor signs assignment to project owner. Heavy admin burden; controversial in OSS community.
- **DCO (Developer Certificate of Origin)** — contributor sign-off in every commit certifies they have the right to contribute. Lightweight; industry standard (Linux kernel, Docker, GitLab).

## Decision

**Licence: Mozilla Public License 2.0 (MPL-2.0).**

**Contribution mechanism: Developer Certificate of Origin (DCO) v1.1.**

- Every contributor signs off every commit (`git commit -s`).
- A DCO bot enforces sign-off on every PR.
- No CLA required.
- Every source file carries an SPDX header: `// SPDX-License-Identifier: MPL-2.0`.
- Currently applied to 416/416 Kotlin + TypeScript files.

## Consequences

**Positive**
- Permissive enough for commercial adoption (banks can deploy without legal review pain).
- File-level copyleft protects against pure-extraction forks (modifications to MPL files stay open).
- Patent grant protects the project.
- DCO is lightweight; no contributor friction.
- SPDX headers enable automated licence compliance scanning.

**Negative**
- File-level copyleft is sometimes misunderstood — "if I modify file X, must I release file Y?" Answer: no, only X.
- Stricter than Apache-2.0; some contributors prefer Apache.
- AGPL purists may consider MPL too permissive.

**Mitigation**
- README and CONTRIBUTING explain MPL-2.0 in plain language.
- License FAQ in `docs/governance/license-faq.md` (to be created).
- For genuine licence-fit issues, dual-license is possible (operator pays for proprietary licence terms). Not pursued today.

## Amendment — 2026-05-30: AGPL-3.0 dual-licensed agent component (ADR-0031 D8)

The AI agent runtime component (ADR-0031) is the part of OpenBank intended for **commercialization** and
therefore deviates from the repo-wide MPL-2.0 + DCO baseline above — **scoped to that component only**.
This is the "dual-license is possible" path foreseen in the Mitigation above, now pursued for one component:

- **Licence: AGPL-3.0 + a parallel commercial licence** (open-core / dual-licensing). The network/SaaS
  copyleft is the moat against pure-extraction SaaS forks; the paid commercial exception is the revenue
  lever. AGPL alone is still free software and does not by itself monetize — the model around it does.
- **Contribution mechanism: CLA (not DCO) for that component.** Dual-licensing requires the project to
  own / be able to relicense contributions; DCO certifies provenance but grants no relicensing right, a
  CLA does. **All existing MPL-2.0 code keeps DCO unchanged** — the CLA applies only to the agent component.
- **Isolation:** the agent component lives in a **separate repository / module** with its own LICENSE +
  CLA, not mixed into MPL services. `openbank-libs/governance/rules.yaml` `license_denylist` (which lists
  AGPL-3.0 as MPL-incompatible) carries an explicit, documented **carve-out** for this component so the
  governance gate and reality agree. Combining AGPL agent code with MPL `openbank-libs` is one-directional
  and acceptable (MPL files stay MPL; the conveyed agent work is AGPL).

This amendment changes neither the licence nor the contribution mechanism of any existing MPL-2.0 code.

## References

- Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/
- MPL-2.0 FAQ: https://www.mozilla.org/MPL/2.0/FAQ/
- Developer Certificate of Origin: https://developercertificate.org/
- SPDX licence identifiers: https://spdx.org/licenses/MPL-2.0.html
