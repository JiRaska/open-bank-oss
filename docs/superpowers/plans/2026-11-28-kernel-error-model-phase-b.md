# Kernel Error Model Phase B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build ADR-0326's enforced code catalogue and mapper ratchet, add narrow legacy-body compatibility, and prove it with two product-catalog migrations.

**Architecture:** A stdlib/Python scanner extracts closed `ErrorCode` enums and feeds an enforced checker plus deterministic documentation/OpenAPI generators. A separate source scanner ratchets service-local JAX-RS mappers. The Kotlin change adds one optional compatibility value to the existing typed exception/mapper path; the service change then deletes only two compatible local mappers.

**Tech Stack:** Kotlin, Quarkus/JAX-RS, Jackson, JUnit 5/AssertJ, Python 3 stdlib, PyYAML only for generated-document validation, Gradle, the repository gate runner.

## Global Constraints

- Do not start ADR-0326 phases c/d or change generic JDK mapper behavior.
- Do not globally replace `require` or `requireNotNull`.
- Preserve the two migrated product-catalog statuses and legacy `error` messages exactly.
- Leave product-catalog's 412, 428 and catalog-validation mappers unchanged.
- Never hand-edit `version.txt`, `CHANGELOG.md`, or `quarkus.application.version`.
- Use `git commit -s -S`; the two release-relevant slices use `feat(libs)` and `feat(product-catalog)` respectively.
- Reference #11610 with `Refs`; do not close it.

---

### Task 1: Error-code scanner and enforced catalogue

**Files:**
- Create: `.github/scripts/error_code_catalogue.py`
- Create: `.github/scripts/check-error-code-catalogue.py`
- Create: `.github/scripts/error-code-catalogue-baseline.tsv`
- Create: `.github/scripts/error-code-duplicate-owners.tsv`

**Interfaces:**
- Produces: `CodeRecord(code: str, category: str, title: str, retryable: bool, module: str, enum: str, path: str)`.
- Produces: `scan(root: pathlib.Path) -> tuple[list[CodeRecord], int]`; the integer is the number of Kotlin source files examined.
- Produces: `load_baseline(path)` and `load_duplicate_owners(path)` for the checker and generator.

- [x] **Step 1: Write failing scanner/checker self-tests**

Create temporary trees containing this valid enum and require a zero exit:

```kotlin
enum class WidgetErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    WIDGET_NOT_FOUND(ErrorCategory.NOT_FOUND, "The widget does not exist"),
    ;
    override val code: String get() = name
}
```

Add cases asserting non-zero exits for a duplicate owner, a category change, a removed code, a malformed code, an unrecorded code, an unsupported non-`name` code mapping, and a stale duplicate owner. Include a clean control and assert `SUBJECTS` is printed.

- [x] **Step 2: Run the self-test and verify the missing checker fails**

Run: `python3 .github/scripts/check-error-code-catalogue.py --self-test`
Expected: failure because the checker does not exist.

- [x] **Step 3: Implement deterministic parsing and both baseline directions**

Mask nested Kotlin block comments and line comments while retaining string literals. Parse only production `openbank-*/**/src/main/**/*.kt`. For every enum whose supertype list contains `ErrorCode`, parse top-level enum entries, `ErrorCategory.<NAME>`, a literal title, and `retryableOverride = true|false`; default retryability from `RATE_LIMITED` and `UNAVAILABLE`. Require `override val code: String get() = name`; fail on an unevaluable expression rather than dropping the enum.

Use append-only TSV rows with this exact schema:

```text
CODE<TAB>CATEGORY<TAB>true|false<TAB>TITLE
```

Require every current code to match its baseline row and every baseline code to remain present. Group records by wire code and reject any current duplicate owner not listed as `CODE<TAB>MODULE<TAB>ENUM` in the legacy duplicate-owner file; reject stale duplicate-owner rows too.

- [x] **Step 4: Generate the current baselines and verify both modes**

