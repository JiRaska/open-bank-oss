# Capacity evidence integrity bundle

`.github/scripts/verify-capacity-evidence.py` checks that a run bundle is internally
bound to the proposed ADR-0356 profile, that its declared files match their SHA-256
digests and sizes, and that GitHub reports a successful attested workflow run attempt
for the same source commit. It does not evaluate whether the workload passed.

## Manifest contract

Provide an evidence directory containing `manifest.json` and exactly the regular files
listed in its `artifacts` array. The manifest uses
`openbank.capacity-evidence/v1`; it binds the exact profile bytes and schema version,
repository/ref/commit/workflow, workflow run ID and attempt, campaign scope, application
and k6 image digests, effective configuration, topology, dataset seed/generator version,
and artifact path/size/SHA-256. Effective-configuration and topology digest bindings
name their inventory artifact paths; each path and digest must exactly match the
verified inventory. The dataset binding names a descriptor artifact and digest. That
strict JSON descriptor has exactly `schema`, `seed_sha256`, and `generator_version`,
which must match the signed binding. Artifact paths must be relative, unique, and contain
no traversal or symlink. JSON duplicate keys and non-finite values are rejected.

Timestamps and workload bindings in this manifest are signed workflow-produced claims;
the verifier joins run ID and attempt to GitHub's authenticated API and compares source,
workflow, status, conclusion, and run times. A signature does not independently prove
that the workload actually used the declared images, configuration, topology, or data.
Configuration/topology/dataset descriptors are byte-bound artifacts, but their
collection and meaning still depend on the trusted workflow. Application and k6 OCI
digests remain typed, signed runtime claims; they are not independently proven as
executed images by this verifier. The future workflow must collect these values from
authoritative runtime observations.
`--deny-self-hosted-runners` constrains the attestation signer; it is not proof that the
workload itself ran in an isolated environment.

## Invocation and current boundary

From a checkout of the trusted repository, run:

```sh
python3 .github/scripts/verify-capacity-evidence.py --evidence-dir /path/to/evidence
```

The verifier invokes `gh attestation verify` with repository, signer workflow, SLSA
provenance predicate, GitHub Actions OIDC issuer, source ref and digest constraints, and
`--deny-self-hosted-runners`. It then calls `gh api` for the exact run attempt extracted
from the verified certificate's immutable `runInvocationURI` and requires the
workflow-controlled SLSA predicate invocation to match it exactly. It checks the signed
certificate's issuer, source repository/ref/commit, workflow identity/SAN, runner type,
and trigger against fixed policy before joining the exact API attempt. Trust constants
are fixed in the script; the manifest cannot select a repository, workflow, issuer, or
API endpoint. GitHub CLI must already be authenticated for the API lookup. The verifier
rechecks the manifest and artifact inventory after external CLI/API calls to reject
local bundle changes during verification, and does not print CLI stderr, which can
contain environment-specific details. The `gh` JSON certificate representation follows
the Sigstore verification result format documented in the
[sigstore-go verification guide](https://github.com/sigstore/sigstore-go/blob/main/docs/verification.md).

The fixed signer workflow path is reserved for a future protected issuer workflow; no
such workflow or real cryptographic end-to-end fixture is part of this change. Unit
tests mock only the subprocess boundary. Therefore real `gh` attestation verification
and API behavior have not been exercised here, and successful synthetic tests are not
evidence that an issuer exists or can produce a valid attestation.

The result always labels itself `evidence-integrity-only`, sets
`capacity_qualified`, `financial_invariants_verified`, and `slo_verified` to false, and
uses `not-evaluated-by-this-tool` for qualification disposition. A `proposed` profile
with pending owner decisions can pass integrity checks; those decisions still block
qualification. Bounded sandbox observations, five-million models, platform
qualification, and bank production qualification remain distinct declared scopes.
The separate CZK ledger oracle is a pilot check and does not establish all profile
financial invariants. No CI gate invokes this verifier yet.
