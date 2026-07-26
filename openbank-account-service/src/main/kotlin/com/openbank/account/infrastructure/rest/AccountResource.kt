// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.`in`.AccountUseCase
import com.openbank.account.application.port.`in`.AddPocketCommand
import com.openbank.account.application.port.`in`.ClearSavingsGoalCommand
import com.openbank.account.application.port.`in`.CloseAccountCommand
import com.openbank.account.application.port.`in`.ClosePocketCommand
import com.openbank.account.application.port.`in`.FreezeAccountCommand
import com.openbank.account.application.port.`in`.GetAccountByIbanQuery
import com.openbank.account.application.port.`in`.GetAccountQuery
import com.openbank.account.application.port.`in`.ListAccountsQuery
import com.openbank.account.application.port.`in`.ListActiveAccountsQuery
import com.openbank.account.application.port.`in`.ListPocketsQuery
import com.openbank.account.application.port.`in`.OpenAccountCommand
import com.openbank.account.application.port.`in`.ResolvePocketQuery
import com.openbank.account.application.port.`in`.SearchAccountsQuery
import com.openbank.account.application.port.`in`.UnfreezeAccountCommand
import com.openbank.account.application.port.`in`.UpdateSavingsGoalCommand
import com.openbank.account.infrastructure.rest.dto.AccountBalanceResponse
import com.openbank.account.infrastructure.rest.dto.AccountResponse
import com.openbank.account.infrastructure.rest.dto.AddPocketRequest
import com.openbank.account.infrastructure.rest.dto.CloseAccountRequest
import com.openbank.account.infrastructure.rest.dto.FreezeAccountRequest
import com.openbank.account.infrastructure.rest.dto.OpenAccountRequest
import com.openbank.account.infrastructure.rest.dto.PocketResolutionResponse
import com.openbank.account.infrastructure.rest.dto.PocketResponse
import com.openbank.account.infrastructure.rest.dto.SavingsGoalRequest
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.security.Roles
import io.quarkus.logging.Log
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.net.URI
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): account-service exposes account, balance and pocket data, so
 * **no endpoint may be `@PermitAll`/unauthenticated**. The reads were previously `@PermitAll` — a
 * money-path information-disclosure exposure — now gated to service callers (payment routing reads
 * pocket/balance) plus viewers/operators/admin. Mutations (open/close/freeze/pocket lifecycle) stay
 * operator/admin. Roles come from [Roles] (not raw strings). Enforced by Quarkus OIDC and locked by
 * AccountSecurityContractTest.
 */
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounts", description = "Account management")
class AccountResource(
    private val accountUseCase: AccountUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {

    // OIDC identity is taken from the CDI request-scoped SecurityIdentity, NOT the
    // JAX-RS @Context SecurityContext. In a Kotlin `suspend` resource method the
    // @Context principal does not reliably resolve to the bearer JsonWebToken,
    // whereas SecurityIdentity is carried across the coroutine dispatch by
    // smallrye-context-propagation.
    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * Customer-mediated ownership guard — defense-in-depth for the edge IDOR fix (security finding A1).
     * The customer-edge proxies customer reads with its operator M2M token but tags the call with the
     * caller's party via [CUSTOMER_PARTY_HEADER]. When that header is present the read MUST belong to
     * that party; this catches an edge bug (or a new edge route) that forwards the header but forgets
     * its own ownership check. Operator/service calls carry no such header and are unaffected. Returns
     * 404 (not 403) so a customer-scoped caller cannot use the endpoint as an account-existence oracle.
     * Owner-only, matching the edge's check (delegated authorizations are not exposed to the customer
     * surface today). `null` return = allowed.
     */
    private fun denyIfNotOwner(ownerPartyId: UUID, customerPartyId: UUID?): Response? =
        if (isCustomerOwnershipViolation(ownerPartyId, customerPartyId)) {
            Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Account not found"))
                .build()
        } else {
            null
        }

    companion object {
        // Contract: matches customer-edge UpstreamClient.PARTY_HEADER. The edge stamps every
        // customer-scoped upstream call with the caller's validated party id under this header.
        const val CUSTOMER_PARTY_HEADER = "X-Customer-Party-Id"

        /**
         * The ownership decision (package-visible for unit tests). A customer-scoped call (header
         * present) that targets an account owned by a different party is a violation; an operator/
         * service call (no header) is never a violation here.
         */
        internal fun isCustomerOwnershipViolation(ownerPartyId: UUID, customerPartyId: UUID?): Boolean =
            customerPartyId != null && ownerPartyId != customerPartyId
    }

    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.create")
    @Operation(summary = "Open a new account")
    suspend fun openAccount(
        request: OpenAccountRequest,
        @HeaderParam("Idempotency-Key") idempotencyKey: String,
    ): Response {
        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val account = accountUseCase.openAccount(
            OpenAccountCommand(
                idempotencyKey = idempotencyKey,
                partyId = request.partyId,
                productId = request.productId,
                accountType = request.accountType,
                currency = CurrencyCode.of(request.currencyCode),
                requestedBy = operatorId(),
                legalName = request.legalName,
            ),
        )
        val responseBody = account.toResponse()
        val json = objectMapper.writeValueAsString(responseBody)
        idempotencyStore.save(idempotencyKey, 201, json)

        return Response.created(URI.create("/api/v1/accounts/${account.id}"))
            .entity(responseBody)
            .build()
    }

    @GET
    @Path("/{accountId}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Get account by ID")
    suspend fun getAccount(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        val account = accountUseCase.getAccount(GetAccountQuery(accountId))
        denyIfNotOwner(account.partyId, customerPartyId)?.let { return it }
        return Response.ok(account.toResponse()).build()
    }

    @GET
    @Path("/iban/{iban}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#iban")
    @Operation(summary = "Get account by IBAN")
    suspend fun getAccountByIban(
        @PathParam("iban") iban: String,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        val account = accountUseCase.getAccountByIban(GetAccountByIbanQuery(iban))
        denyIfNotOwner(account.partyId, customerPartyId)?.let { return it }
        return Response.ok(account.toResponse()).build()
    }

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.list")
    @Operation(summary = "List accounts for a party")
    suspend fun listAccounts(
        @QueryParam("partyId") partyId: UUID?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        if (partyId == null) return Response.status(400).entity(mapOf("error" to "partyId is required")).build()
        val page = accountUseCase.listAccounts(ListAccountsQuery(partyId, limit, cursor))
        return Response.ok(page.toResponse()).build()
    }

    @GET
    @Path("/search")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.search")
    @Operation(summary = "Search accounts by IBAN fragment (trigram), cursor-paginated")
    suspend fun searchAccounts(
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        if (q.isNullOrBlank()) {
            return Response.status(400).entity(mapOf("error" to "q is required")).build()
        }
        val page = accountUseCase.searchAccounts(SearchAccountsQuery(q, limit, cursor))
        return Response.ok(page.toResponse()).build()
    }

    // ADR-0143: the fleet-wide "list every billable account" read billing-service's cycle
    // scheduler discovers its batch from. Deliberately NOT customer-facing: no partyId scoping
    // header, staff/service roles only, page size capped in the use-case. The literal /active
    // segment wins over the /{accountId} template per JAX-RS matching, so no route ambiguity.
    @GET
    @Path("/active")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.list")
    @Operation(summary = "List all ACTIVE accounts fleet-wide, cursor-paginated (billing discovery)")
    suspend fun listActiveAccounts(
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        val page = accountUseCase.listActiveAccounts(ListActiveAccountsQuery(limit, cursor))
        return Response.ok(page.toResponse()).build()
    }

    @GET
    @Path("/{accountId}/balance")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Get account balance")
    suspend fun getBalance(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        if (customerPartyId != null) {
            val ownerPartyId = accountUseCase.getAccount(GetAccountQuery(accountId)).partyId
            denyIfNotOwner(ownerPartyId, customerPartyId)?.let { return it }
        }
        val balance = accountUseCase.getBalance(accountId)
        return Response.ok(
            AccountBalanceResponse(
                accountId = balance.accountId,
                availableBalance = balance.available,
                currentBalance = balance.booked,
                reservedBalance = balance.reserved,
                pendingBalance = balance.pending,
                currencyCode = balance.currency,
                lastUpdatedAt = balance.updatedAt,
            ),
        ).build()
    }

    @GET
    @Path("/{accountId}/pockets")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "List currency pockets of an account")
    suspend fun listPockets(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val pockets = accountUseCase.listPockets(ListPocketsQuery(accountId))
        return Response.ok(mapOf("pockets" to pockets.map { it.toResponse() })).build()
    }

    @POST
    @Path("/{accountId}/pockets")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.update", resource = "#accountId")
    @Operation(summary = "Add a currency pocket to an account")
    suspend fun addPocket(
        @PathParam("accountId") accountId: UUID,
        request: AddPocketRequest,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        // Customer self-service (ADR-0104): when the edge forwards the party header, the
        // pocket must belong to that party and the customer is the audited actor.
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val pocket = accountUseCase.addPocket(
            AddPocketCommand(
                accountId = accountId,
                currency = CurrencyCode.of(request.currencyCode),
                requestedBy = customerPartyId ?: operatorId(),
            ),
        )
        return Response.status(201).entity(pocket.toResponse()).build()
    }

    @DELETE
    @Path("/{accountId}/pockets/{currency}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.update", resource = "#accountId")
    @Operation(summary = "Close a currency pocket")
    suspend fun closePocket(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val pocket = accountUseCase.closePocket(
            ClosePocketCommand(
                accountId = accountId,
                currency = CurrencyCode.of(currency),
                requestedBy = customerPartyId ?: operatorId(),
            ),
        )
        return Response.ok(pocket.toResponse()).build()
    }

    @PUT
    @Path("/{accountId}/goal")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.update", resource = "#accountId")
    @Operation(summary = "Set or replace the account's savings goal (ADR-0153)")
    suspend fun updateSavingsGoal(
        @PathParam("accountId") accountId: UUID,
        request: SavingsGoalRequest,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val account = accountUseCase.updateSavingsGoal(
            UpdateSavingsGoalCommand(
                accountId = accountId,
                name = request.name,
                targetMinorUnits = request.targetMinorUnits,
                targetDate = request.targetDate,
                requestedBy = customerPartyId ?: operatorId(),
            ),
        )
        return Response.ok(account.toResponse()).build()
    }

    @DELETE
    @Path("/{accountId}/goal")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.update", resource = "#accountId")
    @Operation(summary = "Clear the account's savings goal (ADR-0153)")
    suspend fun clearSavingsGoal(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val account = accountUseCase.clearSavingsGoal(
            ClearSavingsGoalCommand(accountId = accountId, requestedBy = customerPartyId ?: operatorId()),
        )
        return Response.ok(account.toResponse()).build()
    }

    @GET
    @Path("/{accountId}/pockets/resolve")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Resolve which pocket settles a payment in a given currency")
    suspend fun resolvePocket(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("currency") currency: String,
        @QueryParam("policy") @DefaultValue("CONVERT_TO_PRIMARY") policy: String,
        @HeaderParam(CUSTOMER_PARTY_HEADER) customerPartyId: UUID?,
    ): Response {
        customerPartyId?.let {
            val account = accountUseCase.getAccount(GetAccountQuery(accountId))
            denyIfNotOwner(account.partyId, it)?.let { deny -> return deny }
        }
        val resolution = accountUseCase.resolvePocket(
            ResolvePocketQuery(
                accountId = accountId,
                paymentCurrency = CurrencyCode.of(currency),
                policy = com.openbank.account.domain.model.MissingPocketPolicy.valueOf(policy),
            ),
        )
        return Response.ok(resolution.toResponse()).build()
    }

    @POST
    @Path("/{accountId}/close")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.close", resource = "#accountId")
    @Operation(summary = "Close an account")
    suspend fun closeAccount(@PathParam("accountId") accountId: UUID, request: CloseAccountRequest): Response {
        val account = accountUseCase.closeAccount(
            CloseAccountCommand(
                accountId = accountId,
                reason = request.reason,
                requestedBy = operatorId(),
            ),
        )
        return Response.ok(account.toResponse()).build()
    }

    @POST
    @Path("/{accountId}/freeze")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.freeze", resource = "#accountId")
    @Operation(summary = "Freeze an account")
    suspend fun freezeAccount(@PathParam("accountId") accountId: UUID, request: FreezeAccountRequest): Response {
        val account = accountUseCase.freezeAccount(
            FreezeAccountCommand(
                accountId = accountId,
                reason = request.reason,
                requestedBy = operatorId(),
            ),
        )
        return Response.ok(account.toResponse()).build()
    }

    @POST
    @Path("/{accountId}/unfreeze")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.unfreeze", resource = "#accountId")
    @Operation(summary = "Unfreeze an account")
    suspend fun unfreezeAccount(@PathParam("accountId") accountId: UUID, request: FreezeAccountRequest): Response {
        val account = accountUseCase.unfreezeAccount(
            UnfreezeAccountCommand(
                accountId = accountId,
                reason = request.reason,
                requestedBy = operatorId(),
            ),
        )
        return Response.ok(account.toResponse()).build()
    }

    // Single source of the acting operator's identity for audit/requestedBy: the
    // OIDC `sub` claim (a stable Keycloak user id, UUID). Read from the injected
    // SecurityIdentity's principal — for an OIDC bearer that principal is the
    // JsonWebToken, so `.subject` is the raw `sub`. Prefer the UUID subject over
    // principal.name (which Quarkus sets to preferred_username, not a UUID).
    // Fall back to principal.name for non-OIDC identities (e.g. @TestSecurity sets
    // a plain principal whose name is the UUID and exposes no JsonWebToken).
    private fun operatorId(): UUID {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        val parsed = runCatching { UUID.fromString(subject) }.getOrNull()
        if (parsed != null) return parsed

        Log.warn(
            "operatorId: unresolved subject — anonymous=${identity.isAnonymous} " +
                "principalClass=${principal?.javaClass?.name} principalName=${principal?.name} " +
                "subject=$subject roles=${identity.roles}",
        )
        throw WebApplicationException(
            "Authenticated token is missing a usable subject",
            Response.Status.UNAUTHORIZED,
        )
    }
}

