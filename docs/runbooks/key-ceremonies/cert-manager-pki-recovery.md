# cert-manager PKI recovery

This is the manual-intervention path when automatic renewal or issuance fails. It does not
replace cert-manager's normal automated rotation and never includes private keys.

## Pre-check

Create a P3 exercise entry, inspect Certificate/CertificateRequest status and controller events,
confirm the issuer and trust bundle are healthy, and identify the affected namespace. Do not
delete a Certificate or Secret before a verified backup and rollback plan exist.

## Execute

Repair the underlying issuer or approval condition using the approved platform procedure, then
reconcile the Certificate resource. If a replacement certificate is required, create it through
the issuer rather than applying a hand-generated secret. Preserve the previous certificate until
all consumers reload successfully.

## Verify and evidence

Check Ready/NotAfter conditions, TLS handshake and trust validation from a separate client, and
controller events showing successful issuance. Record resource names (not secret contents), key
IDs if applicable, timestamps, approvers, and the rollback outcome in the register.
