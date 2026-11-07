// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.libs.identity.BlindIndex
import com.openbank.libs.identity.RodneCislo
import com.openbank.party.application.port.`in`.SearchPartiesQuery
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.Party
import com.openbank.party.infrastructure.persistence.repository.foldForSearch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/** Operator search shapes: name, name + date of birth, and an exact birth number (never a prefix). */
class PartyServiceSearchTest {
    private val pepper = "test-pepper"

    private fun service(repo: PartyRepository) = PartyService().apply {
        partyRepo = repo
        rcPepper = Optional.of(pepper)
    }

    @Test
    fun `a plain name searches business keys without a date filter`(): Unit = runBlocking {
        val repo = mockk<PartyRepository> {
            coEvery { searchByBusinessKeys(any(), any(), any(), any()) } returns emptyList()
        }
        service(repo).searchParties(SearchPartiesQuery("Novák"))
        coVerify { repo.searchByBusinessKeys("Novák", 21, null, emptyList()) }
    }

    @Test
    fun `a czech date in the term narrows the name match to that date of birth`(): Unit = runBlocking {
        val repo = mockk<PartyRepository> {
            coEvery { searchByBusinessKeys(any(), any(), any(), any()) } returns emptyList()
        }
        service(repo).searchParties(SearchPartiesQuery("Jan Novák 5. 6. 1985"))
        coVerify { repo.searchByBusinessKeys("Jan Novák", 21, null, listOf("1985-06-05")) }
    }

    @Test
    fun `an iso date is accepted too`(): Unit = runBlocking {
        val repo = mockk<PartyRepository> {
            coEvery { searchByBusinessKeys(any(), any(), any(), any()) } returns emptyList()
        }
        service(repo).searchParties(SearchPartiesQuery("1985-06-05 Novák"))
        coVerify { repo.searchByBusinessKeys("Novák", 21, null, listOf("1985-06-05")) }
    }

    @Test
    fun `a bare date never lists everyone born that day`(): Unit = runBlocking {
        val repo = mockk<PartyRepository>()
        val page = service(repo).searchParties(SearchPartiesQuery("15.6.1985"))
        assertThat(page.data).isEmpty()
        coVerify(exactly = 0) { repo.searchByBusinessKeys(any(), any(), any(), any()) }
    }

    @Test
    fun `a czech birth number resolves through the blind index and the exact identifier match`(): Unit = runBlocking {
        val rc = "8506150004"
        val party = mockk<Party>()
        val canonical = (RodneCislo.parse(rc) as RodneCislo.Parsed).canonical
        val index = BlindIndex.compute(pepper.toByteArray(Charsets.UTF_8), canonical)
        every { party.id } returns java.util.UUID.randomUUID()
        val repo = mockk<PartyRepository> {
            coEvery { findByRcBlindIndex(index) } returns party
            coEvery { findByIdentifier("8506150004", 20) } returns listOf(party)
        }

        val page = service(repo).searchParties(SearchPartiesQuery("850615/0004"))

        assertThat(page.data).containsExactly(party)
        coVerify(exactly = 0) { repo.searchByBusinessKeys(any(), any(), any(), any()) }
    }

    @Test
    fun `a foreign national identifier is an exact normalised lookup in any country format`(): Unit = runBlocking {
        val repo = mockk<PartyRepository> {
            coEvery { findByIdentifier(any(), any()) } returns
                listOf(mockk<Party> { every { id } returns java.util.UUID.randomUUID() })
        }
        service(repo).searchParties(SearchPartiesQuery("123-45-6789")) // US SSN
        service(repo).searchParties(SearchPartiesQuery("QQ 12 34 56 C")) // UK NINO
        coVerify { repo.findByIdentifier("123456789", 20) }
        coVerify { repo.findByIdentifier("qq123456c", 20) }
        coVerify(exactly = 0) { repo.searchByBusinessKeys(any(), any(), any(), any()) }
    }

    @Test
    fun `an alphanumeric fragment with no identifier hit falls back to the name search`(): Unit = runBlocking {
        val repo = mockk<PartyRepository> {
            coEvery { findByIdentifier(any(), any()) } returns emptyList()
            coEvery { searchByBusinessKeys(any(), any(), any(), any()) } returns emptyList()
        }
        service(repo).searchParties(SearchPartiesQuery("ab12cd34"))
        coVerify { repo.searchByBusinessKeys("ab12cd34", 21, null, emptyList()) }
    }

    @Test
    fun `a name is not mistaken for an identifier`() {
        assertThat(normalizeIdentifier("Novák")).isNull()
        assertThat(normalizeIdentifier("Anna Smith")).isNull()
    }

    @Test
    fun `date formats from around the world`() {
        assertThat(splitBirthDate("Doe 12/31/1985")).isEqualTo(listOf("1985-12-31") to "Doe")
        assertThat(splitBirthDate("Doe 31/12/1985")).isEqualTo(listOf("1985-12-31") to "Doe")
        assertThat(splitBirthDate("Doe 1985.12.31")).isEqualTo(listOf("1985-12-31") to "Doe")
        assertThat(splitBirthDate("Novák 3.4.1985")).isEqualTo(listOf("1985-04-03") to "Novák")
        assertThat(splitBirthDate("Doe 03/04/1985").first).containsExactlyInAnyOrder("1985-04-03", "1985-03-04")
    }

    @Test
    fun `splitBirthDate rejects an impossible date`() {
        assertThat(splitBirthDate("Novák 31.2.1985")).isEqualTo(emptyList<String>() to "Novák 31.2.1985")
    }
}

class FoldForSearchTest {
    @Test
    fun `diacritics and case are folded so a partial plain-ascii term matches`() {
        assertThat(foldForSearch("  Jan  NOVÁK Müller Ångström Žluťoučký "))
            .isEqualTo("jan novak muller angstrom zlutoucky")
    }
}
