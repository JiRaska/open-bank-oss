# AML service: client certificate and API caller identity

The production HTTPS listener requires a client certificate signed by the trusted workload CA.
That certificate authenticates the transport, but is not an AML API identity. Quarkus can
otherwise promote any accepted certificate to an authenticated `SecurityIdentity` (#12511).

All `/api/*` calls on the HTTPS and plain HTTP listeners select the OIDC bearer mechanism.
Existing role checks and OPA policy still authorize the resulting bearer principal. A caller
with only a certificate receives HTTP 401; a caller with neither certificate nor bearer cannot
complete the HTTPS handshake. The real listener parity test also sends a valid bearer over both
ports and requires the same application response.

This change leaves the separate health and management listeners outside `/api/*`. A rollout
must verify the same certificate-only rejection and valid-bearer behavior on the deployed
listener before #12511 is closed. Rollback would restore the bare-certificate identity risk;
do not remove the bearer-only permission without an approved replacement control.
