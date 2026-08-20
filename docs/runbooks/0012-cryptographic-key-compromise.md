# Cryptographic key-compromise response (ADR-0172)

This provider-neutral runbook covers suspected compromise of a signing, encryption,
issuer, or trust-anchor key. Provider commands and identifiers remain environment-local.

## Declare and preserve

1. Create a P1/P2 entry in the durable ICT register (`POST /api/v1/ict-incidents`),
   recording key class, identifier, suspected-use window, and affected artifacts. Never
   put key material in the incident record.
2. Freeze KMS/OpenBao, CI, audit, and admission-verifier logs before rotating anything.
3. Disable new use at the enforcement boundary and fail closed; do not fall back to a
   generated key.

## Contain and recover

1. Provision a separately approved replacement key and publish its public trust material
   through the reviewed deployment path.
2. Quarantine artifacts signed during the suspect window. Rotation does not invalidate
   historical signatures; re-verify using key version and transparency-log evidence.
3. For encryption keys, scope re-encryption or crypto-shredding under legal-hold and
   backup approval; do not destroy the old key during forensics.
4. Mark the incident `CONTAINED`, then `RESOLVED` only after verifier tests reject the
   old key and accept the replacement. Record RTO/RPO and regulator report ID.

## Closure evidence

Attach the inventory diff, access-log query, replacement public-material digest, verifier
output, affected scope, report ID, and blameless RCA. A live KMS rotation or tabletop is
an operational acceptance item; this runbook does not claim that it has run.
