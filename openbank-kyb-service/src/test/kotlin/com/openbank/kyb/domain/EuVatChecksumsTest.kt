// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.EuVatChecksums
import com.openbank.kyb.domain.model.IdentifierChecksums
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Member-state VAT rules added in #16774 and hardened after review.
 *
 * Every number below is SYNTHETIC and comes from `.github/scripts/eu-vat-vector-corpus.py` (fixed
 * seed; regenerate with the command in its header). A VALID vector is kept only if python-stdnum
 * 2.2 (`stdnum.eu.vat`) accepts it and no local-policy rule rejects it; an INVALID one only if
 * stdnum rejects it. POLICY-INVALID vectors are accepted by stdnum and rejected here on purpose:
 * all-zero payload, BE remainder 0, IE old form with a 9th character, FR zero SIREN, RO future
 * birth date, SK third digit outside 2,3,4,7,8,9 where stdnum lets a birth number through first.
 */
class EuVatChecksumsTest {

    private val valid = listOf(
        "ES57089925S", // ES
        "ES79114601Y", // ES
        "IT00849840970", // IT
        "IT43019340710", // IT
        "NL706392747B48", // NL
        "NL310652145B97", // NL
        "BE0485618622", // BE
        "BE0888531183", // BE
        "PT500489238", // PT
        "PT424827093", // PT
        "IE9091738W", // IE
        "IE1428924W", // IE
        "DK63863939", // DK
        "DK28925700", // DK
        "FI14587578", // FI
        "FI68907290", // FI
        "SE388052876901", // SE
        "SE158638214301", // SE
        "LU01001850", // LU
        "LU66278288", // LU
        "SI29963885", // SI
        "SI79368557", // SI
        "HR55233937159", // HR
        "HR17829572706", // HR
        "HU32900284", // HU
        "HU00696690", // HU
        "RO956461", // RO
        "RO90265615", // RO
        "BG113020609", // BG
        "BG842801578", // BG
        "EE484917120", // EE
        "EE264005537", // EE
        "LV49490112106", // LV
        "LV64659976025", // LV
        "LT508966017", // LT
        "LT336263312", // LT
        "EL462658326", // EL
        "EL280885573", // EL
        "CY07797666A", // CY
        "CY06234477A", // CY
        "MT99435384", // MT
        "MT84806177", // MT
        "XI495619791", // XI
        "XI850656082", // XI
        "SK2626504705", // SK third=2
        "SK6533591163", // SK third=3
        "SK1443424697", // SK third=4
        "SK6475152585", // SK third=7
        "SK5387192580", // SK third=8
        "SK5298597546", // SK third=9
        "ESZ8303792S", // ES NIE
        "ESK7369978L", // ES K
        "ESA94116712", // ES CIF A digit-control
        "ESA8664173E", // ES CIF A letter-control
        "ESB90724089", // ES CIF B digit-control
        "ESB6056765H", // ES CIF B letter-control
        "ESE61926085", // ES CIF E digit-control
        "ESE7457191J", // ES CIF E letter-control
        "ESH81389801", // ES CIF H digit-control
        "ESH4634396H", // ES CIF H letter-control
        "ESP14793541", // ES CIF P digit-control
        "ESP8632538H", // ES CIF P letter-control
        "ESQ36749216", // ES CIF Q digit-control
        "ESQ7562507I", // ES CIF Q letter-control
        "ESR96843636", // ES CIF R digit-control
        "ESR3235050F", // ES CIF R letter-control
        "ESS59148114", // ES CIF S digit-control
        "ESS1131598C", // ES CIF S letter-control
        "ESN65923070", // ES CIF N digit-control
        "ESN8274092I", // ES CIF N letter-control
        "ESW41413246", // ES CIF W digit-control
        "ESW8337728C", // ES CIF W letter-control
        "ESC85808673", // ES CIF C digit-control
        "ESC3079063H", // ES CIF C letter-control
        "ESD26482844", // ES CIF D digit-control
        "ESD5392520B", // ES CIF D letter-control
        "ESF33847823", // ES CIF F digit-control
        "ESF6986090F", // ES CIF F letter-control
        "ESG85624039", // ES CIF G digit-control
        "ESG6462916E", // ES CIF G letter-control
        "ESJ51175578", // ES CIF J digit-control
        "ESJ4903753D", // ES CIF J letter-control
        "ESU86983285", // ES CIF U digit-control
        "ESU0432574B", // ES CIF U letter-control
        "ESV12770962", // ES CIF V digit-control
        "ESV0001997F", // ES CIF V letter-control
        "IE9G63420N", // IE old
        "IE8319064SB", // IE two letters
        "BE1896175497", // BE remainder 0 / last=97
        "NL736407593B05", // NL mod97-only
        "BG7930734908", // BG10
        "LT062474622218", // LT12
        "XI221064067097", // XI12
        "XIGD499", // XI GD499
        "XIHA500", // XI HA500
        "XIHA999", // XI HA999
        "LV17046202478", // LV person
        "LV32124227570", // LV 32-code
        "SI41822030", // SI remainder 1 last 0
        "FRCJ603436635", // FR alpha key
        "FRXZ677525800", // FR alpha key
        "FR33957629694", // FR numeric key
        "RO5050615020364", // RO CNP born 2005
        "RO2980801068381", // RO CNP
    )

