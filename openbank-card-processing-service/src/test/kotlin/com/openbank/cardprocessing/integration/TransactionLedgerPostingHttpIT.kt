// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.integration

import com.openbank.cardprocessing.application.port.out.PostingOutcome
import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.infrastructure.client.TransactionLedgerPostingAdapter
import com.openbank.cardprocessing.infrastructure.client.TransactionServiceClient
import com.openbank.cardprocessing.it.PostgresTestResource
import com.openbank.cardprocessing.it.StubTransactionServiceResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the ledger-posting adapter does with each REAL answer transaction-service can give to the
 * generated client (ADR-0319), over a real HTTP socket and the real Quarkus REST client + Jackson
 * mapper. The posting runs AFTER the clearing committed, so the outcome is a report, never a veto:
 * a 2xx must read POSTED whatever its body looks like (the debit is in the books), a rejection must
 * read FAILED (the gap has to stay visible).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(StubTransactionServiceResource::class, restrictToAnnotatedClass = true)
@TestProfile(TransactionLedgerPostingHttpIT.Profile::class)
class TransactionLedgerPostingHttpIT {

    class Profile : QuarkusTestProfile {
        // oidc-client off: without it every call dies fetching a token from a Keycloak that is not there,
        // and the FAILED assertions below would pass for that reason instead of the stub's answer.
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.oidc-client.enabled" to "false",
        )
    }

    @Inject
    @RestClient
    lateinit var client: TransactionServiceClient

    private val now = Instant.parse("2026-09-05T12:00:00Z")

    private fun adapter() =
        TransactionLedgerPostingAdapter(client, Clock.fixed(now, ZoneOffset.UTC), postingEnabled = true)

    /** The adapter sends `merchantName` as the transfer description, which is what the stub keys on. */
    private fun authorization(merchantName: String) = CardAuthorization(
        id = UUID.randomUUID(),
        cardId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        amountMinorUnits = 12_345,
        currencyCode = "CZK",
        channel = PresentmentChannel.ONLINE,
        mcc = "5411",
        merchantName = merchantName,
        merchantCountry = "CZ",
        status = AuthorizationStatus.APPROVED,
        category = "GROCERIES",
        declineReason = null,
        clearedAmountMinorUnits = 0,
        networkReference = "acq-1",
        authorizedAt = now,
        expiresAt = now.plusSeconds(3600),
        updatedAt = now,
    )

    @Test
    fun `a 2xx body with an unknown field and every optional field missing is POSTED`(): Unit = runBlocking {
        val result = adapter().postClearedSpend(authorization("stub-extra"), 12_345, "clr-1")

        assertThat(result.outcome).isEqualTo(PostingOutcome.POSTED)
        assertThat(result.transactionId).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000f1"))
        assertThat(result.detail).isEqualTo("COMPLETED")
    }

    @Test
    fun `a 2xx body that cannot bind is POSTED - the 2xx status proves the booking, so it is not UNKNOWN`(): Unit =
        runBlocking {
            val result = adapter().postClearedSpend(authorization("stub-unbindable"), 12_345, "clr-2")

            assertThat(result.outcome).isEqualTo(PostingOutcome.POSTED)
            assertThat(result.transactionId).isNull()
        }

    @Test
    fun `a real 422 is FAILED so the gap stays visible`(): Unit = runBlocking {
        val result = adapter().postClearedSpend(authorization("stub-422"), 12_345, "clr-3")

        assertThat(result.outcome).isEqualTo(PostingOutcome.FAILED)
        assertThat(result.detail).describedAs("the stub's status, not an auth/connect error").contains("422")
    }

    @Test
    fun `a real 503 is FAILED, never an exception into the committed clearing`(): Unit = runBlocking {
        val result = adapter().postClearedSpend(authorization("stub-503"), 12_345, "clr-4")

        assertThat(result.outcome).isEqualTo(PostingOutcome.FAILED)
        assertThat(result.detail).describedAs("the stub's status, not an auth/connect error").contains("503")
    }

    @Test
    fun `403 and 409 are FAILED, never POSTED`(): Unit = runBlocking {
        listOf("stub-403" to "403", "stub-409" to "409").forEach { (marker, status) ->
            val result = adapter().postClearedSpend(authorization(marker), 12_345, "clr-$marker")

            assertThat(result.outcome).describedAs(marker).isEqualTo(PostingOutcome.FAILED)
            assertThat(result.detail).describedAs(marker).contains(status)
        }
    }

    @Test
    fun `a connection cut with no status is never POSTED and never thrown`(): Unit = runBlocking {
        val result = adapter().postClearedSpend(authorization("stub-drop"), 12_345, "clr-drop")

        assertThat(result.outcome).isNotEqualTo(PostingOutcome.POSTED)
    }
}
