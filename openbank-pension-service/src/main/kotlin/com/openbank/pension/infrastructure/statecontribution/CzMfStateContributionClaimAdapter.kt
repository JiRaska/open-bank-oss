// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.AgencyDocument
import com.openbank.pension.application.port.out.AgencyDocumentKind
import com.openbank.pension.application.port.out.ClaimReceiptLine
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.RenderedClaimBatch
import com.openbank.pension.application.port.out.StateAgencyGateway
import com.openbank.pension.application.port.out.StateIncentiveClaimPort
import com.openbank.pension.domain.incentive.ClaimBatch
import com.openbank.pension.domain.incentive.IncentiveClaim
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

/**
 * The CZ state-contribution claim channel (ADR-0334, #12382), replacing S3's
 * `agency-monthly-batch-v0` placeholder. The pack `cz-dps-v1` names its format.
 *
 * - Filing period = the calendar QUARTER (ZDPS §16(2)). Entitlement stays monthly (§14(1)), so
 *   each claim is one month, and a quarter's months go in one application.
 * - The application is rendered in [CzMfStateContributionFormat] (a placeholder; MF's spec is not
 *   public). Once the batch is durably filed, it is handed to the [StateAgencyGateway]. The
 *   channel reference the gateway answers is
 *   stored on the batch. `MANUAL:` means nothing was transmitted.
 * - The result (P1/P3) is one aggregate payment split per line. Lines that do not add up to the
 *   aggregate are refused whole.
 */
@ApplicationScoped
class CzMfStateContributionClaimAdapter(
    private val gateway: StateAgencyGateway,
    private val directory: ContractFundingDirectory,
    /** The pension company's IČO for the header (§16(3)). Absent means no filing; it never defaults. */
    @param:ConfigProperty(name = "openbank.pension.state-contribution.cz.company-ico")
    private val configuredIco: Optional<String>,
) : StateIncentiveClaimPort {

    override val claimFormat: String = CzMfStateContributionFormat.FORMAT

    override fun filingPeriod(claimPeriod: YearMonth): YearMonth = CzStateContributionCalendar.quarterStart(claimPeriod)

    override fun fileable(claimPeriod: YearMonth, today: LocalDate): Boolean =
        CzStateContributionCalendar.fileable(claimPeriod, today)

    override suspend fun submit(
        period: YearMonth,
        claims: List<IncentiveClaim>,
        references: Map<UUID, String>,
    ): RenderedClaimBatch {
        val quarter = CzStateContributionCalendar.quarterStart(period)
        claims.forEach {
            require(CzStateContributionCalendar.quarterStart(it.period) == quarter) {
                "claim ${it.id} for ${it.period} is not in the quarter of $quarter"
            }
        }
        val lines = claims.map { c ->
            CzMfStateContributionFormat.ApplicationLine(
                claimId = c.id,
                contractReference = references.getValue(c.contractId),
                participantRef = directory.find(c.contractId)?.participantPartyId?.toString()
                    ?: error("contract ${c.contractId} of claim ${c.id} not found"),
                month = c.period,
                contribution = c.basis,
                requested = c.claimedAmount,
            )
        }
        val companyIco = configuredIco.filter { ICO.matches(it) }.orElseThrow {
            IllegalStateException(
                "openbank.pension.state-contribution.cz.company-ico (8 digits) is not configured; nothing filed",
            )
        }
        return RenderedClaimBatch(CzMfStateContributionFormat.application(companyIco, quarter, lines), null)
    }

    override suspend fun transmit(batch: ClaimBatch): String {
        val quarter = CzStateContributionCalendar.quarterStart(batch.period)
        val receipt = gateway.transmit(
            AgencyDocument(
                AgencyDocumentKind.CLAIM_APPLICATION,
                claimFormat,
                "SP-${quarter.year}Q${CzStateContributionCalendar.quarterOf(quarter)}-${batch.id}.txt",
                batch.payload,
            ),
        )
        return receipt.channelReference
    }

    override fun parseReceipt(payload: String): List<ClaimReceiptLine> =
        CzMfStateContributionFormat.parseApplicationResult(payload)

    private companion object {
        val ICO = Regex("^[0-9]{8}$")
    }
}
