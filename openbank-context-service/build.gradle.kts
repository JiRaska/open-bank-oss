// SPDX-License-Identifier: Apache-2.0
plugins { id("openbank.quarkus-service") }

dependencies {
    implementation(libs.quarkus.hibernate.reactive.panache)
    implementation(libs.quarkus.hibernate.reactive.panache.base)
    implementation(libs.quarkus.reactive.pg.client)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.rest.client.reactive)
    implementation(libs.quarkus.rest.client.reactive.jackson)
    implementation(libs.quarkus.smallrye.kafka)
    implementation(libs.quarkus.scheduler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(project(":openbank-libs-domain"))
    implementation(project(":openbank-libs-runtime"))
    testImplementation(libs.quarkus.test.security)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.pact.provider)
    testImplementation(libs.smallrye.reactive.messaging.inmemory)
    testImplementation(project(":openbank-libs-testing"))
}

kover { reports { verify { rule { bound { minValue = 60 } } } } }

// Context's Quarkus/PostgreSQL suite holds multiple application profiles in one test JVM.
// The default test heap exhausted on CI before the suite completed; keep the correction local.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
