// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheme

import com.openbank.cardprocessing.infrastructure.scheme.MastercardBinLookupAdapter
import com.openbank.cardprocessing.infrastructure.scheme.MastercardOAuthSigner
import com.openbank.cardprocessing.infrastructure.scheme.MastercardSchemeClient
import com.openbank.cardprocessing.infrastructure.scheme.RoutedBinLookupPort
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedSchemeAdapter
import com.openbank.cardprocessing.infrastructure.scheme.VisaBinLookupAdapter
import com.openbank.cardprocessing.infrastructure.scheme.VisaBinResponse
import com.openbank.cardprocessing.infrastructure.scheme.VisaSchemeClient
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.FundingSource
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * How each vendor adapter classifies what it gets back, and how the router chooses one.
 *
 * The whole point of `SchemeFailure` having five values instead of a boolean is that a caller acts
 * on them differently. These tests hold each mapping to that: an unconfigured adapter is not a
 * broken one, a 401 will not fix itself by retrying, and a 404 is a real answer from a working
 * integration.
 */
class SchemeAdapterFailureTest {

    private val visaClient = mockk<VisaSchemeClient>()
    private val mastercardClient = mockk<MastercardSchemeClient>()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)

    private fun visa(apiKey: String = "a-key") = VisaBinLookupAdapter(visaClient, apiKey)

    private fun noSigner(): Instance<MastercardOAuthSigner> = mockk {
        every { isResolvable } returns false
    }

    private fun signer(): Instance<MastercardOAuthSigner> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val real = MastercardOAuthSigner("consumer", keyPair.private, clock)
        return mockk {
            every { isResolvable } returns true
            every { get() } returns real
        }
    }

    @Test
    fun `an unconfigured Visa adapter is NOT_BOUND and calls nothing`(): Unit = runBlocking {
        val result = visa(apiKey = "").lookup("411111") as SchemeResult.Unanswered

        // NOT_BOUND is permanent until someone configures a credential; UNAVAILABLE clears on its
        // own. A caller that cannot tell them apart retries the first for ever.
        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        assertThat(result.scheme).isEqualTo(CardScheme.VISA)
    }

    @Test
    fun `a Visa 401 is UNAUTHENTICATED, not UNAVAILABLE`(): Unit = runBlocking {
        coEvery { visaClient.binAttributes(any(), any()) } throws
            WebApplicationException(Response.status(401).build())

        val result = visa().lookup("411111") as SchemeResult.Unanswered

        assertThat(result.failure).isEqualTo(SchemeFailure.UNAUTHENTICATED)
    }

    @Test
    fun `a Visa 404 is NOT_FOUND — a real answer from a working integration`(): Unit = runBlocking {
        coEvery { visaClient.binAttributes(any(), any()) } throws
            WebApplicationException(Response.status(404).build())

        assertThat((visa().lookup("411111") as SchemeResult.Unanswered).failure)
            .isEqualTo(SchemeFailure.NOT_FOUND)
    }

    @Test
    fun `a Visa response with no brand is MALFORMED rather than a placeholder brand`(): Unit = runBlocking {
        coEvery { visaClient.binAttributes(any(), any()) } returns VisaBinResponse(binNumber = "411111")

        val result = visa().lookup("411111") as SchemeResult.Unanswered

        // Substituting "UNKNOWN" would publish a card brand Visa never sent, into a row somebody
        // later reads as fact.
        assertThat(result.failure).isEqualTo(SchemeFailure.MALFORMED)
    }

    @Test
    fun `an unrecognised funding source is UNKNOWN, which the enum has a value for`(): Unit = runBlocking {
        coEvery { visaClient.binAttributes(any(), any()) } returns
            VisaBinResponse(binNumber = "411111", cardBrand = "VISA", fundingSource = "something-new")

        val result = visa().lookup("411111") as SchemeResult.Answered

        assertThat(result.value.fundingSource).isEqualTo(FundingSource.UNKNOWN)
        assertThat(result.scheme).isEqualTo(CardScheme.VISA)
    }

    @Test
    fun `Mastercard without a signing key is NOT_BOUND`(): Unit = runBlocking {
        val adapter = MastercardBinLookupAdapter(mastercardClient, noSigner(), "https://api.example.com")

        val result = adapter.lookup("555555") as SchemeResult.Unanswered

        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        assertThat(result.scheme).isEqualTo(CardScheme.MASTERCARD)
    }

    @Test
    fun `Mastercard with a signer but no base URL is also NOT_BOUND`(): Unit = runBlocking {
        val adapter = MastercardBinLookupAdapter(mastercardClient, signer(), "")

        assertThat((adapter.lookup("555555") as SchemeResult.Unanswered).failure)
            .isEqualTo(SchemeFailure.NOT_BOUND)
    }

    @Test
    fun `the router sends the call to the configured binding`(): Unit = runBlocking {
        val simulator = SimulatedSchemeAdapter()
        val router = RoutedBinLookupPort(simulator, visa(apiKey = ""), unboundMastercard(), "simulator")

        val result = router.lookup("411111") as SchemeResult.Answered

        assertThat(result.scheme).isEqualTo(CardScheme.SIMULATOR)
    }

    @Test
    fun `a configured binding that cannot answer does NOT fall back to the simulator`(): Unit = runBlocking {
        val router = RoutedBinLookupPort(SimulatedSchemeAdapter(), visa(apiKey = ""), unboundMastercard(), "visa")

        val result = router.lookup("411111") as SchemeResult.Unanswered

        // The property the whole routing design rests on: a fallback would make an unconfigured
        // vendor integration indistinguishable from a working one at every call site.
        assertThat(result.scheme).isEqualTo(CardScheme.VISA)
        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
    }

    @Test
    fun `an unknown binding name names itself in the failure`(): Unit = runBlocking {
        val router = RoutedBinLookupPort(SimulatedSchemeAdapter(), visa(), unboundMastercard(), "amex")

        val result = router.lookup("411111") as SchemeResult.Unanswered

        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        // A deployment mistake, so the message has to say what to fix.
        assertThat(result.detail).contains("amex")
    }

    private fun unboundMastercard() = MastercardBinLookupAdapter(mastercardClient, noSigner(), "")
}
