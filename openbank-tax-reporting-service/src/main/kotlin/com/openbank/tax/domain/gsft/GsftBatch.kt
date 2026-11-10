// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.domain.model.requireValid
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/*
 * The depositor data batch for the Garanční systém finančního trhu, rendered from an
 * [InsuredClaimsAssembly] (issue #15356).
 *
 * Every rule here is the annex of vyhláška č. 71/2011 Sb. (Příloha, part 1), read from the
 * consolidated text — not a paraphrase:
 *   1.1.1   file name = 3-letter prefix + 4-character bank code + 2-letter branch country (empty
 *           for Czech branches) + "." + 5-digit batch number starting at 00001
 *   1.1.3   a file whose record count in DIW is zero MUST NOT exist; DIW is always present
 *   1.1.6.1 CSV in CP-1250, records end in CR LF, fields separated by a comma, no trailing comma;
 *           a text containing a comma is enclosed in double quotes and an embedded quote doubled;
 *           an amount is 15 digits with leading zeros and no separator, the last two being
 *           hundredths; every field is present even when empty
 *   1.2.1   DIW — one header record; N_ERROR is 0 for a verification run; T_PAYMENT is the sum of
 *           DIC.PAYMENT; TEST is "TEST" for a test tranche and "KEEN" for live data
 *   1.2.2   DIC — one record per entitled person, sorted by IDC, TYPE, ORDER_P
 *   1.2.3   DIA — one record per (owner, account) with the account's TOTAL balance
 *   1.2.4   DIL — one record per share of a joint account; an account in DIL is also in DIA under
 *           each owner, and its DIA balance is not counted again in DIC.T_CLAIM
 *
 * NOT RENDERED HERE
 *   DII (institutional accounts, § 41f odst. 7–8) — not modelled by [InsuredClaims] yet, so
 *   N_SPECIAL is 0. Compression into one ZIP and PGP encryption (1.1.6) are transport, done by the
 *   adapter that holds GSFT's public key; this file returns the plain CSV files by name.
 *   SUSPENSION (§ 41g) is left empty: a filled value requires a matching DIN control record,
 *   which this renderer does not produce.
 *
 * It FAILS rather than emitting a file GSFT would reject or, worse, accept wrongly: a field longer
 * than its maximum, a character CP-1250 cannot encode, an amount that does not fit 15 digits, a
 * negative balance (the format has no sign), or a claim with no identity.
 */

private val CP1250: Charset = Charset.forName("windows-1250")
private const val CRLF = "\r\n"
private const val AMOUNT_DIGITS = 15
private const val TEXT_MAX = 240
private const val IDC_MAX = 20
private const val ZIP_MAX = 10
private const val PHONE_MAX = 20
private const val EMAIL_MAX = 100
private const val IBAN_MAX = 24
private const val BANK_CODE_LENGTH = 4
private const val BATCH_MAX = 99_999
private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val HUNDRED = BigDecimal.ONE.movePointRight(2)
private const val FIRST_PAYOUT = "00001"
private const val SORT_KEY_FIELDS = 4

/** DIC.TYPE. */
enum class EntitledPersonType(val code: String) {
    NATURAL_PERSON("F"),
    LEGAL_PERSON("P"),
    NO_CZECH_BIRTH_NUMBER("C"),
    OTHER("X"),
}

/** Who the entitled person is. Read from party-service at render time; never stored here. */
data class DepositorIdentity(
    val partyId: UUID,
    val idc: String,
    val type: EntitledPersonType,
    val name: String,
    val street: String,
    val city: String,
    val zip: String,
    val country: String,
    val language: String,
    val cellPhone: String? = null,
    val email: String? = null,
    val note: String? = null,
)

enum class BatchMode(val code: String) { TEST("TEST"), LIVE("KEEN") }

/** DIW identity of the batch and the file names it implies (1.1.1). */
data class BatchHeader(
    val bankCode: String,
    val batchNumber: Int,
    val createdAt: LocalDateTime,
    val mode: BatchMode,
    val branchCountry: String = "",
)

/** A person left out of the batch, with the reason an operator needs to correct the data. */
data class RejectedPerson(val partyId: UUID, val reason: String)

data class GsftBatch(
    val files: Map<String, ByteArray>,
    val totalPaymentCzk: BigDecimal,
    val rejected: List<RejectedPerson>,
    /** Exclusion evidence carried from the assembly, so the delivery records what it left out. */
    val droppedSyntheticDepositors: Int = 0,
    val droppedSyntheticAccounts: Int = 0,
    val droppedOverdrawnAccounts: Int = 0,
    val excludedDepositors: Map<ExclusionReason, Int> = emptyMap(),
    val cappedClaims: Int = 0,
)

