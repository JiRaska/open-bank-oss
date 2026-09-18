// SPDX-License-Identifier: Apache-2.0
package com.openbank.document.infrastructure.rest

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.ForbiddenException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.Principal
import java.time.Instant
import java.util.UUID

class LendingGuaranteeEvidenceResourceTest {
    private val loan = UUID.randomUUID()
    private val guarantor = UUID.randomUUID()
    private val documentId = UUID.randomUUID()
    private val digest = "a".repeat(64)

    @Test
    fun `only a signed sealed document for the exact case party and bank matches`(): Unit = runBlocking {
        val request = request()
        val document = document()
        assertThat(document.matches(request)).isTrue()
        assertThat(document.copy(status = DocumentStatus.PENDING_SIGNATURE).matches(request)).isFalse()
        assertThat(document.copy(sealedSha256 = null).matches(request)).isFalse()
        assertThat(document.copy(caseRef = UUID.randomUUID().toString()).matches(request)).isFalse()
        assertThat(document.copy(partyRef = UUID.randomUUID().toString()).matches(request)).isFalse()
        assertThat(document.copy(bankScope = null).matches(request)).isFalse()
        assertThat(document.matches(request.copy(sealedSha256 = "b".repeat(64)))).isFalse()

        val query = mockk<DocumentQueryUseCase>()
        coEvery { query.getMetadata(documentId) } returns document
        assertThat(resource(query, "service-account-openbank-lending-graph").verify(request).matches).isTrue()
        coEvery { query.getMetadata(documentId) } returns null
        assertThat(resource(query, "service-account-openbank-lending-graph").verify(request).matches).isFalse()
    }

    @Test
    fun `shared backend identity is refused before reading a document`(): Unit = runBlocking {
        val query = mockk<DocumentQueryUseCase>()
        assertThatThrownBy {
            runBlocking { resource(query, "service-account-openbank-services").verify(request()) }
        }.isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { query.getMetadata(any()) }
    }

    @Test
    fun `cross deployment bank scope is refused before reading a document`(): Unit = runBlocking {
        val query = mockk<DocumentQueryUseCase>()
        assertThatThrownBy {
            runBlocking {
                resource(query, "service-account-openbank-lending-graph")
                    .verify(request().copy(bankScope = "another-bank"))
            }
        }.isInstanceOf(ForbiddenException::class.java)
        coVerify(exactly = 0) { query.getMetadata(any()) }
    }

    private fun resource(query: DocumentQueryUseCase, principalName: String): LendingGuaranteeEvidenceResource {
        val identity = mockk<SecurityIdentity>()
        val principal = mockk<Principal>()
        every { principal.name } returns principalName
        every { identity.principal } returns principal
        return LendingGuaranteeEvidenceResource(query, identity, "test-bank-a")
    }

    private fun request() = LendingGuaranteeEvidenceRequest(documentId, loan, guarantor, "test-bank-a", digest)

    private fun document() = Document(
        id = documentId,
        templateCode = "GUARANTEE",
        templateVersion = "1",
        sha256 = "b".repeat(64),
        storageKey = "documents/$documentId",
        contentType = "application/pdf",
        sizeBytes = 100,
        status = DocumentStatus.SIGNED,
        metadata = mapOf("bankScope" to "test-bank-a"),
        partyRef = guarantor.toString(),
        caseRef = loan.toString(),
        productRef = null,
        retainUntil = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        sealedSha256 = digest,
        bankScope = "test-bank-a",
    )
}
