# Cosign KMS signing-key rotation

Rotate the configured KMS alias (currently documented as `alias/openbank-cosign-signing`) using
the provider's approved two-person change process. The alias and account are deployment config,
not credentials; never commit key ARNs or private material.

## Pre-check

Open a P3 exercise entry, confirm the current verifier trusts the existing public key, and stage
a new key in the KMS/HSM without changing the alias. Ensure the image-signing and verification
pipelines have a tested rollback to the previous alias target.

## Execute

With two approvers present, create the successor key, publish its public certificate through the
approved trust-distribution path, then atomically move the alias. Do not destroy or disable the
previous key until the retention window and verification checks pass.

## Verify and evidence

Sign and verify a synthetic non-production artifact, inspect the admission/policy check, and
confirm an artifact signed before rotation remains verifiable. Record register ID, key version
metadata (not material), approvers, timestamps, and rollback decision.