    private val invalid = listOf(
        "ES60259747L", // ES
        "ES32140717N", // ES
        "IT36198999980", // IT
        "IT85700517160", // IT
        "NL489696246B14", // NL
        "NL945129098B77", // NL
        "BE0656267045", // BE
        "BE0832595246", // BE
        "PT922189774", // PT
        "PT390667664", // PT
        "IE5416083W", // IE
        "IE0778457W", // IE
        "DK34395693", // DK
        "DK64309081", // DK
        "FI39812921", // FI
        "FI99732556", // FI
        "SE213803734101", // SE
        "SE392180534201", // SE
        "LU31318145", // LU
        "LU71045277", // LU
        "SI59401348", // SI
        "SI54791899", // SI
        "HR37544214759", // HR
        "HR50327776266", // HR
        "HU07355554", // HU
        "HU29354019", // HU
        "RO815950054", // RO
        "RO2595686", // RO
        "BG202801499", // BG
        "BG160992129", // BG
        "EE553323217", // EE
        "EE524491996", // EE
        "LV87805869086", // LV
        "LV41211663267", // LV
        "LT385624819", // LT
        "LT021317718", // LT
        "EL444708622", // EL
        "EL714344143", // EL
        "CY35776314A", // CY
        "CY43899494A", // CY
        "MT91327215", // MT
        "MT70213228", // MT
        "XI092827860", // XI
        "XI124142003", // XI
        "SK6708662180", // SK third=0
        "SK5517477636", // SK third=1
        "SK7266657937", // SK third=6
        "SK2626504706", // SK
        "SK0571482417", // SK first=0
        "ESX3646543O", // ES NIE
        "ESK3015280P", // ES K
        "ESA3521640J", // ES CIF A
        "ESB7159930C", // ES CIF B
        "ESE42058540", // ES CIF E
        "ESH4525962D", // ES CIF H
        "ESP78550982", // ES CIF P
        "ESQ2866394C", // ES CIF Q
        "ESR2513108E", // ES CIF R
        "ESS31181866", // ES CIF S
        "ESN2156019E", // ES CIF N
        "ESW25785789", // ES CIF W
        "ESC7043151G", // ES CIF C
        "ESD9822585J", // ES CIF D
        "ESF8761545A", // ES CIF F
        "ESG8219464F", // ES CIF G
        "ESJ0582375G", // ES CIF J
        "ESU9336175I", // ES CIF U
        "ESV88383937", // ES CIF V
        "IE3A60482L", // IE old
        "IE4678742OA", // IE two letters
        "BG2668763224", // BG10
        "LT929396689812", // LT12
        "XI538299619461", // XI12
        "XIGD500", // XI GD500
        "XIHA499", // XI HA499
        "XIGD8888499", // XI GD8888499
        "XIGD1", // XI GD1
        "LV03097403306", // LV person
        "LV32654434034", // LV 32-code
        "LV27087808190", // LV 1101-variant
        "SI62043250", // SI remainder 0 last 0
        "FRWX671433739", // FR alpha key
        "FR1Q947575421", // FR alpha key
        "FR46614966529", // FR numeric key
        "RO5356838703649", // RO CNP
    )

