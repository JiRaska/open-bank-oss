// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.account

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.account.api.AccountsApi
import com.openbank.clients.account.model.AccountResponse
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import java.io.InputStream
import java.util.UUID

/**
 * The ONLY place that interprets an HTTP answer from account-service (ADR-0319, #13169).
 *
 * **Consumers must not call the generated [AccountsApi] directly.** It returns the raw JAX-RS [Response].
 * Wrap the consumer's `@RestClient` sub-interface: `AccountClient(client).getAccount(id)`.
 *
 * The rule, once, for the single-account reads:
 *
 *  - 2xx with a readable [AccountResponse] body -> [AccountLookup.Found];
 *  - 404 -> [AccountLookup.NotFound]: account-service holds no such account. That is an answer, not a failure;
 *  - every other status (3xx, 401, 403, 5xx) is thrown as ONE type, [AccountHttpException], whether the client
 *    handed back a [Response] or threw a [WebApplicationException] (that one is its `cause`);
 *  - a 2xx whose body is missing or cannot be read is thrown as [AccountBodyException];
 *  - every failure that carries no status (refused or reset connection, timeout) is thrown unchanged.
 *
 * What a consumer then does with a throw (swallow to `null`, retry, open a breaker) stays that consumer's own
 * decision and lives in the consumer, next to its `@Retry` / `@Timeout` annotations.
 *
 * Every [Response] that reaches this class is closed on every path, including the ones that throw.
 *
 * **Versioning.** The artifact version is the provider's API-contract version (ADR-0319 D2, ADR-0048); this
 * module has no `version.txt`, and consumers are `project()` dependencies inside the monorepo.
 */
class AccountClient(private val api: AccountsApi) {

    /** `GET /api/v1/accounts/{accountId}`. */
    fun getAccount(accountId: UUID): Uni<AccountLookup> = classify(api.getAccount(accountId))

    /** `GET /api/v1/accounts/iban/{iban}`. */
    fun getAccountByIban(iban: String): Uni<AccountLookup> = classify(api.getAccountByIban(iban))

    internal companion object {
        private const val HTTP_OK = 200
        private const val HTTP_MULTIPLE_CHOICES = 300
        private const val HTTP_NOT_FOUND = 404

        private val log = System.getLogger(AccountClient::class.java.name)

        fun classify(call: Uni<Response>): Uni<AccountLookup> = call
            .onItem().transform { response -> fromResponse(response) }
            .onFailure(WebApplicationException::class.java).recoverWithUni { failure ->
                // Uni (not Item): rethrowing from a recover-with-item function would wrap the original
                // exception in a CompositeException and hide its status from the caller.
                fromError(failure as WebApplicationException)
            }

        @Suppress("TooGenericExceptionCaught")
        private fun closeQuietly(response: Response?) {
            try {
                response?.close()
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "closing an account-service Response failed", e)
            }
        }

        private fun fromResponse(response: Response): AccountLookup = try {
            when (val status = response.status) {
                in HTTP_OK until HTTP_MULTIPLE_CHOICES -> AccountLookup.Found(readAccount(response, status))
                HTTP_NOT_FOUND -> AccountLookup.NotFound
                else -> throw AccountHttpException(status)
            }
        } finally {
            closeQuietly(response)
        }

        private fun fromError(failure: WebApplicationException): Uni<AccountLookup> {
            val response: Response? = failure.response
            return try {
                when (val status = response?.status) {
                    HTTP_NOT_FOUND -> Uni.createFrom().item(AccountLookup.NotFound)
                    null -> Uni.createFrom().failure(failure)
                    // A 2xx can only arrive here if the client failed AFTER the status (body handling):
                    // the body was not read, so it is not proof of an account.
                    in HTTP_OK until HTTP_MULTIPLE_CHOICES ->
                        Uni.createFrom().failure(AccountBodyException(status, failure))
                    else -> Uni.createFrom().failure(AccountHttpException(status, failure))
                }
            } finally {
                closeQuietly(response)
            }
        }

        /**
         * This client's OWN mapper, deliberately not the consumer's global one. A status value account-service
         * adds before its spec (and so this generated enum) catches up binds as `null` instead of failing the
         * whole read: for VoP that keeps a real 2xx a `Found`, rather than a "failure" that trips retry and the
         * circuit breaker. Unknown properties are tolerated for the same reason.
         */
        internal val mapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        @Suppress("TooGenericExceptionCaught")
        private fun readAccount(response: Response, status: Int): AccountResponse {
            val account = try {
                if (response.hasEntity()) {
                    response.readEntity(InputStream::class.java).use {
                        mapper.readValue(it, AccountResponse::class.java)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                throw AccountBodyException(status, e)
            }
            return account ?: throw AccountBodyException(status, null)
        }
    }
}

/** The answer of a single-account read. */
sealed interface AccountLookup {
    /** account-service returned the account. */
    data class Found(val account: AccountResponse) : AccountLookup

    /** account-service answered 404: it holds no such account. */
    data object NotFound : AccountLookup
}

/**
 * account-service answered with a status that is neither a found account nor 404 (3xx, 401, 403, 5xx).
 * [cause] is the client's own `WebApplicationException` when there was one.
 */
class AccountHttpException(val status: Int, cause: Throwable? = null) :
    RuntimeException("account-service answered HTTP $status", cause)

/** account-service answered 2xx, but no readable account body came with it. */
class AccountBodyException(val status: Int, cause: Throwable? = null) :
    RuntimeException("account-service answered HTTP $status without a readable account body", cause)
