// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.pension

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.iso20022.HoldingQuantityType
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.tax.application.port.out.CompanyPortfolio
import com.openbank.tax.application.port.out.CompanyPortfolioPort
import com.openbank.tax.application.port.out.CompanyPortfolioPosition
import com.openbank.tax.application.port.out.CompanyPortfolioSourceStatement
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * The pension company's OWN treasury instance (`treasury-pension-co`, ADR-0337): its portfolio at a
 * period end. The configKey is the provider module's; the URL names the company's deployment
 * (`PENSION_COMPANY_TREASURY_URL`), never the bank's treasury.
 */
@Path("/api/v1/treasury/portfolio")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "treasury-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface TreasuryPortfolioClient {
    @GET
    @Path("/period-end")
    suspend fun periodEnd(@QueryParam("date") date: String): TreasuryPortfolioDto
}

/** Quantities and valuations arrive as decimal STRINGS (no binary-float rounding on the wire). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TreasuryPositionDto(
    val instrumentClass: String? = null,
    val isin: String? = null,
    val quantity: String? = null,
    val valuation: String? = null,
    val valuationCurrency: String? = null,
    val quantityBasis: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TreasuryPortfolioDto(
    val entity: String? = null,
    val asOf: String? = null,
    val currency: String? = null,
    val positions: List<TreasuryPositionDto>? = null,
    val sourceStatements: List<TreasurySourceStatementDto>? = null,
)

/** Period-end statement provenance for one safekeeping account. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TreasurySourceStatementDto(
    val safekeepingAccount: String? = null,
    val statementUuid: String? = null,
    val statementId: String? = null,
    val version: Int? = null,
)

/**
 * Binds [CompanyPortfolioPort] to the treasury instance. With no URL configured the source is
 * absent, and PSP 34-12 PS is refused (503) with that reason — never assembled from nothing.
 * The client is resolved lazily so an unconfigured URL cannot stop the service booting.
 */
