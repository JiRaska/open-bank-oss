// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    // Service-to-service auth: acquire a client-credentials token (openbank-services)
    // and attach it as a Bearer on the outbound MCP tool calls (ADR-0031 / ADR-0034).
    // Catalog alias maps to quarkus-rest-client-oidc-filter (the new REST client's OIDC filter).
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.smallrye.kafka)
    // Cross-replica PoP nonce replay guard (issue #4728) — same shared TTL-cache mechanism
    // already deployed for ~30 services fleet-wide (RedisIdempotencyStore/RedisApprovalStore
    // in openbank-libs-runtime are the closest siblings). See RedisNonceStore.
    implementation(libs.quarkus.redis.client)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Proposals store: plain Agroal JDBC (sync MCP path) + Flyway. NOT Hibernate ORM —
    // openbank-libs ships reactive Panache entities that the ORM entity scanner can't index
    // on this service's (non-reactive) classpath. See AgentProposal / ProposalService.
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
    // Pact consumers pin balance-holds, sanctions and SEPA Instant read tools against their
    // providers (AgentBalanceHoldsPactConsumerTest, AgentSanctionsReadPactConsumerTest and
    // AgentSepaInstantReadPactConsumerTest, ADR-0063).
    // Pact consumers also cover account/transaction, FX, AML, Catalog and Ledger read tools.
    // They drive read-adapter dispatch through mock-server clients, not live OIDC.
    testImplementation(libs.pact.consumer)
    // First fleet adopter of the shared OTel trace-contract kit. It keeps the agent's existing
    // span test coupled to an evidence-safe assertion surface reusable by E2E/synthetic tests.
    testImplementation(project(":openbank-libs-testing"))
    // D3b SVID tests build an EC CA + leaf cert at runtime (no committed private keys → gitleaks-clean).
    // Declared directly (not via the shared catalog) so this one-service test dep does not trigger a
    // full-fleet rebuild on the shared libs.versions.toml.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // First integration test (ADR-0011 L2): isolated PostgreSQL per JVM (#578 pattern).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // Standalone V6 -> V7 migration test runs outside Quarkus' isolated Flyway classloader.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    // OpenAPI 3.1 uses JSON Schema 2020-12; prove null unions with the fleet's existing validator.
    testImplementation("com.networknt:json-schema-validator:1.5.9") {
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
}

// Package the live ADR-0148 registry prompts onto the classpath so the assistant and oversight
// agent load their system prompts from the registered files at runtime instead of inline constants.
// This copy is derived from openbank-libs/governance/prompts/ — never hand-edit it here.
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance/prompts/compliance-officer")) {
        include("oversight.v1.md")
        into("governance-prompts/compliance-officer")
    }
    from(rootProject.file("openbank-libs/governance/prompts/ui-assistant")) {
        include("system.v3.md", "catalog-review.v1.md")
        into("governance-prompts/ui-assistant")
    }
}

// Forked test JVM heap — issue #9919, observed here 2026-10-28. The same intermittent
// kover test.ic truncation that consent-service carries (#9919) now hits this module:
// two consecutive Services CI runs of PR #14059 failed koverVerify with
// `Failed to load coverage data ... java.io.EOFException` and coverage 60.08% / 86.24%
// against this floor of 87, on a change that touches no agent-service source. The
// incomplete report does not establish whether the JVM exited early, ran out of memory, or a
// writer remained active. The 2g override is a per-module mitigation already used by
// account-service / consent-service / kyc / domestic-payment. Not a fleet default —
// see account-service's comment for why.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

// DIAGNOSTIC, not a fix — issue #9919 (copied from consent-service, adapted for this
// module). koverVerify intermittently reads a TRUNCATED test.ic here
// (`Failed to load coverage data ... java.io.EOFException`), on ~10% of CI builds
// including main, never locally. The EOF warning is also present in GREEN runs, so
// truncation is routine and only sometimes costs enough to fail the floor. The
// mechanism is not established, and the two live candidates need opposite fixes:
//   (a) the test JVM exits before kover's shutdown hook finishes writing (the file is
//       already short when Gradle moves on, and nothing writes it afterwards), or
//   (b) something still holds and writes the file after `test` ends — a JVM that
//       inherited the agent — so koverCachedVerify reads a file that is still growing.
// Two readings help investigate them: size + mtime when `test` finishes, and again
// immediately before koverCachedVerify reads it, plus any live process still carrying
// the kover agent. Equal readings or a visible agent process are diagnostic clues, not proof
// of a single mechanism or an out-of-memory exit. Remove this block once a failing run has
// printed both lines and #9919 has its answer.
val koverTestIc = layout.buildDirectory.file("kover/bin-reports/test.ic")
fun describeKoverIc(moment: String, ic: File): String {
    // Epoch millis, not java.time: inside a build script `java` resolves to the
    // project's java extension, so `java.time.Instant` does not compile here.
    val state = if (ic.isFile) "size=${ic.length()} mtimeMs=${ic.lastModified()}" else "absent"
    // Only a JVM actually carrying the agent. A bare "kover" substring also matches
    // the Gradle and Kotlin daemons (the plugin is on their classpath), which would
    // report (b) on every run.
    val self = ProcessHandle.current().pid()
    val agentJvms = ProcessHandle.allProcesses()
        .filter { it.pid() != self }
        .filter {
            val cmd = it.info().commandLine().orElse("")
            cmd.contains("-javaagent:") && cmd.contains("kover-jvm-agent")
        }
        .map { it.pid() }
        .toList()
    return "[kover-ic #9919] $moment: test.ic $state; " +
        "live kover-agent JVMs=$agentJvms; nowMs=${System.currentTimeMillis()}"
}
tasks.named<Test>("test") {
    doLast { logger.lifecycle(describeKoverIc("after test", koverTestIc.get().asFile)) }
}
tasks.matching { it.name == "koverCachedVerify" }.configureEach {
    doFirst { logger.lifecycle(describeKoverIc("before koverCachedVerify", koverTestIc.get().asFile)) }
}

// Coverage floor (ADR-0020, ratchet-only — sweep #466: was a placebo minValue = 0
// gate that could never fail). Measured 75.8% LINE coverage (1344/1772) at
// introduction; re-measured 87.3% on 2026-10-14. Floor 87, raise-only from here.
kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 87
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
