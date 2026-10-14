// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.tax.application.port.out.CorporateFactsPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.infrastructure.returns.pension.CompanyBooksPort
import com.openbank.tax.infrastructure.returns.pension.FundPeriodFiguresDto
import com.openbank.tax.infrastructure.returns.pension.PensionFundReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingClient
import com.openbank.tax.infrastructure.returns.pension.PensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.PensionReturnDataAdapter
import com.openbank.tax.infrastructure.returns.pension.RestPensionReportingSources
import com.openbank.tax.infrastructure.returns.pension.VerifiedFundClose
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jboss.resteasy.reactive.RestResponse
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class FundClosedSourceTest {
    private val fundId = UUID.fromString("17ded338-5768-42d1-83f1-ea91734a5db6")
    private val closeId = UUID.fromString("d53c2305-0875-4e7a-a245-c24516ca0d75")
    private val navId = UUID.fromString("8c16d486-14f1-48e4-aace-acf956cbf38b")
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val payload = """{
        "fundId":"$fundId","currency":"CZK","periodStart":"2026-01-01","periodEnd":"2026-12-31",
        "closingValuationDate":"2026-12-31","closingNavId":"$navId","basisNavIds":["$navId"],
        "fingerprint":"${"a".repeat(64)}",
        "balanceSheet":{"totalAssets":3,"totalLiabilities":0,"totalEquity":3},
        "profitAndLossYtd":{"revaluationGains":0,"revaluationLosses":0,
            "otherInvestmentResult":3,"managementFees":0,"profitLoss":3},
        "units":{"opening":0,"issued":3,"cancelled":0,"closing":3,
            "unitValue":1,"unitValuePeriodMax":1},
        "portfolio":{"carryingValue":3,"holdingsCount":1,"cash":0,"loansOutstanding":3,
            "unclassifiedCount":0},
        "entitlements":{"opening":0,"increase":3,"decrease":0,"closing":3},
        "participants":{"holders":1,"subscribing":1}
    }
    """.trimIndent()
    private val funds = mockk<PensionFundReportingClient>()
    private val sources = RestPensionReportingSources(funds, mockk<PensionReportingClient>(), mapper)

    private fun response(
        body: String = payload,
        declaredSha: String = sha(body),
        declaredId: UUID = closeId,
        revision: String = "1",
        schema: String = "1.3.0",
    ): RestResponse<ByteArray> = mockk<RestResponse<ByteArray>>().also { response ->
        every { response.status } returns 200
        every { response.entity } returns body.toByteArray(Charsets.UTF_8)
        every { response.mediaType } returns MediaType.APPLICATION_JSON_TYPE
        every { response.getHeaderString("X-Reporting-Payload-SHA256") } returns declaredSha
        every { response.getHeaderString("X-Reporting-Close-Id") } returns declaredId.toString()
        every { response.getHeaderString("X-Reporting-Close-Revision") } returns revision
        every { response.getHeaderString("X-Reporting-Payload-Schema-Version") } returns schema
        every { response.close() } returns Unit
        coEvery { funds.sealedPeriod(fundId, closeId) } returns response
    }

    @Test
    fun `exact selected sealed bytes and receipt are verified before decoding`(): Unit = runBlocking {
        response()
        val verified = sources.closedFund(fundId, closeId)
        assertThat(verified.closeId).isEqualTo(closeId)
        assertThat(verified.figures.closingNavId).isEqualTo(navId)
        assertThat(verified.payload.at("/participants/holders").intValue()).isEqualTo(1)
    }

    @Test
    fun `modified bytes with stale digest are unavailable`(): Unit = runBlocking {
        response(body = payload.replace("\"holders\":1", "\"holders\":2"), declaredSha = sha(payload))
        assertUnavailable()
    }

    @Test
    fun `wrong close identifier or nonpositive revision or schema is unavailable`(): Unit = runBlocking {
        response(declaredId = UUID.randomUUID())
        assertUnavailable()
        response(revision = "0")
        assertUnavailable()
        response(schema = "1.2.0")
        assertUnavailable()
    }

    @Test
    fun `signed wrong fund or currency or missing closing NAV basis is unavailable`(): Unit = runBlocking {
        response(body = payload.replace(fundId.toString(), UUID.randomUUID().toString()))
        assertUnavailable()
        response(body = payload.replace("\"currency\":\"CZK\"", "\"currency\":\"EUR\""))
        assertUnavailable()
        response(body = payload.replace("\"basisNavIds\":[\"$navId\"]", "\"basisNavIds\":[]"))
        assertUnavailable()
    }

    @Test
    fun `all eight fund returns bind each mapped cell to a present sealed numeric source`(): Unit = runBlocking {
        val figures = mapper.readValue(payload, FundPeriodFiguresDto::class.java)
        val source = mockk<PensionReportingSources>()
        val period = ReportingPeriod(Periodicity.YEAR, LocalDate.of(2026, 12, 31))
        val verified = VerifiedFundClose(closeId, 1, "1.3.0", sha(payload), figures, mapper.readTree(payload))
        coEvery { source.closedFund(fundId, closeId) } returns verified
        val adapter = PensionReturnDataAdapter(source, mockk<CorporateFactsPort>(), mockk<CompanyBooksPort>())
        val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
        val codes = setOf(
            "PSP10-12-FUND",
            "PEF12-04-FUND",
            "PSP20-12-FUND",
            "PEF13-04",
            "PSP30-12",
            "PSP34-12-FUND",
            "PEF14-04",
            "PEF15-01",
        )
        codes.forEach { code ->
            val definition = checkNotNull(catalogue.definition(code)).copy(periodicity = Periodicity.YEAR)
            val extracted = adapter.fetchClosedFund(catalogue, definition, fundId, period, closeId)
            assertThat(extracted.values.keys).containsExactlyInAnyOrderElementsOf(definition.datapoints)
            assertThat(
                extracted.fundCloseLineage.cellPaths.keys,
            ).containsExactlyInAnyOrderElementsOf(definition.datapoints)
        }
        val missingHolder = payload.replace("\"holders\":1,", "")
        coEvery { source.closedFund(fundId, closeId) } returns verified.copy(
            payload = mapper.readTree(missingHolder),
        )
        assertThatThrownBy {
            runBlocking {
                adapter.fetchClosedFund(
                    catalogue,
                    checkNotNull(catalogue.definition("PEF15-01")),
                    fundId,
                    period,
                    closeId,
                )
            }
        }.isInstanceOf(ReturnDataUnavailableException::class.java)
    }

    private fun assertUnavailable() {
        assertThatThrownBy { runBlocking { sources.closedFund(fundId, closeId) } }
            .isInstanceOf(ReturnDataUnavailableException::class.java)
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
