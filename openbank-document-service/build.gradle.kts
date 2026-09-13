// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

plugins {
    id("openbank.quarkus-service")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.resteasy.reactive)
    implementation(libs.quarkus.resteasy.reactive.jackson)
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.redis.client)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.scheduler)
    // SignerVerificationPort's ScaVerificationAdapter calls sca-service (ADR-0162 D4), with an
    // openbank-services M2M token minted by the oidc-client filter — same pattern as
    // openbank-standing-order-service's SepaPaymentClient.
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.oidc.client.reactive.filter)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    // ADR-0161: openbank-libs-runtime only compiles S3ObjectStore against the AWS SDK as
    // compileOnly — a consuming service opts in itself to get the real jar on its runtime
    // classpath. This service supports openbank.objectstore.backend=s3.
    implementation(libs.aws.sdk.s3)
    // ADR-0162 D2: logic-less Handlebars templating behind TemplateRenderPort.
    implementation(libs.handlebars)
    // ADR-0162 D4 phase 1: PAdES-B sealing behind SignatureSealPort.
    implementation(libs.pdfbox)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.assertj)
    testImplementation(libs.mockk)
    // ADR-0063: document-service is a pact PROVIDER. Three merged services render documents
    // through this service's template list + preview endpoints (ADR-0248 #3) — sepa-payment and
    // domestic-payment (payment confirmations) and statement-service — and until
    // DocumentPactProviderVerificationTest nothing replayed those contracts. The only cover was
    // each consumer's own WireMock/mock stub, written from the client and so unable to disagree
    // with it (the #2269 shape).
    testImplementation(libs.pact.provider)
    // AuditEventTime — the ONE copy of the rule openbank-audit-service's AuditConsumer applies to
    // a domain-event payload, so this service's producer tests assert against the real contract
    // rather than a per-service restatement of it (#3914).
    testImplementation(project(":openbank-libs-testing"))
    testImplementation(libs.rest.assured.kotlin)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// Pact: resolve the git-pact folder and forward broker config for provider verification
// (ADR-0063). pactbroker.* / pact.provider.* props are injected by CI with -D; on a pull request
// none of them are set, and DocumentPactProviderVerificationTest is @PactFolder-sourced, so it
// runs regardless — that is the point (issue #2338).
// Pact rootDir + Pact Broker property forwarding centralised into
// build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts's `tasks.withType<Test>().configureEach { }`
// (ADR-0250 Phase 2, issue #4414) — this module's copy was byte-identical in substance to the
// fleet-standard block, so nothing service-specific remains here.

kover {
    reports {
        verify {
            rule {
                bound {
                    // Introduction floor (ADR-0020): measured 21.1% LINE in CI at introduction (the
                    // scaffold's initial 30 was an unmeasured guess that failed the very first CI
                    // run) — set with a small margin below that, ratchet-only from here. The largest
                    // untested surfaces are the new crypto/render adapters (PdfBoxPadesSealAdapter,
                    // HttpPdfRenderAdapter, ScaVerificationAdapter); raising this floor via adapter
                    // tests is a tracked follow-up, not required to ship this increment.
                    minValue = 83
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}
