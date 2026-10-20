---
date: 2026-10-20
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, architecture, testing, resilience]
summary: "Keep critical shared code in-process; ship immutable consumer images from a conservative dependency graph. Version dynamic rules separately and require compatibility, failure, mutation and performance evidence for library changes."
followup: "#13448 — shared graph implemented; compatibility baselines, content inventory, revision-aware reconciliation and broader quality evidence remain"
---

# ADR-0346 — Shared library delivery and quality evidence

## Context

Shared libraries affect the correctness and availability of many OpenBank services. Their quality
must be demonstrated at the library boundary and in consumers. A green pod or a high line-coverage
percentage alone cannot establish that the intended library revision is running, that a failure
path is safe, or that a changed dependency remains compatible.

ADR-0122 and ADR-0317 already establish separate platform/domain/runtime and bounded-context
modules. The current source-build model also supplies Gradle caching, layered fast-jar images,
SBOMs, signing and provenance. Progressive delivery exists under ADR-0098. These are the basis
for improving updates; replacing them with a second distribution system would add coordination.

Deployment previously followed only direct project declarations. A dependency such as
`customer-edge -> openbank-libs -> openbank-libs-domain` therefore needed transitive traversal.
CI also needs test dependencies, while a runtime-only deployment should not be seeded by test code.

## Decision

### Execution and update boundaries

Keep deterministic domain computations and common types in-process. Continue to build immutable
service images from source, reusing unchanged Gradle outputs and container layers. A library
change selects every affected consumer automatically; it does not require an operator to enumerate
services. A change to genuinely shared core code may correctly affect the whole fleet.

Do not hot-swap arbitrary JARs inside a running production JVM. Quarkus augmentation and loaded
class state are part of the built application. An update produces a coherent image and uses the
existing rollout, health checks, workload draining and rollback mechanisms. Compatibility during
a mixed-version rollout is an explicit test condition.

Use independently versioned policy/configuration artifacts for behavior intended to change at
runtime. Validate and authenticate each revision, activate a complete revision atomically, record
the decision revision, and retain a rollback target. Each consumer must specify bounded evaluation
time, startup behavior, stale-data limits and the correct failure outcome. Authorization must not
turn an unavailable policy into permission; ordinary feature availability has a different contract.
These requirements do not assert that every existing provider already meets them.

Extract a remote service only when independent ownership or release cadence justifies its network
latency and availability cost. An extraction requires a measured call budget, isolation, a versioned
protocol and recovery tests. It is not the default way to make a utility library updateable.

### One dependency model, distinct proof scopes

Use `library_dependency_graph.py` for literal Gradle project edges. CI includes test edges;
deployment excludes only recognized direct test/benchmark configurations. Both traverse the
complete transitive graph, terminate on cycles and intersect their result with the caller's
buildable set. Unsupported configurations and cross-project mutations also select the full set.

Dynamic or unsupported dependency syntax, missing modules and unassignable convention-plugin
references select the full buildable set. A concurrent consumer build-file change can remove an
edge from the current graph; until a before/after graph is available, that also selects the full
set. Parser failure must never mean an empty successful rollout.

The initial implementation leaves reconciliation/Pact equivalence conservative. Narrowing it
requires proving the union of dependencies at both revisions, including provider test inputs and
global build inputs. Deployment scope and transferable contract evidence are different questions.

### Quality is a set of independently reported proofs

Each proof records source revision, tool/configuration version, environment, outcome, duration and
artifact reference in the existing Test Intelligence model. Missing, failed, stale, skipped and
not-applicable are distinct states. A skipped external contract is not a passed contract.

