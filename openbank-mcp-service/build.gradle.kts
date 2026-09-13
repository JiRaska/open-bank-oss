// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    // M2M client-credentials token attached to the downstream account/balance/transaction/consent
    // RestClient calls (ADR-0195 step 2) — same pattern as openbank-agent-service's ServiceClients.
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    // ADR-0224 D2: the agent-session store — this service's first persistence (governance.yaml's
    // ownsNoDatabase note anticipated exactly this step). The reactive pg client is what creates
    // the pool hibernate-reactive boots from — jdbc.postgresql alone is only the Flyway/driver leg.
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // Apache-2.0 shared libs — copyleft may consume permissive (ADR-0136). The shared ADR-0034 PDP
    // (PolicyDecisionPoint / OpaSidecarPolicyDecisionPoint) lives here.
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.rest.assured.kotlin)
    // Consumer-driven contract tests (ADR-0063, issue #2255 dimension C3). mcp's read ports became
    // real HTTP clients in #2262 and are not wired as the default yet — pinning the routes now is
    // what keeps the ADR-0195 cutover from shipping a call to a path that does not exist (#2269).
    testImplementation(libs.pact.consumer)
    // @TestSecurity + @OidcSecurity(claims=...) — simulates a validated agent OAuth token
    // (sub, consent_id) for McpAuditEventIT (ADR-0195 step 4), no real IdP round-trip.
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.quarkus.test.security.oidc)
    // Session-store integration tests against a real Postgres (ADR-0224 D2).
    testImplementation(libs.testcontainers.postgresql)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020). Raise-only. 55 at introduction; the audit-event
                    // tests (#2207) took the measured value to 82.1% LINE, so the floor moves to 75
                    // (~7pt headroom) rather than leaving a 27pt gap the ratchet cannot see through.
                    minValue = 75
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// Pact: write the generated consumer contracts into the repo-root pacts/ dir (git-pact, ADR-0063),
// where the providers replay them. The consumer tests rewrite the files on every run and the result
// is committed; pact-drift-check.yml fails the build if a committed pact and a fresh regeneration
// diverge. NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would
// not propagate). The pactbroker.* keys are forwarded so CI's publish step behaves as elsewhere.
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.
