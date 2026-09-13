// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.rest

import com.openbank.cardissuance.application.port.`in`.CardUseCase
import com.openbank.cardissuance.domain.model.AuthorizationChannel
import com.openbank.cardissuance.domain.model.AuthorizationRequest
import com.openbank.cardissuance.domain.model.CardAuthorizationPolicy
import com.openbank.cardissuance.domain.model.CategoryRule
import com.openbank.cardissuance.domain.model.MerchantCategoryTaxonomy
import com.openbank.cardissuance.infrastructure.persistence.repository.CardCategoryRuleRepository
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/**
 * The card authorisation decision point, and the per-category rules it enforces.
 *
 * Separate resource class from [CardResource] because these are a different job: that one manages
 * a card's lifecycle for an operator, this one answers "may this payment go through" for the
 * acquirer path and lets the customer configure what it answers.
 */
@Path("/api/v1/cards")
@Produces(MediaType.APPLICATION_JSON)
class CardAuthorizationResource(
    private val cardUseCase: CardUseCase,
    private val categoryRules: CardCategoryRuleRepository,
) {

    /**
     * The category taxonomy, served rather than hardcoded in clients.
     *
     * A client that hardcoded "gambling is 7995" would keep letting spend through after the bank
     * added a code, on every build already installed. Publishing it means the enforcement point and
     * the screen agree by construction.
     */
    @GET
    @Path("/category-taxonomy")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.taxonomy.read", resource = "")
    @Operation(summary = "Merchant category taxonomy — ids, labels, MCC ranges, what may be blocked")
    fun taxonomy(): Response = Response.ok(
        TaxonomyResponse(
            categories = MerchantCategoryTaxonomy.CATEGORIES.map {
                TaxonomyCategory(it.id, it.label, it.mccRanges, it.blockable, it.limitable)
            },
            unmappedMccCategory = MerchantCategoryTaxonomy.UNMAPPED,
        ),
    ).build()

    @GET
    @Path("/{id}/category-limits")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.category-limits.read", resource = "#id")
    @Operation(summary = "A card's per-category blocks and monthly caps")
    suspend fun listCategoryLimits(@PathParam("id") id: UUID): Response {
        val rules = categoryRules.findByCard(id)
        return Response.ok(
            CategoryLimitsResponse(
                limits = rules.map { CategoryLimit(it.category, it.monthlyLimitMinorUnits, it.blocked) },
                // Honest about what we cannot yet show: per-category spend needs authorisation
                // history this service does not keep. The flag exists so a UI renders "no data"
                // instead of a progress ring against a zero that looks measured.
                spendTracking = false,
            ),
        ).build()
    }

    /**
     * Replaces the card's category rules with the payload.
     *
     * Whole-state replacement, not a patch: the caller sends what it wants to be true, so it can
     * never be in a read-modify-write race with itself, and a rule the customer removed from the
     * screen genuinely stops being enforced.
     *
     * SCA is enforced at the edge, which is where the customer's challenge lives — raising a cap or
     * unblocking a category is a step-up there. This service is reached only by trusted callers.
     */
    @PUT
    @Path("/{id}/category-limits")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.category-limits.update", resource = "#id")
    @Operation(summary = "Replace a card's per-category blocks and monthly caps")
    suspend fun replaceCategoryLimits(@PathParam("id") id: UUID, req: CategoryLimitsRequest): Response {
        val limits = req.requireLimits()
        val unknown = limits.map { it.category }.filterNot { MerchantCategoryTaxonomy.isKnown(it) }
        if (unknown.isNotEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse("Unknown category: ${unknown.joinToString()}", "CATEGORY_UNKNOWN"))
                .build()
        }
        val notBlockable = limits.filter { it.blocked }.map { it.category }
            .filterNot { MerchantCategoryTaxonomy.isBlockable(it) }
        if (notBlockable.isNotEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(
                    ErrorResponse(
                        "Category cannot be blocked: ${notBlockable.joinToString()}",
                        "CATEGORY_NOT_BLOCKABLE",
                    ),
                )
                .build()
        }
        val saved = categoryRules.replaceForCard(
            id,
            limits.map { CategoryRule(it.category, it.blocked, it.monthlyLimitMinorUnits) },
        )
        return Response.ok(
            CategoryLimitsResponse(
                limits = saved.map { CategoryLimit(it.category, it.monthlyLimitMinorUnits, it.blocked) },
                spendTracking = false,
            ),
        ).build()
    }

    /**
     * Decides one card authorisation.
     *
     * **This is the enforcement point the channel controls never had.** Before it, a customer who
     * turned off "payments abroad" changed a stored boolean that nothing read. The decision itself
     * is [CardAuthorizationPolicy] — pure, and unit-tested branch by branch; this method only
     * gathers the card and its rules and hands them over.
     *
     * A card that does not exist declines rather than 404s: an acquirer needs an answer, and the
     * safe answer to "may this unknown card spend" is no.
     */
    @POST
    @Path("/{id}/authorizations")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "card.authorization.decide", resource = "#id")
    @Operation(summary = "Decide a card authorization against status, channel controls and category rules")
    suspend fun authorize(@PathParam("id") id: UUID, req: AuthorizationDecisionRequest): Response {
        val card = cardUseCase.getCard(id)
            ?: return Response.ok(
                AuthorizationDecisionResponse(
                    approved = false,
                    declineReason = "CARD_NOT_ACTIVE",
                    category = MerchantCategoryTaxonomy.UNMAPPED,
                ),
            ).build()

        val channel = runCatching { AuthorizationChannel.valueOf(req.channel.uppercase()) }.getOrNull()
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse("Unknown channel: ${req.channel}", "CHANNEL_UNKNOWN"))
                .build()

        val decision = CardAuthorizationPolicy.decide(
            card = card,
            request = AuthorizationRequest(
                amountMinorUnits = req.amountMinorUnits,
                channel = channel,
                mcc = req.mcc,
                countryCode = req.countryCode,
                spentTodayMinorUnits = req.spentTodayMinorUnits,
                spentThisMonthMinorUnits = req.spentThisMonthMinorUnits,
                spentThisMonthInCategoryMinorUnits = req.spentThisMonthInCategoryMinorUnits,
            ),
            rules = categoryRules.findByCard(id),
        )
        return Response.ok(
            AuthorizationDecisionResponse(
                approved = decision.approved,
                declineReason = decision.reason?.name,
                category = decision.category,
            ),
        ).build()
    }
}