Run: `python3 .github/scripts/check-error-code-catalogue.py --root . --write-baseline`
Expected: the existing kernel and domain codes are recorded, including the four existing `CURRENCY_NOT_ALLOWED` owners.

Run: `python3 .github/scripts/check-error-code-catalogue.py --root . && python3 .github/scripts/check-error-code-catalogue.py --self-test`
Expected: PASS, a non-empty current catalogue, and all positive/negative fixtures behave as asserted.

- [x] **Step 5: Commit the scanner**

```bash
git add .github/scripts/error_code_catalogue.py .github/scripts/check-error-code-catalogue.py .github/scripts/error-code-catalogue-baseline.tsv .github/scripts/error-code-duplicate-owners.tsv
git commit -s -S -m "ci(libs): enforce the fleet error-code catalogue"
```

### Task 2: Generated reference and shared OpenAPI component

**Files:**
- Create: `.github/scripts/gen-error-code-catalogue.py`
- Create: `docs/reference/error-codes.md`
- Create: `openbank-libs/governance/error-model.openapi.yaml`
- Modify: `.github/scripts/regen-derived.sh`

**Interfaces:**
- Consumes: `error_code_catalogue.scan`, `load_baseline`, and `load_duplicate_owners`.
- Produces: `render_markdown(records) -> str`, `render_openapi(records) -> str`, and CLI `--check` for drift detection.

- [x] **Step 1: Write generator self-tests**

Use two synthetic codes and assert that Markdown contains their code/category/title/retryability and sorted owners. Assert that the OpenAPI document contains `components.schemas.ProblemDetail`, a sorted `code.enum`, all RFC 9457/ApiError properties, and the optional legacy `error` string. Assert `--check` exits non-zero when an output differs.

- [x] **Step 2: Run the generator self-test before implementation**

Run: `python3 .github/scripts/gen-error-code-catalogue.py --self-test`
Expected: failure because the generator does not exist.

- [x] **Step 3: Implement deterministic output and register it**

Generate a header stating that the file is produced by `gen-error-code-catalogue.py`. The shared schema has `type: object`, `required: [type, title, status, detail, instance, code, correlationId, retryable, traceId, message, timestamp]`, and no required legacy `error`. Add this exact inventory entry and both output paths:

```bash
"error-code-catalogue|python3 .github/scripts/gen-error-code-catalogue.py|src/main .github/scripts/error-code-catalogue-baseline.tsv .github/scripts/error-code-duplicate-owners.tsv"
```

```bash
"docs/reference/error-codes.md"
"openbank-libs/governance/error-model.openapi.yaml"
```

- [x] **Step 4: Generate, validate and repeat**

Run: `python3 .github/scripts/gen-error-code-catalogue.py && python3 .github/scripts/gen-error-code-catalogue.py --check && bash .github/scripts/regen-derived.sh --selftest`
Expected: both generated files exist, the second generation changes nothing, and the inventory self-test passes.

- [x] **Step 5: Commit generated artifacts with their generator**

```bash
git add .github/scripts/gen-error-code-catalogue.py .github/scripts/regen-derived.sh docs/reference/error-codes.md openbank-libs/governance/error-model.openapi.yaml
git commit -s -S -m "docs(libs): generate the shared error catalogue contract"
```

### Task 3: Service-local mapper shrink-only ratchet and gate registration

**Files:**
- Create: `.github/scripts/check-service-local-exception-mappers.py`
- Create: `.github/scripts/service-local-exception-mappers-baseline.txt`
- Modify: `.github/gates/gates.yaml`

**Interfaces:**
- Produces: stable baseline keys `MODULE<TAB>REPO_RELATIVE_PATH<TAB>DECLARATION`, where declaration is a class/object name or `anonymous-N` in source order.
- Produces: CLI `--list`, `--write-baseline`, and `--self-test`.

- [x] **Step 1: Write failing ratchet fixtures**

