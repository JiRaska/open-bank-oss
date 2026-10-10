// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.pension

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional

/**
 * The pension company's OWN ledger (ADR-0337): a separate ledger-service deployment holding that
 * legal entity's books, read through the same fail-closed evidence route FINREP uses
 * (`frozen-trial-balance`, ADR-0096 D1 / ADR-0097). The configKey is the provider module's;
 * the URL names the pension company's instance, never the bank's.
 */
@Path("/api/v1/ledger/periods")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface CompanyLedgerClient {
    @GET
    @Path("/{type}/{date}/frozen-trial-balance")
    suspend fun frozenTrialBalance(
        @PathParam("type") type: String,
        @PathParam("date") date: String,
    ): FrozenTrialBalanceDto
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FrozenTrialBalanceLineDto(val code: String, val type: String, val currency: String, val net: BigDecimal)

/** [balanced] nullable: an absent flag is its own fact, never read as `false` or `true` (#6011). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FrozenTrialBalanceDto(
    val period: String,
    val balanced: Boolean? = null,
    val contentHash: String? = null,
    val lines: List<FrozenTrialBalanceLineDto>,
)

/** The company's figures at a period end, summed from frozen evidence only. */
data class CompanyBookFigures(
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    val totalEquity: BigDecimal,
    val incomeYtd: BigDecimal,
    val expensesYtd: BigDecimal,
    /** Period label -> content hash of every frozen trial balance the figures were summed from. */
    val evidence: Map<String, String>,
)

/** The company's own books at a period end (ADR-0337). */
fun interface CompanyBooksPort {
    /** @throws ReturnDataUnavailableException when any period since the books opened is not frozen evidence. */
    suspend fun figures(periodEnd: LocalDate): CompanyBookFigures
}

/** Reads one frozen period trial balance; split from the arithmetic so the arithmetic is testable without HTTP. */
fun interface FrozenTrialBalanceSource {
    suspend fun frozen(type: String, anyDateInside: LocalDate): FrozenTrialBalanceDto
}

/**
 * Balances from movements (ADR-0337 D3). A frozen period trial balance is the period's MOVEMENTS
 * (ledger's `trialBalanceForPeriod` sums lines dated inside it), so a balance at a period end is
 * the sum of every frozen period since the books opened: one YEAR close per full prior year, then
 * the MONTH closes of the current year. Every one must be FROZEN LINES_V1 (ledger answers 409
 * otherwise), balanced, and in the reporting currency — a missing or partial period refuses the
 * whole answer, because a balance summed over a gap is wrong by exactly the gap.
 *
 * Sign convention is ledger's: `net = debit - credit`. Assets are debit-normal; liabilities,
 * equity and income credit-normal. Equity includes the result not yet closed to retained earnings
 * (income and expense balances since the books opened), so assets = liabilities + equity exactly
 * when every summed period balances.
 */
object CompanyBooksCalculator {
    suspend fun figures(
        source: FrozenTrialBalanceSource,
        booksOpened: LocalDate,
        currency: String,
        periodEnd: LocalDate,
    ): CompanyBookFigures {
        if (booksOpened.isAfter(periodEnd)) {
            throw ReturnDataUnavailableException("the pension company's books open on $booksOpened, after $periodEnd")
        }
        val endMonth = YearMonth.from(periodEnd)
        if (periodEnd != endMonth.atEndOfMonth()) {
            throw ReturnDataUnavailableException("$periodEnd is not a month end; frozen evidence is monthly")
        }
        val years = (booksOpened.year until periodEnd.year).map { "YEAR" to LocalDate.of(it, 1, 1) }
        val firstMonth = if (booksOpened.year ==
            periodEnd.year
        ) {
            YearMonth.from(booksOpened)
        } else {
            YearMonth.of(periodEnd.year, 1)
        }
        val months = generateSequence(firstMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(endMonth) }
            .map { "MONTH" to it.atDay(1) }.toList()
        val prior = years.map { (t, d) -> checked(source.frozen(t, d), currency) }
        val current = months.map { (t, d) -> checked(source.frozen(t, d), currency) }
        val all = (prior + current).flatMap { it.lines }
        fun net(lines: List<FrozenTrialBalanceLineDto>, type: String) =
            lines.filter { it.type == type }.fold(BigDecimal.ZERO) { a, l -> a + l.net }
        val ytd = current.flatMap { it.lines }
        return CompanyBookFigures(
            totalAssets = net(all, "ASSET"),
            totalLiabilities = net(all, "LIABILITY").negate(),
            totalEquity = (net(all, "EQUITY") + net(all, "INCOME") + net(all, "EXPENSE")).negate(),
            incomeYtd = net(ytd, "INCOME").negate(),
            expensesYtd = net(ytd, "EXPENSE"),
            evidence = (prior + current).associate { it.period to (it.contentHash ?: "") },
        )
    }

    private fun checked(tb: FrozenTrialBalanceDto, currency: String): FrozenTrialBalanceDto {
        val foreign = tb.lines.firstOrNull { it.currency != currency }
        val unknown = tb.lines.firstOrNull { it.type !in ACCOUNT_TYPES }
        val defect = when {
            tb.balanced != true -> "is not reported balanced (${tb.balanced})"
            foreign != null -> "books ${foreign.currency} on ${foreign.code}; returns are filed in $currency"
            unknown != null -> "has unknown account type ${unknown.type} on ${unknown.code}"
            else -> null
        }
        if (defect != null) throw ReturnDataUnavailableException("frozen trial balance ${tb.period} $defect")
        return tb
    }

    private val ACCOUNT_TYPES = setOf("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE")
}

/**
 * Binds the calculator to the pension company's ledger instance. Without
 * `openbank.statutory-returns.company-books.opened` the completeness of the summed periods is
 * unknowable, so every company-books return is refused with that reason.
 */
@ApplicationScoped
class CompanyBooks(
    @RestClient private val ledger: CompanyLedgerClient,
    @ConfigProperty(name = "openbank.statutory-returns.company-books.opened") private val opened: Optional<String>,
    @ConfigProperty(name = "openbank.statutory-returns.company-books.currency", defaultValue = "CZK")
    private val currency: String,
) : CompanyBooksPort {
    override suspend fun figures(periodEnd: LocalDate): CompanyBookFigures {
        val booksOpened = opened.map { LocalDate.parse(it) }.orElseThrow {
            ReturnDataUnavailableException(
                "the pension company's ledger is not configured (openbank.statutory-returns.company-books.opened, ADR-0337)",
            )
        }
        return CompanyBooksCalculator.figures(::frozen, booksOpened, currency, periodEnd)
    }

    private suspend fun frozen(type: String, date: LocalDate): FrozenTrialBalanceDto = try {
        ledger.frozenTrialBalance(type, date.toString())
    } catch (e: WebApplicationException) {
        throw ReturnDataUnavailableException(
            "pension company ledger answered ${e.response.status} for $type containing $date (not FROZEN LINES_V1?)",
            e,
        )
    } catch (e: ProcessingException) {
        throw ReturnDataUnavailableException("pension company ledger unreachable: ${e.message}", e)
    }
}
