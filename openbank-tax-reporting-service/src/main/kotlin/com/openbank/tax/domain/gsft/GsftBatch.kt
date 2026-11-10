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
 *   1.2.5   DII — one record per beneficiary (podílník) share of a special-regime account: IDR "D",
 *           IDC, TYPE, ORDER_P, ACCOUNT, CURRENCY, AMOUNT, AMOUNT_CZK, sorted by IDC, TYPE, ORDER_P,
 *           ACCOUNT. The account must also be in DIA under its OWNER; the beneficiary must NOT be in
 *           DIA. A person whose funds are only in special-regime accounts has DIC.PAYMENT 0.
 *
 * DII — WHAT IS NOT DECIDED HERE (docs/gsft-depositor-data.md, "unverified")
 *   - The coefficient k (1.2.5.1), applied when a beneficiary holds funds through TWO OR MORE
 *     owners, is not computed: such a beneficiary FAILS the batch rather than being reported with
 *     an amount the decree would compute differently.
 *   - A beneficiary who is also a depositor in their own right fails the batch: whether the DII
 *     shares merge into that person's single DIC record is not stated by the annex.
 *   - DIC.T_CLAIM of a beneficiary-only person is the sum of their DII shares in CZK (the annex
 *     says only "sum of all accounts under I_IDC"); unverified.
 *
 * NOT RENDERED HERE
 *   Compression into one ZIP and PGP encryption (1.1.6) are transport, done by the
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
private const val DIC_SORT_FIELDS = 3
private const val MONEY_SCALE = 2
private const val SHARE_SCALE = 12

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

/**
 * One beneficiary's share of a special-regime account (DII). [amount] is in the account currency;
 * the CZK column converts it at the relevant day's rate.
 */
data class SpecialRegimeShare(val beneficiary: DepositorIdentity, val accountId: UUID, val amount: BigDecimal)

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
    val temporarilyHighClaims: Int = 0,
    val temporarilyHighCompensationCzk: BigDecimal = BigDecimal.ZERO,
    val refusedQualifyingCredits: Int = 0,
)

private class PersonRecords(val dic: List<String>, val dia: List<List<String>>, val dil: List<List<String>>)

private class RenderedPeople(val rejected: List<RejectedPerson>, val accounts: Set<UUID>, val totalPayment: BigDecimal)

private class ClaimRecords(
    val dic: MutableList<List<String>>,
    val dia: MutableList<List<String>>,
    val dil: MutableList<List<String>>,
    val people: RenderedPeople,
)

private class SpecialRecords(
    val dic: List<List<String>>,
    val dii: MutableList<List<String>>,
    val rejected: List<RejectedPerson>,
)

object GsftBatchRenderer {

