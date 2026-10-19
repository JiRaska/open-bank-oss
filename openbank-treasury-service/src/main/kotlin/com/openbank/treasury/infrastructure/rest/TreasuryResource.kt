// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.CashFlowUseCase
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.`in`.TreasuryCalendarEvent
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.`in`.TreasuryEventType
import com.openbank.treasury.application.port.out.PositionClose
import com.openbank.treasury.application.port.out.SavedFilter
import com.openbank.treasury.application.port.out.ViewsRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxTerms
import com.openbank.treasury.domain.model.ProductType
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID

/**
 * Money-market deals (ADR-0315). Roles are literal realm names (#10618), like risk-engine's
 * ROLE_RISK: ROLE_TREASURY_DEALER drafts, submits and cancels; ROLE_TREASURY_APPROVER (the
 * back office) approves, rejects, confirms, settles, matures and reverses. RBAC here, OPA
 * (`treasury_rest_ext.rego`, human-only) behind it, and the domain's four-eyes / non-human checks behind both — the domain is the one
 * that holds when `AUTHZ_ENFORCE` is off.
 *
 * NOTE the annotation order: `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Money-market deals with four-eyes booking (ADR-0315)")
@Path("/api/v1/treasury")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE, REFERENCE_EDITOR)
@Suppress("TooManyFunctions", "LargeClass")
class TreasuryResource {

    @Inject
    lateinit var deals: TreasuryDealUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    /** After a journal-posting transition, answer with the journal references too. */
    private suspend fun view(id: UUID): DealResponse = deals.get(id).let { DealResponse.from(it.deal, it.journals) }

    /**
     * Money-path idempotency (#8351): every command POST requires `Idempotency-Key`. Declared
     * nullable and checked here — a non-null Kotlin header parameter is a 500 for the absent case.
     */
    private fun requireKey(key: String?): String = requireNotNull(key) { "header '$IDEMPOTENCY_KEY' is required" }

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    @Inject
    lateinit var dayCompletion: com.openbank.treasury.application.port.out.DayCompletionRepository

    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "openbank.treasury.close.due-steps",
        defaultValue = "ACCRUALS,FIXINGS,PAYMENTS",
    )
    lateinit var closeDueSteps: String

    @Inject
    lateinit var cashFlows: CashFlowUseCase

    @Inject
    lateinit var views: ViewsRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/deals")
    @Operation(summary = "Dealer blotter: list deals, filtered; X-Total-Count carries the match count")
    @Authorize(action = "treasury.deal.read")
    @Suppress("LongParameterList")
    suspend fun list(
        @QueryParam("state") state: DealState?,
        @QueryParam("instrument") instrument: String?,
        @QueryParam("bookId") bookId: String?,
        @QueryParam("counterpartyId") counterpartyId: String?,
        @QueryParam("currency") currency: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("hideMatured") hideMatured: Boolean?,
        @QueryParam("hideAuthorised") hideAuthorised: Boolean?,
        @QueryParam("text") text: String?,
        @QueryParam("tradeDateFrom") tradeDateFrom: LocalDate?,
        @QueryParam("tradeDateTo") tradeDateTo: LocalDate?,
        @QueryParam("maturityFrom") maturityFrom: LocalDate?,
        @QueryParam("maturityTo") maturityTo: LocalDate?,
    ): Response {
        val filter = DealFilter(
            state, instrument?.let(ProductType::valueOf), bookId, counterpartyId, currency,
            kind?.let(DealKind::valueOf), hideMatured == true, hideAuthorised == true, text,
            tradeDateFrom, tradeDateTo, maturityFrom, maturityTo,
        )
        val rows = deals.list(state).filter(filter::matches).map { DealResponse.from(it) }
        return Response.ok(rows).header("X-Total-Count", rows.size).build()
    }

    @GET
    @Path("/deals/export")
    @Produces(MediaType.APPLICATION_JSON, "text/csv")
    @Operation(summary = "Export deals as CSV or JSON: all, or a selection of ids; STANDARD or REGULATORY layout")
    @Authorize(action = "treasury.deal.read")
    suspend fun export(
        @QueryParam("format") format: String?,
        @QueryParam("scope") scope: String?,
        @QueryParam("layout") layout: String?,
        @QueryParam("ids") ids: String?,
    ): Response {
        val fmt = (format ?: "json").lowercase()
        require(fmt in setOf("json", "csv")) { "format must be csv or json" }
        val lay = DealExport.Layout.valueOf((layout ?: "STANDARD").uppercase())
        val rows = when (scope ?: "all") {
            "all" -> deals.list(null)
            "selection" -> {
                val wanted = requireNotNull(ids?.takeIf { it.isNotBlank() }) { "scope=selection needs ids" }
                    .split(',').map { UUID.fromString(it.trim()) }.toSet()
                deals.list(null).filter { it.id in wanted }
            }
            else -> throw IllegalArgumentException("scope must be all or selection")
        }
        val table = DealExport.rows(rows, lay)
        return if (fmt == "csv") {
            Response.ok(DealExport.csv(table), "text/csv").build()
        } else {
            Response.ok(table, MediaType.APPLICATION_JSON).build()
        }
    }

    @GET
    @Path("/deals/{id}/history")
    @Operation(summary = "The deal's timeline and its versions")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun history(@PathParam("id") id: UUID): Map<String, Any> {
        val deal = deals.get(id).deal
        return mapOf(
            "dealId" to id,
            "transitions" to DealResponse.from(deal).history,
            "versions" to cashFlows.versions(id).map(DealVersionDto::from),
        )
    }

    @GET
    @Path("/day-completion/{date}")
    @Operation(
        summary = "The treasury accounting day's completion record: every step with its last outcome (ADR-0344 D7)",
    )
    @Authorize(action = "treasury.deal.read")
    suspend fun dayCompletion(@PathParam("date") date: LocalDate): Map<String, Any?> {
        val recorded = dayCompletion.day(date)
        // ADR-0342: what treasury reports (or, while a due step is unfinished, withholds) for this day.
        val report = com.openbank.treasury.infrastructure.close.TreasuryCloseParticipation.report(
            date,
            com.openbank.treasury.infrastructure.close.TreasuryCloseParticipation.dueSteps(closeDueSteps),
            recorded,
        )
        return mapOf(
            "date" to date,
            "steps" to com.openbank.treasury.domain.model.DayStepRecord.day(date, recorded).map {
                mapOf(
                    "step" to it.step.name,
                    "status" to it.status.name,
                    "count" to it.count,
                    "journals" to it.journals,
                    "refused" to it.refused,
                    "detail" to it.detail,
                    "at" to it.at,
                )
            },
            "closeParticipation" to report?.let {
                mapOf(
                    "participant" to com.openbank.treasury.infrastructure.close.TreasuryCloseParticipation.PARTICIPANT,
                    "outcome" to it.outcome.name,
                    "journalsPosted" to it.journalsPosted,
                    "itemsProcessed" to it.itemsProcessed,
                    "reason" to it.reason,
                )
            },
        )
    }

    @GET
    @Path("/cash-flow-overrides")
    @Operation(summary = "The four-eyes override queue across deals (default PENDING, oldest first, at most `limit`)")
    @Authorize(action = "treasury.cashflow.read")
    suspend fun overrideQueue(
        @QueryParam("status") status: String?,
        @QueryParam("limit") limit: Int?,
    ): List<CashFlowOverrideDto> = cashFlows.overrideQueue(
        status?.let { com.openbank.treasury.domain.cashflow.OverrideStatus.valueOf(it) }
            ?: com.openbank.treasury.domain.cashflow.OverrideStatus.PENDING,
        limit ?: DEFAULT_QUEUE,
    ).map(CashFlowOverrideDto::from)

    @GET
    @Path("/deals/{id}/journals")
    @Operation(
        summary = "The journals the ledger accepted for the deal: event, idempotency key, journal id (ADR-0344 D7)",
    )
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun journals(@PathParam("id") id: UUID): List<JournalRefResponse> =
        deals.get(id).journals.map { JournalRefResponse(it.event.key, it.idempotencyKey, it.journalId, it.postedAt) }

    @GET
    @Path("/posting-rules")
    @Operation(summary = "The posting-rule matrix: (product, event, IFRS 9 category) to journal template (ADR-0344 D7)")
    @Authorize(action = "treasury.reference.read")
    fun postingRules(): List<PostingRuleDto> = deals.postingRules().map(PostingRuleDto::from)

    @GET
    @Path("/deals/{id}/versions")
    @Operation(summary = "The deal's versions with a summary of what changed")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun versions(@PathParam("id") id: UUID): List<DealVersionDto> =
        cashFlows.versions(id).map(DealVersionDto::from)

    @POST
    @Path("/deals/{id}/schedule-preview")
    @RolesAllowed(DEALER, BACK_OFFICE)
    @Operation(summary = "Generate the cash-flow ledger the deal would have now, without storing it")
    @Authorize(action = "treasury.deal.preview", resource = "#id")
    suspend fun preview(@PathParam("id") id: UUID): CashFlowLedgerDto = CashFlowLedgerDto.from(cashFlows.preview(id))

    @POST
    @Path("/deals/{id}/send-confirmation")
    @RolesAllowed(BACK_OFFICE, APPROVER)
    @Operation(summary = "Record that the bank's confirmation was sent (never by the deal's creator or submitter)")
    @Authorize(action = "treasury.deal.send-confirmation", resource = "#id")
    suspend fun sendConfirmation(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: SendConfirmationRequest?,
    ): DealResponse =
        DealResponse.from(deals.sendConfirmation(id, request?.documentId, request?.channel, actor(), requireKey(key)))

    @GET
    @Path("/calendar")
    @Operation(summary = "Treasury events between two dates: refixings, payments, maturities, settlements")
    @Authorize(action = "treasury.calendar.read")
    suspend fun calendar(
        @QueryParam("from") from: LocalDate?,
        @QueryParam("to") to: LocalDate?,
        @QueryParam("bookId") bookId: String?,
        @QueryParam("types") types: String?,
    ): List<TreasuryCalendarEvent> = cashFlows.calendar(
        requireNotNull(from) { "query parameter 'from' is required" },
        requireNotNull(to) { "query parameter 'to' is required" },
        bookId,
        types?.split(',')?.map { TreasuryEventType.valueOf(it.trim()) }?.toSet(),
    )

    @POST
    @Path("/positions/close-of-day")
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Record a close-of-day position snapshot")
    @Authorize(action = "treasury.position.close")
    suspend fun closeOfDay(@HeaderParam("Idempotency-Key") key: String?, request: PositionCloseRequest): Response =
        close(requireKey(key), request, special = false)

    @POST
    @Path("/positions/close-of-day/special")
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Record a special (out-of-cycle or scenario) position close")
    @Authorize(action = "treasury.position.close")
    suspend fun closeSpecial(@HeaderParam("Idempotency-Key") key: String?, request: PositionCloseRequest): Response =
        close(requireKey(key), request, special = true)

    private suspend fun close(key: String, request: PositionCloseRequest, special: Boolean): Response {
        val date = requireNotNull(request.date) { "date is required" }
        val a = actor()
        require(a.type == ActorType.HUMAN) { "only a person records a position close" }
        val report = deals.positions(date)
        val close = views.recordClose(
            PositionClose(
                snapshotId = UUID.randomUUID(),
                date = date,
                bookId = request.bookId,
                scenario = request.scenario,
                special = special,
                closedBy = a.id,
                closedAt = java.time.Instant.now(),
                positionsJson = objectMapper.writeValueAsString(PositionsResponse.from(report)),
            ),
            key,
        )
        return Response.status(Response.Status.CREATED).entity(PositionCloseResponse.from(close, objectMapper)).build()
    }

    @GET
    @Path("/views/deal-filters")
    @Operation(summary = "The caller's saved blotter filters")
    @Authorize(action = "treasury.deal.read")
    suspend fun filters(): List<SavedFilterDto> = views.filters(actor().id).map {
        SavedFilterDto.from(it, objectMapper)
    }

    @POST
    @Path("/views/deal-filters")
    @Operation(summary = "Save (or replace by name) one of the caller's blotter filters")
    @Authorize(action = "treasury.deal.read")
    suspend fun saveFilter(request: SavedFilterDto): Response {
        val name = requireNotNull(request.name?.takeIf { it.isNotBlank() && it.length <= MAX_FILTER_NAME }) {
            "name is required (1..$MAX_FILTER_NAME)"
        }
        val filter = requireNotNull(request.filter?.takeIf { it.isObject }) { "filter must be a JSON object" }
        val saved = views.saveFilter(SavedFilter(actor().id, name, filter.toString(), java.time.Instant.now()))
        return Response.status(Response.Status.CREATED).entity(SavedFilterDto.from(saved, objectMapper)).build()
    }

    @DELETE
    @Path("/views/deal-filters/{name}")
    @Operation(summary = "Delete one of the caller's saved filters")
    @Authorize(action = "treasury.deal.read")
    suspend fun deleteFilter(@PathParam("name") name: String): Response = if (views.deleteFilter(
            actor().id,
            name,
        )
    ) {
        Response.noContent().build()
    } else {
        Response.status(Response.Status.NOT_FOUND).build()
    }

    @POST
    @Path("/deals")
    @RolesAllowed(DEALER)
    @Operation(
        summary = "Draft a deal (DRAFT); nothing posts and no limit is consumed. An AI agent " +
            "(ADR-0315 D10) must send rationale and inputs; only a human dealer can submit it.",
    )
    @Authorize(action = "treasury.deal.draft")
    suspend fun draft(@HeaderParam("Idempotency-Key") key: String?, request: DraftDealRequest): Response {
        val deal = deals.draft(commandOf(request), actor(), requireKey(key))
        return Response.status(Response.Status.CREATED).entity(DealResponse.from(deal)).build()
    }

    @PUT
    @Path("/deals/{id}")
    @RolesAllowed(DEALER)
    @Operation(summary = "New terms for a DRAFT deal (a human, or the agent that drafted it); the terms version grows")
    @Authorize(action = "treasury.deal.draft", resource = "#id")
    suspend fun editDraft(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: DraftDealRequest,
    ): DealResponse = DealResponse.from(deals.editDraft(id, commandOf(request), actor(), requireKey(key)))

    @Suppress("CyclomaticComplexMethod") // optional v2 fields falling back to the legacy flat ones
    private fun commandOf(request: DraftDealRequest): DraftDealCommand {
        val product = requireNotNull(request.product) { "product is required" }
        val fx = if (product == ProductType.FX_SPOT) {
            FxTerms.fromCurrencies(
                requireNotNull(request.buyCurrency) { "buyCurrency is required for FX_SPOT" },
                requireNotNull(request.sellCurrency) { "sellCurrency is required for FX_SPOT" },
            ).also { (foreign, _) ->
                require(request.currency == null || request.currency == foreign) {
                    "currency, when given for FX_SPOT, is the foreign currency $foreign"
                }
            }
        } else {
            require(request.buyCurrency == null && request.sellCurrency == null) {
                "buyCurrency/sellCurrency apply to FX_SPOT only"
            }
            null
        }
        val legs = request.legs.orEmpty().map { it.toDomain() }
        // ADR-0344 D2: a leg-described product's flat fields come from its first leg when not given.
        val first = legs.firstOrNull().takeIf { !product.legacy }
        return DraftDealCommand(
            product = product,
            counterpartyId = requireNotNull(request.counterpartyId) { "counterpartyId is required" },
            currency = requireNotNull(fx?.first ?: request.currency ?: first?.currency) { "currency is required" },
            principal = requireNotNull(
                request.principal
                    ?: first?.let { f -> legs.filter { it.currency == f.currency }.maxOf { it.notional } },
            ) { "principal is required" },
            rate = requireNotNull(request.rate ?: first?.let { it.rate.rate ?: java.math.BigDecimal.ZERO }) {
                "rate is required"
            },
            tradeDate =
            request.tradeDate
                ?: request.tradeDateTime?.atZone(java.time.ZoneId.of("Europe/Prague"))?.toLocalDate(),
            valueDate = request.valueDate ?: first?.let { legs.minOf { l -> l.accrualStart } },
            maturityDate = request.maturityDate ?: first?.let { legs.maxOf { l -> l.accrualEnd } },
            kind = request.kind ?: DealKind.ACTUAL,
            legs = if (product.legacy) emptyList() else legs,
            header = headerOf(request),
            rationale = request.rationale,
            inputs = request.inputs?.let { node ->
                require(node.isObject) { "inputs must be a JSON object" }
                node.toString()
            },
            fxSide = fx?.second,
        )
    }

    @GET
    @Path("/deals/{id}")
    @Operation(summary = "One deal with its lifecycle timeline and ledger journal references")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): DealResponse =
        deals.get(id).let { DealResponse.from(it.deal, it.journals) }

    @POST
    @Path("/deals/{id}/submit")
    @RolesAllowed(DEALER)
    @Operation(summary = "Submit for approval (PENDING_APPROVAL); runs the counterparty-limit check")
    @Authorize(action = "treasury.deal.submit", resource = "#id")
    suspend fun submit(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.submit(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/cancel")
    @RolesAllowed(DEALER, APPROVER)
    @Operation(summary = "Cancel a DRAFT or PENDING_APPROVAL deal")
    @Authorize(action = "treasury.deal.cancel", resource = "#id")
    suspend fun cancel(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.cancel(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/approve")
    @RolesAllowed(APPROVER)
    @Operation(
        summary = "Four-eyes approval, books the deal (422: approver is creator/submitter, or limit breach)",
    )
    @Authorize(action = "treasury.deal.approve", resource = "#id")
    suspend fun approve(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.approve(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/override-limit")
    @RolesAllowed(SENIOR_APPROVER)
    @Operation(
        summary = "Senior override of a counterparty-limit breach, with a reason (ADR-0315 D4). " +
            "The deal must be PENDING_APPROVAL and breached; booking still needs a different approver.",
    )
    @Authorize(action = "treasury.deal.override-limit", resource = "#id")
    suspend fun overrideLimit(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): DealResponse = DealResponse.from(
        deals.overrideLimit(id, requireNotNull(request.reason) { "reason is required" }, actor(), requireKey(key)),
    )

    @POST
    @Path("/deals/{id}/reject")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reject a pending deal back to DRAFT with a reason")
    @Authorize(action = "treasury.deal.reject", resource = "#id")
    suspend fun reject(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): DealResponse = DealResponse.from(
        deals.reject(id, requireNotNull(request.reason) { "reason is required" }, actor(), requireKey(key)),
    )

    @POST
    @Path("/deals/{id}/confirm")
    @RolesAllowed(APPROVER)
    @Operation(
        summary = "Record the counterparty's confirmation of a BOOKED deal (CONFIRMED, ADR-0315 D2); posts " +
            "nothing. Never the deal's creator or submitter, never an AI agent.",
    )
    @Authorize(action = "treasury.deal.confirm", resource = "#id")
    suspend fun confirm(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ConfirmRequest?,
    ): DealResponse = DealResponse.from(deals.confirm(id, request?.reference, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/settle")
    @RolesAllowed(APPROVER)
    @Operation(
        summary = "Settle a CONFIRMED deal on or after its value date (BOOKED too only when " +
            "openbank.treasury.confirmation.required is false); posts the settlement journal",
    )
    @Authorize(action = "treasury.deal.settle", resource = "#id")
    suspend fun settle(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        deals.settle(id, actor(), requireKey(key)).let { view(id) }

    @POST
    @Path("/deals/{id}/mature")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Mature a SETTLED deal on or after its maturity date; posts principal and interest")
    @Authorize(action = "treasury.deal.mature", resource = "#id")
    suspend fun mature(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        deals.mature(id, actor(), requireKey(key)).let { view(id) }

    @POST
    @Path("/deals/{id}/reverse")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reverse a BOOKED, CONFIRMED or SETTLED deal; a settled one gets an offsetting journal")
    @Authorize(action = "treasury.deal.reverse", resource = "#id")
    suspend fun reverse(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): DealResponse =
        deals.reverse(id, requireNotNull(request.reason) { "reason is required" }, actor(), requireKey(key))
            .let { view(id) }

    @GET
    @Path("/counterparties")
    @Operation(summary = "Counterparty limits with current exposure and headroom per currency")
    @Authorize(action = "treasury.counterparty.read")
    suspend fun counterparties(): List<CounterpartyResponse> = deals.counterparties().map(CounterpartyResponse::from)

    /**
     * Read-only limit-utilisation view (ADR-0315 D4, #10896): reuses [TreasuryDealUseCase.counterparties]
     * (and, underneath it, the same repository exposure query the booking-time limit check calls) so
     * this can never disagree with what actually blocks booking. Same read action/roles as the other
     * treasury GETs — no new rego rule needed.
     */
    @GET
    @Path("/limits/utilisation")
    @Operation(summary = "Per-counterparty limit utilisation: limit, utilised, available, % and active overrides")
    @Authorize(action = "treasury.counterparty.read")
    suspend fun limitsUtilisation(): LimitUtilisationResponse =
        LimitUtilisationResponse(deals.counterparties().map(LimitUtilisationEntryResponse::from))

    @GET
    @Path("/positions")
    @Operation(summary = "Daily position per currency: placed, borrowed, at ČNB, net")
    @Authorize(action = "treasury.position.read")
    suspend fun positions(@QueryParam("asOf") asOf: LocalDate?): PositionsResponse =
        PositionsResponse.from(deals.positions(asOf))
}

/** Realm roles (#10618), literal like risk-engine's: adding them to libs Roles.ALL is fleet-wide. */
const val DEALER = "ROLE_TREASURY_DEALER"
const val IDEMPOTENCY_KEY = "Idempotency-Key"
const val APPROVER = "ROLE_TREASURY_APPROVER"

/** ADR-0315 D4: overrides a counterparty-limit breach. Never the same person who books the deal. */
const val SENIOR_APPROVER = "ROLE_TREASURY_SENIOR_APPROVER"

private const val MAX_FILTER_NAME = 64
private const val DEFAULT_QUEUE = 100

/** ADR-0344 D3: proposes reference-data changes. Never approves them (that is [APPROVER]). */
const val REFERENCE_EDITOR = "ROLE_TREASURY_REFERENCE_EDITOR"

/**
 * ADR-0344 D4/D10: back office — proposes cash-flow overrides and reference-data changes. Never
 * approves either (that is [APPROVER]), so no single role both writes and activates.
 */
const val BACK_OFFICE = "ROLE_TREASURY_BACK_OFFICE"

/**
 * ADR-0344 D6: releases a proposed own-account payment to the rail. Never the deal's creator or a
 * term author (the domain), never an AI agent, and no other treasury role releases.
 */
const val PAYMENT_RELEASER = "ROLE_TREASURY_PAYMENT_RELEASER"
