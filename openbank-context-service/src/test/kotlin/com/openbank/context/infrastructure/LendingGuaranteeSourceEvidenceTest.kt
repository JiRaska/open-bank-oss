// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

class LendingGuaranteeSourceEvidenceTest {
    private val client = mockk<LendingGuaranteeSourceClient>()
    private val source = LendingGuaranteeSourceEvidence(client, Optional.of("https://source.invalid:8443"))
    private val loanId = UUID.randomUUID()
    private val bearer = "Bearer investigator-token"

    @Test
    fun `source URL must use the mTLS listener before any bearer is forwarded`() {
        assertThat(isTrustedLendingSourceUrl("http://source.invalid:8126")).isFalse()
        assertThat(isTrustedLendingSourceUrl("https://source.invalid:443")).isFalse()
        assertThat(isTrustedLendingSourceUrl("https://user@source.invalid:8443")).isFalse()
        assertThat(isTrustedLendingSourceUrl("https://source.invalid:8443/path")).isFalse()
        assertThat(isTrustedLendingSourceUrl("https://source.invalid:8443")).isTrue()
        assertThatThrownBy {
            runBlocking { LendingGuaranteeSourceEvidence(client, Optional.empty()).read(loanId, bearer) }
        }.isInstanceOf(LendingGuaranteeSourceUnavailable::class.java)
        io.mockk.verify(exactly = 0) { client.approvedGuarantees(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `forwards exact investigator scope and accepts one loan evidence`(): Unit = runBlocking {
        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().item(history())

        assertThat(source.read(loanId, bearer).guarantees).hasSize(1)
    }

    @Test
    fun `rejects wrong loan missing source reference and oversized response`(): Unit = runBlocking {
        val valid = history()
        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().item(valid.copy(loanId = UUID.randomUUID()))
        assertUnavailable()

        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().item(valid.copy(guarantees = listOf(guarantee().copy(sourceDocumentId = null))))
        assertUnavailable()

        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().item(valid.copy(guarantees = List(101) { guarantee() }))
        assertUnavailable()
    }

    @Test
    fun `denial and outage release no evidence`(): Unit = runBlocking {
        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().failure(WebApplicationException(403))
        assertThatThrownBy { runBlocking { source.read(loanId, bearer) } }
            .isInstanceOf(LendingGuaranteeSourceDenied::class.java)

        every {
            client.approvedGuarantees(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW", 100)
        } returns Uni.createFrom().failure(IllegalStateException("offline"))
        assertUnavailable()
    }

    @Test
    fun `shared response must be bounded source evidence for distinct other loans`(): Unit = runBlocking {
        val related = UUID.randomUUID()
        val valid = LendingSharedGuarantorHistory(
            loanId,
            Instant.parse("2026-09-20T00:00:00Z"),
            Instant.parse("2026-09-20T00:00:00Z"),
            false,
            false,
            listOf(LendingRelatedLoanEvidence(related, listOf(guarantee()), false)),
        )
        every {
            client.sharedGuarantorCandidates(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW")
        } returns Uni.createFrom().item(valid)
        assertThat(source.readShared(loanId, bearer).relatedLoans).hasSize(1)

        listOf(
            valid.copy(rootLoanId = UUID.randomUUID()),
            valid.copy(relatedLoans = listOf(LendingRelatedLoanEvidence(loanId, listOf(guarantee()), false))),
            valid.copy(
                relatedLoans = List(5) {
                    LendingRelatedLoanEvidence(UUID.randomUUID(), listOf(guarantee()), false)
                },
            ),
            valid.copy(relatedLoans = listOf(LendingRelatedLoanEvidence(related, emptyList(), false))),
            valid.copy(relatedLoans = List(2) { LendingRelatedLoanEvidence(related, listOf(guarantee()), false) }),
        ).forEach { invalid ->
            every {
                client.sharedGuarantorCandidates(loanId, bearer, loanId.toString(), "LENDING_EXPOSURE_REVIEW")
            } returns Uni.createFrom().item(invalid)
            assertThatThrownBy { runBlocking { source.readShared(loanId, bearer) } }
                .isInstanceOf(LendingGuaranteeSourceUnavailable::class.java)
        }
    }

    @Test
    fun `shared read never sends bearer to an unconfigured source`() {
        assertThatThrownBy {
            runBlocking { LendingGuaranteeSourceEvidence(client, Optional.empty()).readShared(loanId, bearer) }
        }.isInstanceOf(LendingGuaranteeSourceUnavailable::class.java)
        io.mockk.verify(exactly = 0) { client.sharedGuarantorCandidates(any(), any(), any(), any()) }
    }

    private fun assertUnavailable() {
        assertThatThrownBy { runBlocking { source.read(loanId, bearer) } }
            .isInstanceOf(LendingGuaranteeSourceUnavailable::class.java)
    }

    private fun history() = LendingGuaranteeHistory(
        loanId,
        Instant.parse("2026-09-20T00:00:00Z"),
        Instant.parse("2026-09-20T00:00:00Z"),
        listOf(guarantee()),
        false,
    )

    private fun guarantee() = LendingGuaranteeEvidence(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        null,
        UUID.randomUUID(),
        BigDecimal("1000.00"),
        "EUR",
        BigDecimal("0.5"),
        1,
        Instant.parse("2026-09-01T00:00:00Z"),
        null,
        UUID.randomUUID(),
        "a".repeat(64),
        Instant.parse("2026-09-02T00:00:00Z"),
    )
}
