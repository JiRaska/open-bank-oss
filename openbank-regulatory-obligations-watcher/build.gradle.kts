// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.scheduler)
    // Persistence: reactive Panache + Postgres + Flyway, the fleet standard (same as docs-truth-agent).
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.jdbc.postgresql) // Flyway runs migrations over JDBC
    implementation(libs.quarkus.flyway)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // The register is parsed, not grepped. Version comes from the Quarkus BOM.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // Apache-2.0 shared libs (ADR-0136): LlmGatewayPort (ADR-0174), SafeHttpClient (ADR-0320),
    // DomainMetrics workflow liveness (ADR-0237).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.testcontainers.postgresql)
}

// Package the obligations register and this agent's registered prompts onto the classpath. Both
// are DERIVED copies of openbank-libs/governance/ — never hand-edit the packaged files. The prompt
// is read byte-for-byte so the audited prompt_hash resolves to the registry file (ADR-0148).
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance")) {
        include("regulatory-obligations.yaml")
        into("governance-register")
    }
    from(rootProject.file("openbank-libs/governance/prompts/regulatory-obligations-watcher")) {
        include("*.md")
        into("governance-prompts/regulatory-obligations-watcher")
    }
}

kover {
    reports {
        filters {
            excludes {
                annotatedBy("io.quarkus.runtime.annotations.RegisterForReflection")
            }
        }
        verify {
            rule {
                bound {
                    // Introduction floor (ADR-0020), ratchet-only from here.
                    minValue = 60
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
