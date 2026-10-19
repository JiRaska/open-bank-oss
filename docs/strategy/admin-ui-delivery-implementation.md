# Admin UI delivery: first executable stages

This implements the first stages of the delivery and evidence proposal: a measured
delivery baseline, one independently published dataset, artifact-level browser proof,
and an enforced payment-module import boundary. It does not claim the target latency
budgets are achieved or that the whole UI has been split into independently deployed apps.

## Delivery baseline

Run `python3 .github/scripts/admin-ui-delivery-baseline.py --repo OWNER/REPO --target 30
--max-runs 600 --output /tmp/admin-ui-delivery-baseline.json` from the repository root.
The report samples started image-build steps by run and attempt, separates unsuccessful
builds and skipped attempts, and includes the denominator for each percentile. Incomplete
samples explicitly remain incomplete. Job-boundary queue measurements are proxies, not
runner scheduling telemetry. Historical build, push and signing share one recorded step;
human review and completed rollout require separate evidence and are not inferred.

The initial 2026-10-19 sample contains 30 started build attempts: 21 successful,
8 failed and 1 incomplete. It required inspecting 311 attempts; unstarted/skipped
builds were excluded. These are attempt timings, not 30 successful releases.

| Observed interval | n | p50 | p95 |
| --- | ---: | ---: | ---: |
| Run created to source job start (queue proxy) | 30 | 15 s | 374 s |
| Evidence coalescing contribution | 30 | 0 s | 600 s |
| Exact-source validation wait | 30 | 669 s | 1390 s |
| Validation finish to build job start (handoff proxy) | 30 | 24 s | 667 s |
| Build job preparation | 30 | 298 s | 357 s |
| Collection, image build, push and attestation combined | 29 | 341 s | 361 s |

One attempt has no build completion timestamp. Percentiles use nearest rank; phase
percentiles must not be added to estimate an end-to-end percentile. This is a baseline,
not evidence of improvement from these unmerged changes.

## Data delivery without an image build

Gate Health is the first extracted dataset. `admin-ui-evidence.yml` uses the existing
collector, validates the schema and producer timestamp, writes an immutable object keyed
by run, attempt and SHA-256, and compare-and-swaps `current.json` only afterwards. The
30-minute schedule bounds collector API cost; manual dispatch supports an immediate refresh.
This cadence is intentionally not the proposed two-minute CI-evidence target.

The authenticated BFF reads the store without credentials. It verifies the manifest,
same-origin path, byte digest, payload shape and observation time, with a bounded body,
timeout and single-flight cache. Bad publication cannot replace the last verified data.
The UI distinguishes unavailable, stale and bundled data and shows observation time.
The browser never receives GitHub or object-store credentials.

The publisher has a separate role limited to this dataset. It can list only the selector
key and read/write its selector and snapshots. Existing service-evidence bodies retain
write-once protection. The selector alone permits conditional replacement; CAS conflicts
fail instead of retrying with a blind overwrite. Bucket versioning remains enabled.
OIDC trust requires this repository's main ref. The workflow-ref condition follows the
existing `StringEqualsIfExists` provider convention: it narrows claims when supplied;
it is not proof of workflow identity when the issuer omits that claim.

Rollout sequence, through the normal reviewed infrastructure and GitOps paths:

1. Review the infrastructure preview, then dispatch **Platform OpenTofu** on `main`
   with scope `admin-ui-evidence`. This fixed scope targets the publisher role, its
   inline policy and the evidence bucket policy. A saved-plan check rejects any other
   managed-resource mutation or any deletion/replacement before apply. OIDC, state
   locking and applying the same saved plan remain in the existing workflow. This
   permits activating the dataset without applying unrelated platform changes.
2. Configure repository variables `ADMIN_UI_EVIDENCE_WRITER_ROLE_ARN` (output
   `admin_ui_evidence_writer_role_arn`), `ADMIN_UI_EVIDENCE_BUCKET` (existing
   `test_evidence_bucket` output), and `ADMIN_UI_EVIDENCE_REGION` for that deployment.
3. Dispatch **Admin UI evidence** and verify its successful publication. The job fails
   explicitly if configuration is missing or collection is unavailable.
4. Deploy the UI through its signed image and GitOps PR. The new URL is derived from
   the existing evidence-store environment variable. When the independent publisher is
   configured, image builds stop collecting Gate Health themselves.
5. Refresh data with another publication and confirm the displayed observation changes
   while `/api/build-info` remains unchanged. A cloud round trip is a rollout acceptance
   check, distinct from the local publisher/reader/route regression suite.

For rollback, dispatch **Admin UI evidence** with a prior
`snapshots/<run>-<attempt>-<sha256>.json` key. The publisher verifies the prior object's
digest and producer metadata before switching the selector. It preserves the original
observation time; selecting old evidence never makes it fresh. No app build is involved.

## Image identity and build cache

The image build can reuse a scoped GitHub Actions cache v2, including intermediate
stages. Cache credentials are short-lived and cleared before subsequent steps. Cache
export failure does not turn an otherwise valid build into a deployment failure.
Cache reuse does not bypass source checks, signing or browser validation.

After signing, delivery starts the exact image by registry digest on loopback and runs
an authenticated Playwright probe against its actual standalone server. The probe checks
unauthenticated rejection, source revision, protected page rendering, static assets and
a client-side theme interaction that proves hydration.
There is no second Next.js build or mocked BFF in that probe. Only success allows the
GitOps PR to be opened. The PR records the tested digest, and the workflow retains image
metadata and browser proof. The existing immutable deployment tag continues to identify
that digest. This proves packaged behavior, not live Keycloak or backend connectivity;
the deployed environment still needs its separate authenticated read-only smoke checks.

`build-push-admin-ui.sh --collect-only --no-sbom` prepares real image inputs for local
validation without an image push or GitOps change. `test-admin-ui-image.sh` accepts a
registry digest or local immutable image ID and creates only disposable local auth.

If a run fails after pushing its immutable tag, start a new workflow dispatch rather
than rerunning that run's build step. The existing tag includes the run ID, so a new
dispatch has a new tag; retrying the same pushed tag is not an artifact-resume mechanism.

## Incremental module boundaries

The single shared shell remains responsible for authentication, navigation, localization
and design primitives. ESLint now prevents payments code under app/components/lib from
importing private ledger, balance-sheet or treasury implementations. Alias and relative
imports, re-exports, type imports and literal dynamic imports are checked. Shared primitives
and imports within payments remain allowed. Expand the policy domain by domain after
extracting real shared contracts; avoid moving the entire tree or adding deployment zones
without a measured ownership or build-time benefit.

## Remaining acceptance work

The largest historical latency category is source-validation waiting, which cache alone
cannot remove. A trusted handoff of the exact validated source/build should replace this
wait only after artifact identity and privilege boundaries are proven. Other bundled
datasets, broader feature boundaries, and deployed rollback/rollout timing are subsequent
stages. The present changes keep those gaps visible rather than declaring the long-term
strategy complete on the strength of local tests.