Test named and anonymous declarations:

```kotlin
class LocalMapper : ExceptionMapper<WidgetException>
val anonymous = object : ExceptionMapper<OtherWidgetException> { }
```

Assert a new declaration fails, an exactly baselined declaration passes, removal of one baseline declaration passes, and a stale baseline entry fails when its owning declaration disappeared without an intentional baseline rewrite. Assert comments do not count, libs-runtime is excluded, and a PSD2 file is excluded only when its mapper body contains `tppMessages`.

- [x] **Step 2: Run the absent checker's self-test**

Run: `python3 .github/scripts/check-service-local-exception-mappers.py --self-test`
Expected: failure because the checker does not exist.

- [x] **Step 3: Implement the source scan and current baseline**

Scan production Kotlin sources outside `openbank-libs*`; strip nested comments before matching named and anonymous `ExceptionMapper<...>` declarations. Use file/path plus declaration identity, never line numbers. Report `SUBJECTS=<number of source files>` unconditionally. Generate the initial baseline with `--write-baseline`, inspect every exclusion, then require an exact current-vs-baseline match in normal mode. Removing a mapper therefore requires deleting its baseline row in the same commit; adding a mapper requires no mysterious count edit and fails until explicitly reviewed.

- [x] **Step 4: Register both enforced gates with complete metadata**

Add `error-code-catalogue` and `service-local-exceptionmapper-ratchet` to the `kotlin` group. Each gets `mode: enforced`, `rationale`, `review_after: 2027-05-28`, `budget_seconds: 30`, a source-floor `min_subjects`, `selftest`, `selftest_expect: pass`, and `selftest_inputs`. The catalogue run also executes `python3 .github/scripts/gen-error-code-catalogue.py --check`.

- [x] **Step 5: Run targeted and meta-gates**

Run:

```bash
python3 .github/scripts/run-gates.py --only error-code-catalogue
python3 .github/scripts/run-gates.py --only service-local-exceptionmapper-ratchet
python3 .github/scripts/run-gates.py --only gate-lifecycle-metadata
python3 .github/scripts/run-gates.py --only gate-subject-floor
python3 .github/scripts/run-gates.py --only gate-selftest-declaration
python3 .github/scripts/run-gates.py --only regen-derived-inventory
```

Expected: all six PASS; no `UNFALSIFIED` result.

- [x] **Step 6: Commit the ratchet and manifest**

```bash
git add .github/scripts/check-service-local-exception-mappers.py .github/scripts/service-local-exception-mappers-baseline.txt .github/gates/gates.yaml
git commit -s -S -m "ci(libs): ratchet service-local exception mappers"
```

### Task 4: Optional legacy error compatibility in the typed mapper

**Files:**
- Modify: `openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/error/DomainException.kt`
- Modify: `openbank-libs-domain/src/test/kotlin/com/openbank/libs/domain/error/DomainExceptionTest.kt`
- Modify: `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/api/error/ProblemDetail.kt`
- Modify: `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/api/error/DomainExceptionMapper.kt`
- Modify: `openbank-libs-runtime/src/test/kotlin/com/openbank/libs/api/error/DomainExceptionMapperTest.kt`

**Interfaces:**
- Produces: `DomainException.legacyError: String?`, default `null`, exposed by `NotFound` and `Conflict` as their final optional constructor argument.
- Produces: `ProblemDetail.error: String? = null`, omitted by Jackson when null.

- [x] **Step 1: Write failing Kotlin tests**

Add a domain assertion:

```kotlin
val legacy = NotFound("gone", legacyError = "gone")
assertThat(legacy.legacyError).isEqualTo("gone")
assertThat(NotFound("gone").legacyError).isNull()
```

Add runtime assertions that `mapper.toResponse(legacy).entity.error == "gone"`, ordinary `NotFound("gone")` has no serialized `error` member, and a legacy `Conflict` keeps status 409, its selected code, message, and the exact legacy value.

