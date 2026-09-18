// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CancellationException

/** The dedicated Context service identity; the investigator bearer remains a separate credential. */
@ApplicationScoped
class FraudCaseServiceToken(private val oidcClient: Instance<OidcClient>) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun bearer(): String = try {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        require(!token.isNullOrBlank()) { "empty Context service token" }
        "Bearer $token"
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw FraudCaseSourceUnavailable(exception)
    }
}