data class TaxonomyCategory(
    val id: String,
    val label: String,
    val mccRanges: List<String>,
    val blockable: Boolean,
    val limitable: Boolean,
)

data class TaxonomyResponse(val categories: List<TaxonomyCategory>, val unmappedMccCategory: String)

data class CategoryLimit(val category: String, val monthlyLimitMinorUnits: Long?, val blocked: Boolean)

data class CategoryLimitsResponse(val limits: List<CategoryLimit>, val spendTracking: Boolean)

data class CategoryLimitsRequest(
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection, so `{"limits": [null]}` deserialises happily into a `List<CategoryLimitInput>`
     * holding a null. Writing the type honestly is what makes [requireLimits] reachable instead of
     * dead code.
     */
    val limits: List<CategoryLimitInput?> = emptyList(),
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`; no
     * service-local mapper is added (#526).
     */
    fun requireLimits(): List<CategoryLimitInput> = limits.mapIndexed { index, limit ->
        requireNotNull(limit) { "limits[$index] must not be null" }
    }
}

data class CategoryLimitInput(
    val category: String = "",
    val monthlyLimitMinorUnits: Long? = null,
    val blocked: Boolean = false,
)

data class AuthorizationDecisionRequest(
    val amountMinorUnits: Long = 0,
    val channel: String = "",
    val mcc: String? = null,
    val countryCode: String? = null,
    val spentTodayMinorUnits: Long = 0,
    val spentThisMonthMinorUnits: Long = 0,
    val spentThisMonthInCategoryMinorUnits: Long = 0,
)

data class AuthorizationDecisionResponse(val approved: Boolean, val declineReason: String?, val category: String)

data class ErrorResponse(val error: String, val code: String)
