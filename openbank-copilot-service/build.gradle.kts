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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)

    // Customer session is a Keycloak JWT arriving via the customer edge (ADR-0065).
    implementation(libs.quarkus.oidc)
    // Downstream tool calls run as the scoped customer via on-behalf-of token exchange
    // (ADR-0089 D5). REST clients reuse the new reactive client's OIDC filter.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)

    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.quarkus.redis.client)
    // copilot conversation memory T1 (#3710): durable conversation history (Postgres, #3710).
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    // PARTY_ERASED consumer — GDPR Art. 17 erasure of conversation history (#3870).
    implementation(libs.quarkus.smallrye.kafka)
    // Retention sweep that hard-deletes past-expires_at conversations (#3870).
    implementation(libs.quarkus.scheduler)

    // Shared runtime plumbing: model gateway seam (ADR-0031), OPA authz (ADR-0034),
    // feature flags (ADR-0067), AI-attributed audit (ADR-0031 D5).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    // The custom pgvector lifecycle resources emit the same secret-free runtime evidence as the
    // shared Testcontainers kit; this remains strictly on the test classpath.
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.rest.assured.kotlin)
    // Consumer-driven contract with customer-edge (ADR-0063, issue #2322).
    testImplementation(libs.pact.consumer)
}

// Pact: write generated pact files to the shared pacts/ dir at the repo root (git-pact, ADR-0063).
// NOTE: must be set on the test JVM fork, not the Gradle daemon (System.setProperty would not propagate).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    // Ratchet floor (ADR-0020, sweep #466): measured 46.8% (597/1275) LINE at introduction,
                    // ~5 pt headroom, raise-only from here.
                    minValue = 41
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