| Proof | What it must establish | Existing foundation / remaining work |
|---|---|---|
| Unit and property | Monetary conservation, valid-state transitions, rounding/calendar boundaries and rejection of invalid inputs | Module tests and property tests exist; extend invariants with each changed capability. |
| Integration | Actual database/Redis behavior, transactions, serialization and Quarkus/CDI wiring | Runtime container tests and consumer builds exist; mocks do not substitute for these boundaries. |
| Contract and compatibility | Consumer behavior, wire compatibility and old/new library API linkage | Consumer conformance/Pact exist; module-wide JVM ABI baselines remain to be established. |
| Concurrency and failure | Atomicity, bounded work, cancellation, outages and recovery without false success | Add targeted failure injection to each affected boundary, then exercise recovery through a consumer. |
| Fuzz | Parser robustness and bounded resource use across malformed inputs | ClusterFuzzLite exists for domain/ISO parsers; corpus and target coverage must follow module moves. |
| Mutation | Tests reject meaningful control-flow and arithmetic changes | PIT lanes exist; moved production code must remain in their target sets. Surviving critical mutations require review. |
| Performance | Allocation, latency and throughput at declared workloads | JMH exists for selected kernels. Allocation comparison is advisory; wall-clock results require comparable runners. Lending and dispatch workload baselines remain to be measured. |
| End-to-end and recovery | The shipped consumer revision completes a business flow and survives rollback/provider failure | Reuse simulation, synthetic journeys and progressive-delivery evidence; library unit results alone are insufficient. |

Keep existing coverage and mutation ratchets. Establish new latency, allocation and recovery budgets
from reproducible baselines before enforcing thresholds. Test a deliberate regression for each new
gate; a gate whose rejection cannot be demonstrated is not delivery evidence. Do not use a single
combined score to hide a missing critical proof.

## Delivery check

The first slice is the shared dependency graph and deploy/CI integration. Its fixture suite covers
umbrella chains, diamonds, cycles, test-only edges, quoted text/comments, dynamic syntax, missing
modules and unknown configurations. End-to-end selector checks prove actual umbrella consumers
are selected, unrelated services are excluded and empty results exit successfully.

Run `bash .github/scripts/libs-change-dependents.sh --selftest` and
`python3 .github/scripts/service-ci-library-dependents.py --self-test`. The existing
`libs-change-dependents` governance gate executes these checks.

The remaining work is tracked by #13448. Independent binary publication is still deferred until
API compatibility, content inventory and consumer evidence support it. No claim of complete
fleet resilience or best-in-class performance follows from this initial slice.

## Alternatives considered

- **Live JVM/JAR replacement:** considered for avoiding restarts; rejected as the general update
  mechanism because loaded state and Quarkus augmentation are not safely replaced by copying a JAR.
- **One remote shared-library service:** considered for one-place updates; rejected as a default
  because it converts local computations into network-dependent operations.
- **Rebuild every service for every library edit:** conservative but unnecessarily expensive for
  bounded-context modules. Retained as the fallback when dependency scope cannot be established.
- **Independent published binary libraries now:** deferred under ADR-0317 until compatibility and
  provenance controls make staggered adoption reviewable.

## Consequences

Consumers receive coherent, attributable updates; limited library changes can remain scoped, while
uncertainty increases verification rather than silently dropping consumers. Runtime/test graph
modes avoid unnecessary deployments without skipping required CI.

Updates still require producing and validating consumer artifacts. Parser support must evolve with
the repository's Gradle dialect, and conservative fallbacks can increase build cost. Versioned
policies and fault/compatibility testing add engineering work that must be measured and maintained.

## Compliance impact

- PCI DSS: supports reviewable shared-code changes; no new compliance claim or card-data path.
- DORA: strengthens traceable changes and recovery evidence; does not establish compliance alone.
- GDPR: no new personal-data processing or storage is introduced by the graph resolver.
- PSD2: policy revisions and consumer evidence support controlled authorization changes; no new authorization rule is introduced here.
- CNB: not applicable to reporting calculations in this delivery slice.

## References

- [ADR-0122](0122-split-openbank-libs-into-domain-and-runtime.md)
- [ADR-0317](0317-split-openbank-libs-domain-into-a-platform-core-and-per-bounded-context-libs-modules.md)
- [ADR-0098](0098-progressive-delivery-argo-rollouts.md)
- [Quarkus augmentation](https://quarkus.io/guides/reaugmentation/)
- [Shared graph](../../.github/scripts/library_dependency_graph.py)
- [Libs benchmark workflow](../../.github/workflows/libs-bench.yml)
- [Mutation workflow](../../.github/workflows/pitest.yml)
- [Implementation backlog](https://github.com/JiRaska/open-bank-oss/issues/13448)
