# Key-ceremony runbooks

These procedures are deliberately provider-neutral. Operators must use the deployment's
approved secret manager and record the ceremony in the ICT incident register as a P3
(scheduled resilience exercise), never in this repository. Never paste key material, recovery
shares, tokens, or production identifiers into tickets, chat, or command output.

Each ceremony has four phases: pre-check, two-person execution, verification, and evidence.
The operator records the register entry ID, approvers, start/end timestamps, result, and any
rollback action. A failed pre-check stops the ceremony; it is not a reason to improvise a
break-glass procedure.

Runbooks:

- [OpenBao unseal](openbao-unseal.md)
- [Cosign KMS signing-key rotation](cosign-kms-rotation.md)
- [Keycloak realm signing-key renewal](keycloak-signing-key-renewal.md)
- [cert-manager PKI recovery](cert-manager-pki-recovery.md)

Annual dry-runs must use a non-production namespace and synthetic keys. A dry-run proves the
procedure and evidence capture, not production access or recovery time.
