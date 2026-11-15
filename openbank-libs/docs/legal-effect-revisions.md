# Legal effect revision contracts

ADR-0381's shared protocol lives in `openbank-libs-legal`, package
`com.openbank.libs.legal`. It reuses `CaseId` and `PartyId` from `openbank-libs-domain`. ADR-0317 keeps this
bounded context out of platform core; only future operational consumers opt in to it.

`LegalEffectRevisionGuard.evaluate(current, incoming)` distinguishes apply, duplicate,
stale and conflict. The identity binds one effect to its case and subject. A later
revision cannot reassign it to a different person, and releasing an effect leaves a
revision tombstone so an old attachment cannot resurrect it. Equal versions with
changed disposition or payload digest conflict. Complete snapshots can skip versions;
deltas cannot use this protocol.

The guard is pure and has **no production consumer yet**. It does not check legal
competence, authenticate an order, calculate protected funds or persist anything. The
future operational adapter must verify the canonical snapshot's SHA-256 and serialize
read/check/write with the effect's state mutation and outbox write. Only a committed
application can be acknowledged. Keep evidence/delivery timestamps in the instrument
and receipt models; timestamp ordering is not the revision protocol.

Ownership remains explicit:

| Shared in libs | Kept at its operational owner |
| --- | --- |
| Effect identity, revision envelope and replay outcomes | Legal Process: instruments, originals, service, legal interpretation |
| Existing PartyId / CaseId | Party: capacity, representation, consent scope |
| Common protocol tests | Account: account regime and links; Balance: protection, allocations, reservations |
| Design contracts under governance/legal-process | Transaction/Ledger: execution and booked money |

`governance/legal-process/{CZ,SK,DE}.json` are **design contracts, not executable or
approved country packs**. Every capability remains unimplemented. `reviewed_on` is the
research date, not legal approval or the date a law takes effect. `effective_from` must
remain null. Joint accounts are not offered in this initial scope; the DE joint-account
requirement remains recorded and cannot be dropped to make a future activation green.

Run the structural gate and its adversarial tests:

```bash
python3 .github/scripts/check-legal-process-contracts.py
python3 .github/scripts/check-legal-process-contracts.py --self-test
```

Default success is `LEGAL_PROCESS_DESIGN_CONTRACTS_OK`, explicitly not operational
readiness. The ADR delivery command with `--require-evidence` returns nonzero even for
well-formed contracts, until trusted execution evidence and legal-approval verification
are implemented. Neither the metadata nor this CLI is a deployed activation service.

Implementation continues in issue #16324. Do not copy lending pack types into this
package: they carry lending-specific product/disclosure semantics. Reuse their approval,
version and canonicalization mechanisms when runtime legal packs are implemented, with
new consumers tested before treating these foundations as operational enforcement.
