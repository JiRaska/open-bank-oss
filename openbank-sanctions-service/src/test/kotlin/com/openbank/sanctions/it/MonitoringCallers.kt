// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Callers of the monitoring operations as the service sees them in production: a verified token
 * of a client's OWN service account (`azp` bound to `service-account-<azp>`).
 *
 * `@TestSecurity` alone yields a principal that is not a JWT, which the owner check refuses, as
 * it must. These annotations add the token: the identity carries the client id as an attribute
 * and [ServiceAccountTokenAugmentor] turns it into the principal. The augmentor ignores every
 * identity without that attribute, so no other test in the module is affected by it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@TestSecurity(
    user = "service-account-" + MonitoringFixture.OWNER,
    roles = ["ROLE_API"],
    attributes = [SecurityAttribute(key = ServiceAccountTokenAugmentor.CLIENT, value = MonitoringFixture.OWNER)],
    augmentors = [ServiceAccountTokenAugmentor::class],
)
annotation class AsKycService

/** The fleet's shared client: a verified service-account token that identifies no single service. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@TestSecurity(
    user = "service-account-" + MonitoringFixture.SHARED_CLIENT,
    roles = ["ROLE_API"],
    attributes = [
        SecurityAttribute(key = ServiceAccountTokenAugmentor.CLIENT, value = MonitoringFixture.SHARED_CLIENT),
    ],
    augmentors = [ServiceAccountTokenAugmentor::class],
)
annotation class AsSharedFleetClient

@ApplicationScoped
class ServiceAccountTokenAugmentor : SecurityIdentityAugmentor {

    override fun augment(identity: SecurityIdentity, context: AuthenticationRequestContext): Uni<SecurityIdentity> {
        val clientId = identity.getAttribute<String>(CLIENT) ?: return Uni.createFrom().item(identity)
        return Uni.createFrom().item(
            QuarkusSecurityIdentity.builder(identity).setPrincipal(ServiceAccountToken(clientId)).build(),
        )
    }

    private class ServiceAccountToken(clientId: String) : JsonWebToken {
        private val claims = mapOf(
            "azp" to clientId,
            "preferred_username" to "service-account-$clientId",
            "sub" to "00000000-0000-4000-8000-000000000000",
        )

        override fun getName(): String = claims.getValue("preferred_username")

        override fun getClaimNames(): Set<String> = claims.keys

        @Suppress("UNCHECKED_CAST")
        override fun <T> getClaim(claimName: String): T? = claims[claimName] as? T
    }

    companion object {
        const val CLIENT = "monitoring-it.client-id"
    }
}