@ApplicationScoped
class TreasuryCompanyPortfolio(
    @RestClient private val client: Instance<TreasuryPortfolioClient>,
    @ConfigProperty(name = "quarkus.rest-client.treasury-service.url") private val url: Optional<String>,
    @ConfigProperty(name = "openbank.statutory-returns.company-id", defaultValue = "company")
    private val companyId: String,
) : CompanyPortfolioPort {
    override suspend fun portfolio(periodEnd: LocalDate): CompanyPortfolio {
        if (url.isEmpty || url.get().isBlank()) {
            throw ReturnDataUnavailableException(
                "the pension company's treasury is not configured as a source " +
                    "(PENSION_COMPANY_TREASURY_URL, ADR-0337)",
            )
        }
        val dto = try {
            client.get().periodEnd(periodEnd.toString())
        } catch (e: WebApplicationException) {
            throw unavailable(e.response.status, periodEnd, e)
        } catch (e: ProcessingException) {
            throw ReturnDataUnavailableException("pension company treasury unreachable: ${e.message}", e)
        }
        return decode(dto, companyId)
    }

    companion object {
        /**
         * A refusal by the treasury, as the reason the return is unavailable (503). 409 is the
         * provider's "one or more safekeeping accounts has no statement for that date"
         * (PORTFOLIO_SNAPSHOT_MISSING — never an empty list, ADR-0337 amendment): a missing
         * semt.002 upload, which an operator fixes by uploading it, rather than a status code.
         */
        fun unavailable(status: Int, periodEnd: LocalDate, cause: Throwable? = null): ReturnDataUnavailableException =
            if (status == HTTP_CONFLICT) {
                ReturnDataUnavailableException(
                    "no complete custodian statement set for $periodEnd: the pension company's treasury " +
                        "lacks a semt.002 snapshot for one or more safekeeping accounts " +
                        "(upload the missing statement, then assemble again)",
                    cause,
                )
            } else {
                ReturnDataUnavailableException(
                    "pension company treasury answered $status for the portfolio at $periodEnd",
                    cause,
                )
            }

        private const val HTTP_CONFLICT = 409

        /** Wire -> port; any absent or unparsable field refuses the whole answer, never a default. */
        fun decode(dto: TreasuryPortfolioDto, expectedEntity: String): CompanyPortfolio {
            val entity = dto.entity ?: bad("entity absent")
            if (entity != expectedEntity) {
                bad("entity '$entity' does not match configured pension company '$expectedEntity'")
            }
            val sourceStatements = decodeSourceStatements(dto.sourceStatements)
            return CompanyPortfolio(
                parseAsOf(dto.asOf),
                dto.currency ?: bad("currency absent"),
                decodePositions(dto.positions),
                sourceStatements,
            )
        }

        private fun parseAsOf(raw: String?): LocalDate = try {
            LocalDate.parse(raw ?: bad("asOf absent"))
        } catch (e: java.time.format.DateTimeParseException) {
            bad("asOf '$raw' is not a date (${e.message})")
        }

        private fun decodePositions(raw: List<TreasuryPositionDto>?): List<CompanyPortfolioPosition> =
            (raw ?: bad("positions absent")).mapIndexed(::decodePosition)

        private fun decodePosition(index: Int, position: TreasuryPositionDto): CompanyPortfolioPosition {
            val rawBasis = position.quantityBasis ?: bad("positions[$index].quantityBasis absent")
            val basis = HoldingQuantityType.entries.firstOrNull { it.name == rawBasis }
                ?: bad("positions[$index].quantityBasis '$rawBasis' is unsupported")
            return CompanyPortfolioPosition(
                instrumentClass = position.instrumentClass ?: bad("positions[$index].instrumentClass absent"),
                isin = position.isin ?: bad("positions[$index].isin absent"),
                quantity = parseDecimal(index, "quantity", position.quantity),
                valuation = parseDecimal(index, "valuation", position.valuation),
                valuationCurrency = position.valuationCurrency ?: bad("positions[$index].valuationCurrency absent"),
                quantityBasis = basis,
            )
        }

        private fun parseDecimal(index: Int, field: String, raw: String?): BigDecimal =
            raw?.toBigDecimalOrNull() ?: bad("positions[$index].$field '$raw'")

        private fun decodeSourceStatements(
            raw: List<TreasurySourceStatementDto>?,
        ): List<CompanyPortfolioSourceStatement> {
            val statements = (raw ?: bad("sourceStatements absent")).mapIndexed(::decodeSourceStatement)
            if (statements.isEmpty()) bad("sourceStatements is empty")
            val duplicateAccounts = statements.groupBy { it.safekeepingAccount }.filterValues { it.size > 1 }.keys
            if (duplicateAccounts.isNotEmpty()) {
                bad("sourceStatements repeats safekeeping account(s) $duplicateAccounts")
            }
            val duplicateUuids = statements.groupBy { it.statementUuid }.filterValues { it.size > 1 }.keys
            if (duplicateUuids.isNotEmpty()) {
                bad("sourceStatements repeats statement UUID(s) $duplicateUuids")
            }
            return statements
        }

        private fun decodeSourceStatement(
            index: Int,
            statement: TreasurySourceStatementDto,
        ): CompanyPortfolioSourceStatement {
            val account = statement.safekeepingAccount?.takeIf { it.isNotBlank() }
                ?: bad("sourceStatements[$index].safekeepingAccount absent")
            val rawUuid = statement.statementUuid ?: bad("sourceStatements[$index].statementUuid absent")
            val canonicalUuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            if (!canonicalUuid.matches(rawUuid)) {
                bad("sourceStatements[$index].statementUuid is not a canonical UUID")
            }
            val version = statement.version?.takeIf { it >= 1 }
                ?: bad("sourceStatements[$index].version must be positive")
            return CompanyPortfolioSourceStatement(account, UUID.fromString(rawUuid), statement.statementId, version)
        }

        private fun bad(what: String): Nothing =
            throw ReturnDataUnavailableException("pension company treasury portfolio is malformed: $what")
    }
}

/** One row of PSP 34-12 PS: a holding, by instrument class and ISIN. */
data class Psp3412PsRow(
    val instrumentClass: String,
    val isin: String,
    val quantity: BigDecimal,
    val valuation: BigDecimal,
    val quantityBasis: HoldingQuantityType,
)

/** The assembled return: instrument-level [rows] and the catalogue [datapoints] derived from them. */
data class Psp3412PsReport(
    val asOf: LocalDate,
    val rows: List<Psp3412PsRow>,
    val datapoints: Map<String, BigDecimal>,
    val sourceStatements: List<CompanyPortfolioSourceStatement>,
)