- [x] **Step 2: Run focused tests and verify compilation failure**

Run: `./gradlew :openbank-libs-domain:test :openbank-libs-runtime:test --tests '*DomainException*'`
Expected: FAIL because `legacyError` and `ProblemDetail.error` do not exist.

- [x] **Step 3: Add the narrow compatibility field**

Append `legacyError: String? = null` to the base constructor and the `NotFound`/`Conflict` public constructors, pass it through the base, add nullable `error` to `ProblemDetail`, and set `error = exception.legacyError` only in the successful `render` path. The misuse/INTERNAL_ERROR path must not copy it.

- [x] **Step 4: Run clean libs verification**

Run: `./gradlew :openbank-libs-domain:build :openbank-libs-runtime:build --rerun-tasks`
Expected: BUILD SUCCESSFUL, including tests, ktlint, detekt and Quarkus/CDI packaging where configured.

- [x] **Step 5: Commit the kernel compatibility path**

```bash
git add openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/error/DomainException.kt openbank-libs-domain/src/test/kotlin/com/openbank/libs/domain/error/DomainExceptionTest.kt openbank-libs-runtime/src/main/kotlin/com/openbank/libs/api/error/ProblemDetail.kt openbank-libs-runtime/src/main/kotlin/com/openbank/libs/api/error/DomainExceptionMapper.kt openbank-libs-runtime/src/test/kotlin/com/openbank/libs/api/error/DomainExceptionMapperTest.kt
git commit -s -S -m "feat(libs): preserve legacy error members in typed responses"
```

### Task 5: Product-catalog typed exceptions and mapper deletion

**Files:**
- Create: `openbank-product-catalog/src/main/kotlin/com/openbank/productcatalog/application/CatalogErrorCode.kt`
- Modify: `openbank-product-catalog/src/main/kotlin/com/openbank/productcatalog/application/ProductCatalogService.kt`
- Modify: `openbank-product-catalog/src/main/kotlin/com/openbank/productcatalog/infrastructure/rest/ProductCatalogExceptionMappers.kt`
- Modify: `openbank-product-catalog/src/test/kotlin/com/openbank/productcatalog/infrastructure/rest/ProductCatalogExceptionMapperEquivalenceTest.kt`
- Modify: `.github/scripts/error-code-catalogue-baseline.tsv`
- Modify: `.github/scripts/service-local-exception-mappers-baseline.txt`
- Regenerate: `docs/reference/error-codes.md` and `openbank-libs/governance/error-model.openapi.yaml`

**Interfaces:**
- Produces: `CatalogErrorCode.PRODUCT_NOT_FOUND` (`NOT_FOUND`) and `PRODUCT_CODE_ALREADY_EXISTS` (`CONFLICT`), both with client-safe stable titles.
- Consumes: `NotFound(..., legacyError = message)` and `Conflict(..., legacyError = message)`.

- [x] **Step 1: Write failing product equivalence tests**

For `ProductNotFoundException("missing product")`, assert status 404, `error == "missing product"`, `code == "PRODUCT_NOT_FOUND"`, `message == "missing product"`, and the RFC 9457 members. For `DuplicateProductCodeException("duplicate code")`, assert status 409, `error == "duplicate code"`, and `code == "PRODUCT_CODE_ALREADY_EXISTS"`. Assert no local mapper class remains for either exception.

- [x] **Step 2: Run the focused service test and verify failure**

Run: `./gradlew :openbank-product-catalog:test --tests '*ProductCatalogExceptionMapperEquivalenceTest'`
Expected: FAIL because the new code enum and typed behavior do not exist.

- [x] **Step 3: Migrate only the two compatible exceptions**

Make the two exception classes extend the typed bases, passing the same message as `clientMessage` and `legacyError`. Delete only `DuplicateProductCodeExceptionMapper` and `ProductNotFoundExceptionMapper` plus now-unused imports. Keep all four other mapper declarations byte-for-byte in behavior. Add the two catalogue rows and remove exactly the two mapper baseline rows; regenerate both derived catalogue artifacts.