private class PersonRecords(val dic: List<String>, val dia: List<List<String>>, val dil: List<List<String>>)

object GsftBatchRenderer {

    fun render(
        assembly: InsuredClaimsAssembly,
        accounts: Collection<DepositAccount>,
        identities: Collection<DepositorIdentity>,
        rates: FxRates,
        header: BatchHeader,
    ): GsftBatch {
        val bankCode = header.bankCode
        val batchNumber = header.batchNumber
        val branchCountry = header.branchCountry
        requireValid(bankCode.length == BANK_CODE_LENGTH) { "bank code must be 4 characters, was '$bankCode'" }
        requireValid(branchCountry.isEmpty() || branchCountry.length == 2) {
            "branch country must be empty or 2 letters"
        }
        requireValid(batchNumber in 1..BATCH_MAX) { "batch number must be 1..$BATCH_MAX" }
        requireValid(rates.day == assembly.relevantDay) { "FX rates are not for the relevant day" }
        val who = identities.associateBy { it.partyId }
        requireValid(who.size == identities.size) { "an identity is listed twice for one party" }
        val byAccount = accounts.associateBy { it.accountId }
        requireValid(byAccount.size == accounts.size) { "an account is listed twice" }
        // ORDER_P: the first payout of each entitled person. Later tranches for the same person
        // (a corrected or supplementary payout) take 2, 3, ... and are not produced here.
        val order = FIRST_PAYOUT

        // One entitled person's data must never stop the whole delivery — the bank has 3 working
        // days. A person whose records cannot be rendered is left out of THIS batch and listed in
        // [GsftBatch.rejected] for an operator to correct and send in a follow-up tranche.
        val rejected = mutableListOf<RejectedPerson>()
        val dic = mutableListOf<List<String>>()
        val dia = mutableListOf<List<String>>()
        val dil = mutableListOf<List<String>>()
        var totalPayment = BigDecimal.ZERO
        val ordered = assembly.claims.sortedWith(
            compareBy({ who[it.partyId]?.idc ?: "" }, {
                who[it.partyId]?.type?.code
                    ?: ""
            }),
        )
        for (claim in ordered) {
            val records = try {
                personRecords(claim, who[claim.partyId], byAccount, rates, order)
            } catch (e: TaxValidationException) {
                rejected += RejectedPerson(claim.partyId, e.message ?: "invalid record")
                continue
            }
            dic += records.dic
            dia += records.dia
            dil += records.dil
            totalPayment = totalPayment.add(claim.compensationCzk)
        }
        // Sorted by IDC, TYPE, ORDER_P, ACCOUNT (1.2.3, 1.2.4): the record's fields after IDR.
        val byKey = compareBy<List<String>> { it.drop(1).take(SORT_KEY_FIELDS).joinToString("\u0000") }
        dia.sortWith(byKey)
        dil.sortWith(byKey)

        val diw = diwRecord(header, dic.size, dia.size, dil.size, totalPayment)

        val suffix = "$bankCode$branchCountry.${String.format(Locale.ROOT, "%05d", batchNumber)}"
        val files = linkedMapOf("DIW$suffix" to encode(listOf(diw)))
        if (dic.isNotEmpty()) files["DIC$suffix"] = encode(dic)
        if (dia.isNotEmpty()) files["DIA$suffix"] = encode(dia)
        if (dil.isNotEmpty()) files["DIL$suffix"] = encode(dil)
        return GsftBatch(
            files = files,
            totalPaymentCzk = totalPayment.setScale(2, RoundingMode.HALF_UP),
            rejected = rejected,
            droppedSyntheticDepositors = assembly.droppedSyntheticDepositors,
            droppedSyntheticAccounts = assembly.droppedSyntheticAccounts,
            droppedOverdrawnAccounts = assembly.droppedOverdrawnAccounts,
            excludedDepositors = assembly.excludedDepositors,
            cappedClaims = assembly.cappedClaims,
        )
    }

    private fun personRecords(
        claim: InsuredClaim,
        id: DepositorIdentity?,
        byAccount: Map<UUID, DepositAccount>,
        rates: FxRates,
        order: String,
    ): PersonRecords {
        val identity = id ?: throw TaxValidationException("no identity for entitled person ${claim.partyId}")
        val dia = mutableListOf<List<String>>()
        val dil = mutableListOf<List<String>>()
        for (accountId in claim.accountIds) {
            val account =
                byAccount[accountId] ?: throw TaxValidationException("claim references unknown account $accountId")
            val total = account.total()
            val totalCzk = account.totalCzk(rates)
            val key = listOf(identity.idc, identity.type.code, order, GsftFields.iban(account.iban), account.currency)
            dia += listOf("T") + key + listOf(GsftFields.amount(total), GsftFields.amount(totalCzk))
            if (account.holders.size > 1) {
                // Both columns split in minor units, so each account's shares sum to its DIA row.
                dil +=
                    listOf("O") + key +
                    listOf(
                        GsftFields.amount(account.split(total).getValue(claim.partyId)),
                        GsftFields.amount(account.split(totalCzk).getValue(claim.partyId)),
                    )
            }
        }
        val dic = dicRecord(claim, identity, order)
        (listOf(dic) + dia + dil).flatten().forEach { field ->
            requireValid(field.none { it < ' ' }) {
                "a field contains a control character (CR, LF or below 0x20): '${field.filter { it >= ' ' }}'"
            }
            requireValid(CP1250.newEncoder().canEncode(field)) {
                "a field contains a character CP-1250 cannot encode: '$field'"
            }
        }
        return PersonRecords(dic, dia, dil)
    }

