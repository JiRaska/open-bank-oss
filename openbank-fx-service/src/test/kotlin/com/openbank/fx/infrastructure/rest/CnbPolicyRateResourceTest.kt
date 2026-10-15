// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateFact
import com.openbank.libs.authz.Authorize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.annotation.security.RolesAllowed
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CnbPolicyRateResourceTest {

    private val useCase: CnbPolicyRateUseCase = mockk()

    // 23:30 UTC on 2026-10-04 is already 2026-10-05 in Prague: the default asOf is the Prague day.
    private val clock = Clock.fixed(Instant.parse("2026-10-04T23:30:00Z"), ZoneOffset.UTC)
    private val resource = CnbResource(mockk(), useCase, clock)

    private val repo = CnbPolicyRateFact(
        CnbPolicyInstrument.REPO_2W,
        LocalDate.of(2026, 6, 19),
        BigDecimal("0.03750000"),
        "https://www.cnb.cz/cs/casto-kladene-dotazy/.galleries/vyvoj_repo_historie.txt",
        Instant.parse("2026-10-04T12:45:00Z"),
        "b".repeat(64),
        null,
        null,
        null,
    )

    @Test
    fun `serves the fact in effect on asOf with provenance and a percent view`(): Unit = runBlocking {
        coEvery { useCase.effectiveAt(CnbPolicyInstrument.REPO_2W, LocalDate.of(2026, 7, 1)) } returns repo

        val resp = resource.getPolicyRate("repo_2w", "2026-07-01")

        assertThat(resp.status).isEqualTo(200)
        val body = resp.entity as CnbPolicyRateResponse
        assertThat(body.effectiveFrom).isEqualTo(LocalDate.of(2026, 6, 19))
        assertThat(body.asOf).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(body.rate.toPlainString()).isEqualTo("0.0375")
        assertThat(body.ratePercent.toPlainString()).isEqualTo("3.75")
        assertThat(body.sourceUrl).endsWith("vyvoj_repo_historie.txt")
        assertThat(body.contentSha256).hasSize(64)
    }

    @Test
    fun `asOf defaults to today in Prague, not in UTC`(): Unit = runBlocking {
        coEvery { useCase.effectiveAt(any(), any()) } returns repo

        resource.getPolicyRate("REPO_2W", null)

        coVerify { useCase.effectiveAt(CnbPolicyInstrument.REPO_2W, LocalDate.of(2026, 10, 5)) }
    }

    @Test
    fun `404 when no fact is in effect — never a default`(): Unit = runBlocking {
        coEvery { useCase.effectiveAt(CnbPolicyInstrument.MIN_RESERVE_RATIO, LocalDate.of(2024, 6, 30)) } returns null

        assertThat(resource.getPolicyRate("MIN_RESERVE_RATIO", "2024-06-30").status).isEqualTo(404)
    }

    @Test
    fun `an unknown instrument or a malformed date is a 400-class IllegalArgumentException`() {
        assertThatThrownBy { runBlocking { resource.getPolicyRate("EURIBOR", null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { resource.getPolicyRate("LOMBARD", "01.07.2026") } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("asOf")
        assertThatThrownBy { runBlocking { resource.getPolicyRate(null, null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the read is staff-only fx-read — no machine caller, the risk engine gets these by event`() {
        val method = CnbResource::class.java.declaredMethods.single { it.name == "getPolicyRate" }
        val roles = method.getAnnotation(RolesAllowed::class.java).value.toList()
        assertThat(roles).containsExactlyInAnyOrder("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
        assertThat(roles).doesNotContain("ROLE_API")
        assertThat(method.getAnnotation(Authorize::class.java).action).isEqualTo("fx.read")
    }
}
