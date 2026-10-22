# Library compatibility, fuzzing and recovery

Shared libraries are validated independently as well as through consuming services. Changes to
the six code-bearing modules (`domain`, `runtime`, `lending`, `iso20022`, `temporal`, `testing`)
must preserve their public JVM contracts and pass their existing lint and coverage ratchets.
The umbrella module re-exports libraries and has no separate public classes to snapshot.

## ABI checks

Each module enables the Kotlin Gradle plugin's ABI validation. Its generated
`api/openbank-libs-<module>.api` is the reference for `checkKotlinAbi`, which also runs through
the normal `check`/`build` lifecycle. Missing snapshots and signature differences fail the check.

For example:

```sh
./gradlew :openbank-libs-lending:checkKotlinAbi
./gradlew :openbank-libs-lending:updateKotlinAbi
```

Run the update only after reviewing the API change. A changed snapshot does not make a removed
method compatible: retain an old overload or plan an explicit incompatible transition. Generated
snapshots retain Kotlin's canonical formatting. Do not hand-edit their declarations.

`CreditQuoteBinaryCompatibilityTest` compiles a Java consumer against the original four-argument
quote API, removes the old implementation stub and executes the unchanged consumer bytecode with
the actual current library. This checks linkage in addition to snapshot drift. ABI checks do not
establish REST, event, serialization or behavioral compatibility; those need their own contracts.

## XML output construction and isolation

ISO builders create output DOMs through `SecureXml.newDocument()`. A private, lazily initialized,
hardened builder is used only for empty-document creation under a short lock. Each call returns
a separate document; subsequent DOM mutation and serialization belong to that caller. Input
parsing continues to use separate hardened builders, and each serialization uses its own
transformer. Never expose or reuse the private output builder for parsing.

`SecureXmlTest` verifies concurrent document ownership/content isolation and recovery after
rejected XML. The existing namespace, XSD and external-entity rejection tests remain applicable.
`Iso20022FuzzRecoveryTest` additionally checks escaped output values, deterministic malformed-wire
mutations, typed status-reader failures and invalid-to-valid recovery on the same reader and
validator instances.

The existing `Iso20022Bench.buildPacs008` workload measures the allocation effect. Compare results
using the same JVM, heap, collector, workload and multiple forks. Allocation budgets are not raised
to hide regressions; throughput and timing observations need comparable hardware and load.

## Fuzz findings become regression cases

The pacs.008 Jazzer target exercises both the reader and XSD validator. For each arbitrary input,
only the documented typed reader rejection is accepted. The target then builds a fresh complete
message and requires XSD validity plus exact recovered fields. A raw exception or failed recovery
is a finding. The public seed is a reader example and is not the positive XSD oracle.

Compile and run the deterministic corpus checks from the repository root:

```sh
./gradlew :openbank-libs-domain:classes :openbank-libs-iso20022:classes
./gradlew -p fuzz/ossfuzz test shadowJar
```

Use a JDK 25-compatible Jazzer driver for coverage-guided runs. Record driver/JVM versions,
budgets, source revision, findings and execution count. Retain each fixed finding as a regression
fixture and replay it before continuing fuzzing. The validator's coordinate-free SAX failure
has a permanent synthetic fixture under `openbank-libs-iso20022/src/test/resources/iso20022/regressions`.
Unexpected infrastructure failures must not be swallowed as successful input rejection.

Generated corpus, crash artifacts and full logs belong outside tracked sources while a finding
is being classified. Follow the private advisory process for a security finding. A bounded fuzz
run with no finding is evidence for that run, not proof that every possible input is safe.

## CI evidence and delivery

`libs-quality.yml` builds/tests all six libraries, checks ABI snapshots and existing coverage
floors, runs the standalone fuzz corpus tests and uploads JUnit results. `cflite-pr.yml` runs the
coverage-guided targets; its result must be inspected separately from the deterministic tests.
Four intentionally disabled negative conformance fixtures in `libs-testing` are reported as
skipped; the conformance self-test exercises their rejection directly.

The shared dependency graph selects transitive consumers, including umbrella dependencies.
Deployment excludes recognized test-only edges; CI includes them. Unknown syntax or ambiguous
dependency ownership selects the complete supplied fleet. Code updates use coherent consumer
images and the existing staged rollout. Broader fleet rollback and external contract evidence
remain separate from library-level recovery tests; see [ADR-0346](../../docs/adr/0346-shared-library-delivery-and-quality-evidence.md).
