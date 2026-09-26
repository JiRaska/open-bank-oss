// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.RegistrySearchQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The ARES name search (issue #9707). The request-body test is the point of this file.
 *
 * `sidlo.nazevObce` is the field name ARES puts in every search RESPONSE, so it is the one a
 * reader reaches for — and the search endpoint ignores it. Measured against the live service on
 * 2026-09-11 with `obchodniJmeno=Asseco`, six matches in total:
 *
 * ```
 * sidlo.textovaAdresa  Praha 4   Brno 0   Ostrava 0   Bratislava 2     (4 + 2 = 6, discriminates)
 * sidlo.nazevObce      Praha 6   Brno 6                Bratislava 6     (ignored — every town, 6)
 * ```
 *
 * A town box wired to `nazevObce` still returns results, so nothing downstream would notice: the
 * customer would be shown companies from the wrong end of the country while believing they had
 * filtered. This test fails the moment the key changes, which is the only cheap defence against a
 * "fix" that makes the field name look more correct.
 */
class AresRegistrySearchTest {

    private val json = ObjectMapper()
    private val adapter = AresRegistryAdapter()

    @Test
    fun `the town filter is sidlo_textovaAdresa, the field that actually narrows`() {
        val body = adapter.searchBody(RegistrySearchQuery(name = "Asseco", city = "Praha", limit = 20))

        assertThat(body.path("sidlo").path("textovaAdresa").asText()).isEqualTo("Praha")
        assertThat(body.path("sidlo").has("nazevObce"))
            .describedAs("nazevObce is silently ignored by the ARES search endpoint; see the class KDoc")
            .isFalse()
        assertThat(body.path("obchodniJmeno").asText()).isEqualTo("Asseco")
        assertThat(body.path("pocet").asInt()).isEqualTo(20)
    }

    @Test
    fun `no town means no sidlo filter at all, not an empty one`() {
        val body = adapter.searchBody(RegistrySearchQuery(name = "Kofola"))

        assertThat(body.has("sidlo"))
            .describedAs("an empty sidlo object would narrow to nothing on some registers")
            .isFalse()
    }

    @Test
    fun `a search payload maps to hits carrying only what tells two companies apart`() {
        val response = json.readTree(
            """
            {"pocetCelkem":2,"ekonomickeSubjekty":[
              {"ico":"27074358","obchodniJmeno":"Asseco Central Europe, a.s.","pravniForma":"121",
               "sidlo":{"textovaAdresa":"Budějovická 778/3, 14000 Praha 4"}},
              {"ico":"45274649","obchodniJmeno":"Příklad s.r.o.","pravniForma":"112",
               "sidlo":{"textovaAdresa":"Náměstí 1, 60200 Brno"}}
            ]}
            """.trimIndent(),
        )

        val result = adapter.toResult(response)

        assertThat(result.totalMatches).isEqualTo(2)
        assertThat(result.tooManyMatches).isFalse()
        assertThat(result.hits).hasSize(2)
        val first = result.hits.first()
        assertThat(first.identifier.scheme).isEqualTo(IdentifierScheme.CZ_ICO)
        assertThat(first.identifier.value).isEqualTo("27074358")
        assertThat(first.name).isEqualTo("Asseco Central Europe, a.s.")
        assertThat(first.legalFormCode).isEqualTo("121")
        assertThat(first.registeredAddress).contains("Praha")
    }

    @Test
    fun `totalMatches keeps the register's own count when it exceeds the returned page`() {
        val response = json.readTree(
            """
            {"pocetCelkem":412,"ekonomickeSubjekty":[
              {"ico":"27074358","obchodniJmeno":"Jedna s.r.o.","pravniForma":"112","sidlo":{"textovaAdresa":"Praha"}}
            ]}
            """.trimIndent(),
        )

        val result = adapter.toResult(response)

        assertThat(result.hits).hasSize(1)
        assertThat(result.totalMatches)
            .describedAs("the customer needs to know a narrower query exists, not just see one row")
            .isEqualTo(412)
    }

    @Test
    fun `a row without an identifier or a name is dropped rather than shown unpickable`() {
        val response = json.readTree(
            """
            {"pocetCelkem":3,"ekonomickeSubjekty":[
              {"obchodniJmeno":"Bez IČO s.r.o.","pravniForma":"112"},
              {"ico":"27074358","pravniForma":"112"},
              {"ico":"45274649","obchodniJmeno":"Dobrá s.r.o.","pravniForma":"112"}
            ]}
            """.trimIndent(),
        )

        val result = adapter.toResult(response)

        assertThat(result.hits).hasSize(1)
        assertThat(result.hits.single().name).isEqualTo("Dobrá s.r.o.")
    }

    @Test
    fun `search returns null for a scheme this register does not serve`() {
        // Null is "this register cannot search", which the router turns into a hidden search box —
        // distinct from an empty result, which would read as "your company does not exist".
        val result = kotlinx.coroutines.runBlocking {
            adapter.search(IdentifierScheme.GB_CRN, RegistrySearchQuery(name = "Anything"))
        }

        assertThat(result).isNull()
    }
}
