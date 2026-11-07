// SPDX-License-Identifier: Apache-2.0
import com.openbank.buildlogic.buildFactsInputs
import com.openbank.buildlogic.pactBrokerSystemProperties

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
        val brokerConfigured = systemProperties.keys.any { it in pactBrokerSystemProperties }
        buildFactsInputs(classpath.files, normalizeCommit = name == "test" && !brokerConfigured)
    })
    // Execution stamp, deliberately NOT a task output: a FROM-CACHE/UP-TO-DATE `test` restores
    // results produced for an earlier identical-source commit and never runs this action, so the
    // stamp keeps naming the commit that really executed. collect-test-run-evidence.py compares
    // it to GITHUB_SHA and records run.testTaskOutcome = executed | reused.
    val stamp = layout.buildDirectory.file("test-task-outcome/$name.properties")
    val executingCommit = providers.environmentVariable("GITHUB_SHA").orElse("local")
    doFirst {
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("executedCommit=${executingCommit.get()}\n")
    }
}
