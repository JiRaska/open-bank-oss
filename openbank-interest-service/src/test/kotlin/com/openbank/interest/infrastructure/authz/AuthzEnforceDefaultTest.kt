// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.infrastructure.authz

import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `authz.enforce` must not depend on a deployment manifest remembering to set `AUTHZ_ENFORCE`
 * (issue #3679).
 *
 * interest-service is money-path (it posts real GL journals, #1478). While `application.yaml` read
 * `${AUTHZ_ENFORCE:false}`, enforcement was a property of ONE gitops manifest rather than of the
 * service: the deployed Rollout sets the variable to `"true"`, so the cluster was fine, but every
 * environment that does not set it — a new cluster, a local run, an ad-hoc container, a restored
 * namespace — silently ran a money-path service in ADVISORY mode, where `AuthorizeInterceptor`
 * evaluates every `@Authorize` decision, logs the denies and lets the request through anyway.
 * Nothing failed; the only symptom was a WARN line nobody was reading.
 *
 * WHY THIS TEST RESOLVES RATHER THAN GREPS. Asserting the literal string `${AUTHZ_ENFORCE:true}`
 * would pass in both worlds this test has to tell apart: the value that reaches the interceptor is
 * produced by SmallRye's expression expansion and profile merging, not by the characters in the
 * file, so a `%prod` override or a second `authz:` mapping key later in the document would change
 * the answer without changing the string. So the config is actually built and the value read back.
 *
 * The sources are deliberately ONLY the YAML file — no env, no system properties. That is what
 * makes the assertion mean "with `AUTHZ_ENFORCE` unset", and it keeps the test deterministic on a
 * workstation that happens to export the variable.
 */
class AuthzEnforceDefaultTest {

    private fun resolveAuthzEnforce(profile: String): Boolean = SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(YamlConfigSource(applicationYaml(), YAML_ORDINAL))
        .withProfile(profile)
        .build()
        .getValue("authz.enforce", Boolean::class.java)

    private fun applicationYaml() = requireNotNull(javaClass.classLoader.getResource("application.yaml")) {
        "application.yaml is not on the test classpath"
    }

    @Test
    fun `enforces authorization by default when AUTHZ_ENFORCE is unset`() {
        assertThat(resolveAuthzEnforce("prod"))
            .describedAs(
                "authz.enforce must resolve to true with AUTHZ_ENFORCE unset — otherwise any " +
                    "environment that forgets the variable runs this money-path service in advisory mode",
            )
            .isTrue()
    }

    /**
     * The one deliberate exception, asserted so it stays deliberate: no OPA sidecar exists in the
     * test JVM, and with `authz.enforce=true` a `@Authorize` method whose PDP is unreachable raises
     * `PolicyDecisionException` -> HTTP 503 (the interceptor fails CLOSED, by design). The
     * integration tests that drive authorized endpoints — `InterestMissingParamStatusIT` — would
     * turn into 503s. Same shape aml-service and pid-service already carry.
     */
    @Test
    fun `runs advisory under the test profile because no OPA sidecar exists there`() {
        assertThat(resolveAuthzEnforce("test"))
            .describedAs("the %test profile must keep advisory mode; the exception is scoped and explicit")
            .isFalse()
    }

    private companion object {
        const val YAML_ORDINAL = 100
    }
}