    private fun diwRecord(header: BatchHeader, clients: Int, accounts: Int, shares: Int, totalPayment: BigDecimal) =
        listOf(
            "R",
            header.createdAt.format(TIMESTAMP),
            String.format(Locale.ROOT, "%05d", header.batchNumber),
            GsftFields.count(clients),
            GsftFields.count(accounts),
            GsftFields.count(shares),
            GsftFields.count(0),
            GsftFields.count(0),
            GsftFields.count(0),
            GsftFields.amount(totalPayment),
            "1",
            header.mode.code,
        )

    private fun dicRecord(claim: InsuredClaim, id: DepositorIdentity, order: String): List<String> = listOf(
        "E",
        GsftFields.nonBlank(GsftFields.text(id.idc, IDC_MAX, "IDC"), "IDC"),
        id.type.code,
        order,
        if (id.type == EntitledPersonType.NATURAL_PERSON) "1" else "0",
        GsftFields.nonBlank(GsftFields.text(id.name, TEXT_MAX, "NAME"), "NAME"),
        GsftFields.text(id.street, TEXT_MAX, "STREET"),
        GsftFields.text(id.city, TEXT_MAX, "CITY"),
        GsftFields.text(id.zip, ZIP_MAX, "ZIP"),
        GsftFields.exactly(id.country, 2, "COUNTRY"),
        GsftFields.amount(claim.eligibleBalanceCzk.max(BigDecimal.ZERO)),
        GsftFields.amount(claim.compensationCzk),
        GsftFields.text(id.note.orEmpty(), TEXT_MAX, "NOTE"),
        "",
        GsftFields.text(id.cellPhone.orEmpty(), PHONE_MAX, "CELL_PHONE"),
        GsftFields.text(id.email.orEmpty(), EMAIL_MAX, "EMAIL"),
        GsftFields.exactly(id.language, 2, "LANGUAGE"),
    )

    private fun encode(records: List<List<String>>): ByteArray {
        val text = records.joinToString(separator = "") { r -> r.joinToString(",") { GsftFields.csvField(it) } + CRLF }
        val encoder = CP1250.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer: ByteBuffer = try {
            encoder.encode(CharBuffer.wrap(text))
        } catch (e: CharacterCodingException) {
            requireValid(false) { "a field contains a character CP-1250 cannot encode: ${e.message}" }
            error("unreachable")
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

/** Field encodings of the decree annex, 1.1.6.1.2. */
internal object GsftFields {
    /** 15 digits, hundredths, no separator. Rounds half-up to hundredths first. */
    fun amount(value: BigDecimal): String {
        val hundredths = value.setScale(2, RoundingMode.HALF_UP).multiply(HUNDRED).toBigIntegerExact()
        requireValid(hundredths.signum() >= 0) { "amount $value is negative; the format carries no sign" }
        val digits = hundredths.toString()
        requireValid(digits.length <= AMOUNT_DIGITS) { "amount $value does not fit $AMOUNT_DIGITS digits" }
        return digits.padStart(AMOUNT_DIGITS, '0')
    }

    fun count(n: Int) = String.format(Locale.ROOT, "%09d", n)

    fun iban(value: String): String {
        val compact = value.replace(" ", "")
        requireValid(compact.length <= IBAN_MAX) { "IBAN '$value' is longer than $IBAN_MAX characters" }
        return compact
    }

    fun text(value: String, max: Int, field: String): String {
        requireValid(value.length <= max) { "$field is longer than $max characters" }
        return value
    }

    fun nonBlank(value: String, field: String): String {
        requireValid(value.isNotBlank()) { "$field is blank" }
        return value
    }

    fun exactly(value: String, length: Int, field: String): String {
        requireValid(value.length == length) { "$field must be exactly $length characters, was '$value'" }
        return value
    }

    /** CSV per 1.1.6.1 — quoted only when the text contains a comma or a quote. */
    fun csvField(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
