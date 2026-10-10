// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.pension

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.tax.application.port.out.CompanyPortfolio
import com.openbank.tax.application.port.out.CompanyPortfolioPort
import com.openbank.tax.application.port.out.CompanyPortfolioPosition
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
import java.time.format.DateTimeParseException
import java.util.Optional

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
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TreasuryPortfolioDto(
    val asOf: String? = null,
    val currency: String? = null,
    val positions: List<TreasuryPositionDto>? = null,
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
            throw ReturnDataUnavailableException(
                "pension company treasury answered ${e.response.status} for the portfolio at $periodEnd",
                e,
            )
        } catch (e: ProcessingException) {
            throw ReturnDataUnavailableException("pension company treasury unreachable: ${e.message}", e)
        }
        return decode(dto)
    }

    companion object {
        /** Wire -> port; any absent or unparsable field refuses the whole answer, never a default. */
        fun decode(dto: TreasuryPortfolioDto): CompanyPortfolio {
            fun bad(what: String): Nothing =
                throw ReturnDataUnavailableException("pension company treasury portfolio is malformed: $what")
            val asOf = try {
                LocalDate.parse(dto.asOf ?: bad("asOf absent"))
            } catch (e: DateTimeParseException) {
                bad("asOf '${dto.asOf}' is not a date (${e.message})")
            }
            val positions = (dto.positions ?: bad("positions absent")).mapIndexed { i, p ->
                fun dec(name: String, v: String?) = v?.toBigDecimalOrNull() ?: bad("positions[$i].$name '$v'")
                CompanyPortfolioPosition(
                    instrumentClass = p.instrumentClass ?: bad("positions[$i].instrumentClass absent"),
                    isin = p.isin ?: bad("positions[$i].isin absent"),
                    quantity = dec("quantity", p.quantity),
                    valuation = dec("valuation", p.valuation),
                    valuationCurrency = p.valuationCurrency ?: bad("positions[$i].valuationCurrency absent"),
                )
            }
            return CompanyPortfolio(asOf, dto.currency ?: bad("currency absent"), positions)
        }
    }
}

/** One row of PSP 34-12 PS: a holding, by instrument class and ISIN. */
data class Psp3412PsRow(
    val instrumentClass: String,
    val isin: String,
    val quantity: BigDecimal,
    val valuation: BigDecimal,
)

/** The assembled return: instrument-level [rows] and the catalogue [datapoints] derived from them. */
data class Psp3412PsReport(val asOf: LocalDate, val rows: List<Psp3412PsRow>, val datapoints: Map<String, BigDecimal>)

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
        val duplicate = portfolio.positions.groupBy { it.isin }.filterValues { it.size > 1 }.keys
        if (duplicate.isNotEmpty()) refuse("ISIN(s) $duplicate reported more than once")

        val rows = portfolio.positions
            .map { Psp3412PsRow(it.instrumentClass, it.isin, it.quantity, it.valuation) }
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
        return Psp3412PsReport(periodEnd, rows, datapoints)
    }
}
