# Statutory delegation authority in KYB and Party

Scope is the verified representation source for company delegations (#10247). KYB owns the
human confirmation, register text and verified signing roster. Party owns the company's live
mandates. Delegation-service must establish both sources before using a rule.

## Trust boundaries

- KYB staff confirm the mode, threshold and any required offices against the hash of the register
  text they reviewed. A verified human staff identity is required to set the typed mode.
- Only the named KYB service identity can publish a Party snapshot. Party checks every listed
  actor is a distinct active natural person and holds a current registry mandate for the company.
- Only the named delegation-service identity can read the Party snapshot and check whether its
  KYB attestation remains current. Both reads disable HTTP storage.

## Threats and controls

| Threat | Control |
| --- | --- |
| An incomplete case is published with a guessed quorum or role assignment | KYB requires the exact pinned human attestation, explicit joint mode, matching rule text and identity-linked signers of its verified agreement ceremony. Fixed quorum needs the attested count and offices. Joint-all needs the full register roster. Party checks the signer-bound mandate digest and distinct office assignment. |
| A mandate from another case or actor is substituted | Party compares each active mandate's evidence digest to the value supplied for that actor. The snapshot retains the exact mandate IDs. |
| A withdrawn mandate or superseded attestation is used | Party rechecks current mandates on every read. KYB returns unavailable when the pinned attestation is superseded, including before a replacement is published. The caller must obtain both current proofs. |
| A shared role or selected profile is treated as evidence | OPA checks the named client and the service verifies the authenticated identity in code. Employee approval groups remain separate. |
| A retry rewrites a published revision | Party inserts once per attestation and compares an existing snapshot against the retried evidence. |

The snapshot contains identifiers, office slots, and evidence digests. Raw register text and
signature references stay with their existing owners. The KYB check verifies the current recorded
attestation. A register change becomes visible after it is recorded by the KYB workflow. This
slice does not expose delegation activation or customer-facing statutory signing.
