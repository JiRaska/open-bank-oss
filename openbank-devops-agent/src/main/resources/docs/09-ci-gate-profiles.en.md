# CI gate profiles in the daily analysis report

The existing daily analysis collects a bounded CPU profile after its six detector
passes. The optional `ciGateProfile` field in the analysis report carries the
collection time, repository, source SHA, run and attempt, artifact identity and
SHA256 digest, artifact creation time, and the original gate costs and verdicts.
The new activity is versioned: histories created before this addition replay
without scheduling a profile collection. A failed or timed-out activity leaves
this optional field null and preserves the existing findings flow. Null means no
profile, never zero cost. A completed collection that cannot verify evidence
returns `UNAVAILABLE` with a reason and an empty gate list.

The reader resolves the remote default branch and selects a completed push of the
repository's own CI workflow at that exact source. It verifies one attempt-qualified
`gate-results-<shard>-a<attempt>` artifact, checks its ZIP digest before parsing,
and rejects stale, ambiguous, mismatched or malformed evidence. It does not fall
back to an older source or attempt. It preserves producer status, failure kind,
nullable subject and budget counts, and self-test state. CPU can exceed wall time
for parallel children; detached descendants can escape producer CPU accounting.

One verified artifact is always `PARTIAL` coverage (`SINGLE_ARTIFACT`), even if all
its gates passed. It is not a ranking of the whole pipeline and does not prove a
saving, runner capacity, throughput or deployment. A future optimization still
needs a paired benchmark including setup and tests, the same corpus and verdicts,
and independent review. This reader does not create implementation PRs or gain
merge or deployment authority.

## Access and request bounds

The reader uses the existing optional `devops.github.token` and shared PostgreSQL
GitHub read budget/backoff. Metadata, artifact listing, archive requests and the
one permitted download redirect each reserve observer admission. Failed HTTP
attempts remain charged. The existing limits remain twenty total requests and
eighteen observer requests per rolling hour across replicas. There is no extra
poll loop, quota endpoint request or automatic activity retry. This does not make
a token-global cap claim: existing proposal writes and their reference lookup
remain outside this read-budget path.

Archive redirects are subject to the existing `openbank.egress.allowed-hosts`
policy. The default API-only allowlist does **not** authorize artifact storage.
An operator must explicitly allow the exact HTTPS storage hostname returned by
GitHub for the repository's artifact service through the normal reviewed
configuration path. Do not use an unrestricted wildcard or weaken DNS/private
address checks. The archive request never carries the API bearer token. A denied
storage destination produces unavailable evidence; this change neither modifies
runtime egress nor enables a deployment.

The collector bounds its request count, duration, archive and expanded JSON sizes,
entry count and gate count. It stops on budget denial, shared backoff, transport
failure or validation failure. Runtime qualification, image pinning and any
production activation require their usual independent evidence and review.

The artifact metadata/digest and archive redirect follow the
[GitHub Actions artifact API](https://docs.github.com/en/rest/actions/artifacts).


## CPU measurement advisories

The daily workflow can retain one deterministic measurement request from a trusted, fresh single-artifact profile. Selection is limited to successful enforced gates with positive finite CPU, an actually passed self-test and a satisfied declared subject floor. The largest eligible CPU value wins within that artifact; ties use the gate identifier. Missing, unavailable, stale or malformed evidence produces no advisory. This is not a ranking of the whole pipeline and does not estimate savings.

The request retains the repository, source, run, attempt, artifact digest and selected gate observation in the existing findings store. Its stable identity binds those immutable inputs. Concurrent delivery and activity retries insert once; existing human approvals, rejections and other lifecycle data are never overwritten. A separate Temporal version marker preserves pre-profile and profile-only histories. Activity unavailability leaves the existing detector report intact.

The advisory requires an alternating whole-gate A/B benchmark including setup, tests and cleanup; identical corpus, verdicts, diagnostics and provenance; total process-tree CPU, wall and process/request counts; and independent review. Net regression is not a saving. It does not call an LLM, open a proposal or implementation PR, authorize skipping controls, merge, deploy, add polling, or spend another GitHub request. The existing read budget and storage egress policy remain unchanged. Actual autonomous implementation PRs require a later reviewed capability.
