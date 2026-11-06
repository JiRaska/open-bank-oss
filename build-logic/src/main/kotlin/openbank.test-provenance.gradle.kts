// SPDX-License-Identifier: Apache-2.0
import com.openbank.buildlogic.buildFactsInputs

// Keep the actual execution classpath intact. Only source identity is redundant
// for cached tests; version, API version, prose and malformed metadata are not.
normalization.runtimeClasspath {
    ignore("docs/00-build.md")
    ignore("openbank-service-build.properties")
}
// Contract, native and other custom Test tasks retain the complete metadata.
// Broker publication keeps its existing always-run/non-cacheable policy as well.
tasks.withType<Test>().configureEach {
    inputs.property("buildFactsTestInputs", providers.provider {
        val brokerConfigured = systemProperties.keys.any {
            it in setOf("pactbroker.url", "pact.provider.version", "pact.verifier.publishResults")
        }
        buildFactsInputs(classpath.files, normalizeCommit = name == "test" && !brokerConfigured)
    })
}
