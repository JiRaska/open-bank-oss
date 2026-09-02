// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.integration

import com.openbank.customeredge.infrastructure.rest.KeycloakAdminClient
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import com.openbank.customeredge.infrastructure.webauthn.WebAuthnKeycloakClient
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Settles the sibling question #5946 left open, and locks in the invariant the answer produced.
 *
 * Four `openbank-customer-edge` properties carry the same shape as the `@ConfigProperty` that
 * stopped `openbank-audit-service` booting (#5844): a plain, non-`Optional` `String` with
 * `defaultValue = ""`. #5946 guessed these were safer than the audit-service case because the
 * beans are `@ApplicationScoped` and therefore lazy, so a failure would land on first use.
 *
 * Both halves of that guess are wrong, measured 2026-08-21 with a throwaway
 * `@ConfigProperty(name = "openbank.test.issue5946.defined.nowhere", defaultValue = "")` on a plain
 * `String` in this module. Quarkus did not start at all:
 *
 * ```
 * DeploymentException: Failed to load config value of type class java.lang.String
 *   for: openbank.test.issue5946.defined.nowhere
 * Suppressed: SRCFG00014: The config property … is required but it could not be found
 *   in any config source
 *   at io.quarkus.arc.runtime.ConfigRecorder.validateConfigProperties(ConfigRecorder.java:70)
 * ```
 *
 * So an empty `defaultValue` is not a value — SmallRye reports the property as absent from every
 * source — and `ConfigRecorder` validates every injection point at STARTUP, which no bean scope
 * defers. The four below are therefore required properties, and customer-edge boots only because
 * every one of them is supplied: by `RedpandaRedisTestResource` here, and by ExternalSecrets-backed
 * env vars on the gitops workload. Drop any one of those and the service does not start.
 *
 * That is what this test pins. Each assertion below names the supplied value, so removing a
 * supplier turns the whole class red — as a boot failure, which is the point of #5946: a module
 * that cannot start reports its tests as SKIPPED, and a skip count reads like a pass.
 *
 * `check-configproperty-supplied.py` enforces the same rule statically across the fleet.
 *
 * It has to be a `@QuarkusTest`: the behaviour lives in Arc's config validation, and a unit test
 * that constructs the bean by hand supplies the very value the framework does not.
 */
@QuarkusTest
class EmptyDefaultConfigPropertyBootIT {

    @Inject
    lateinit var upstream: UpstreamClient

    @Inject
    lateinit var keycloakAdmin: KeycloakAdminClient

    @Inject
    lateinit var webAuthn: WebAuthnKeycloakClient

    @Inject
    lateinit var enrollmentTickets: EnrollmentTicketService

    @Test
    fun `the four required secrets are supplied, so the service boots and they inject non-empty`() {
        // Asserting the supplied VALUE is what makes this non-vacuous: an `isNotNull` or `isEmpty`
        // assertion would pass just as well against a bean whose @ConfigProperty was never applied.
        assertThat(upstream.clientSecret).isEqualTo("test-upstream-secret")
        assertThat(keycloakAdmin.adminClientSecret).isEqualTo("test-keycloak-admin-secret")
        assertThat(webAuthn.clientSecret).isEqualTo("test-webauthn-kc-client-secret")
        assertThat(enrollmentTickets.secret).isEqualTo("test-enrollment-ticket-secret")
    }
}
