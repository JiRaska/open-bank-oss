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

    // Shared runtime plumbing: model gateway seam (ADR-0031), OPA authz (ADR-0034),
    // feature flags (ADR-0067), AI-attributed audit (ADR-0031 D5).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    testImplementation(libs.rest.assured.kotlin)
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 0
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

tasks.named("koverVerify") {
    enabled = true
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
