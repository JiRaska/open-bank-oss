// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.integration

import io.quarkus.test.Mock
import jakarta.enterprise.context.RequestScoped
import org.eclipse.microprofile.jwt.JsonWebToken

@Mock
@RequestScoped
class TestJsonWebToken : JsonWebToken {
    override fun getName(): String = actor

    override fun getRawToken(): String = "test-token"

    override fun getClaimNames(): Set<String> = setOf("sub")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getClaim(claimName: String): T? = when (claimName) {
        "sub" -> actor as T
        else -> null
    }

    companion object {
        var actor: String = "maker@openbank.test"
    }
}