- [x] **Step 4: Run service and ratchet verification**

Run:

```bash
./gradlew :openbank-product-catalog:test :openbank-product-catalog:quarkusBuild
python3 .github/scripts/check-error-code-catalogue.py --root .
python3 .github/scripts/check-service-local-exception-mappers.py --root .
python3 .github/scripts/gen-error-code-catalogue.py --check
```

Expected: service tests and packaging PASS; both new codes are accepted and the mapper baseline has shrunk by two.

- [x] **Step 5: Commit the service pilot**

```bash
git add openbank-product-catalog .github/scripts/error-code-catalogue-baseline.tsv .github/scripts/service-local-exception-mappers-baseline.txt docs/reference/error-codes.md openbank-libs/governance/error-model.openapi.yaml
git commit -s -S -m "feat(product-catalog): migrate legacy product errors to the kernel"
```

### Task 6: Product OpenAPI contract and final verification

**Files:**
- Modify: `openbank-product-catalog/src/main/resources/openapi.yaml`
- Modify: product-catalog contract tests only if the existing suite does not already replay the affected responses
- Modify: `docs/superpowers/plans/2026-11-28-kernel-error-model-phase-b.md` checkboxes as tasks complete

- [x] **Step 1: Describe the additive response members**

Extend `LegacyError` with the optional typed members actually served by the two migrated responses (`type`, `title`, `status`, `detail`, `instance`, `code`, `correlationId`, `retryable`, `traceId`, `message`, `timestamp`; nullable/absent `violations` and `details`). Keep `error` required and keep every existing status and media type unchanged. Bump `info.version` only after the diff classifier runs; an additive result requires the next MINOR, not a guessed patch.

- [x] **Step 2: Run the API classifier and replay contracts**

Run: `python3 .github/scripts/check-api-contract.py --base "$(git merge-base HEAD origin/main)" --enforce`
Expected: PASS with the observed classification and required version. Run `./gradlew :openbank-product-catalog:test --tests '*Pact*'` and inspect JUnit XML for failures and skipped tests; console Pact text alone is not the verdict.

- [x] **Step 3: Run clean service and lint verification**

Run:

```bash
./gradlew :openbank-product-catalog:build --rerun-tasks
./gradlew :openbank-product-catalog:quarkusBuild :openbank-product-catalog:ktlintCheck :openbank-product-catalog:detekt
bash .github/scripts/check-duplicate-yaml-keys.sh
```

Expected: BUILD SUCCESSFUL with tests actually executed, no new lint findings, and no duplicate-key finding in changed YAML.

- [x] **Step 4: Run the full gate manifest with PR context**

After opening the draft PR, use its real number/body and current merge base:

```bash
PR_DIFF_BASE="$(git merge-base HEAD origin/main)" BASE_REF="$(git merge-base HEAD origin/main)" \
GITHUB_REPOSITORY=JiRaska/open-bank-oss PR_NUMBER=<actual-number> PR_BODY="$(gh pr view <actual-number> --json body -q .body)" \
python3 .github/scripts/run-gates.py --all
```

Expected: no newly introduced FAIL. Any environment-specific unresolved input is named explicitly rather than counted as a pass.

- [x] **Step 5: Commit the contract and publish the stacked PRs**

```bash
git add openbank-product-catalog/src/main/resources/openapi.yaml docs/superpowers/plans/2026-11-28-kernel-error-model-phase-b.md
git commit -s -S -m "feat(product-catalog): document typed legacy error responses"
```

Push with `git push -u origin <branch>`, write each PR body to a file, pass `--body-file`, include `Refs #11610`, and verify the published body, branch signatures and actual PR URLs. Do not enable auto-merge or claim the whole ADR is complete.