    fun render(
        assembly: InsuredClaimsAssembly,
        accounts: Collection<DepositAccount>,
        identities: Collection<DepositorIdentity>,
        rates: FxRates,
        header: BatchHeader,
        specialShares: Collection<SpecialRegimeShare> = emptyList(),
    ): GsftBatch {
        validateHeader(header)
        requireValid(rates.day == assembly.relevantDay) { "FX rates are not for the relevant day" }
        val who = identities.associateBy { it.partyId }
        requireValid(who.size == identities.size) { "an identity is listed twice for one party" }
        val byAccount = accounts.associateBy { it.accountId }
        requireValid(byAccount.size == accounts.size) { "an account is listed twice" }
        // ORDER_P: the first payout of each entitled person. Later tranches for the same person
        // (a corrected or supplementary payout) take 2, 3, ... and are not produced here.
        val order = FIRST_PAYOUT

        val claimed = renderClaims(assembly, who, byAccount, rates, order)
        val dic = claimed.dic
        val dia = claimed.dia
        val dil = claimed.dil
        val people = claimed.people
        val rejected = people.rejected
        val renderedAccounts = people.accounts
        val totalPayment = people.totalPayment
        // Sorted by IDC, TYPE, ORDER_P, ACCOUNT (1.2.3, 1.2.4): the record's fields after IDR.
        val byKey = compareBy<List<String>> { it.drop(1).take(SORT_KEY_FIELDS).joinToString("\u0000") }
        dia.sortWith(byKey)
        dil.sortWith(byKey)

        val dii = specialRecords(
            byAccount,
            specialShares,
            rates,
            order,
            assembly.claims.map { it.partyId }.toSet(),
            renderedAccounts,
        )
        dic += dii.dic
        dic.sortWith(compareBy { it.drop(1).take(DIC_SORT_FIELDS).joinToString("\u0000") })
        dii.dii.sortWith(byKey)

        val diw = diwRecord(header, dic.size, dia.size, dil.size, dii.dii.size, totalPayment)

        val suffix = "${header.bankCode}${header.branchCountry}.${String.format(
            Locale.ROOT,
            "%05d",
            header.batchNumber,
        )}"
        return GsftBatch(
            files = files(
                suffix,
                listOf(
                    "DIW" to listOf(diw),
                    "DIC" to dic,
                    "DIA" to dia,
                    "DIL" to dil,
                    "DII" to dii.dii,
                ),
            ),
            totalPaymentCzk = totalPayment.setScale(2, RoundingMode.HALF_UP),
            rejected = rejected + dii.rejected,
            droppedSyntheticDepositors = assembly.droppedSyntheticDepositors,
            droppedSyntheticAccounts = assembly.droppedSyntheticAccounts,
            droppedOverdrawnAccounts = assembly.droppedOverdrawnAccounts,
            excludedDepositors = assembly.excludedDepositors,
            cappedClaims = assembly.cappedClaims,
            temporarilyHighClaims = assembly.temporarilyHighClaims,
            temporarilyHighCompensationCzk = assembly.temporarilyHighCompensationCzk,
            refusedQualifyingCredits = assembly.refusedQualifyingCredits,
        )
    }

    /** 1.1.3: DIW always exists; any other file with zero records must not. */
    private fun files(suffix: String, records: List<Pair<String, List<List<String>>>>): Map<String, ByteArray> =
        records.filter { (prefix, rows) -> prefix == "DIW" || rows.isNotEmpty() }
            .associateTo(linkedMapOf()) { (prefix, rows) -> "$prefix$suffix" to encode(rows) }

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

    /**
     * DII rows plus a DIC record (PAYMENT 0) per beneficiary. Every failure here is batch-level:
     * a DII row that does not reconcile with its owner's DIA row would misstate the owner's payout.
     */
    private fun renderClaims(
        assembly: InsuredClaimsAssembly,
        who: Map<UUID, DepositorIdentity>,
        byAccount: Map<UUID, DepositAccount>,
        rates: FxRates,
        order: String,
    ): ClaimRecords {
        // One entitled person's data must never stop the whole delivery — the bank has 3 working
        // days. A person whose records cannot be rendered is left out of THIS batch and listed in
        // [GsftBatch.rejected] for an operator to correct and send in a follow-up tranche.
        val rejected = mutableListOf<RejectedPerson>()
        val dic = mutableListOf<List<String>>()
        val dia = mutableListOf<List<String>>()
        val dil = mutableListOf<List<String>>()
        val renderedAccounts = mutableSetOf<UUID>()
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
            renderedAccounts += claim.accountIds
            totalPayment = totalPayment.add(claim.compensationCzk)
        }
        return ClaimRecords(dic, dia, dil, RenderedPeople(rejected, renderedAccounts, totalPayment))
    }

    private fun validateHeader(header: BatchHeader) {
        requireValid(header.bankCode.length == BANK_CODE_LENGTH) {
            "bank code must be 4 characters, was '${header.bankCode}'"
        }
        requireValid(header.branchCountry.isEmpty() || header.branchCountry.length == 2) {
            "branch country must be empty or 2 letters"
        }
        requireValid(header.batchNumber in 1..BATCH_MAX) { "batch number must be 1..$BATCH_MAX" }
    }

    /** [reported] holds only accounts actually rendered in DIA: a rejected owner takes theirs out. */
    private fun validateShares(
        byAccount: Map<UUID, DepositAccount>,
        shares: Collection<SpecialRegimeShare>,
        depositorIds: Set<UUID>,
        reported: Set<UUID>,
    ) {
        shares.forEach { share ->
            val account = byAccount[share.accountId]
            requireValid(account != null && account.specialRegime) {
                "DII share references ${share.accountId}, which is not a special-regime account"
            }
            requireValid(share.accountId in reported) {
                "special-regime account ${share.accountId} is not in DIA under its owner"
            }
            requireValid(share.beneficiary.partyId !in depositorIds) {
                "beneficiary ${share.beneficiary.partyId} is also a depositor; merging DII into DIC is unresolved"
            }
            requireValid(share.amount.signum() >= 0) { "DII share of ${share.accountId} is negative" }
            requireValid(share.amount.stripTrailingZeros().scale() <= MONEY_SCALE) {
                "DII share of ${share.accountId} is finer than hundredths"
            }
        }
        shares.groupBy { it.accountId }.forEach { (accountId, of) ->
            val sum = of.fold(BigDecimal.ZERO) { s, x -> s.add(x.amount) }
            requireValid(sum <= byAccount.getValue(accountId).total()) {
                "DII shares of $accountId exceed the account balance"
            }
        }
    }

    private fun specialRecords(
        byAccount: Map<UUID, DepositAccount>,
        shares: Collection<SpecialRegimeShare>,
        rates: FxRates,
        order: String,
        depositorIds: Set<UUID>,
        reported: Set<UUID>,
    ): SpecialRecords {
        if (shares.isEmpty()) return SpecialRecords(emptyList(), mutableListOf(), emptyList())
        validateShares(byAccount, shares, depositorIds, reported)
        val czk = shares.groupBy { it.accountId }.flatMap { (accountId, of) ->
            of.zip(GsftShareSplit.shareCzk(byAccount.getValue(accountId), of, rates))
        }.toMap()
        val rows = mutableListOf<List<String>>()
        val dic = mutableListOf<List<String>>()
        val rejected = mutableListOf<RejectedPerson>()
        shares.groupBy { it.beneficiary.partyId }.forEach { (_, of) ->
            val owners = of.map { s -> byAccount.getValue(s.accountId).holders.map { it.partyId }.toSet() }.toSet()
            requireValid(owners.size == 1) {
                "beneficiary ${of.first().beneficiary.partyId} holds funds through several owners; " +
                    "coefficient k (annex 1.2.5.1) is not computed"
            }
            val id = of.first().beneficiary
            requireValid(of.all { it.beneficiary == id }) { "beneficiary ${id.partyId} has two identities" }
            // Like an owner, one beneficiary's bad data rejects that beneficiary only.
            try {
                val mine = of.map { s ->
                    val account = byAccount.getValue(s.accountId)
                    listOf(
                        "D",
                        GsftFields.nonBlank(GsftFields.text(id.idc, IDC_MAX, "IDC"), "IDC"),
                        id.type.code,
                        order,
                        GsftFields.iban(account.iban),
                        account.currency,
                        GsftFields.amount(s.amount),
                        GsftFields.amount(czk.getValue(s)),
                    )
                }
                val claim = of.fold(BigDecimal.ZERO.setScale(MONEY_SCALE)) { sum, x -> sum.add(czk.getValue(x)) }
                val record = dicRecord(InsuredClaim(id.partyId, claim, BigDecimal.ZERO, emptyList()), id, order)
                (listOf(record) + mine).flatten().forEach { field ->
                    requireValid(field.none { it < ' ' }) { "a DII field contains a control character" }
                    requireValid(CP1250.newEncoder().canEncode(field)) { "a DII field cannot be encoded in CP-1250" }
                }
                rows += mine
                dic += record
            } catch (e: TaxValidationException) {
                rejected += RejectedPerson(id.partyId, e.message ?: "invalid DII record")
            }
        }
        val keys = rows.map { it.drop(1).take(SORT_KEY_FIELDS) }
        requireValid(keys.toSet().size == keys.size) { "a beneficiary is listed twice on one special account" }
        return SpecialRecords(dic, rows, rejected)
    }

    private fun diwRecord(
        header: BatchHeader,
        clients: Int,
        accounts: Int,
        shares: Int,
        special: Int,
        totalPayment: BigDecimal,
    ) = listOf(
        "R",
        header.createdAt.format(TIMESTAMP),
        String.format(Locale.ROOT, "%05d", header.batchNumber),
        GsftFields.count(clients),
        GsftFields.count(accounts),
        GsftFields.count(shares),
        GsftFields.count(special),
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

/** DII CZK amounts, reconciled with the owner's DIA row. */
internal object GsftShareSplit {
    /**
     * CZK of each share, split from the account's DIA CZK total in haléře by largest remainder (the
     * unallocated rest of the account is the last part), so DII AMOUNT_CZK never sums past DIA.
     */
    fun shareCzk(account: DepositAccount, of: List<SpecialRegimeShare>, rates: FxRates): List<BigDecimal> {
        val total = account.total()
        val rest = total.subtract(of.fold(BigDecimal.ZERO) { s, x -> s.add(x.amount) })
        val parts = of.map { it.amount } + rest
        if (total.signum() == 0) return of.map { BigDecimal.ZERO.setScale(MONEY_SCALE) }
        return weightedSplit(account.totalCzk(rates), parts, total).dropLast(1)
    }

    /** [amount] split in haléře in proportion parts/whole, largest remainder, ties to list order. */
    private fun weightedSplit(amount: BigDecimal, parts: List<BigDecimal>, whole: BigDecimal): List<BigDecimal> {
        val minor = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY).unscaledValue()
        val exact = parts.map { BigDecimal(minor).multiply(it).divide(whole, SHARE_SCALE, RoundingMode.HALF_EVEN) }
        val floors = exact.map { it.setScale(0, RoundingMode.FLOOR).toBigIntegerExact() }
        var leftover = minor.subtract(floors.fold(java.math.BigInteger.ZERO, java.math.BigInteger::add)).toInt()
        val result = floors.toMutableList()
        val order = parts.indices.sortedWith(
            compareByDescending<Int> { exact[it].subtract(BigDecimal(floors[it])) }.thenBy { it },
        )
        for (i in order) {
            if (leftover <= 0) break
            result[i] = result[i].add(java.math.BigInteger.ONE)
            leftover--
        }
        return result.map { BigDecimal(it, MONEY_SCALE) }
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
