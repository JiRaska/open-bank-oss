# CRA conformity assessment — classification analysis

> Product classification of OpenBank under the Cyber Resilience Act — Regulation (EU)
> 2024/2847 — and the conformity-assessment path that follows from it. This document
> resolves the question [ADR-0278](../adr/0278-cyber-resilience-act-readiness-secure-sdlc-sbom-and-vulnerability-reporting-duties.md)
> deliberately deferred ("decided per product surface later, before 2027-12-11").
> Tracked in issue #8488. **Positioning analysis, not legal advice** — the final
> classification is made by the manufacturer at first market placement, against the
> Annex III/IV text and the technical descriptions of Implementing Regulation (EU)
> 2025/2392 in force at that date.

## Headline

**OpenBank, as a platform, classifies into the CRA default category** — no Annex III
or Annex IV category matches its core functionality (a banking platform: accounts,
payments, lending, KYC). The default category means **internal control (self-assessment,
Art. 32(1))** — no notified body. Two caveats bound that headline:

1. **Classification follows core functionality of the product as marketed, not its
   components.** If any OpenBank component is ever placed on the market *standalone* as
   an identity-management / access-control product (the SCA and consent surfaces are
   the candidates), that component re-classifies as **Important Class I** on its own.
2. **Scope precedes classification.** While OpenBank is supplied free of charge as
   open source with no commercial activity around it, it is arguably not "made
   available on the market" at all. Classification becomes binding the moment a
   commercial distribution, paid support, or a hosted offering exists.

## Classification analysis per product surface

CRA classification is by the product's **core functionality** against the category
technical descriptions (Implementing Regulation (EU) 2025/2392) — a product that merely
*contains* security functionality is not classified by it.

| Product surface | Closest Annex III/IV category | Match? | Class |
|---|---|---|---|
| Platform as a whole (accounts, payments, lending, KYC orchestration) | none | No — banking domain logic is not an Annex III category | **Default** |
| `openbank-sca-service` (strong customer authentication) | Class I: "identity management systems … including authentication and access control readers" | Only if marketed standalone as an identity/auth product. As an internal component of the banking platform: no | Default (conditional Class I) |
| `openbank-consent-service` (PSD2 consent management) | Class I: identity / privileged access management | Same condition as SCA — consent *governance*, not a standalone IdM product | Default (conditional Class I) |
| Admin UI / admin APIs | none | No — an operator console, not a "network management system" in the Annex III sense | Default |
| Embedded third-party components (Keycloak, Kafka, Postgres) | Class I/II categories in their own right | Not our classification to make — each upstream manufacturer classifies its own product; our duty is SBOM + vulnerability handling (Annex I Part II) | n/a |
| Hardware | Annex IV (smartcards, security boxes) | No hardware shipped | n/a |

**Conclusion:** Default category for the platform as shipped; **zero** surfaces in
Class II (we ship no hypervisor, container runtime, firewall, or IDS/IPS) or Annex IV
(no hardware). The only re-classification trigger is standalone marketing of the
identity/auth surfaces.

## What the default-category path requires of us

Internal control (Art. 32(1)) still requires the full manufacturer dossier — the class
changes *who attests*, not *what must be true*:

| Requirement (main obligations from 2027-12-11) | Status today | Evidence |
|---|---|---|
| Annex I Part I essential requirements (secure by design/default) | Partially — enforced in CI | `.github/workflows/security.yml` (CodeQL, Trivy, Gitleaks), threat-model gate (ADR-0030) |
| Technical documentation (Art. 31, Annex VII) | Gap — must be assembled per released component | this doc + evidence pack (`docs/compliance/evidence-pack.md`) are the skeleton |
| Machine-readable SBOM, kept current | **Done** | `release-evidence` job in `release-please.yml`; signed CycloneDX on every GitHub Release |
| Coordinated vulnerability disclosure (Art. 13, Annex I Part II) | **Done** | `SECURITY.md`, `security.txt` (RFC 9116) |
| Art. 14 reporting pipeline (from 2026-09-11) | **Done, pending rehearsal** | runbooks 0017 + 0018; tabletop exercise due 2026-09-11 |
| Support period ≥ 5 years, free security updates | Declared as commitment, formal value open | `SECURITY.md` "Supported Versions"; formal declaration before first production release |
| EU declaration of conformity + CE marking | Not applicable yet | required at first market placement |
| Vulnerability handling docs public (free-OSS important products) | N/A while default | if a surface re-classifies to Class I, public technical documentation preserves the self-assessment path |

## Re-classification triggers (review this document when any fires)

1. **Commercial distribution or paid support** of OpenBank begins → full CRA scope
   applies; confirm default classification at that date's Annex III text (the
   Commission may amend Annex III by delegated acts).
2. **Any component marketed standalone** as identity management, authentication, or
   access control → that component moves to Important Class I; self-assessment remains
   available only if harmonised standards / common specifications are fully applied
   (or, for free open source, technical documentation is public).
3. **Shipping a network/security product** (VPN, firewall, SIEM functionality as a
   product, not an internal control) → Class I/II analysis required.
4. **A delegated act adds a matching category** to Annex III covering core banking
   software → re-run this analysis.

## References

- Regulation (EU) 2024/2847 (CRA) — Art. 7, Art. 8, Art. 13, Art. 14, Art. 31, Art. 32,
  Annex III, Annex IV, Art. 71.
- Implementing Regulation (EU) 2025/2392 — technical descriptions of Annex III/IV
  categories (classification by core functionality).
- [ADR-0278](../adr/0278-cyber-resilience-act-readiness-secure-sdlc-sbom-and-vulnerability-reporting-duties.md)
  (readiness track), `SECURITY.md` (disclosure + support period), runbooks
  `0017-cra-article-14-reporting.md`, `0018-cra-art14-tabletop-exercise.md`.
- Tracking: issue #8488.
