// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.tppregistry.application.usecase

import com.openbank.tppregistry.application.port.`in`.BlacklistTppCommand
import com.openbank.tppregistry.application.port.`in`.CheckTppAuthorizationQuery
import com.openbank.tppregistry.application.port.`in`.GetTppQuery
import com.openbank.tppregistry.application.port.`in`.RegisterTppCommand
import com.openbank.tppregistry.application.port.out.TppRepository
import com.openbank.tppregistry.domain.model.TppAuthorizationResult
import com.openbank.tppregistry.domain.model.TppEntry
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking

class TppRegistryServiceTest {

    private val repo = mockk<TppRepository>()
    private val clock = Clock.systemUTC()
    private val service = TppRegistryService(repo, clock)

    @Test
    fun `checkAuthorization returns unauthorized when TPP not found`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-1") } returns null

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("tpp-1", TppRole.AISP))

        assertThat(result).isEqualTo(
            TppAuthorizationResult("tpp-1", false, emptySet(), "TPP not found in registry"),
        )
        coVerify(exactly = 1) { repo.findByTppId("tpp-1") }
        confirmVerified(repo)
    }

    @Test
    fun `checkAuthorization returns unauthorized when TPP not ACTIVE`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-2") } returns sampleTpp(status = TppStatus.SUSPENDED)

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("tpp-2", TppRole.AISP))

        assertThat(result.authorized).isFalse()
        assertThat(result.reason).isEqualTo("TPP status is SUSPENDED")
        assertThat(result.roles).containsExactly(TppRole.AISP)
        coVerify(exactly = 1) { repo.findByTppId("tpp-2") }
        confirmVerified(repo)
    }

    @Test
    fun `checkAuthorization returns unauthorized when missing required role`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-3") } returns sampleTpp(roles = setOf(TppRole.PISP))

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("tpp-3", TppRole.AISP))

        assertThat(result.authorized).isFalse()
        assertThat(result.reason).isEqualTo("TPP does not have role AISP")
        assertThat(result.roles).containsExactly(TppRole.PISP)
        coVerify(exactly = 1) { repo.findByTppId("tpp-3") }
        confirmVerified(repo)
    }

    @Test
    fun `checkAuthorization returns authorized for valid TPP`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-4") } returns sampleTpp(
            roles = setOf(TppRole.AISP, TppRole.PISP),
            qwacExpiresAt = LocalDate.now().plusDays(10),
        )

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("tpp-4", TppRole.AISP))

        assertThat(result.authorized).isTrue()
        assertThat(result.reason).isNull()
        assertThat(result.roles).containsExactlyInAnyOrder(TppRole.AISP, TppRole.PISP)
        coVerify(exactly = 1) { repo.findByTppId("tpp-4") }
        confirmVerified(repo)
    }

    @Test
    fun `checkAuthorization returns unauthorized when QWAC expired`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-5") } returns sampleTpp(
            roles = setOf(TppRole.AISP),
            qwacExpiresAt = LocalDate.now().minusDays(1),
        )

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("tpp-5", TppRole.AISP))

        assertThat(result.authorized).isFalse()
        assertThat(result.reason).isEqualTo("QWAC certificate expired")
        coVerify(exactly = 1) { repo.findByTppId("tpp-5") }
        confirmVerified(repo)
    }

    @Test
    fun `registerTpp saves new entry`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-6") } returns null
        val saved = slot<TppEntry>()
        coEvery { repo.save(capture(saved)) } answers { firstArg() }

        val result = service.registerTpp(
            RegisterTppCommand(
                tppId = "tpp-6",
                name = "Example TPP",
                countryCode = "CZ",
                nca = "CNB",
                roles = setOf(TppRole.AISP, TppRole.PISP),
                qwacSubjectDn = "CN=QWAC",
                qsealSubjectDn = "CN=QSEAL",
            ),
        )

        assertThat(saved.isCaptured).isTrue()
        assertThat(saved.captured.tppId).isEqualTo("tpp-6")
        assertThat(saved.captured.status).isEqualTo(TppStatus.ACTIVE)
        assertThat(saved.captured.qwacSubjectDn).isEqualTo("CN=QWAC")
        assertThat(result).isEqualTo(saved.captured)
        coVerify(exactly = 1) { repo.findByTppId("tpp-6") }
        coVerify(exactly = 1) { repo.save(any()) }
        confirmVerified(repo)
    }

    @Test
    fun `registerTpp throws TppAlreadyExistsException when exists`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-7") } returns sampleTpp(tppId = "tpp-7")

        assertThatThrownBy {
            runBlocking {
                service.registerTpp(
                    RegisterTppCommand(
                        tppId = "tpp-7",
                        name = "Existing TPP",
                        countryCode = "CZ",
                        nca = "CNB",
                        roles = setOf(TppRole.AISP),
                        qwacSubjectDn = null,
                        qsealSubjectDn = null,
                    ),
                )
            }
        }.isInstanceOf(TppAlreadyExistsException::class.java)
            .hasMessage("TPP tpp-7 already registered")

        coVerify(exactly = 1) { repo.findByTppId("tpp-7") }
        coVerify(exactly = 0) { repo.save(any()) }
        confirmVerified(repo)
    }

    @Test
    fun `blacklistTpp sets BLACKLISTED status`(): Unit = runBlocking {
        val existing = sampleTpp(tppId = "tpp-8", status = TppStatus.ACTIVE)
        coEvery { repo.findByTppId("tpp-8") } returns existing
        val updated = slot<TppEntry>()
        coEvery { repo.update(capture(updated)) } answers { firstArg() }

        val result = service.blacklistTpp(BlacklistTppCommand("tpp-8", "fraud"))

        assertThat(updated.isCaptured).isTrue()
        assertThat(updated.captured.status).isEqualTo(TppStatus.BLACKLISTED)
        assertThat(updated.captured.blacklistReason).isEqualTo("fraud")
        assertThat(updated.captured.blacklistedAt).isNotNull()
        assertThat(result).isEqualTo(updated.captured)
        coVerify(exactly = 1) { repo.findByTppId("tpp-8") }
        coVerify(exactly = 1) { repo.update(any()) }
        confirmVerified(repo)
    }

    @Test
    fun `blacklistTpp throws TppNotFoundException when not found`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-9") } returns null

        assertThatThrownBy {
            runBlocking {
                service.blacklistTpp(BlacklistTppCommand("tpp-9", "fraud"))
            }
        }.isInstanceOf(TppNotFoundException::class.java)
            .hasMessage("TPP tpp-9 not found")

        coVerify(exactly = 1) { repo.findByTppId("tpp-9") }
        coVerify(exactly = 0) { repo.update(any()) }
        confirmVerified(repo)
    }

    @Test
    fun `getTpp throws TppNotFoundException when not found`(): Unit = runBlocking {
        coEvery { repo.findByTppId("tpp-10") } returns null

        assertThatThrownBy {
            runBlocking {
                service.getTpp(GetTppQuery("tpp-10"))
            }
        }.isInstanceOf(TppNotFoundException::class.java)
            .hasMessage("TPP tpp-10 not found")

        coVerify(exactly = 1) { repo.findByTppId("tpp-10") }
        confirmVerified(repo)
    }

    private fun sampleTpp(
        id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        tppId: String = "tpp-1",
        status: TppStatus = TppStatus.ACTIVE,
        roles: Set<TppRole> = setOf(TppRole.AISP),
        qwacExpiresAt: LocalDate? = LocalDate.now().plusDays(30),
    ): TppEntry {
        val now = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        return TppEntry(
            id = id,
            tppId = tppId,
            name = "Example TPP",
            countryCode = "CZ",
            nca = "CNB",
            roles = roles,
            status = status,
            qwacSubjectDn = "CN=QWAC",
            qsealSubjectDn = "CN=QSEAL",
            qwacExpiresAt = qwacExpiresAt,
            qsealExpiresAt = null,
            registeredAt = now,
            updatedAt = now,
            blacklistedAt = null,
            blacklistReason = null,
        )
    }
}