    private val policyInvalid = listOf(
        "SK7155014548", // SK third=5
        "IE3A73216MB", // IE old+9th
        "BE0379580400", // BE remainder 0 / last=00
        "XIGD000", // XI GD000
        "FR12000000000", // FR Monaco zero SIREN
        "RO6670615017673", // RO CNP born 2067
    )

    private val prefixes = "AT BE BG CY CZ DE DK EE EL ES FI FR HR HU IE IT LT LU LV MT NL PL PT RO SE SI SK XI".split(
        " ",
    )

    @TestFactory
    fun `every synthetic valid number is accepted`(): List<DynamicTest> =
        valid.map { DynamicTest.dynamicTest(it) { assertThat(EuVatChecksums.valid(it)).describedAs(it).isTrue() } }

    @TestFactory
    fun `every synthetic corrupted number is rejected`(): List<DynamicTest> =
        invalid.map { DynamicTest.dynamicTest(it) { assertThat(EuVatChecksums.valid(it)).describedAs(it).isFalse() } }

    @TestFactory
    fun `numbers stdnum accepts but local policy rejects are rejected`(): List<DynamicTest> = policyInvalid.map {
        DynamicTest.dynamicTest(it) { assertThat(EuVatChecksums.valid(it)).describedAs(it).isFalse() }
    }

    @Test
    fun `every VIES prefix has a rule or a named reason, and an unknown prefix is rejected`() {
        prefixes.forEach { cc ->
            val shapeOnly = cc in EuVatChecksums.SHAPE_ONLY_STATES
            assertThat(EuVatChecksums.valid(cc + "99999999999")).describedAs(cc).isEqualTo(shapeOnly)
        }
        assertThat(EuVatChecksums.SHAPE_ONLY_STATES).isEmpty()
        listOf("GB123456789", "GR123456783", "ZZ12345678").forEach {
            assertThat(EuVatChecksums.valid(it)).describedAs(it).isFalse()
        }
    }

    @Test
    fun `format variants outside the published shape are rejected`() {
        assertThat(EuVatChecksums.valid("BE271318698")).describedAs("9-digit BE").isFalse()
        assertThat(EuVatChecksums.valid("EL23631265")).describedAs("8-digit EL").isFalse()
        assertThat(EuVatChecksums.valid("CY12000000A")).describedAs("CY starting 12").isFalse()
        assertThat(EuVatChecksums.valid("SK0895446915")).describedAs("SK leading zero").isFalse()
    }

    /**
     * A number whose PAYLOAD (everything but the check) is all zero is never issued. Local policy, not
     * a cited spec: stdnum accepts several of these (LU, FI, HU, EE, BG, EL, CY, XI, ES, SE, FR Monaco).
     * One guard, applied by the dispatch to every state; here every state is tried with an all-zero
     * payload and EVERY check completion (each digit and letter, both orders of two-character
     * checks, the key of FR, the `01` of SE, the `B01` of NL, the letter or `U` prefix of ES/AT).
     */
    @Test
    fun `an all-zero payload is rejected in every state whatever check completes it`() {
        val checks = ('0'..'9') + ('A'..'Z')
        val twoDigits = (0..99).map { "%02d".format(it) }
        val classLetters = "ABCDEFGHJNPQRSUVWKLMXYZ"
        fun bodies(cc: String): List<String> = (1..13).flatMap { k ->
            val zeros = "0".repeat(k)
            val lettered = classLetters.flatMap { l -> checks.map { "$l$zeros$it" } }
            checks.map { "$zeros$it" } + checks.map { "U$zeros$it" } + lettered
        } + when (cc) {
            "LU" -> twoDigits.map { "000000$it" }
            "BE" -> twoDigits.map { "00000000$it" }
            "SE" -> checks.map { "000000000${it}01" }
            "NL" -> checks.map { "00000000${it}B01" }
            else -> emptyList()
        }
        val accepted = prefixes.flatMap { cc -> bodies(cc).map { cc + it } }.filter { EuVatChecksums.valid(it) }
        assertThat(accepted).isEmpty()
        val alphabet = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val frKeys = alphabet.flatMap { a -> alphabet.map { b -> "FR$a${b}000000000" } }
        assertThat(frKeys.filter { EuVatChecksums.valid(it) }).isEmpty()
        assertThat(EuVatChecksums.valid("XIGD001")).describedAs("GD001 is a government department").isTrue()
    }

