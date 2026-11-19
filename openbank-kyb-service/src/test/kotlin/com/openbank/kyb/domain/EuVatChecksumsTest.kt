// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.EuVatChecksums
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * One valid and one invalid side for every member-state rule added in #16774. Every number is
 * SYNTHETIC: drawn at random from a fixed seed and kept only if the state's published check-digit
 * algorithm (python-stdnum 2.2, an independent implementation) accepts it (valid side) or rejects it
 * (invalid side); none was copied from a register.
 */
class EuVatChecksumsTest {

    private val valid = listOf(
        "ES72872490A", "ESB33113879", "ESQ7200317A", "ESX6996784T", "ESK2425089S",
        "IT49377390700", "IT81990370900",
        "NL541608307B60", "NL746813858B30", // the second passes only the 2020 MOD 97-10 rule
        "BE0271318698", "BE0849071880",
        "PT125789335", "PT402032918",
        "IE2253051W", "IE4G94281B", "IE6813858GH",
        "DK11996388", "DK83315776",
        "FI57616527", "FI74940134",
        "SE538832615801", "SE980158070601",
        "LU29091260", "LU42297648",
        "SI72107944", "SI83968741",
        "HR14285975465", "HR85859178380",
        "HU10459450", "HU79099561",
        "RO6954354750", "RO86", "RO6760709240134",
        "BG339264914", "BG513075278", "BG2173485457",
        "EE475187273", "EE813894571",
        "LV83020583577", "LV87301594116", "LV25071408970",
        "LT171527714", "LT198437312", "LT651046544014",
        "EL236312651", "EL314714576",
        "CY30343000A", "CY31609130A",
        "MT13797393", "MT13919074",
        "XI727817758", "XI865265541", "XI626686940944",
        "SK1983589520", "SK6894390613",
    )

    private val invalid = listOf(
        "ES77499556A", "ES89687647A", "ESB81135119", "ESQ6712343A", "ESX1796484J", "ESK6141579X",
        "IT37569191100", "IT41514289240",
        "NL323396898B74", "NL885174357B45",
        "BE0367716694", "BE0997931889",
        "PT120201226", "PT406758811",
        "IE0565090W", "IE8453284W", "IE0E68127F", "IE0922834KB",
        "DK57649543", "DK67618791",
        "FI72850502", "FI78806772",
        "SE150452482101", "SE682318414801",
        "LU23031656", "LU94771756",
        "SI30923992", "SI67814418",
        "HR71095914825", "HR80296157952",
        "HU64745429", "HU74029087",
        "RO49978530", "RO97", "RO1890210191763",
        "BG053866745", "BG437971010", "BG5530008390",
        "EE368996670", "EE653180945",
        "LV54942089995", "LV71249827507", "LV21081204810",
        "LT417353512", "LT503828616", "LT451307871516",
        "EL404634396", "EL534823132",
        "CY11186578A", "CY75245824A",
        "MT49247783", "MT97850548",
        "XI060148162", "XI560486562", "XI787909165549",
        "SK1983589521", "SK9309064974", "SK7016480801", "SK0895446915",
    )

    @TestFactory
    fun `every synthetic valid number is accepted`(): List<DynamicTest> =
        valid.map { DynamicTest.dynamicTest(it) { assertThat(EuVatChecksums.valid(it)).describedAs(it).isTrue() } }

    @TestFactory
    fun `every synthetic corrupted number is rejected`(): List<DynamicTest> =
        invalid.map { DynamicTest.dynamicTest(it) { assertThat(EuVatChecksums.valid(it)).describedAs(it).isFalse() } }

    @Test
    fun `every VIES prefix has a rule or a named reason, and an unknown prefix is rejected`() {
        val prefixes = "AT BE BG CY CZ DE DK EE EL ES FI FR HR HU IE IT LT LU LV MT NL PL PT RO SE SI SK XI".split(" ")
        // Each prefix must be decided by its own rule: a garbage national part is rejected for all of them
        // unless the state is declared shape-only.
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
}