/**
 * ČNB PSP (ČNB) 34-12 PS — composition of the pension company's own portfolio (vyhl. 425/2012 Sb.
 * §3(1)(b)(4)). Pure: a fixed portfolio in, a deterministic report out.
 *
 * LEGAL-REVIEW: the ČNB SDAT cell-level datapoint dictionary for PSP 34-12 was NOT read
 * (docs/research/cz-pension-regulatory-reporting.md §1), so neither the instrument-class
 * breakdown below nor the datapoint ids are the official row/column codes. They are this
 * platform's own vocabulary; mapping them onto the SDAT data model is an open TODO. The research
 * also lists nominal, acquisition cost and investment-limit mapping per ISIN, which the treasury
 * portfolio contract does not carry yet — TODO, not reported here rather than reported as zero.
 */
object Psp3412PsAssembler {
    /** LEGAL-REVIEW/TODO: platform classes, not ČNB codes. An unlisted class refuses the return. */
    val INSTRUMENT_CLASSES = listOf(
        "GOVERNMENT_BOND",
        "CORPORATE_BOND",
        "EQUITY",
        "FUND_UNITS",
        "MONEY_MARKET",
        "DEPOSIT",
    )

    private val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")

    fun datapointIds(): List<String> = listOf("holdings_carrying_value", "holdings_count") +
        INSTRUMENT_CLASSES.flatMap { c -> listOf(quantityId(c), valuationId(c)) }

    fun quantityId(instrumentClass: String) = "${instrumentClass.lowercase()}_quantity"

    fun valuationId(instrumentClass: String) = "${instrumentClass.lowercase()}_valuation"

    fun assemble(portfolio: CompanyPortfolio, periodEnd: LocalDate, reportingCurrency: String): Psp3412PsReport {
        fun refuse(why: String): Nothing = throw ReturnDataUnavailableException("PSP34-12-PS: $why")
        if (portfolio.asOf != periodEnd) refuse("treasury answered as of ${portfolio.asOf}, not $periodEnd")
        if (portfolio.currency != reportingCurrency) {
            refuse("portfolio is in ${portfolio.currency}; the return is filed in $reportingCurrency")
        }
        portfolio.positions.forEach { p ->
            if (p.instrumentClass !in INSTRUMENT_CLASSES) {
                refuse("instrument class ${p.instrumentClass} of ${p.isin} has no PSP 34-12 mapping")
            }
            if (!ISIN.matches(p.isin)) refuse("'${p.isin}' is not an ISIN")
            if (p.valuationCurrency != reportingCurrency) {
                refuse("${p.isin} is valued in ${p.valuationCurrency}; no FX conversion is applied here")
            }
        }
        val mixedQuantityBasis = portfolio.positions.groupBy { it.instrumentClass }
            .filterValues { positions -> positions.map { it.quantityBasis }.distinct().size > 1 }
            .keys
        if (mixedQuantityBasis.isNotEmpty()) {
            refuse("instrument class(es) $mixedQuantityBasis mix quantity bases; class quantities cannot be added")
        }
        val duplicate = portfolio.positions.groupBy { it.isin }.filterValues { it.size > 1 }.keys
        if (duplicate.isNotEmpty()) refuse("ISIN(s) $duplicate reported more than once")

        val rows = portfolio.positions
            .map { Psp3412PsRow(it.instrumentClass, it.isin, it.quantity, it.valuation, it.quantityBasis) }
            .sortedWith(compareBy({ INSTRUMENT_CLASSES.indexOf(it.instrumentClass) }, { it.isin }))
        val byClass = rows.groupBy { it.instrumentClass }
        val datapoints = linkedMapOf(
            "holdings_carrying_value" to rows.fold(BigDecimal.ZERO) { a, r -> a + r.valuation },
            "holdings_count" to BigDecimal(rows.size),
        )
        INSTRUMENT_CLASSES.forEach { c ->
            val inClass = byClass[c].orEmpty()
            datapoints[quantityId(c)] = inClass.fold(BigDecimal.ZERO) { a, r -> a + r.quantity }
            datapoints[valuationId(c)] = inClass.fold(BigDecimal.ZERO) { a, r -> a + r.valuation }
        }
        return Psp3412PsReport(periodEnd, rows, datapoints, portfolio.sourceStatements)
    }
}