private fun com.openbank.account.domain.model.Account.toResponse() = AccountResponse(
    id = id,
    accountNumber = accountNumber.value,
    accountType = accountType,
    partyId = partyId,
    productId = productId,
    currencyCode = currency.code,
    status = status,
    openedAt = openedAt,
    closedAt = closedAt,
    goalName = goalName,
    goalTargetMinorUnits = goalTargetMinorUnits,
    goalTargetDate = goalTargetDate,
)

private fun CursorPage<com.openbank.account.domain.model.Account>.toResponse() =
    CursorPage(data = data.map { it.toResponse() }, pagination = pagination)

private fun com.openbank.account.domain.model.CurrencyPocket.toResponse() = PocketResponse(
    id = id,
    accountId = accountId,
    currencyCode = currency.code,
    isPrimary = isPrimary,
    status = status,
    openedAt = openedAt,
    closedAt = closedAt,
)

private fun com.openbank.account.domain.model.PocketResolution.toResponse(): PocketResolutionResponse = when (this) {
    is com.openbank.account.domain.model.PocketResolution.UseExisting ->
        PocketResolutionResponse(outcome = "USE_EXISTING", pocketCurrency = pocket.currency.code)
    is com.openbank.account.domain.model.PocketResolution.CreateNew ->
        PocketResolutionResponse(outcome = "CREATE_NEW", pocketCurrency = currency.code)
    is com.openbank.account.domain.model.PocketResolution.ConvertToPrimary ->
        PocketResolutionResponse(
            outcome = "CONVERT_TO_PRIMARY",
            pocketCurrency = primary.currency.code,
            convertFrom = from.code,
        )
    is com.openbank.account.domain.model.PocketResolution.Rejected ->
        PocketResolutionResponse(outcome = "REJECTED", reason = reason)
}
