# Threat model — openbank-ci-runner

Status: Draft — maintainer security review required.

Owner: Platform engineering. Method: STRIDE. Related: ADR-0030, ADR-0053 and ADR-0031.

## Scope and assets

The image is built from the pinned Actions runner base and versioned tool downloads in
`openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile`. Its registry digest is
declared by `arc-runners.tf`; the fleet attestation gate includes that image. CI execution
inherits each job's declared workflow permissions, service identity, credentials and mounts.
The image itself is a tool distribution, rather than an authorization boundary.

The bounded development pilot reuses that digest solely as a source-fetch init container.
It receives no repository credential, secret mount or Kubernetes API token. Its writable
storage is a size-limited scratch volume; the application mounts the volume read-only.
Assets are the integrity of the verifier/toolchain image, the public test snapshot, the
application's separate credentials and the operator's ability to distinguish a failed
collection from an empty result. Test source is untrusted data even on a reviewed branch.

## Data flow and trust boundaries

1. The build workflow produces and attests the CI image; registry admission verifies the
   pinned image. A digest change requires ordinary source review and fresh verification.
2. Public GitHub main crosses HTTPS into the unprivileged init container. A bounded sparse
   checkout materializes only the writer's own Kotlin test tree. Global/system Git config
   is disabled, the credential helper is empty, and checkout hooks are disabled.
3. The scratch snapshot crosses into the application as read-only data. Startup refuses
   missing/empty test sources and symlinks. The exact source revision is logged. The init
   container never runs a build, test, repository script or submodule initialization.
4. The application inspects text. Any proposed repair still crosses the packaged charter,
   OPA decision and mechanical source validator before the GitHub proposal boundary. A
   human controls merge; the source-fetch container has no access to the writer credential.

## STRIDE analysis

| Threat | Control and verification | Residual risk |
|---|---|---|
| Spoofing: substitute a registry tag or source endpoint | Immutable runner digest, existing signed-image admission/attestation, fixed HTTPS repository and ordinary certificate validation | A compromised trusted publisher remains inside the supply-chain trust boundary |
| Tampering: turn source data into executable repository code | Sparse own-test checkout, disabled Git configuration/helpers/hooks, no build/test execution, dropped capabilities and read-only root filesystem | Git and the image's installed tools remain executable trusted code |
| Tampering / elevation of privilege: symlink source paths into application secrets | Startup rejects symlinks before the snapshot reaches the application; an actual Git-fixture regression test supplies an escaping symlink | Parser correctness and future mount changes require review |
| Information disclosure: expose CI or application credentials to a source fetch | Init container has no secret inputs or API token; writer credential belongs only to the application container; snapshot mount is read-only | The normal CI-runner role can have job-scoped secrets and is reviewed under its own workflow trust boundary |
| Repudiation: claim a scan of current source without provenance | Init logs the exact revision; Temporal records operator workflow execution and the ordinary proposal/merge audit records remain required | The snapshot is taken at pod startup and can age until the next deployment |
| Denial of service: source outage, large repository or stalled clone | 120-second fetch bound, sparse partial clone, 256 MiB scratch limit and CPU/memory limits; missing/empty checkout fails startup | A source outage can block this non-money-path agent's rollout; reverting the activation restores the previous deployment |
| Elevation of privilege: use the tool image to widen the agent's authority | Source-fetch pod runs non-root without a Kubernetes token; existing proposal gate and own-test writer bounds remain unchanged | An allowed proposal still requires meaningful human review of the test's intent |

## Validation and release conditions

`test-agent-pilot-source.py` exercises the actual manifest script with a local Git fixture:
it admits the bounded tree, rejects unavailable/empty input and symlink escape, and checks
the read-only application mount, absent secret inputs and absent Kubernetes API token.
Negative controls widen the checkout and make the application mount writable; both must
fail. The normal fleet-attestation workflow runs this suite and verifies the image.

Before phase advancement, independently verify the deployed source revision, effective
writer switch, readiness and an operator scan with nonzero source count. A successful
source fetch alone is input-path evidence. A real merge row and separate durable audit
event remain separate ADR-0031 requirements. Stop proposals through the existing writer
switch, or revert the activation to remove both the source fetch and writer enablement.
