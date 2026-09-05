// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.delegation.infrastructure.authz

import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `authz.enforce` must not depend on a deployment manifest remembering to set `AUTHZ_ENFORCE`
 * (issue #3679).
 *
 * delegation-service is money-path and mints capabilities to spend other people's money. While
 * `application.yaml` read `${AUTHZ_ENFORCE:false}`, enforcement was a property of ONE gitops
 * manifest rather than of the service: the deployed Rollout sets the variable to `"true"`, so the
 * cluster was fine, but every environment that does not set it — a new cluster, a local run, an
 * ad-hoc container, a restored namespace — silently ran in ADVISORY mode, where
 * `AuthorizeInterceptor` evaluates every `@Authorize` decision, logs the denies and lets the
 * request through anyway. Nothing failed; `AuthzModeAnnouncer` logged one WARN nobody was reading.
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

    private fun resolve(property: String, profile: String): Boolean = SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(YamlConfigSource(applicationYaml(), YAML_ORDINAL))
        .withProfile(profile)
        .build()
        .getValue(property, Boolean::class.java)

    private fun applicationYaml() = requireNotNull(javaClass.classLoader.getResource("application.yaml")) {
        "application.yaml is not on the test classpath"
    }

    @Test
    fun `enforces authorization by default when AUTHZ_ENFORCE is unset`() {
        assertThat(resolve("authz.enforce", "prod"))
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
     * integration tests that drive authorized endpoints — `SpendReservationConcurrencyIT` and
     * `SpendReservationStateOutboxIT`, which POST the reservation trio — would turn into 503s.
     * Same shape aml-service and pid-service already carry.
     */
    @Test
    fun `runs advisory under the test profile because no OPA sidecar exists there`() {
        assertThat(resolve("authz.enforce", "test"))
            .describedAs("the %test profile must keep advisory mode; the exception is scoped and explicit")
            .isFalse()
    }

    /**
     * Four-eyes stays OFF, and that is deliberate rather than an oversight this change forgot.
     *
     * `delegation.revoke` and `delegation.reserve.release` do evaluate `four_eyes_required=true`
     * against the deployed bundle (both end in a verb listed in `rules.yaml: four_eyes.verbs`, and
     * delegation-service is in `money_path_services`). But this service wires no `ApprovalStore`
     * bean, and `AuthorizeInterceptor.requireFourEyesOrProceed` treats that case as
     * "no_approval_store": it logs an error and PROCEEDS — ADR-0155 D3 deliberately keeps it a
     * no-op rather than failing closed. So flipping `AUTHZ_FOUR_EYES_ENFORCE` here would gate
     * nothing while looking like it did, which is worse than leaving it visibly off. It is a
     * separate piece of work (wire the store, then flip), not a rider on this change.
     */
    @Test
    fun `leaves four-eyes enforcement off until an ApprovalStore is wired`() {
        assertThat(resolve("authz.four-eyes.enforce", "prod"))
            .describedAs("four-eyes must stay off while no ApprovalStore bean exists to gate on")
            .isFalse()
    }

    private companion object {
        const val YAML_ORDINAL = 100
    }
}
