// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    // agents.yaml is parsed, not grepped (ADR-0387 D1). Version comes from the Quarkus BOM.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // In-cluster PDF text extraction (ADR-0387 D3) — already in the catalog for document-service.
    implementation(libs.pdfbox)
    // Apache-2.0 shared libs (ADR-0136): SafeHttpClient/EgressPolicy (ADR-0320), the shared PDP
    // (ADR-0034), AuditEventPublisher and DomainMetrics workflow liveness (ADR-0237).
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.quarkus.test.security)
    // @OidcSecurity(claims=...) — drives the sub/aud checks without a real IdP.
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.pdfbox)
}

// ADR-0387 D1: package the agent charters so the broker re-checks each caller's
// data_scope.public_web.hosts in-process. Derived from openbank-libs/governance/agents.yaml —
// never hand-edit the packaged copy.
tasks.named<Copy>("processResources") {
    from(rootProject.file("openbank-libs/governance")) {
        include("agents.yaml")
        into("governance-charters")
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