    @Test
    fun `the shared checksum helpers reject an all-zero value too`() {
        assertThat(IdentifierChecksums.nip("0000000000")).describedAs("NIP").isFalse()
        assertThat(IdentifierChecksums.siren("000000000")).describedAs("SIREN").isFalse()
        assertThat(IdentifierChecksums.ico("00000000")).describedAs("IČO").isFalse()
        assertThat(IdentifierChecksums.ico("00000001")).describedAs("the synthetic demo IČO stays valid").isTrue()
        assertThat(EuVatChecksums.valid("CZ00000001")).describedAs("but not as a VAT number").isFalse()
        assertThat(IdentifierChecksums.nip("0000000000")).describedAs("NIP zero").isFalse()
        assertThat(EuVatChecksums.sk("0000000000")).describedAs("SK").isFalse()
    }

    /** Kotlin's `Char.isDigit` accepts every Unicode decimal digit; the object API must not. */
    @Test
    fun `non-ASCII digits are rejected when the object is called directly`() {
        fun arabic(s: String) = s.map { if (it in '0'..'9') ('\u0660' + (it - '0')) else it }.joinToString("")
        valid.forEach { assertThat(EuVatChecksums.valid(arabic(it))).describedAs(arabic(it)).isFalse() }
        // One Arabic-Indic digit among ASCII ones: parsers that use toLong()/toInt() read it as the
        // same digit, so only an explicit ASCII check can reject it.
        val leaked = valid.flatMap { v ->
            v.indices.filter { v[it] in '0'..'9' }.map { i ->
                v.substring(0, i) + ('\u0660' + (v[i] - '0')) +
                    v.substring(i + 1)
            }
        }.filter { EuVatChecksums.valid(it) }
        assertThat(leaked).isEmpty()
        assertThat(EuVatChecksums.sk("1983589520".replaceRange(0, 1, "\u0661"))).describedAs("SK direct").isFalse()
        assertThat(EuVatChecksums.sk(arabic("2626504705"))).describedAs("SK").isFalse()
        assertThat(IdentifierChecksums.nip(arabic("5260250995"))).describedAs("NIP").isFalse()
        assertThat(IdentifierChecksums.ico(arabic("25596641"))).describedAs("IČO").isFalse()
        assertThat(IdentifierChecksums.luhn(arabic("552081317"))).describedAs("Luhn").isFalse()
        assertThat(IdentifierChecksums.siren(arabic("552081317"))).describedAs("SIREN").isFalse()
        assertThat(EuVatChecksums.valid("FR" + arabic("40303265045"))).describedAs("FR").isFalse()
        assertThat(EuVatChecksums.valid("CZ" + arabic("25596641"))).describedAs("CZ").isFalse()
    }

    @Test
    fun `a RO CNP is rejected for a birth date after today and accepted for today, with an injected clock`() {
        val noon = Instant.parse("2026-11-19T12:00:00Z")
        val clock = Clock.fixed(noon, ZoneOffset.UTC)
        assertThat(EuVatChecksums.valid("RO5261119128317", clock)).describedAs("born today").isTrue()
        assertThat(EuVatChecksums.valid("RO5261120121411", clock)).describedAs("born tomorrow").isFalse()
        // the same number is fine once the clock has moved on
        val later = Clock.fixed(noon.plusSeconds(86_400L), ZoneOffset.UTC)
        assertThat(EuVatChecksums.valid("RO5261120121411", later)).describedAs("tomorrow's child, a day later").isTrue()
    }
}
