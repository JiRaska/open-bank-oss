// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import com.openbank.tax.application.port.out.GsftSnapshotPort
import com.openbank.tax.application.port.out.ReturnAssemblyData
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.domain.gsft.BatchHeader
import com.openbank.tax.domain.gsft.BatchMode
import com.openbank.tax.domain.gsft.GsftBatch
import com.openbank.tax.domain.gsft.GsftBatchRenderer
import com.openbank.tax.domain.gsft.InsuredClaims
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnLine
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Optional

/**
 * Assembles the GSFT depositor batch for one relevant day onto the statutory-return lifecycle
 * (catalogue `cz-gsft`, issue #15356): snapshot → [com.openbank.tax.application.port.out.GsftSnapshot.requireComplete]
 * → [InsuredClaims.assemble] → [GsftBatchRenderer.render] → [GsftPackage.zip].
 *
 * The datapoints are the batch's own figures (record counts read back from the rendered files, so
 * they are what GSFT will count) and the evidence of what the assembly left out. The lines are one
 * per decree file plus one for the ZIP, each with its SHA-256: the four-eyes approval therefore
 * attests the exact package, and the delivery route refuses an archive that no longer hashes to it.
 *
 * A snapshot with any unsourced field is REFUSED with the gap list — today that is every
 * production snapshot (`GsftSnapshotAdapter.UNRESOLVED_GAPS`). Incomplete depositor data is never
 * filed. The mode is the return code: GSFT-DIW-TEST renders DIW.TEST = TEST, and KEEN (live data)
 * is rendered only for GSFT-DIW-LIVE, which an operator must name explicitly.
 */
@ApplicationScoped
class GsftReturnDataAdapter(
    private val snapshots: GsftSnapshotPort,
    private val packages: GsftPackageStore,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.gsft.bank-code") private val bankCode: Optional<String>,
    @ConfigProperty(name = "openbank.gsft.branch-country") private val branchCountry: Optional<String>,
) {
    // `entityId` is part of the router's adapter signature; GSFT reports for the company alone and
    // numbers batches per bank code, so nothing here depends on it.
    @Suppress("UnusedParameter")
    suspend fun fetch(
        catalogue: ReturnCatalogue,
        definition: ReturnDefinition,
        entityId: String,
        period: ReportingPeriod,
    ): ReturnAssemblyData {
        val mode = MODES[definition.code]
            ?: throw ReturnDataUnavailableException("catalogue ${catalogue.id} has no GSFT mode for ${definition.code}")
        val bank = bankCode.map { it.trim() }.filter { it.isNotEmpty() }.orElseThrow {
            ReturnDataUnavailableException("openbank.gsft.bank-code is not configured; no GSFT batch can be named")
        }
        val snapshot = snapshots.snapshot(period.endDate).requireComplete()
        val rates = checkNotNull(snapshot.rates) { "a complete GSFT snapshot carries rates" }
        val assembly = InsuredClaims.assemble(
            snapshot.relevantDay,
            snapshot.depositors,
            snapshot.accounts,
            rates,
            snapshot.qualifyingCredits,
        ).let { claims ->
            claims.copy(
                droppedSyntheticAccounts = claims.droppedSyntheticAccounts + snapshot.syntheticAccountsDroppedAtSource,
            )
        }
        val createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val header = BatchHeader(
            bankCode = bank,
            batchNumber = packages.nextBatchNumber(bank),
            createdAt = LocalDateTime.ofInstant(createdAt, PRAGUE),
            mode = mode,
            branchCountry = branchCountry.map { it.trim() }.orElse(""),
        )
        val batch = GsftBatchRenderer.render(
            assembly,
            snapshot.accounts,
            snapshot.identities,
            rates,
            header,
            snapshot.specialShares,
        )
        val archive = GsftPackage.zip(batch.files)
        val archiveName = GsftPackage.archiveName(batch.files.keys.first { it.startsWith("DIW") }.drop(PREFIX))
        val archiveSha = sha256Hex(archive)
        return ReturnAssemblyData(
            values = figures(batch, header, createdAt.epochSecond),
            lines = batch.files.map { (name, bytes) -> line(KIND_FILE, name, sha256Hex(bytes), bytes.size) } +
                line(KIND_ARCHIVE, archiveName, archiveSha, archive.size),
            // The personal-data archive is stored only once its return has validated and been stored.
            onStored = { packages.put(archiveName, archive, createdAt) },
        )
    }

    private fun figures(batch: GsftBatch, header: BatchHeader, createdAtEpoch: Long): Map<String, BigDecimal> {
        fun records(prefix: String): Int = batch.files.entries.firstOrNull { it.key.startsWith(prefix) }
            ?.value?.let { bytes -> String(bytes, Charsets.ISO_8859_1).split("\r\n").size - 1 } ?: 0
        return mapOf(
            "N_CLIENT" to records("DIC"),
            "N_ACCOUNT" to records("DIA"),
            "N_COLLECTIVE" to records("DIL"),
            "N_SPECIAL" to records("DII"),
            "BATCH_NUMBER" to header.batchNumber,
            "REJECTED_PERSONS" to batch.rejected.size,
            "EXCLUDED_DEPOSITORS" to batch.excludedDepositors.values.sum(),
            "CAPPED_CLAIMS" to batch.cappedClaims,
            "SYNTHETIC_DEPOSITORS_DROPPED" to batch.droppedSyntheticDepositors,
            "SYNTHETIC_ACCOUNTS_DROPPED" to batch.droppedSyntheticAccounts,
            "OVERDRAWN_ACCOUNTS_DROPPED" to batch.droppedOverdrawnAccounts,
            "THB_CLAIMS" to batch.temporarilyHighClaims,
            "THB_REFUSED_CREDITS" to batch.refusedQualifyingCredits,
            "FILES" to batch.files.size,
        ).mapValues { BigDecimal(it.value) } + mapOf(
            "T_PAYMENT" to batch.totalPaymentCzk,
            "THB_COMPENSATION_CZK" to batch.temporarilyHighCompensationCzk,
            "CREATED_AT_EPOCH_S" to BigDecimal(createdAtEpoch),
        )
    }

    private fun line(kind: String, name: String, sha: String, size: Int) =
        ReturnLine(mapOf("kind" to kind, "name" to name, "sha256" to sha, "size" to size.toString()))

    companion object {
        const val CATALOGUE_ID = "cz-gsft"
        const val TEST_CODE = "GSFT-DIW-TEST"
        const val LIVE_CODE = "GSFT-DIW-LIVE"
        const val KIND_FILE = "FILE"
        const val KIND_ARCHIVE = "ARCHIVE"
        private const val PREFIX = 3
        private val MODES = mapOf(TEST_CODE to BatchMode.TEST, LIVE_CODE to BatchMode.LIVE)

        /** DIW.TIMESTAMP is local time; the decree's regulator is Czech. */
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")
    }
}
