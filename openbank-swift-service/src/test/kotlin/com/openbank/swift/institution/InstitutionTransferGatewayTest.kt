// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeStatus
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.infrastructure.client.ClearingSimulatorClient
import com.openbank.swift.infrastructure.client.SchemeGatewayAdapter
import com.openbank.swift.institution.InstitutionTransferFixtures.release
import com.openbank.swift.institution.InstitutionTransferFixtures.transfer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class InstitutionTransferGatewayTest {
    private val client = mockk<ClearingSimulatorClient>()
    private val adapter = SchemeGatewayAdapter(client).also { it.self = it }
    private val validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)

    private fun pacs002(status: PaymentStatus, reason: String? = null) = Pacs002Builder().build(
        PaymentStatusReport("SIM-STS-1", OffsetDateTime.now(ZoneOffset.UTC), "E2E", null, status, reason, null),
    )

    @Test
    fun `routes by rail, submits an XSD-valid pacs_009 and maps every TxSts`(): Unit = runBlocking {
        val expected = mapOf(
            PaymentStatus.ACSC to InstitutionTransferVerdict.SETTLED,
            PaymentStatus.ACSP to InstitutionTransferVerdict.IN_PROCESS,
            PaymentStatus.RCVD to InstitutionTransferVerdict.IN_PROCESS,
            PaymentStatus.RJCT to InstitutionTransferVerdict.REJECTED,
        )
        for (rail in listOf("CERTIS", "TARGET2", "SWIFT")) {
            for ((sts, verdict) in expected) {
                val body = slot<String>()
                every { client.submitInstitutionCreditTransfer(rail.lowercase(), capture(body)) } returns
                    Uni.createFrom().item(pacs002(sts, if (sts == PaymentStatus.RJCT) "AM03" else null))
                val out = adapter.submitInstitutionTransfer(transfer(release(rail)))
                assertThat(out.verdict).isEqualTo(verdict)
                assertThat(out.reasonCode).isEqualTo(if (sts == PaymentStatus.RJCT) "AM03" else null)
                assertThat(validator.validate(body.captured)).isEqualTo(Iso20022ValidationResult.Valid)
                assertThat(out.rawPacs009).isEqualTo(body.captured)
            }
        }
    }

    @Test
    fun `an unreachable scheme or an unreadable answer is SchemeGatewayUnavailable`() {
        every { client.submitInstitutionCreditTransfer(any(), any()) } returns
            Uni.createFrom().failure(RuntimeException("down"))
        assertThatThrownBy { runBlocking { adapter.submitInstitutionTransfer(transfer()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
        every { client.submitInstitutionCreditTransfer(any(), any()) } returns Uni.createFrom().item("<garbage")
        assertThatThrownBy { runBlocking { adapter.submitInstitutionTransfer(transfer()) } }
            .isInstanceOf(SchemeGatewayUnavailableException::class.java)
    }

    @Test
    fun `a message failing its own schema is never sent`() {
        // Bypasses validation on purpose: a BIC the XSD refuses, to prove the adapter checks before sending.
        val broken = transfer().let { it.copy(creditor = it.creditor.copy(bic = "bad")) }
        assertThatThrownBy { runBlocking { adapter.submitInstitutionTransfer(broken) } }
            .isInstanceOf(NonConformingMessageException::class.java)
        verify(exactly = 0) { client.submitInstitutionCreditTransfer(any(), any()) }
    }

    @Test
    fun `the institution submission carries no automatic retry, the status query may`() {
        val methods = SchemeGatewayAdapter::class.java.declaredMethods
        val submit = methods.single { it.name == "submitInstitutionOnce" }
        assertThat(submit.getAnnotation(org.eclipse.microprofile.faulttolerance.Retry::class.java))
            .describedAs("a retried pacs.009 submission can pay twice").isNull()
        val query = methods.single { it.name == "queryInstitutionWithResilience" }
        assertThat(query.getAnnotation(org.eclipse.microprofile.faulttolerance.Retry::class.java)).isNotNull()
    }

    private fun submitted() = transfer().let { it.copy(messageId = InstitutionTransfer.messageIdFor(it)) }

    private fun statusFor(t: InstitutionTransfer, uetr: String = t.uetr, msgId: String? = t.messageId) =
        Pacs002Builder().build(
            PaymentStatusReport(
                "SIM-STS-1",
                OffsetDateTime.now(ZoneOffset.UTC),
                "E2E",
                null,
                PaymentStatus.ACSC,
                originalMessageId = msgId,
                originalMessageNameId = "pacs.009.001.08",
                originalUetr = uetr,
            ),
        )

    private fun notFound(body: String?): Uni<String> {
        val response = jakarta.ws.rs.core.Response.status(404).apply { body?.let { entity(it) } }.build()
        return Uni.createFrom().failure(jakarta.ws.rs.WebApplicationException(response))
    }

    @Test
    fun `a status naming this MsgId and UETR is Known`(): Unit = runBlocking {
        val t = submitted()
        every { client.institutionTransferStatus(t.uetr) } returns Uni.createFrom().item(statusFor(t))
        val known = adapter.queryInstitutionTransferStatus(t) as SchemeStatus.Known
        assertThat(known.submission.verdict).isEqualTo(InstitutionTransferVerdict.SETTLED)
    }

    @Test
    fun `a status naming another UETR or MsgId, or none, is Ambiguous`(): Unit = runBlocking {
        val t = submitted()
        for (xml in listOf(
            statusFor(t, uetr = "00000000-0000-4000-8000-000000000000"),
            statusFor(t, msgId = "FI-SOMETHINGELSE"),
            statusFor(t, msgId = null),
        )) {
            every { client.institutionTransferStatus(t.uetr) } returns Uni.createFrom().item(xml)
            assertThat(
                adapter.queryInstitutionTransferStatus(t),
            ).isEqualTo(SchemeStatus.Ambiguous(SchemeStatus.MISMATCHED))
        }
    }

    @Test
    fun `only the typed UNKNOWN_UETR 404 for this UETR is NotKnown, any other 404 is Ambiguous`(): Unit = runBlocking {
        val t = submitted()
        every { client.institutionTransferStatus(t.uetr) } returns
            notFound("""{"code":"UNKNOWN_UETR","uetr":"${t.uetr}"}""")
        assertThat(adapter.queryInstitutionTransferStatus(t)).isEqualTo(SchemeStatus.NotKnown)
        for (body in listOf(
            null,
            "",
            "<html>Not Found</html>",
            """{"code":"UNKNOWN_UETR","uetr":"another"}""",
            """{"code":"OTHER"}""",
        )) {
            every { client.institutionTransferStatus(t.uetr) } returns notFound(body)
            assertThat(adapter.queryInstitutionTransferStatus(t)).describedAs("body %s", body)
                .isEqualTo(SchemeStatus.Ambiguous(SchemeStatus.UNTYPED_NOT_FOUND))
        }
    }

    @Test
    fun `no answer at all is SchemeGatewayUnavailable, not a verdict`() {
        val t = submitted()
        for (failure in listOf<Uni<String>>(
            Uni.createFrom().failure(jakarta.ws.rs.WebApplicationException(503)),
            Uni.createFrom().failure(RuntimeException("timeout")),
            Uni.createFrom().item("<garbage"),
        )) {
            every { client.institutionTransferStatus(t.uetr) } returns failure
            assertThatThrownBy { runBlocking { adapter.queryInstitutionTransferStatus(t) } }
                .isInstanceOf(SchemeGatewayUnavailableException::class.java)
        }
    }

    @Test
    fun `a resend carries the persisted MsgId and CreDtTm`(): Unit = runBlocking {
        val created = java.time.Instant.parse("2026-10-19T07:00:00Z")
        val t = transfer().let { it.copy(messageId = InstitutionTransfer.messageIdFor(it), messageCreatedAt = created) }
        val body = slot<String>()
        every { client.submitInstitutionCreditTransfer(any(), capture(body)) } returns
            Uni.createFrom().item(pacs002(PaymentStatus.ACSC))
        adapter.submitInstitutionTransfer(t)
        assertThat(
            body.captured,
        ).contains("<MsgId>${t.messageId}</MsgId>", "<CreDtTm>2026-10-19T07:00:00Z</CreDtTm>", "<UETR>${t.uetr}</UETR>")
    }
}
