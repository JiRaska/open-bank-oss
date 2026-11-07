---
date: 2026-10-20
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#13429 — admin-ui Product Studio still calls the deprecated /pension-approvals alias"
tags: [product-catalog, governance, api-contract]
summary: "Catalog packs declare revision approval roles in pack.json, immutable per schema version; the kernel names no role. The legacy v1 vocabulary is frozen. Gate catalog-kernel-industry-neutral enforces both."
---

# ADR-0345 — Catalog packs declare revision approval policy as data; legacy v1 vocabulary is frozen

## Context

ADR-0257 decided that the product-catalog kernel "contains no bank, insurance or telecom enum"
and that industry packs contribute types, schemas and validators. ADR-0258 decided that packs
register from trusted classpath manifests. Neither one said where a pack's governance rules
live. Neither had a gate. When the retirement pack needed dual legal and product approval
(ADR-0334), the rule went into the kernel as code. That code added a role enum in the generic
repository port, `schemaRef.id == "…pension-savings" && version == 2` branches in the service
and the repository, pension columns on the generic revision, a pension-only table and a
pension-only resource. The "manifest" itself was a Kotlin list in the seeder. Every test
passed, and the next pack needing approvals would have had to copy the kernel (#13429).

The `/api/v1` banking vocabulary (`ProductType`, `CardNetwork`, `CardTier`, `WithdrawalNotice`,
`EligibilitySegment` and others) is a different case. ADR-0257 §3 already makes v1 a
compatibility adapter. ADR-0257 §3 together with ADR-0145 already decides its removal: the
deprecation window is declared and telemetry shows zero live consumers. What no ADR said is
whether that vocabulary may keep growing while v1 lives.

`TaxTreatment` on `PriceComponent` stays a kernel enum. ADR-0339 D fences it as price semantics.

## Decision

1. **A pack's manifest is `catalog-packs/<pack>/pack.json`.** It lists the pack's schemas in
   registration order, and each schema version may carry an `approvalPolicy`. The policy has
   these parts:
   - role codes, each bound to an internal authority name;
   - an optional `requireEffectiveFrom`;
   - required top-level attribute values.

   The seeder discovers enabled packs from their manifests. The kernel holds no list of packs,
   schemas, roles or authorities.
2. **A registered policy is immutable per schema version**, like the schema document. It is
   stored append-only in `catalog_schema_approval_policies`, and a manifest that changes or
   drops it fails startup. Declaring a policy for a version that had none only tightens
   governance, so it is accepted. Enforcement reads the database, never the manifest. A
   disabled pack therefore cannot become an unapproved publication path.
3. **Independence is a kernel invariant, not policy.** Each declared role is approved by a
   different principal and never by the maker, against the exact content digest. Publication
   requires every declared role and freezes the digest and the approved interval.
4. **Packs declare authorities and deployments bind them.** An authority is held only when one
   token carries both the scope and the realm role configured under
   `openbank.catalog.approval.authorities`. An unbound authority can never be held. Machine
   evidence readers are configured the same way.
5. **The v1 vocabulary is frozen.** No value may be added to a v1 enum. New vocabulary lands
   only in a pack schema. Removal still follows ADR-0257 §3 and ADR-0145.
6. **The generic surface is `/api/v2/offerings/{o}/revisions/{r}/approvals[/{role}]`**, with the
   OPA actions `catalog.revisionApproval.read|decide`. `/pension-approvals`, the
   `catalog.pensionApproval.*` actions and the `pension*` revision fields remain as deprecated
   aliases until 2027-01-31. Their callers are the admin-ui and the pension service, both
   internal, which ADR-0145 lets declare a shorter window.

## Alternatives considered

- **Policy as a JSON Schema extension keyword (`x-openbank-approval`) inside the schema
  document.** This keeps one file per type. It was rejected because schema documents are
  hash-locked at registration (ADR-0258). Adding the keyword to pension v2 would have needed a
  new schema version, which republishes every offering. It would also mix validation with
  governance in a document that external tools consume.
- **A per-pack Kotlin plugin (SPI) contributing approval rules.** This is flexible and type
  safe. It was rejected because it is the same defect in a different file: industry rules
  would stay code, reviewable only by Kotlin developers. A gate could not tell a plugin from a
  kernel leak.
- **Migrating v1 enums to free strings now.** This was rejected because v1 is a frozen contract
  with Pact consumers (ADR-0257 §3), and loosening its types gains nothing before its removal.

## Consequences

**Positive**
- A new pack declares its own approval roles with no kernel change.
  `ConformancePackApprovalPolicyIT` proves this with a test-only pack whose role and authority
  names appear nowhere in `src/main`.
- The V14 migration renames the evidence table and columns in place, so every existing approval
  keeps its id, digest and timestamp.

**Negative**
- During the compatibility window there are two endpoints, two pairs of wire fields and two
  sets of OPA actions to keep consistent.
- The audit and outbox event type changed from `…pension_<role>_approved` to
  `…revision_role_<role>_approved`. No consumer of the old type exists in this repository.

**Neutral**
- Enforcement: gate `catalog-kernel-industry-neutral`, mode `enforced`. It is default-deny:
  every Kotlin file is kernel unless `rules.yaml: catalog_kernel_neutrality.non_kernel_files`
  names it. Pack vocabulary is derived from `catalog-packs/`. `/api/v2` names carrying it must be
  `deprecated`. v1 enum value sets are pinned. Alias entries expire.

### Delivery check

```bash
python3 .github/scripts/check-catalog-kernel-neutral.py        # exit 0, "0 finding(s)"
git grep -n -i pension -- 'openbank-product-catalog/src/main/kotlin' \
  ':!**/PensionRevisionApprovalResource.kt' ':!**/CatalogReferenceOfferingSeeder.kt'
# expected: only the two deprecated wire-alias assignments in GenericCatalogResource.kt
git grep -n 'pension-approvals' -- openbank-admin-ui/src      # expected after the follow-up: nothing
```

## Compliance impact

- PCI DSS: not applicable — catalog definitions carry no cardholder data.
- DORA: not applicable — no change to ICT risk, incident or third-party arrangements.
- GDPR: not applicable — approval evidence records operator identities that were already recorded, unchanged.
- PSD2: not applicable — no payment-service surface is touched.
- CNB: not applicable — the dual-approval control for pension offerings is preserved unchanged, only relocated.

## References

- ADR-0257 — industry-neutral product catalog kernel (amended by this ADR)
- ADR-0258 — trusted JSON Schema profile and classpath manifests
- ADR-0145 — API deprecation and sunset policy
- ADR-0334 — pension fund platform (origin of the dual approval)
- ADR-0339 D — `TaxTreatment` is price semantics
- #13429
