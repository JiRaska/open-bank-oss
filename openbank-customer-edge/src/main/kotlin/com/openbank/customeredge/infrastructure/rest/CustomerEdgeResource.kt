// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.domain.model.CustomerIdentity
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.cnb.CnbBanksClient
import com.openbank.customeredge.infrastructure.credit.CreditFunnelPublisher
import com.openbank.customeredge.infrastructure.onboarding.PendingOnboarding
import com.openbank.customeredge.infrastructure.onboarding.PendingOnboardingStore
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Customer-facing edge proxy (ADR-0065). All customer app → cluster traffic goes through
 * here. Responsibilities:
 *  1. Validate the customer JWT from openbank-customers Keycloak realm (Quarkus OIDC).
 *  2. Extract partyId from the JWT `party_id` claim (preferred) or `sub` fallback.
 *  3. Proxy the allow-listed routes to upstream via [UpstreamClient].
 *  4. Pass [UpstreamClient.PARTY_HEADER] so upstream services can scope data without
 *     accepting a different-realm token (B4 fix: edge M2M token, not customer token).
 *
 * Suspend methods use Vert.x WebClient (non-blocking) — no @Blocking needed.
 *
 * SCA ownership (ADR-0021):
 * - enrollDevice: enforced here (partyId path param == JWT party_id/sub).
 * - recordDecision: enforced in sca-service via device-party binding.
 * - getChallenge: known edge limitation (challenge id is opaque; no sensitive data
 *   in response beyond status/method/expires). Follow-up tracked in threat model.
 *
 * Onboarding (ADR-0069):
 * - POST /onboarding/start: unauthenticated; creates a party record and returns partyId.
 *   KC user creation is a separate operator step (Phase 1) or self-service via this route
 *   once the KC Admin API client is wired up (Phase 2).
 * - POST /onboarding/account: authenticated ROLE_CUSTOMER; checks KYC gate then opens
 *   the customer's first account. Edge calls party-service to verify ACTIVE status before
 *   forwarding to account-service.
 *
 * OPA follow-up (ADR-0065 §3): ownership enforcement for account/balance reads currently
 * relies on upstream scoping by X-Customer-Party-Id. Full OPA sidecar is tracked in
 * openbank-customer-edge issue backlog (ADR-0034 fleet sweep).
 */
@Path("/customer/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER") // class-level; individual methods may @PermitAll
// This edge proxy aggregates every allow-listed customer route (accounts, payments, SCA,
// standing orders, cards, FX, disputes, nearby-pay) in one resource bound to /customer/v1;
// splitting it into sibling resources is tracked, but the shared ownership/enrichment helpers
// keep them together for now.
@Suppress("LargeClass")
class CustomerEdgeResource(
    private val upstream: UpstreamClient,
    private val audit: EdgeAuditPublisher,
    private val sessions: PaymentSessionStore,
    private val banksClient: CnbBanksClient,
    private val themePrefs: ThemePreferenceStore,
    private val clock: Clock,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var keycloakAdmin: KeycloakAdminClient

    @Inject
    lateinit var pendingStore: PendingOnboardingStore

    // Field injection, not a constructor parameter: the constructor is already the widest thing
    // detekt tolerates here and every test constructs this resource positionally.
    @Inject
    lateinit var partyMergeResolver: PartyMergeResolver

    // ADR-0284 D4: profile switching. Field-injected for the same LongParameterList reason as the
    // merge resolver above; every test that constructs this resource by hand sets it explicitly.
    @Inject
    lateinit var actingForResolver: ActingForResolver

    @jakarta.ws.rs.core.Context
    lateinit var requestHeaders: jakarta.ws.rs.core.HttpHeaders

    @Inject
    lateinit var creditFunnel: com.openbank.customeredge.infrastructure.credit.CreditFunnelPublisher

    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountServiceUrl: String

    @ConfigProperty(name = "openbank.edge.balance-service-url")
    lateinit var balanceServiceUrl: String

    @ConfigProperty(name = "openbank.edge.audit-service-url")
    lateinit var auditServiceUrl: String

    @ConfigProperty(name = "openbank.edge.interest-service-url")
    lateinit var interestServiceUrl: String

    @ConfigProperty(name = "openbank.edge.kyc-service-url")
    lateinit var kycServiceUrl: String

    @ConfigProperty(name = "openbank.edge.lending-service-url")
    lateinit var lendingServiceUrl: String

    // ADR-0109 P2: product-catalog is the authority for which currencies a product permits.
    // Defaulted so a missing env doesn't break startup; reachable in-cluster.
    @ConfigProperty(
        name = "openbank.edge.product-catalog-url",
        defaultValue = "http://product-catalog.accounts.svc:8104",
    )
    lateinit var productCatalogUrl: String

    @ConfigProperty(
        name = "openbank.edge.incentive-service-url",
        defaultValue = "http://localhost:8156",
    )
    lateinit var incentiveServiceUrl: String

    @ConfigProperty(name = "openbank.edge.transaction-service-url")
    lateinit var transactionServiceUrl: String

    @ConfigProperty(name = "openbank.edge.domestic-payment-service-url")
    lateinit var domesticPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-payment-service-url")
    lateinit var sepaPaymentServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sepa-instant-service-url")
    lateinit var sepaInstantServiceUrl: String

    @ConfigProperty(name = "openbank.edge.swift-service-url")
    lateinit var swiftServiceUrl: String

    @ConfigProperty(name = "openbank.edge.vop-service-url")
    lateinit var vopServiceUrl: String

    @ConfigProperty(name = "openbank.edge.sdd-service-url")
    lateinit var sddServiceUrl: String

    @ConfigProperty(name = "openbank.edge.consent-service-url")
    lateinit var consentServiceUrl: String

    /** The bank's own SWIFT/BIC — the senderBic on outbound MT103s. */
    @ConfigProperty(name = "openbank.edge.bank-bic", defaultValue = "OPENCZPPXXX")
    lateinit var bankBic: String

    @ConfigProperty(name = "openbank.edge.sca-service-url")
    lateinit var scaServiceUrl: String

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    // Same property and default as CustomerDelegationResource — one service, one address.
    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    @ConfigProperty(name = "openbank.edge.pid-service-url")
    lateinit var pidServiceUrl: String

    // Identity-resolution dedup gate (ADR-0072 §6 / ADR-0094): when on, the onboarding
    // register step calls pid /resolve before creating a party, so one human resolves to one
    // party across channels. Default off — enabled deliberately once the id==sub re-linking
    // decision is settled. See resolveIdentityBeforeCreate().
    @ConfigProperty(name = "openbank.edge.identity-resolution-enabled", defaultValue = "false")
    var identityResolutionEnabled: Boolean = false

    // Onboarding auto-resume (ADR-0072): when on, a NEEDS_MANUAL_VERIFICATION parks the onboarding
    // (keyed by caseId) so OnboardingResumeService can replay it on the four-eyes decision.
    @ConfigProperty(name = "openbank.edge.identity-resume-enabled", defaultValue = "false")
    var identityResumeEnabled: Boolean = false

    @ConfigProperty(name = "openbank.edge.notification-service-url")
    lateinit var notificationServiceUrl: String

    /**
     * The app's server-driven engagement surfaces. This is deliberately separate from
     * notification-service: an app surface is rendered after the customer opens the app, it is
     * not an outbound notification pretending to have been delivered (ADR-0220 D1).
     */
    @ConfigProperty(name = "openbank.edge.engagement-service-url")
    lateinit var engagementServiceUrl: String

    /** Campaign validates opaque PUSH interaction references before engagement data is appended. */
    @ConfigProperty(name = "openbank.edge.campaign-service-url")
    lateinit var campaignServiceUrl: String

    @ConfigProperty(name = "openbank.edge.statement-service-url")
    lateinit var statementServiceUrl: String

    @ConfigProperty(name = "openbank.edge.standing-order-service-url")
    lateinit var standingOrderServiceUrl: String

    @ConfigProperty(name = "openbank.edge.fx-service-url")
    lateinit var fxServiceUrl: String

    @ConfigProperty(name = "openbank.edge.card-issuance-service-url")
    lateinit var cardIssuanceServiceUrl: String

    @ConfigProperty(name = "openbank.edge.dispute-service-url")
    lateinit var disputeServiceUrl: String

    // --- Reference data (public, no auth required) ---

    /**
     * Czech bank code list, served from the committed `/banks.json` snapshot.
     *
     * This used to say "refreshed every 24 h from the CNB JERR registry". It never was: that URL
     * has been a 404 for the whole life of the service, and no public replacement exists (#2918).
     * The snapshot is now the declared source of truth, dated in the file itself.
     * @PermitAll — the bank list is public reference data; no customer identity required.
     */
    @GET
    @Path("/banks")
    @PermitAll
    @Blocking
    fun listBanks(): Response = Response.ok(banksClient.banks).build()

    // --- Accounts ---

    @GET
    @Path("/accounts")
    @Authorize(action = "customer.accounts.read")
    @Blocking
    fun listAccounts(): Response {
        val customer = customer()
        val own = upstream.get(
            "$accountServiceUrl/api/v1/accounts?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
        // Accounts shared WITH the caller belong in the list — without this a grantee accepts a
        // share, passes SCA, and then has no way to reach what they were given (issue #3615).
        // Appended, never merged silently: each carries "sharedWithMe": true so the app can say
        // whose account it is. Own accounts are unaffected if delegation-service is unreachable —
        // the customer's own money must not disappear because a secondary service is down.
        if (own.statusInfo.family != Response.Status.Family.SUCCESSFUL) return own
        val shared = sharedAccountsFor(customer.partyId)
        if (shared.isEmpty()) return own
        val merged = runCatching { objectMapper.readTree(own.entity?.toString() ?: "") }
            .getOrNull()?.takeIf { it.isArray } ?: return own
        val out = objectMapper.createArrayNode()
        merged.forEach { out.add(it) }
        shared.forEach { out.add(it) }
        return Response.ok(objectMapper.writeValueAsString(out)).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Read one of the caller's OWN accounts. account-service scopes by id only (the X-Customer-Party-Id
     * header is advisory there), so ownership is enforced HERE — same IDOR guard as the transactions /
     * statements reads. [fetchAccount] already returns the body, so we serve it rather than re-fetching.
     * A non-owned (or non-existent) id returns 403, deliberately not 404, to avoid an existence oracle.
     */
    @GET
    @Path("/accounts/{accountId}")
    @Authorize(action = "customer.accounts.read", resource = "#accountId")
    @Blocking
    fun getAccount(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        val accountJson = fetchAccount(accountId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString() &&
            !hasGrant(customer.partyId, "ACCOUNT", accountId, "ACCOUNT_READ_BALANCES")
        ) {
            return forbidden("Account does not belong to caller")
        }
        return Response.ok(accountJson).type(MediaType.APPLICATION_JSON).build()
    }

    // --- Balances ---

    /**
     * Read the balances of one of the caller's OWN accounts. balance-service scopes by accountId only
     * (it does not consume the party header), so ownership is enforced HERE — without this guard a
     * customer could read another party's balance by guessing an account id (IDOR). Same guard as the
     * transactions / statements reads.
     */
    @GET
    @Path("/balances/{accountId}")
    @Authorize(action = "customer.balances.read", resource = "#accountId")
    @Blocking
    fun getBalance(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        if (!mayReadAccount(accountId, customer.partyId, "ACCOUNT_READ_BALANCES")) {
            return forbidden("Account does not belong to caller")
        }
        return upstream.get("$balanceServiceUrl/api/v1/balances/$accountId", customer.partyId.toString())
    }

    // --- Interest (ADR-0033) ---

    /**
     * The interest view for one of the caller's OWN accounts: the product's annual rate and the
     * interest accrued so far this period. Only interest-bearing products (SAVINGS) have a rate
     * config — for anything else (a CURRENT payment account) this returns `{"eligible": false}`
     * with 200, so the app can simply not draw the interest card rather than treating it as an error.
     *
     * Ownership is enforced HERE (interest-service scopes only by accountId) — same IDOR guard as the
     * balances / statements reads. The projection is deliberately tiny: the app needs the headline
     * rate and the running accrued amount, not the daily accrual ledger.
     */
    @GET
    @Path("/accounts/{accountId}/interest")
    @Authorize(action = "customer.accounts.read", resource = "#accountId")
    @Blocking
    fun getAccountInterest(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        val accountJson = fetchAccount(accountId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val productId = extractTextField(objectMapper, accountJson, "productId")
        val annualRate = productId?.let { fetchEffectiveAnnualRate(accountId, it, customer.partyId) }
        val out = objectMapper.createObjectNode()
        if (annualRate == null) {
            // No active rate config for this product — not interest-bearing (or interest-service is
            // down): report ineligible rather than a spurious 0 % rate.
            return Response.ok(out.put("eligible", false)).type(MediaType.APPLICATION_JSON).build()
        }
        out.put("eligible", true)
        // 0.040000 -> "4"; annualRate is a decimal fraction in interest-service.
        out.put(
            "annualRatePercent",
            annualRate.multiply(java.math.BigDecimal(100)).stripTrailingZeros().toPlainString(),
        )

        val summaryResp = upstream.get(
            "$interestServiceUrl/api/v1/interest/accruals/$accountId/summary",
            customer.partyId.toString(),
        )
        val summary = if (summaryResp.status == 200) {
            runCatching { objectMapper.readTree(summaryResp.entity?.toString() ?: "") }.getOrNull()
        } else {
            null
        }
        out.put("accruedAmount", summary?.get("totalAccrued")?.asText() ?: "0")
        out.put(
            "currency",
            summary?.get("currency")?.asText()
                ?: extractTextField(objectMapper, accountJson, "currencyCode") ?: "CZK",
        )
        summary?.get("fromDate")?.asText()?.let { out.put("periodFrom", it) }
        summary?.get("toDate")?.asText()?.let { out.put("periodTo", it) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /** The annual rate (decimal fraction) EFFECTIVE for this account — an account-specific override
     *  if one is set, else the product default — or null when the account earns no interest (a plain
     *  CURRENT account, whose product default is deactivated) or interest-service is unavailable
     *  (fail-soft: the caller reports ineligible rather than failing the whole request). 204 = no
     *  effective rate. */
    private fun fetchEffectiveAnnualRate(accountId: UUID, productId: String, partyId: UUID): java.math.BigDecimal? {
        val resp = upstream.get(
            "$interestServiceUrl/api/v1/interest/accounts/$accountId/effective-rate?productId=$productId",
            partyId.toString(),
        )
        if (resp.status != 200) return null
        val node = runCatching {
            objectMapper.readTree(resp.entity?.toString() ?: return null)
        }.getOrNull() ?: return null
        return node.get("annualRate")?.decimalValue()
    }

    // --- Fees (ADR-0138 fee schedule transparency) ---

    /**
     * The fee schedule for one of the caller's OWN accounts: the fees the account's product can
     * charge (monthly maintenance, transaction fees, card fees…), each with its amount, frequency and
     * any waiver condition. Read-only transparency — product-catalog is the authority (the same
     * `/products/{id}/fees` read billing-service uses). Ownership is enforced HERE (product-catalog is
     * not party-scoped); an unavailable catalogue or a product with no schedule fail-soft to `[]` so
     * the app draws an empty state rather than an error.
     */
    @GET
    @Path("/accounts/{accountId}/fees")
    @Authorize(action = "customer.accounts.read", resource = "#accountId")
    @Blocking
    fun getAccountFees(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        val accountJson = fetchAccount(accountId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val productId = extractTextField(objectMapper, accountJson, "productId")
            ?: return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        val resp = upstream.get("$productCatalogUrl/api/v1/products/$productId/fees", customer.partyId.toString())
        val body = if (resp.status == 200) resp.entity?.toString()?.takeIf { it.isNotBlank() } ?: "[]" else "[]"
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build()
    }

    // --- Term deposits ---

    /**
     * Customer-safe term-deposit catalogue. The operator catalogue deliberately contains draft,
     * private and historical products too; none of those must become discoverable merely because
     * this edge has an M2M credential. This projection is therefore also the single source for
     * the product eligibility check at [openTermDeposit].
     */
    /**
     * The customer-facing product catalogue: what this customer may open today.
     *
     * Generalises the projection [listTermDepositOffers] already applies to term deposits, for the
     * same reason and with the same filters — the operator catalogue holds draft, private,
     * withdrawn and future-dated products, and none of them may become discoverable merely
     * because this edge holds an M2M credential.
     *
     * **Rates are read, never derived.** A savings product prices by balance tier and a term
     * deposit by its own fixed term, so this endpoint reports the catalogue's numbers and the
     * shape they came in. It does not flatten tiers into one "from" rate or interpolate a term
     * curve: the app would then be quoting a price the bank never set. Where a product carries no
     * rate at all — a current account — the field is absent rather than zero, because 0 % is a
     * price and "not priced" is not.
     *
     * `type` narrows the list; omitted, every discoverable type comes back.
     */
    @GET
    @Path("/products")
    @Authorize(action = "customer.products.read")
    @Blocking
    fun listProductOffers(@QueryParam("type") type: String?): Response {
        val customer = customer()
        val requested = type?.takeIf { it.isNotBlank() }?.uppercase()
        if (requested != null && requested !in CUSTOMER_PRODUCT_TYPES) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "Unsupported product type"))
                .build()
        }
        val query = requested?.let { "?type=$it&status=ACTIVE" } ?: "?status=ACTIVE"
        val catalog = upstream.get("$productCatalogUrl/api/v1/products$query", customer.partyId.toString())
        if (catalog.status != 200) return productCatalogueUnavailable()
        val products = parseJson(catalog)?.takeIf { it.isArray } ?: return productCatalogueUnavailable()
        val today = LocalDate.now(clock)
        val offers = objectMapper.createArrayNode()
        products.forEach { product -> productOffer(product, today)?.let(offers::add) }
        return Response.ok(objectMapper.createObjectNode().set<ArrayNode>("items", offers)).build()
    }

    @GET
    @Path("/products/term-deposits")
    @Authorize(action = "customer.products.read")
    @Blocking
    fun listTermDepositOffers(): Response {
        val customer = customer()
        val catalog = upstream.get(
            "$productCatalogUrl/api/v1/products?type=TERM_DEPOSIT&status=ACTIVE",
            customer.partyId.toString(),
        )
        if (catalog.status != 200) return termDepositCatalogueUnavailable()
        val products = parseJson(catalog)?.takeIf { it.isArray } ?: return termDepositCatalogueUnavailable()
        val offers = objectMapper.createArrayNode()
        products.forEach { product -> termDepositOffer(product)?.let(offers::add) }
        return Response.ok(objectMapper.createObjectNode().set<ArrayNode>("items", offers)).build()
    }

    @GET
    @Path("/products/term-deposits/{productId}")
    @Authorize(action = "customer.products.read", resource = "#productId")
    @Blocking
    fun getTermDepositOffer(@PathParam("productId") productId: UUID): Response =
        when (val result = resolvePublicTermDeposit(customer(), productId)) {
            is TermDepositResolution.Found -> Response.ok(result.offer).build()
            TermDepositResolution.NotFound -> termDepositNotFound()
            TermDepositResolution.Unavailable -> termDepositCatalogueUnavailable()
        }

    /**
     * Reserve the fixed reward attached to a campaign treatment the signed-in customer received.
     * The phone supplies only the opaque interaction reference, promo code and intended product.
     * Party and offer identity are resolved server-side; the product must still be an ACTIVE,
     * public term-deposit offer before Incentive Service sees the request.
     */
    @POST
    @Path("/incentives/claims")
    @Authorize(action = "customer.incentives.claim")
    @Blocking
    fun claimIncentive(body: String, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        require(!idempotencyKey.isNullOrBlank()) { "Idempotency-Key header is required" }
        require(idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH) { "Idempotency-Key is too long" }
        val customer = customer()
        val request = runCatching { objectMapper.readTree(body) }.getOrNull()?.takeIf { it.isObject }
            ?: return badRequest("Malformed incentive claim")
        if (request.fieldNames().asSequence().any { it !in INCENTIVE_CLAIM_FIELDS }) {
            return badRequest("Incentive claim contains unsupported fields")
        }
        val interactionRef = request.uuidField("interactionRef")
            ?: return badRequest("interactionRef must be a UUID")
        val productId = request.uuidField("productId")
            ?: return badRequest("productId must be a UUID")
        val code = request.path("code").takeIf { it.isTextual }?.textValue()?.trim()
            ?.takeIf { it.length in MIN_PROMO_CODE_LENGTH..MAX_PROMO_CODE_LENGTH }
            ?: return badRequest("code must be a string between 8 and 128 characters")

        when (resolvePublicTermDeposit(customer, productId)) {
            is TermDepositResolution.Found -> Unit
            TermDepositResolution.NotFound -> return termDepositNotFound()
            TermDepositResolution.Unavailable -> return termDepositCatalogueUnavailable()
        }

        val attributionResponse = upstream.get(
            "$campaignServiceUrl/api/v1/campaigns/interactions/$interactionRef/attribution",
            customer.partyId.toString(),
        )
        if (attributionResponse.status != Response.Status.OK.statusCode) {
            return if (attributionResponse.status >= UPSTREAM_SERVER_ERROR_MIN) {
                Response.status(Response.Status.BAD_GATEWAY).build()
            } else {
                badRequest("Invalid interaction reference")
            }
        }
        val attribution = parseJson(attributionResponse)
            ?: return Response.status(Response.Status.BAD_GATEWAY).build()
        val offerId = attribution.path("incentiveOfferRef").uuidField("id")
            ?: return Response.status(Response.Status.CONFLICT)
                .entity("{\"error\":\"Campaign treatment has no claimable incentive\"}")
                .type(MediaType.APPLICATION_JSON)
                .build()

        val trustedRequest = objectMapper.createObjectNode()
            .put("code", code)
            .put("productRef", productId.toString())
            .put("attributionRef", interactionRef.toString())
        return upstream.post(
            "$incentiveServiceUrl/api/v1/customer-incentives/offers/$offerId/reservations",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(trustedRequest),
            idempotencyKey,
        )
    }

    /**
     * Opens a term-deposit account selected from [listTermDepositOffers]. The app intentionally
     * supplies no account type or currency: both are fixed by the public catalogue product, so a
     * crafted client cannot turn a term-deposit offer into another account kind or currency.
     * Funding and maturity instructions are outside account-service's account-opening contract;
     * this operation creates the dedicated account and the app can then present its normal funding
     * flow.
     */
    @POST
    @Path("/term-deposits")
    @Authorize(action = "customer.products.open-term-deposit")
    @Blocking
    fun openTermDeposit(body: String, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        requireNotNull(idempotencyKey) { "Idempotency-Key header is required" }
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key header must not be blank" }
        require(idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH) { "Idempotency-Key is too long" }
        val customer = customer()
        val request = runCatching { objectMapper.readTree(body) }.getOrNull()?.takeIf { it.isObject }
            ?: return badRequest("Malformed term-deposit request")
        if (request.fieldNames().asSequence().any { it !in TERM_DEPOSIT_OPEN_FIELDS }) {
            return badRequest("Term-deposit request contains unsupported fields")
        }
        val productId = request.uuidField("productId")
            ?: return Response.status(400).entity("{\"error\":\"productId must be a UUID\"}").build()
        val reservationId = request.get("incentiveReservationId")?.takeUnless { it.isNull }?.let {
            it.takeIf { node -> node.isTextual }?.textValue()?.let { value ->
                runCatching { UUID.fromString(value) }.getOrNull()
            } ?: return badRequest("incentiveReservationId must be a UUID")
        }
        val offer = when (val result = resolvePublicTermDeposit(customer, productId)) {
            is TermDepositResolution.Found -> result.offer
            TermDepositResolution.NotFound -> return termDepositNotFound()
            TermDepositResolution.Unavailable -> return termDepositCatalogueUnavailable()
        }
        val party = when (val result = activeParty(customer)) {
            is ActivePartyResult.Approved -> result
            is ActivePartyResult.Rejected -> return result.response
        }
        val accountBody = objectMapper.createObjectNode()
            .put("partyId", customer.partyId.toString())
            .put("productId", productId.toString())
            .put("accountType", "TERM_DEPOSIT")
            .put("currencyCode", offer.path("currency").asText())
            .put("legalName", party.legalName)
        val response = upstream.post(
            "$accountServiceUrl/api/v1/accounts",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(accountBody),
            idempotencyKey,
        )
        audit.emit(
            eventType = "CUSTOMER_TERM_DEPOSIT_OPENED",
            partyId = customer.partyId.toString(),
            operation = "termDeposits.open",
            result = if (response.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = extractTextField(objectMapper, (response.entity as? String).orEmpty(), "id"),
            details = mapOf("productId" to productId.toString(), "currency" to offer.path("currency").asText()),
        )
        if (reservationId != null) {
            return reconcileTermDepositIncentive(response, customer, productId, reservationId, idempotencyKey)
        }
        return response
    }

    private fun reconcileTermDepositIncentive(
        accountResponse: Response,
        customer: CustomerIdentity,
        productId: UUID,
        reservationId: UUID,
        idempotencyKey: String,
    ): Response {
        if (accountResponse.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
            val account = parseJson(accountResponse)?.takeIf { it.isObject }
                ?: return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Account outcome lacked qualifying evidence\"}").build()
            val qualifiedAt = qualifyingAccountOpenedAt(account, customer.partyId, productId)
                ?: return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"Account outcome lacked qualifying evidence\"}").build()
            val commitBody = objectMapper.createObjectNode()
                .put("productRef", productId.toString())
                .put("qualifiedAt", qualifiedAt.toString())
            val commit = upstream.post(
                "$incentiveServiceUrl/api/v1/customer-incentives/reservations/$reservationId/commit",
                customer.partyId.toString(),
                objectMapper.writeValueAsString(commitBody),
                idempotencyKey,
            )
            if (commit.statusInfo.family != Response.Status.Family.SUCCESSFUL) return commit
            val incentive = parseJson(commit)?.takeIf { it.isObject }
                ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            val result = (account.deepCopy<JsonNode>() as ObjectNode).set<JsonNode>("incentiveReservation", incentive)
            return Response.status(accountResponse.status).entity(result).type(MediaType.APPLICATION_JSON).build()
        }

        if (accountResponse.status !in TERMINAL_ACCOUNT_REJECTION_STATUSES) {
            return accountResponse
        }
        val releaseBody = objectMapper.createObjectNode().put("productRef", productId.toString())
        val release = upstream.post(
            "$incentiveServiceUrl/api/v1/customer-incentives/reservations/$reservationId/release",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(releaseBody),
            idempotencyKey,
        )
        return if (release.statusInfo.family == Response.Status.Family.SUCCESSFUL) accountResponse else release
    }

    private fun qualifyingAccountOpenedAt(account: JsonNode, partyId: UUID, productId: UUID): Instant? {
        val authoritative = account.path("partyId").asText() == partyId.toString() &&
            account.path("productId").asText() == productId.toString() &&
            account.path("accountType").asText() == "TERM_DEPOSIT" &&
            account.path("status").asText() == "ACTIVE"
        if (!authoritative) return null
        return account.path("openedAt").takeIf { it.isTextual }?.textValue()
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    // --- KYC / identity verification status (AML Act §8, ADR-0116) ---

    /**
     * The caller's OWN identity-verification (KYC) status. No path param — the party is taken from
     * the JWT (`customer().partyId`), so a customer can only ever see their own case. Projects
     * kyc-service's case down to a customer-safe shape: the overall [status], when it was verified,
     * and the per-check outcomes (identity / address / sanctions…). Deliberately DROPS the internal
     * risk level, due-diligence tier, notes and assignee — those are compliance-only. No case yet
     * (404 upstream) or an unavailable kyc-service returns `{"status":"NONE","checks":[]}` so the app
     * shows a "not verified yet" state rather than an error.
     */
    @GET
    @Path("/kyc")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun getKycStatus(): Response {
        val customer = customer()
        val resp = upstream.get(
            "$kycServiceUrl/api/v1/kyc/cases/party/${customer.partyId}",
            customer.partyId.toString(),
        )
        val out = objectMapper.createObjectNode()
        val case = if (resp.status == 200) {
            runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
        } else {
            null
        }
        if (case == null) {
            out.put("status", "NONE")
            out.set<com.fasterxml.jackson.databind.JsonNode>("checks", objectMapper.createArrayNode())
            return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
        }
        out.put("status", case.get("status")?.asText() ?: "NONE")
        case.get("reviewedAt")?.takeIf { it.isTextual }?.let { out.put("verifiedAt", it.asText()) }
        val checks = objectMapper.createArrayNode()
        (case.get("checks") as? com.fasterxml.jackson.databind.node.ArrayNode)?.forEach { c ->
            val row = objectMapper.createObjectNode()
            row.put("type", c.get("checkType")?.asText() ?: "")
            row.put("status", c.get("status")?.asText() ?: "")
            c.get("performedAt")?.takeIf { it.isTextual }?.let { row.put("performedAt", it.asText()) }
            checks.add(row)
        }
        out.set<com.fasterxml.jackson.databind.JsonNode>("checks", checks)
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    // --- Loans (ADR lending; read-only customer view) ---

    /**
     * The caller's OWN loans. No path param — the party is taken from the JWT, so a customer only
     * ever sees their own loans. Projects lending-service's [Loan] list to a customer-safe shape
     * (principal, rate, term, status, dates) — internal application/version fields are dropped.
     * Fail-soft to `[]` when lending-service is unavailable.
     */
    @GET
    @Path("/loans")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun listLoans(): Response {
        val customer = customer()
        val resp = upstream.get(
            "$lendingServiceUrl/api/v1/lending/loans?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
        val arr = if (resp.status == 200) {
            runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") as? ArrayNode }.getOrNull()
        } else {
            null
        } ?: return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        val out = objectMapper.createArrayNode()
        arr.forEach { l -> out.add(projectLoan(l)) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Apply for a loan from the customer app (ADR-0211's "Customer intake" row: customer edge,
     * ADR-0065). Until this existed the app could only READ loans — `LendingResource.applyForLoan`
     * is a desk endpoint (`ROLE_LENDING_OFFICER`/`ROLE_CREDIT_RISK`/…), so there was no route by
     * which a customer could apply at all.
     *
     * The party is taken from the JWT and travels as [UpstreamClient.PARTY_HEADER]; the body carries
     * only what the applicant may legitimately choose (amount, term). Price, jurisdiction and
     * product type are lending-side configuration — see `CustomerIntakeResource`.
     *
     * Deliberately NOT fail-soft. `listLoans` above degrades to `[]` because an empty list is an
     * honest answer for an unavailable read; for a WRITE, a synthesised success would tell the
     * customer their application was filed when nothing was. The upstream status and body pass
     * through, so a 400 (amount out of bounds) reaches the app as a 400 with its reason.
     */
    @POST
    @Path("/loan-applications")
    @Authorize(action = "customer.profile.write", resource = "")
    @Blocking
    fun applyForLoan(request: Map<String, Any?>): Response {
        val customer = customer()
        val body = objectMapper.writeValueAsString(
            mapOf("amount" to request["amount"], "termMonths" to request["termMonths"]),
        )
        val resp = upstream.post(
            "$lendingServiceUrl/api/v1/lending/intake/applications",
            customer.partyId.toString(),
            body,
        )
        audit.emit(
            eventType = "LOAN_APPLICATION_SUBMITTED",
            partyId = customer.partyId.toString(),
            operation = "loanApplications.create",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "id"),
            details = mapOf("upstreamStatus" to resp.status.toString()),
        )
        return Response.status(resp.status)
            .entity(resp.entity ?: "{}")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    /**
     * One credit-journey funnel event (ADR-0269 rule 8's metrics).
     *
     * Authenticated on purpose — see [CreditFunnelPublisher] for why this is not the onboarding
     * funnel's public endpoint. The party is taken from the JWT and never from the body, so a
     * caller can only ever describe their own journey.
     *
     * Always 202, even for a rejected value: telemetry must not teach a client anything, and a 400
     * here would turn the allow-list into an oracle for what the bank tracks. Rejected values are
     * counted, not answered.
     */
    @POST
    @Path("/credit/events")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun trackCreditEvent(body: String): Response {
        val customer = customer()
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() as? ObjectNode
        val step = node?.get("step")?.asText()
        val action = node?.get("action")?.asText()
        if (step in CreditFunnelPublisher.VALID_STEPS && action in CreditFunnelPublisher.VALID_ACTIONS) {
            creditFunnel.emit(customer.partyId, step!!, action!!)
        }
        return Response.accepted().build()
    }

    /**
     * The caller's own four-pillar financial health (ADR-0269 / APP-ADR-0001 rule 5).
     *
     * Assembled by lending-service, which already reaches the credit profile and the loan book;
     * this route only scopes it to the caller. No score, no rating, no eligibility — and no path
     * into a credit decision.
     *
     * Fail-soft to an empty list. An unreachable upstream means the app shows no pillars rather
     * than four invented ones, and each pillar can independently answer UNKNOWN, so a partial
     * answer is the normal case rather than an error.
     */
    @GET
    @Path("/financial-health")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun getFinancialHealth(): Response {
        val customer = customer()
        val resp = upstream.get(
            "$lendingServiceUrl/api/v1/lending/intake/financial-health",
            customer.partyId.toString(),
        )
        if (resp.status != 200) return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        return Response.ok(resp.entity ?: "[]").type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * An indicative, non-binding price for an amount and term (ADR-0269 rule 4).
     *
     * The ONLY route by which the app may learn what a loan costs. The client computes no price:
     * rate, instalment, APRC and total come from lending-service, which resolves them from the
     * pinned catalog revision. The body carries amount and term and nothing else — a
     * customer-supplied rate would let the applicant price their own loan.
     *
     * Deliberately NOT fail-soft, and deliberately passes the upstream status through. A 409 means
     * lending's distress floor suppressed pricing and carries a reason code; turning that into an
     * empty 200 would leave the app rendering a quote-shaped hole, which is exactly how a client
     * ends up showing "0".
     */
    @POST
    @Path("/credit/quotes")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun quoteCredit(request: Map<String, Any?>): Response {
        val customer = customer()
        val body = objectMapper.writeValueAsString(
            mapOf("amount" to request["amount"], "termMonths" to request["termMonths"]),
        )
        val resp = upstream.post(
            "$lendingServiceUrl/api/v1/lending/intake/quotes",
            customer.partyId.toString(),
            body,
        )
        return Response.status(resp.status)
            .entity(resp.entity ?: "{}")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    /**
     * The caller's OWN credit applications, as customer-readable journeys (ADR-0269 rule 3).
     *
     * The read half of the intake pair: `applyForLoan` above files an application, this says where
     * it got to. Before this route the app's flow ended at submission — a form into a void.
     *
     * Fail-soft to `[]`, like `listLoans` and for the same reason: an empty list is an honest answer
     * for an unavailable READ, and a journey the app cannot fetch is one it must not invent. The
     * write path stays fail-hard.
     *
     * The upstream projection is already customer-safe (no rate, no instalment, no APRC — that is
     * ADR-0269 rule 4 and arrives as a quote object), so the body passes through unprojected rather
     * than being re-shaped here into a second, drifting copy of the same contract.
     */
    @GET
    @Path("/credit-applications")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun listCreditApplications(): Response {
        val customer = customer()
        val resp = upstream.get(
            "$lendingServiceUrl/api/v1/lending/intake/applications",
            customer.partyId.toString(),
        )
        if (resp.status != 200) return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        return Response.ok(resp.entity ?: "[]").type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * One of the caller's OWN credit applications. Ownership is enforced UPSTREAM by party header —
     * lending-service filters by owner and answers 404 for a foreign id, so a not-found and a
     * not-yours are indistinguishable here too. This route must not "helpfully" convert that 404
     * into anything else.
     *
     * Not fail-soft: unlike the list, there is no honest empty value for "this one application" —
     * a synthesised body would be a fabricated journey state.
     */
    @GET
    @Path("/credit-applications/{applicationId}")
    @Authorize(action = "customer.profile.read", resource = "#applicationId")
    @Blocking
    fun getCreditApplication(@PathParam("applicationId") applicationId: UUID): Response {
        val customer = customer()
        val resp = upstream.get(
            "$lendingServiceUrl/api/v1/lending/intake/applications/$applicationId",
            customer.partyId.toString(),
        )
        return Response.status(resp.status)
            .entity(resp.entity ?: "{}")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    /**
     * The repayment schedule for one of the caller's OWN loans. Ownership enforced HERE: the loan is
     * fetched and its partyId compared to the caller (403 otherwise) — lending-service scopes by loan
     * id only. Projects each installment to {number, dueDate, payment, principal, interest, paid}.
     */
    @GET
    @Path("/loans/{loanId}/schedule")
    @Authorize(action = "customer.profile.read", resource = "#loanId")
    @Blocking
    fun getLoanSchedule(@PathParam("loanId") loanId: UUID): Response {
        val customer = customer()
        val loanResp = upstream.get("$lendingServiceUrl/api/v1/lending/loans/$loanId", customer.partyId.toString())
        if (loanResp.status != 200) return forbidden("Loan does not belong to caller")
        val loan = runCatching { objectMapper.readTree(loanResp.entity?.toString() ?: "") }.getOrNull()
        if (loan?.get("partyId")?.asText() != customer.partyId.toString()) {
            return forbidden("Loan does not belong to caller")
        }
        val schedResp = upstream.get(
            "$lendingServiceUrl/api/v1/lending/loans/$loanId/schedule",
            customer.partyId.toString(),
        )
        val arr = if (schedResp.status == 200) {
            runCatching { objectMapper.readTree(schedResp.entity?.toString() ?: "") as? ArrayNode }.getOrNull()
        } else {
            null
        } ?: return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        val out = objectMapper.createArrayNode()
        arr.forEach { i -> out.add(projectInstallment(i)) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * The SEPA Direct Debit mandates (inkasa) authorised on one of the caller's OWN accounts.
     * Ownership enforced HERE: sdd-service's list is scoped by accountId only, so the edge checks
     * the account belongs to the JWT party (403 otherwise). Projects each mandate to a customer-safe
     * shape (creditor, UMR, scheme, sequence, status, dates) — internal outbox/version fields dropped.
     * Fail-soft to `[]` when sdd-service is unavailable (it is KEDA scale-to-zero — the first call
     * cold-starts it).
     */
    @GET
    @Path("/sdd/mandates")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun listSddMandates(@QueryParam("accountId") accountId: UUID?): Response {
        val customer = customer()
        val account = accountId ?: return badRequest("Missing accountId")
        val accountJson = fetchAccount(account, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val resp = upstream.get("$sddServiceUrl/api/v1/sdd/mandates?accountId=$account", customer.partyId.toString())
        val arr = if (resp.status == 200) {
            runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") as? ArrayNode }.getOrNull()
        } else {
            null
        } ?: return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        val out = objectMapper.createArrayNode()
        arr.forEach { m -> out.add(projectMandate(m)) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Authorise a NEW SEPA Direct Debit mandate on one of the caller's OWN accounts (#6). The app
     * supplies the creditor (name + SEPA creditor id) + scheme; the edge forces the debtor side
     * from the JWT party (debtor IBAN from the owned account, debtor name from party-service) and
     * mints the UMR + signature date, so a customer can never authorise a debit against someone
     * else's account or under a forged debtor identity.
     */
    @POST
    @Path("/sdd/mandates")
    @Authorize(action = "customer.sdd.update", resource = "")
    @Blocking
    @Suppress("MagicNumber") // 20-char UMR suffix from a UUID; a named const adds no clarity here
    fun createSddMandate(body: String): Response {
        val customer = customer()
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return badRequest("Malformed body")
        val accountId = node.get("accountId")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return badRequest("Missing accountId")
        val accountJson = fetchAccount(accountId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val creditorName = node.get("creditorName")?.asText()?.takeIf { it.isNotBlank() }
            ?: return badRequest("Missing creditorName")
        val creditorId = node.get("creditorIdentifier")?.asText()?.takeIf { it.isNotBlank() }
            ?: return badRequest("Missing creditorIdentifier")
        val debtorIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Account IBAN unavailable")
        val debtorName = fetchPartyLegalName(customer.partyId) ?: "OpenBank Customer"
        val req = objectMapper.createObjectNode()
        req.put("accountId", accountId.toString())
        req.put("debtorIban", debtorIban)
        req.put("creditorIdentifier", creditorId)
        req.put("umr", "UMR-" + Ids.randomId().toString().replace("-", "").take(20).uppercase())
        req.put("scheme", node.get("scheme")?.asText() ?: "CORE")
        req.put("sequenceType", node.get("sequenceType")?.asText() ?: "RCUR")
        req.put("creditorName", creditorName)
        req.put("debtorName", debtorName)
        req.put("signatureDate", java.time.LocalDate.now(clock).toString())
        return upstream.post("$sddServiceUrl/api/v1/sdd/mandates", customer.partyId.toString(), req.toString())
    }

    /** Cancel a SEPA Direct Debit mandate the caller owns (terminal — no more collections). */
    @POST
    @Path("/sdd/mandates/{id}/cancel")
    @Authorize(action = "customer.sdd.update", resource = "#id")
    @Blocking
    fun cancelSddMandate(@PathParam("id") id: UUID): Response = sddMandateAction(id, "cancel")

    /** Temporarily suspend a mandate (collections paused; resumable). */
    @POST
    @Path("/sdd/mandates/{id}/suspend")
    @Authorize(action = "customer.sdd.update", resource = "#id")
    @Blocking
    fun suspendSddMandate(@PathParam("id") id: UUID): Response = sddMandateAction(id, "suspend")

    /** Resume a suspended mandate. */
    @POST
    @Path("/sdd/mandates/{id}/resume")
    @Authorize(action = "customer.sdd.update", resource = "#id")
    @Blocking
    fun resumeSddMandate(@PathParam("id") id: UUID): Response = sddMandateAction(id, "resume")

    // Ownership: sdd-service actions are by mandate id only, so the edge resolves the mandate's
    // accountId and checks the caller owns it before proxying — a customer must never suspend or
    // cancel a mandate on someone else's account.
    private fun sddMandateAction(id: UUID, action: String): Response {
        val customer = customer()
        val mResp = upstream.get("$sddServiceUrl/api/v1/sdd/mandates/$id", customer.partyId.toString())
        if (mResp.status != 200) return Response.status(mResp.status).entity(mResp.entity).build()
        val acct = runCatching {
            objectMapper.readTree(mResp.entity?.toString() ?: "").get("accountId")?.asText()
        }.getOrNull() ?: return forbidden("Mandate not found")
        if (!ownsAccount(UUID.fromString(acct), customer.partyId)) {
            return forbidden("Mandate does not belong to caller")
        }
        return upstream.post("$sddServiceUrl/api/v1/sdd/mandates/$id/$action", customer.partyId.toString(), "")
    }

    private fun projectMandate(m: com.fasterxml.jackson.databind.JsonNode): ObjectNode {
        val o = objectMapper.createObjectNode()
        o.put("id", m.get("id")?.asText())
        o.put("creditorName", m.get("creditorName")?.asText() ?: "")
        o.put("creditorIdentifier", m.get("creditorIdentifier")?.asText() ?: "")
        o.put("umr", m.get("umr")?.asText() ?: "")
        o.put("scheme", m.get("scheme")?.asText() ?: "")
        o.put("sequenceType", m.get("sequenceType")?.asText() ?: "")
        o.put("status", m.get("status")?.asText() ?: "")
        m.get("signatureDate")?.asText()?.let { o.put("signatureDate", it) }
        m.get("lastCollectionDate")?.asText()?.takeIf { it.isNotBlank() }?.let { o.put("lastCollectionDate", it) }
        return o
    }

    // ── PSD2 consents (ADR-0126) ─────────────────────────────────────────────
    // The customer's view of who (TPPs, delegated agents) may access their account data, and the
    // ability to revoke. Consents are party-scoped: consent-service keys them by partyId, so the
    // edge injects the caller's partyId from the JWT and never trusts a client-supplied one.

    /** The caller's own access log for the privacy centre (P2-27): audit entries whose
     *  aggregate is the caller's party, projected by audit-service to event metadata only. */
    @GET
    @Path("/privacy/access-log")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun accessLog(): Response {
        val customer = customer()
        return upstream.get(
            "$auditServiceUrl/api/v1/audit/customer/${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /**
     * GDPR Art. 15 — right of access. The subject's full PII set, aggregated by party-service
     * across party, kyc and card-issuance (ADR-0118).
     *
     * ## Why this route exists
     *
     * party-service has implemented both exports since ADR-0118/ADR-0204 and they were reachable by
     * nobody: the handler accepts ROLE_ADMIN, ROLE_DPO, or the subject's own JWT, and this edge
     * forwards none of the three. It validates the customer token in the `openbank-customers` realm
     * and calls upstream with its OWN client_credentials token from the operator realm, so
     * party-service saw `sub = service-account-openbank-edge` with ROLE_OPERATOR — not admin, not
     * DPO, not the subject — and answered 403 to every request a data subject could make. There was
     * also no route here to make one with: 136 `@Path` declarations and none for either export
     * (#8421). ADR-0204 D6 left "who gets a button for it" open rather than deciding against it.
     *
     * ## Scoping
     *
     * Party-scoped by the JWT party — never a client-supplied id — so a customer only ever exports
     * their own record (no IDOR), the same shape as `/profile` and `/privacy/access-log`.
     * party-service independently requires that `X-Customer-Party-Id` name the same party as the
     * path, so a header alone cannot widen the read.
     *
     * A distinct action from `customer.portabilityExport.read` below because Art. 15 and Art. 20 are
     * distinct rights with different output obligations (ADR-0204: Art. 20 excludes Art. 6(1)(c)
     * legal-obligation data and adds transaction history) — party-service audits them under
     * different `gdprArticle` codes for exactly that reason, and collapsing them here would undo it.
     */
    @GET
    @Path("/privacy/gdpr-export")
    @Authorize(action = "customer.gdprExport.read", resource = "")
    @Blocking
    fun gdprExport(): Response {
        val customer = customer()
        return upstream.get(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/gdpr-export",
            customer.partyId.toString(),
        )
    }

    /**
     * GDPR Art. 20 — right to data portability. The consent/contract-basis subset only, with
     * counterparty IBANs redacted per Art. 20(4); Art. 20(2) direct controller-to-controller
     * transmission is explicitly not offered (ADR-0204 D1/D2/D4). Same scoping and same trust
     * boundary as [gdprExport].
     */
    @GET
    @Path("/privacy/portability-export")
    @Authorize(action = "customer.portabilityExport.read", resource = "")
    @Blocking
    fun portabilityExport(): Response {
        val customer = customer()
        return upstream.get(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/gdpr-portability-export",
            customer.partyId.toString(),
        )
    }

    /** The third-party / agent data-access consents granted by the caller. */
    @GET
    @Path("/consents")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun listConsents(): Response {
        val customer = customer()
        val party = customer.partyId.toString()
        val resp = upstream.get("$consentServiceUrl/api/v1/consents/party/$party", party)
        val arr = if (resp.status == 200) {
            runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") as? ArrayNode }.getOrNull()
        } else {
            null
        } ?: return Response.ok("[]").type(MediaType.APPLICATION_JSON).build()
        val out = objectMapper.createArrayNode()
        arr.forEach { c -> out.add(projectConsent(c)) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Revoke a consent the caller owns. consent-service takes partyId as a query param and enforces
     * that the consent belongs to it, so passing the JWT partyId both authorises and scopes the call
     * — a customer can never revoke another party's consent even by guessing an id.
     */
    @DELETE
    @Path("/consents/{id}")
    @Authorize(action = "customer.consent.revoke", resource = "#id")
    @Blocking
    fun revokeConsent(@PathParam("id") id: UUID): Response {
        val customer = customer()
        val body = objectMapper.createObjectNode().put("reason", "Revoked by customer").toString()
        val resp = upstream.delete(
            "$consentServiceUrl/api/v1/consents/$id?partyId=${customer.partyId}",
            customer.partyId.toString(),
            body,
        )
        return Response.status(resp.status).entity(resp.entity).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * The caller's own ADR-0269 credit consents, as three booleans.
     *
     * ## Why this route exists at all
     *
     * consent-service could already grant these scopes, and the edge could already list and revoke
     * consents — but there was no way for a CUSTOMER to switch one ON. The app's existing marketing
     * toggle goes somewhere else entirely (party-service's `marketingConsent` boolean), so without
     * this route the credit consents were grantable only by an operator, which is the opposite of
     * what "the customer decides" means.
     *
     * ## Why booleans and not the consent objects
     *
     * The app renders three switches. Handing it consent aggregates would make every client
     * re-derive "is CREDIT_OFFERS on" from a list, and the first client to write that filter
     * slightly differently gets a different answer — the same reasoning ADR-0210 D2 gives for the
     * party key. The derivation happens once, here.
     */
    @GET
    @Path("/credit/consents")
    @Authorize(action = "customer.profile.read", resource = "")
    @Blocking
    fun getCreditConsents(): Response {
        val customer = customer()
        val party = customer.partyId.toString()
        val resp = upstream.get("$consentServiceUrl/api/v1/consents/party/$party", party)
        val arr = if (resp.status == 200) {
            runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") as? ArrayNode }.getOrNull()
        } else {
            null
        }
        // An unreadable consent list answers "everything off", which is the SAFE default and the
        // true one for every customer who has never granted anything. It is not fail-soft
        // convenience: the client uses this to decide whether to fetch offers at all, so an
        // optimistic default here would fetch offers for a customer whose consent we cannot read.
        val active = arr?.filter { it.get("status")?.asText() == "ACTIVE" }?.flatMap { c ->
            c.get("scopes")?.mapNotNull { it.asText() } ?: emptyList()
        }?.toSet().orEmpty()
        val out = objectMapper.createObjectNode()
        CREDIT_SCOPES.forEach { (field, scope) -> out.put(field, scope in active) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Set the caller's credit consents to exactly the state in the body (ADR-0269 rule 1).
     *
     * A full-state PUT, not a partial patch: "turn offers off" and "leave offers alone" must not be
     * the same request. Anything the body sets to false is revoked, immediately and for every
     * channel — the ADR's requirement that switching offers off takes effect at once rather than at
     * the next batch.
     *
     * These scopes are GDPR Art. 7 data-processing consents, so consent-service activates them
     * without an SCA ceremony; an SCA designed for payment authorisation is a disproportionate
     * burden on a data-processing opt-in (ADR-0205 D1). Granting still requires the customer's own
     * authenticated session — the party comes from the JWT, never the body.
     */
    @PUT
    @Path("/credit/consents")
    @Authorize(action = "customer.profile.consent.update", resource = "")
    @Blocking
    fun putCreditConsents(body: String): Response {
        val customer = customer()
        val requested = runCatching { objectMapper.readTree(body) }.getOrNull() as? ObjectNode
            ?: return badRequest("Malformed credit consent body")
        val desired = CREDIT_SCOPES.mapValues { (field, _) -> requested.get(field)?.asBoolean() ?: false }
        val party = customer.partyId.toString()

        val existing = upstream.get("$consentServiceUrl/api/v1/consents/party/$party", party)
        if (existing.status != 200) return Response.status(existing.status).entity(existing.entity).build()
        val consents = runCatching { objectMapper.readTree(existing.entity?.toString() ?: "") as? ArrayNode }
            .getOrNull() ?: objectMapper.createArrayNode()

        desired.forEach { (field, wanted) ->
            val scope = CREDIT_SCOPES.getValue(field)
            val held = consents.firstOrNull { c ->
                c.get("status")?.asText() == "ACTIVE" &&
                    c.get("scopes")?.any { it.asText() == scope } == true
            }
            when {
                wanted && held == null -> grantCreditScope(customer.partyId, scope)
                !wanted && held != null -> revokeHeldConsent(customer.partyId, held.get("id")?.asText())
                else -> Unit // already in the requested state; granting again would churn the audit trail
            }
        }
        return getCreditConsents()
    }

    private fun grantCreditScope(partyId: UUID, scope: String) {
        val body = objectMapper.createObjectNode().apply {
            put("partyId", partyId.toString())
            put("granteeId", BANK_GRANTEE)
            put("granteeType", "INTERNAL_SERVICE")
            put("granteeName", "OpenBank")
            putArray("scopes").add(scope)
            // 365 days: the non-AISP bucket's maximum. It is a ceiling, not a promise — the customer
            // can revoke at any time, and nothing re-arms the consent when it lapses.
            put("validTo", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(CONSENT_DAYS).toString())
        }.toString()
        upstream.post("$consentServiceUrl/api/v1/consents", partyId.toString(), body)
    }

    private fun revokeHeldConsent(partyId: UUID, consentId: String?) {
        if (consentId.isNullOrBlank()) return
        val body = objectMapper.createObjectNode().put("reason", "Revoked by customer").toString()
        // granteeId is REQUIRED here, not optional: ConsentResource.revoke binds OPA's
        // resource.id to it (`#granteeId`, issue #2911 — binding it to the consent UUID instead
        // made every M2M revoke unconditionally 403). Omitting it left resource.id null, which
        // service-consent-m2m-credit's `input.resource.id == "openbank"` comparison can never
        // satisfy — so turning a credit consent switch OFF 403'd exactly like turning it on did
        // before this fix, just one call further into the flow.
        upstream.delete(
            "$consentServiceUrl/api/v1/consents/$consentId?partyId=$partyId&granteeId=$BANK_GRANTEE",
            partyId.toString(),
            body,
        )
    }

    private fun projectConsent(c: com.fasterxml.jackson.databind.JsonNode): ObjectNode {
        val o = objectMapper.createObjectNode()
        o.put("id", c.get("id")?.asText())
        o.put("granteeName", c.get("granteeName")?.asText() ?: "")
        o.put("granteeType", c.get("granteeType")?.asText() ?: "")
        o.put("status", c.get("status")?.asText() ?: "")
        val scopes = objectMapper.createArrayNode()
        c.get("scopes")?.forEach { scopes.add(it.asText()) }
        o.set<com.fasterxml.jackson.databind.JsonNode>("scopes", scopes)
        val ibans = objectMapper.createArrayNode()
        c.get("accountIbans")?.takeIf { !it.isNull }?.forEach { ibans.add(it.asText()) }
        o.set<com.fasterxml.jackson.databind.JsonNode>("accountIbans", ibans)
        c.get("validFrom")?.asText()?.let { o.put("validFrom", it) }
        c.get("validTo")?.asText()?.let { o.put("validTo", it) }
        c.get("createdAt")?.asText()?.let { o.put("createdAt", it) }
        return o
    }

    private fun projectLoan(l: com.fasterxml.jackson.databind.JsonNode): ObjectNode {
        val o = objectMapper.createObjectNode()
        o.put("id", l.get("id")?.let { if (it.isObject) it.get("value")?.asText() else it.asText() })
        val principal = l.get("principal")
        o.put("principalAmount", principal?.get("amount")?.asText() ?: "0")
        o.put("currency", principal?.get("currency")?.get("code")?.asText() ?: "CZK")
        // nominalAnnualRate is a decimal fraction (0.089) — surface as a percent number "8.9".
        l.get("nominalAnnualRate")?.decimalValue()?.let {
            o.put("annualRatePercent", it.multiply(java.math.BigDecimal(100)).stripTrailingZeros().toPlainString())
        }
        o.put("termPeriods", l.get("termPeriods")?.asInt() ?: 0)
        o.put("status", l.get("status")?.asText() ?: "")
        l.get("firstDueDate")?.asText()?.let { o.put("firstDueDate", it) }
        l.get("disbursedAt")?.asText()?.let { o.put("disbursedAt", it) }
        return o
    }

    private fun projectInstallment(i: com.fasterxml.jackson.databind.JsonNode): ObjectNode {
        val o = objectMapper.createObjectNode()
        o.put("number", i.get("number")?.asInt() ?: 0)
        i.get("dueDate")?.asText()?.let { o.put("dueDate", it) }
        val payment = i.get("payment")
        o.put("paymentAmount", payment?.get("amount")?.asText() ?: "0")
        o.put("currency", payment?.get("currency")?.get("code")?.asText() ?: "CZK")
        o.put("principalAmount", i.get("principal")?.get("amount")?.asText() ?: "0")
        o.put("interestAmount", i.get("interest")?.get("amount")?.asText() ?: "0")
        o.put("paid", i.get("paid")?.asBoolean(false) ?: false)
        return o
    }

    // --- Currency pockets (ADR-0109) ---
    // Customer self-service over the account-service pocket lifecycle. All routes are
    // ownership-checked here (account-service also re-checks via the X-Customer-Party-Id
    // header we send). No SCA — opening/closing a pocket moves no money (ADR-0021 gates
    // payments).

    @GET
    @Path("/accounts/{accountId}/pockets")
    @Authorize(action = "customer.pockets.read", resource = "#accountId")
    @Blocking
    fun listPockets(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        return upstream.get(
            "$accountServiceUrl/api/v1/accounts/$accountId/pockets",
            customer.partyId.toString(),
        )
    }

    @POST
    @Path("/accounts/{accountId}/pockets")
    @Authorize(action = "customer.pockets.create", resource = "#accountId")
    @Blocking
    fun addPocket(@PathParam("accountId") accountId: UUID, body: String): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        val ccy = extractTextField(objectMapper, body, "currencyCode")?.uppercase()
            ?: return badRequest("Missing currencyCode")
        if (!isValidCurrency(ccy)) return badRequest("Invalid currency code")
        // ADR-0109 P2: product-catalog is the authority for which currencies the account's
        // product permits. Enforce when resolvable; fail-open (defer to account-service) if
        // the catalog/product config is unavailable, so a catalog outage can't block adds.
        val supported = supportedCurrenciesFor(accountId, customer.partyId)
        if (supported != null && !supported.contains(ccy)) {
            return Response.status(422)
                .entity(
                    mapOf(
                        "error" to "currency_not_supported",
                        "message" to "This account's product does not support $ccy.",
                        "supported" to supported.sorted(),
                    ),
                )
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        // account-service ownership-checks + audits the caller via the X-Customer-Party-Id header.
        return upstream.post(
            "$accountServiceUrl/api/v1/accounts/$accountId/pockets",
            customer.partyId.toString(),
            """{"currencyCode":"$ccy"}""",
        )
    }

    // --- Verification of Payee (VoP / Confirmation of Payee, EU SEPA rule) ---

    /**
     * Verify that a creditor account (IBAN) belongs to the named payee BEFORE the customer confirms
     * a transfer — the anti-APP-fraud check EU makes mandatory for SEPA payments. Pure name lookup:
     * no money moves, no account ownership needed, so it is safe for any authenticated customer.
     * Proxies to vop-service `/api/v1/vop/verify`, which returns MATCH / CLOSE_MATCH / NO_MATCH.
     */
    @POST
    @Path("/vop/verify")
    @Authorize(action = "customer.vop.verify")
    @Blocking
    fun verifyPayee(body: String): Response {
        val customer = customer()
        val iban = extractTextField(objectMapper, body, "creditorIban")?.trim()
            ?: return badRequest("Missing creditorIban")
        val name = extractTextField(objectMapper, body, "creditorName")?.trim()
            ?: return badRequest("Missing creditorName")
        if (iban.isEmpty() || name.isEmpty()) return badRequest("creditorIban and creditorName must not be blank")
        return upstream.post("$vopServiceUrl/api/v1/vop/verify", customer.partyId.toString(), body)
    }

    @DELETE
    @Path("/accounts/{accountId}/pockets/{currency}")
    @Authorize(action = "customer.pockets.close", resource = "#accountId")
    @Blocking
    fun closePocket(@PathParam("accountId") accountId: UUID, @PathParam("currency") currency: String): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        val ccy = currency.uppercase()
        if (!isValidCurrency(ccy)) return badRequest("Invalid currency code")
        // ADR-0109 safe-close: never strand money in a closed pocket. Refuse unless the
        // pocket balance is zero — the app routes the customer to convert to the primary
        // currency first. Fail-closed: if we can't confirm it's empty, we don't close.
        if (!pocketBalanceIsZero(accountId, customer.partyId, ccy)) {
            return Response.status(Response.Status.CONFLICT)
                .entity(
                    mapOf(
                        "error" to "pocket_not_empty",
                        "message" to "Convert this pocket's balance to your primary currency before closing it.",
                    ),
                )
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        return upstream.delete(
            "$accountServiceUrl/api/v1/accounts/$accountId/pockets/$ccy",
            customer.partyId.toString(),
        )
    }

    // --- Savings goal (ADR-0153) ---
    // Customer self-service metadata on the account, not a money movement — no SCA (same
    // gate rationale as pocket open/close above: ADR-0021 only scopes payments).

    @PUT
    @Path("/accounts/{accountId}/goal")
    @Authorize(action = "customer.accounts.goal.write", resource = "#accountId")
    @Blocking
    fun updateSavingsGoal(@PathParam("accountId") accountId: UUID, body: String): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        // Re-serialize through Jackson (not raw string interpolation) — the goal name is
        // customer-authored free text and must be JSON-escaped, not spliced into a template.
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() as? ObjectNode
            ?: return badRequest("Malformed goal request body")
        val name = node.get("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: return badRequest("Missing goal name")
        val targetMinorUnits = node.get("targetMinorUnits")?.takeIf { it.isIntegralNumber }?.asLong()
            ?: return badRequest("Missing or invalid targetMinorUnits")
        if (targetMinorUnits <= 0) return badRequest("targetMinorUnits must be positive")
        val forwarded = objectMapper.createObjectNode().apply {
            put("name", name)
            put("targetMinorUnits", targetMinorUnits)
            node.get("targetDate")?.takeIf { it.isTextual }?.let { put("targetDate", it.asText()) }
        }
        return upstream.put(
            "$accountServiceUrl/api/v1/accounts/$accountId/goal",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(forwarded),
        )
    }

    @DELETE
    @Path("/accounts/{accountId}/goal")
    @Authorize(action = "customer.accounts.goal.write", resource = "#accountId")
    @Blocking
    fun clearSavingsGoal(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        return upstream.delete(
            "$accountServiceUrl/api/v1/accounts/$accountId/goal",
            customer.partyId.toString(),
        )
    }

    // --- Account rename ---
    // Same class as the savings goal above: cosmetic customer preference, not a money
    // movement — no SCA (ADR-0021 only scopes payments).

    @PATCH
    @Path("/accounts/{accountId}/nickname")
    @Authorize(action = "customer.accounts.nickname.write", resource = "#accountId")
    @Blocking
    fun renameAccount(@PathParam("accountId") accountId: UUID, body: String): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        // Re-serialize through Jackson (not raw string interpolation) — the nickname is
        // customer-authored free text and must be JSON-escaped, not spliced into a template.
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() as? ObjectNode
            ?: return badRequest("Malformed rename request body")
        val nickname = node.get("nickname")?.takeIf { it.isTextual }?.asText()
        val forwarded = objectMapper.createObjectNode().apply {
            if (nickname != null) put("nickname", nickname) else putNull("nickname")
        }
        return upstream.patch(
            "$accountServiceUrl/api/v1/accounts/$accountId/nickname",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(forwarded),
        )
    }

    // NOTE: deliberately no customer-facing "close account" endpoint here. account-service's
    // OPA policy (account_rest_ext.rego) explicitly PROHIBITS account.close for the edge's M2M
    // principal — closed off after a fleet audit (#3734) that found a blanket role-only grant
    // had accidentally exposed the whole sensitive lifecycle (close/freeze/unfreeze/authorize)
    // to this exact proxy path. That is a deliberate security boundary, not a missing feature —
    // don't add a caller here without an explicit human decision to reopen it.

    @GET
    @Path("/accounts/{accountId}/pockets/resolve")
    @Authorize(action = "customer.pockets.read", resource = "#accountId")
    @Blocking
    fun resolvePocket(@PathParam("accountId") accountId: UUID, @QueryParam("currency") currency: String?): Response {
        val customer = customer()
        val ccyRaw = currency ?: return badRequest("Missing required query parameter 'currency'")
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        val ccy = ccyRaw.uppercase()
        if (!isValidCurrency(ccy)) return badRequest("Invalid currency code")
        return upstream.get(
            "$accountServiceUrl/api/v1/accounts/$accountId/pockets/resolve?currency=$ccy",
            customer.partyId.toString(),
        )
    }

    /**
     * Preview converting a pocket's whole balance to the account's primary currency (ADR-0107):
     * returns the sell amount, the quoted bid rate and the resulting buy amount — no money moves.
     * The app shows this for the customer to approve before POSTing the conversion.
     */
    @GET
    @Path("/accounts/{accountId}/pockets/{currency}/convert/quote")
    @Authorize(action = "customer.pockets.read", resource = "#accountId")
    @Blocking
    fun convertQuote(@PathParam("accountId") accountId: UUID, @PathParam("currency") currency: String): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        return when (val plan = planSweep(accountId, customer.partyId, currency)) {
            is SweepPlan.Rejected -> sweepError(plan)
            is SweepPlan.Ready -> Response.ok(plan.toJson()).type(MediaType.APPLICATION_JSON).build()
        }
    }

    /**
     * Convert a pocket's whole balance to the account's primary currency (ADR-0107). An own-account
     * cross-currency FX move (PSD2 RTS Art. 15 SCA-exempt): the full source balance is the sell leg,
     * so the pocket zeroes and becomes closeable. Routed through transaction-service as a
     * sell-specified TRANSFER (source == target account) so the double-entry ledger stays authoritative.
     */
    @POST
    @Path("/accounts/{accountId}/pockets/{currency}/convert")
    @Authorize(action = "customer.pockets.convert", resource = "#accountId")
    @Blocking
    fun convertPocket(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        val plan = when (val p = planSweep(accountId, customer.partyId, currency)) {
            is SweepPlan.Rejected -> return sweepError(p)
            is SweepPlan.Ready -> p
        }
        val out = objectMapper.createObjectNode()
        out.put("idempotencyKey", idempotencyKey?.takeIf { it.isNotBlank() } ?: "pocket-convert-${UUID.randomUUID()}")
        out.put("type", "TRANSFER")
        // Single IBAN: a pocket-to-pocket move is source == target (ADR-0024/0107).
        out.put("sourceAccountId", accountId.toString())
        out.put("targetAccountId", accountId.toString())
        // Buy leg = primary (payment currency); sell leg = the pocket currency, fixed to the full
        // balance via baseAmount so the source pocket debits to exactly zero (ADR-0107 D1).
        out.put("amount", plan.buyAmount.toPlainString())
        out.put("currencyCode", plan.primaryCurrency)
        out.put("baseCurrencyCode", plan.sellCurrency)
        out.put("baseAmount", plan.sellAmount.toPlainString())
        out.put("description", "Směna ${plan.sellCurrency} → ${plan.primaryCurrency}")
        out.put("valueDate", java.time.LocalDate.now(clock).toString())
        out.put("initiatedByPartyId", customer.partyId.toString())
        out.put("scaExemption", SCA_EXEMPTION_OWN_ACCOUNT)
        val resp = upstream.post(
            "$transactionServiceUrl/api/v1/transactions",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(out),
        )
        val ok = resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
        audit.emit(
            eventType = "CUSTOMER_POCKET_CONVERTED",
            partyId = customer.partyId.toString(),
            operation = "pockets.convert",
            result = if (ok) "SUCCESS" else "FAILURE",
            resourceId = accountId.toString(),
            details = mapOf(
                "sellCurrency" to plan.sellCurrency,
                "sellAmount" to plan.sellAmount.toPlainString(),
                "buyCurrency" to plan.primaryCurrency,
                "buyAmount" to plan.buyAmount.toPlainString(),
            ),
        )
        return if (ok) {
            Response.ok(plan.toJson()).type(MediaType.APPLICATION_JSON).build()
        } else {
            resp
        }
    }

    /**
     * Exchange a chosen amount of one pocket currency into another on the SAME account (ADR-0110) —
     * the general FX move behind the app's currency swap. Own-account, PSD2 RTS Art. 15 SCA-exempt.
     * Body: {"toCurrency":"EUR","amount":"1000.00"} (amount is in the {fromCurrency} major unit).
     * Sell-specified (the sell leg is the exact amount asked), routed through transaction-service as a
     * cross-currency TRANSFER (source == target) so the ledger stays authoritative. The target pocket
     * is opened first if it does not exist yet, so CZK→EUR works from a CZK-only account.
     */
    @POST
    @Path("/accounts/{accountId}/pockets/{fromCurrency}/exchange")
    @Authorize(action = "customer.pockets.convert", resource = "#accountId")
    @Blocking
    fun exchangePocket(
        @PathParam("accountId") accountId: UUID,
        @PathParam("fromCurrency") fromCurrency: String,
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        val toCcy = extractTextField(objectMapper, body, "toCurrency")?.uppercase()
            ?: return badRequest("Missing toCurrency")
        val sellAmount = extractTextField(objectMapper, body, "amount")
            ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
            ?: return badRequest("Missing or malformed amount")
        val plan = when (
            val p = planExchange(
                accountId,
                customer.partyId,
                fromCurrency.uppercase(),
                toCcy,
                sellAmount,
            )
        ) {
            is SweepPlan.Rejected -> return sweepError(p)
            is SweepPlan.Ready -> p
        }
        // Make sure the target pocket exists so the credit lands (idempotent; no-op if already open).
        ensurePocketOpen(accountId, customer.partyId, plan.primaryCurrency)

        val out = objectMapper.createObjectNode()
        out.put("idempotencyKey", idempotencyKey?.takeIf { it.isNotBlank() } ?: "pocket-exchange-${UUID.randomUUID()}")
        out.put("type", "TRANSFER")
        out.put("sourceAccountId", accountId.toString())
        out.put("targetAccountId", accountId.toString())
        out.put("amount", plan.buyAmount.toPlainString())
        out.put("currencyCode", plan.primaryCurrency)
        out.put("baseCurrencyCode", plan.sellCurrency)
        out.put("baseAmount", plan.sellAmount.toPlainString())
        out.put("description", "Směna ${plan.sellCurrency} → ${plan.primaryCurrency}")
        out.put("valueDate", java.time.LocalDate.now(clock).toString())
        out.put("initiatedByPartyId", customer.partyId.toString())
        out.put("scaExemption", SCA_EXEMPTION_OWN_ACCOUNT)
        val resp = upstream.post(
            "$transactionServiceUrl/api/v1/transactions",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(out),
        )
        val ok = resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
        audit.emit(
            eventType = "CUSTOMER_POCKET_EXCHANGED",
            partyId = customer.partyId.toString(),
            operation = "pockets.exchange",
            result = if (ok) "SUCCESS" else "FAILURE",
            resourceId = accountId.toString(),
            details = mapOf(
                "sellCurrency" to plan.sellCurrency,
                "sellAmount" to plan.sellAmount.toPlainString(),
                "buyCurrency" to plan.primaryCurrency,
                "buyAmount" to plan.buyAmount.toPlainString(),
            ),
        )
        return if (ok) Response.ok(plan.toJson()).type(MediaType.APPLICATION_JSON).build() else resp
    }

    // Validate + price a same-account currency exchange of [sellAmount] of [sellCcy] into [toCcy]
    // (ADR-0110). Reuses SweepPlan: Ready.primaryCurrency carries the BUY currency. Fail-closed.
    private fun planExchange(
        accountId: UUID,
        partyId: UUID,
        sellCcy: String,
        toCcy: String,
        sellAmount: java.math.BigDecimal,
    ): SweepPlan {
        if (!isValidCurrency(sellCcy) || !isValidCurrency(toCcy)) {
            return SweepPlan.Rejected(Response.Status.BAD_REQUEST, "invalid_currency", "Invalid currency code")
        }
        if (sellCcy == toCcy) {
            return SweepPlan.Rejected(Response.Status.BAD_REQUEST, "same_currency", "Source and target must differ")
        }
        if (sellAmount.signum() <= 0) {
            return SweepPlan.Rejected(Response.Status.BAD_REQUEST, "bad_amount", "Amount must be positive")
        }
        val supported = supportedCurrenciesFor(accountId, partyId)
        if (supported != null && toCcy !in supported) {
            return SweepPlan.Rejected(
                Response.Status.fromStatusCode(422),
                "currency_not_supported",
                "Currency not offered",
            )
        }
        val balance = pocketBookedBalance(accountId, partyId, sellCcy)
            ?: return SweepPlan.Rejected(
                Response.Status.BAD_GATEWAY,
                "balance_unavailable",
                "Cannot read pocket balance",
            )
        if (balance < sellAmount) {
            return SweepPlan.Rejected(Response.Status.CONFLICT, "insufficient_funds", "Not enough $sellCcy to exchange")
        }
        val rate = fxBidRate(partyId, sellCcy, toCcy)
            ?: return SweepPlan.Rejected(Response.Status.BAD_GATEWAY, "fx_rate_unavailable", "No FX rate quoted")
        val buy = sellAmount.multiply(rate).setScale(PRIMARY_MINOR_UNIT_SCALE, java.math.RoundingMode.DOWN)
        if (buy.signum() <= 0) {
            return SweepPlan.Rejected(Response.Status.CONFLICT, "amount_too_small", "Amount too small to exchange")
        }
        return SweepPlan.Ready(sellCcy, sellAmount, toCcy, buy, rate)
    }

    // Open the [currency] pocket if the account does not already have it (idempotent best-effort).
    private fun ensurePocketOpen(accountId: UUID, partyId: UUID, currency: String) {
        if (currency in openPocketCurrencies(accountId, partyId)) return
        upstream.post(
            "$accountServiceUrl/api/v1/accounts/$accountId/pockets",
            partyId.toString(),
            """{"currencyCode":"$currency"}""",
        )
    }

    // Plan a pocket sweep: resolve the primary currency, read the full source balance, quote the
    // bid rate and compute the buy amount. Returns a Rejected with a precise reason/HTTP status
    // (own-account, fail-closed) so both the quote and the execute path render the same errors.
    private fun planSweep(accountId: UUID, partyId: UUID, currency: String): SweepPlan {
        val sellCcy = currency.uppercase()
        if (!isValidCurrency(sellCcy)) {
            return SweepPlan.Rejected(Response.Status.BAD_REQUEST, "invalid_currency", "Invalid currency code")
        }
        val primary = primaryPocketCurrency(accountId, partyId)
            ?: return SweepPlan.Rejected(
                Response.Status.BAD_GATEWAY,
                "primary_unresolved",
                "Cannot resolve primary currency",
            )
        if (sellCcy == primary) {
            return SweepPlan.Rejected(
                Response.Status.BAD_REQUEST,
                "is_primary",
                "The primary currency cannot be converted",
            )
        }
        val sell = pocketBookedBalance(accountId, partyId, sellCcy)
            ?: return SweepPlan.Rejected(
                Response.Status.BAD_GATEWAY,
                "balance_unavailable",
                "Cannot read pocket balance",
            )
        if (sell.signum() <= 0) {
            return SweepPlan.Rejected(Response.Status.CONFLICT, "pocket_empty", "Nothing to convert in this pocket")
        }
        val rate = fxBidRate(partyId, sellCcy, primary)
            ?: return SweepPlan.Rejected(Response.Status.BAD_GATEWAY, "fx_rate_unavailable", "No FX rate quoted")
        // Bank buys the customer's pocket currency at the bid; round the buy down to the primary
        // minor unit (the rounding sub-unit and spread stay in the bank's FX-position GL).
        val buy = sell.multiply(rate).setScale(PRIMARY_MINOR_UNIT_SCALE, java.math.RoundingMode.DOWN)
        if (buy.signum() <= 0) {
            return SweepPlan.Rejected(Response.Status.CONFLICT, "amount_too_small", "Balance too small to convert")
        }
        return SweepPlan.Ready(sellCcy, sell, primary, buy, rate)
    }

    private fun sweepError(r: SweepPlan.Rejected): Response = Response.status(r.status)
        .entity(mapOf("error" to r.code, "message" to r.message))
        .type(MediaType.APPLICATION_JSON)
        .build()

    // The account's primary (locked) pocket currency, or null if pockets can't be read.
    private fun primaryPocketCurrency(accountId: UUID, partyId: UUID): String? {
        val resp = upstream.get("$accountServiceUrl/api/v1/accounts/$accountId/pockets", partyId.toString())
        if (resp.status != 200) return null
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return null) }.getOrNull()
            ?: return null
        val arr = node.get("pockets") ?: return null
        return arr.firstOrNull { it.get("isPrimary")?.asBoolean() == true }
            ?.get("currencyCode")?.asText()?.uppercase()
    }

    // Booked balance of the [currency] pocket as a BigDecimal (zero if no row yet); null if the
    // balance service can't be reached, so the caller fails closed rather than moving money blind.
    private fun pocketBookedBalance(accountId: UUID, partyId: UUID, currency: String): java.math.BigDecimal? {
        val resp = upstream.get("$balanceServiceUrl/api/v1/balances/$accountId", partyId.toString())
        if (resp.status != 200) return null
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return null) }.getOrNull()
            ?: return null
        val balances = node.get("balances") ?: return java.math.BigDecimal.ZERO
        val row = balances.firstOrNull {
            it.get("currency")?.asText()?.equals(currency, ignoreCase = true) == true
        } ?: return java.math.BigDecimal.ZERO
        val raw = row.get("bookedAmount")?.let { if (it.isTextual) it.asText() else it.toString() }
        return raw?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() } ?: java.math.BigDecimal.ZERO
    }

    // The bid rate for base->quote from fx-service (the bank buys the customer's currency at bid).
    private fun fxBidRate(partyId: UUID, base: String, quote: String): java.math.BigDecimal? {
        val resp = upstream.get("$fxServiceUrl/api/v1/fx/rates/$base/$quote", partyId.toString())
        if (resp.status != 200) return null
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return null) }.getOrNull()
            ?: return null
        fun dec(f: String): java.math.BigDecimal? = node.get(f)
            ?.let { if (it.isTextual) it.asText() else it.toString() }
            ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
        return dec("bidRate") ?: dec("rate")
    }

    // True if the pocket for [currency] holds zero booked AND available (or has no balance
    // row yet). Fail-closed: any non-200 / unparseable balance response returns false so a
    // close is refused rather than risking an orphaned balance.
    private fun pocketBalanceIsZero(accountId: UUID, partyId: UUID, currency: String): Boolean {
        val resp = upstream.get("$balanceServiceUrl/api/v1/balances/$accountId", partyId.toString())
        if (resp.status != 200) return false
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return false) }.getOrNull()
            ?: return false
        val balances = node.get("balances") ?: return true
        for (b in balances) {
            if (b.get("currency")?.asText()?.equals(currency, ignoreCase = true) == true) {
                val booked = b.get("bookedAmount")?.asDouble() ?: 0.0
                val available = b.get("availableAmount")?.asDouble() ?: 0.0
                return booked == 0.0 && available == 0.0
            }
        }
        return true
    }

    /**
     * Currencies the customer may still OPEN on this account = the product's supported set
     * (product-catalog, ADR-0109) minus currencies that already have an active pocket. Drives
     * the app's "Add currency" picker. Returns the product's full supported set if pockets
     * can't be read; an empty list if the product has no multi-currency support.
     */
    @GET
    @Path("/accounts/{accountId}/pockets/supported")
    @Authorize(action = "customer.pockets.read", resource = "#accountId")
    @Blocking
    fun supportedCurrencies(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        val supported = supportedCurrenciesFor(accountId, customer.partyId) ?: emptySet()
        val open = openPocketCurrencies(accountId, customer.partyId)
        val openable = supported.filter { it !in open }.sorted()
        return Response.ok(mapOf("currencies" to openable)).type(MediaType.APPLICATION_JSON).build()
    }

    // Product-permitted currencies for the account, or null when not resolvable (catalog down /
    // no multi-currency config) so callers can fail-open. Account JSON carries productId; the
    // product carries multiCurrencyConfig.supportedCurrencies.
    private fun supportedCurrenciesFor(accountId: UUID, partyId: UUID): Set<String>? {
        val accountJson = fetchAccount(accountId, partyId) ?: return null
        val productId = extractTextField(objectMapper, accountJson, "productId") ?: return null
        val resp = upstream.get("$productCatalogUrl/api/v1/products/$productId", partyId.toString())
        if (resp.status != 200) return null
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return null) }.getOrNull()
            ?: return null
        val cfg = node.get("multiCurrencyConfig") ?: return null
        val list = cfg.get("supportedCurrencies") ?: return null
        val set = list.mapNotNull { it.asText()?.uppercase() }.toSet()
        return set.ifEmpty { null }
    }

    // Currencies of the account's currently ACTIVE pockets (best-effort; empty on any error).
    private fun openPocketCurrencies(accountId: UUID, partyId: UUID): Set<String> {
        val resp = upstream.get("$accountServiceUrl/api/v1/accounts/$accountId/pockets", partyId.toString())
        if (resp.status != 200) return emptySet()
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return emptySet()) }.getOrNull()
            ?: return emptySet()
        val arr = node.get("pockets") ?: return emptySet()
        return arr.filter { it.get("status")?.asText()?.equals("ACTIVE", ignoreCase = true) != false }
            .mapNotNull { it.get("currencyCode")?.asText()?.uppercase() }
            .toSet()
    }

    // --- Profile (the caller's own party) ---

    /**
     * The caller's own customer profile (name, contact, KYC status, member-since, consent state).
     * Party-scoped by the JWT party — never a client-supplied id — so a customer only ever reads
     * their own (no IDOR). The party-service response is already customer-safe: it carries no AML
     * status, national id or risk fields (see Party.toResponse).
     */
    @GET
    @Path("/profile")
    @Authorize(action = "customer.profile.read")
    @Blocking
    fun getProfile(): Response {
        val customer = customer()
        return upstream.get("$partyServiceUrl/api/v1/parties/${customer.partyId}", customer.partyId.toString())
    }

    /**
     * Revoke/re-grant marketing consent (mobile app Profile screen). Party-scoped by the JWT party
     * (the M2M call to party-service always carries the CALLER's own id from the token, never a
     * client-supplied one), so a customer can only ever change their own consent — no IDOR. Only
     * `marketingConsent` is accepted here: the onboarding-time `consentGdpr` snapshot has no update
     * path (it isn't revocable GDPR consent in the first place — see party-service's
     * UpdateMarketingConsentCommand kdoc).
     */
    @PATCH
    @Path("/profile/consent")
    @Authorize(action = "customer.profile.consent.update")
    @Blocking
    fun updateConsent(body: String): Response {
        val customer = customer()
        val marketingConsent = extractBooleanField(objectMapper, body, "marketingConsent")
            ?: return badRequest("marketingConsent (boolean) is required")
        val out = """{"marketingConsent":$marketingConsent}"""
        return upstream.patch(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/consent",
            customer.partyId.toString(),
            out,
        )
    }

    // ─── Saved payees (TOP-10 #5) ──────────────────────────────────────────────
    // Server side of the mobile app's device-local PayeeStore. Same shape as /profile above:
    // party-scoped by the JWT party — never a client-supplied id — so a customer only ever
    // reads/writes their own list (no IDOR).

    @GET
    @Path("/payees")
    @Authorize(action = "customer.payees.read")
    @Blocking
    fun listPayees(): Response {
        val customer = customer()
        return upstream.get("$partyServiceUrl/api/v1/parties/${customer.partyId}/payees", customer.partyId.toString())
    }

    @PUT
    @Path("/payees")
    @Authorize(action = "customer.payees.write")
    @Blocking
    fun savePayee(body: String): Response {
        val customer = customer()
        // Re-serialize through Jackson (not raw string interpolation) — name/iban/bic are
        // customer-authored free text and must be JSON-escaped, not spliced into a template.
        val node = runCatching { objectMapper.readTree(body) }.getOrNull() as? ObjectNode
            ?: return badRequest("Malformed payee request body")
        val name = node.get("name")?.takeIf { it.isTextual }?.asText()
            ?: return badRequest("Missing payee name")
        val iban = node.get("iban")?.takeIf { it.isTextual }?.asText()
            ?: return badRequest("Missing payee iban")
        val forwarded = objectMapper.createObjectNode().apply {
            put("name", name)
            put("iban", iban)
            node.get("bic")?.takeIf { it.isTextual }?.let { put("bic", it.asText()) }
        }
        return upstream.put(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/payees",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(forwarded),
        )
    }

    @DELETE
    @Path("/payees/{iban}")
    @Authorize(action = "customer.payees.write")
    @Blocking
    fun deletePayee(@PathParam("iban") iban: String): Response {
        val customer = customer()
        return upstream.delete(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/payees/$iban",
            customer.partyId.toString(),
        )
    }

    /**
     * Pay-to-phone directory lookup. The app sends SHA-256 hashes of phone numbers from the
     * customer's own address book and gets back the subset belonging to parties who opted into
     * being discoverable.
     *
     * What this route deliberately does NOT do:
     *   · it never sees a plaintext phone number (the app hashes before sending), so numbers stay
     *     out of this service's access logs;
     *   · it answers only about numbers the caller already had — it cannot be walked to enumerate
     *     customers, and a party who has not opted in is invisible whatever is asked;
     *   · it returns a party id and a name, never an account, an email or an address. The payment
     *     rail resolves the account from the party id server-side, so knowing someone banks here
     *     does not hand out their account number.
     *
     * The hashing is honest about its limits: it keeps plaintext off the wire and out of logs, it
     * is NOT a privacy guarantee against OpenBank, which holds the numbers anyway. The opt-in is
     * the control that matters.
     */
    @POST
    @Path("/directory/lookup")
    @Authorize(action = "customer.directory.lookup")
    @Blocking
    fun directoryLookup(body: String): Response {
        val customer = customer()
        val parsed = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return badRequest("body must be JSON")
        val hashes = parsed.path("phoneHashes").takeIf { it.isArray }?.mapNotNull { it.asText(null) }
            ?: return badRequest("phoneHashes (array of hex sha-256) is required")
        if (hashes.isEmpty()) return Response.ok(mapOf("matches" to emptyList<Any>())).build()
        val out = objectMapper.createObjectNode()
        out.set<com.fasterxml.jackson.databind.JsonNode>(
            "phoneHashes",
            objectMapper.valueToTree(hashes.take(MAX_DIRECTORY_HASHES)),
        )
        return upstream.post(
            "$partyServiceUrl/api/v1/parties/directory/lookup",
            customer.partyId.toString(),
            out.toString(),
        )
    }

    /**
     * Turn the caller's own pay-to-phone findability on or off. Party-scoped by the JWT party, so
     * a customer can only ever change their own — the id is never taken from the body.
     */
    @PUT
    @Path("/directory/discoverable")
    @Authorize(action = "customer.directory.update")
    @Blocking
    fun setDiscoverable(body: String): Response {
        val customer = customer()
        val discoverable = extractBooleanField(objectMapper, body, "discoverable")
            ?: return badRequest("discoverable (boolean) is required")
        return upstream.put(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}/discoverable",
            customer.partyId.toString(),
            """{"discoverable":$discoverable}""",
        )
    }

    /**
     * Turn a directory hit into something payable, WITHOUT ever telling the payer the payee's
     * account. Body: {"phoneHash":"<hex sha-256>"}.
     *
     * This closes pay-to-phone. Until now the directory resolved a number to a partyId and nothing
     * could be done with it: the customer still typed the account number by hand, so a lookup
     * bought them a name and nothing else (issue #3176).
     *
     * **Why a session token and not an IBAN.** Answering with the payee's account number would turn
     * this route into a harvester: phone numbers are guessable in a way account numbers are not, so
     * anyone could walk a range of numbers and collect IBANs. Instead the edge resolves the account
     * privately and hands back the SAME opaque token the nearby-pay rail already uses (ADR-0095):
     * the payer sees a name and a masked account, signs SCA against that masked form, and
     * [createDomesticPayment] resolves the token back to the real account inside the edge. So a
     * LOOKUP never yields an IBAN — the harvesting threat is what this defeats, and it is defeated
     * because learning the account now costs a real, SCA-signed payment to that person rather than
     * a free directory probe.
     *
     * It is deliberately NOT a promise that the account never reaches the payer at all. Once they
     * have actually paid, the confirmation names the account their money went to (see
     * [createDomesticPayment]), exactly as a statement does; do not build on the stronger reading
     * (issue #3890).
     *
     * **Why the hash and not the partyId.** The caller must prove they already know the number, not
     * merely an id they saw once. Taking a partyId from the body would let anyone with a party id —
     * ids appear in shared payloads and delegation offers — mint a payment target for a stranger.
     *
     * **Opt-in is re-checked here, at payment time.** The lookup that produced the hit may be
     * minutes or days old; someone who has since turned findability off must stop being payable
     * immediately. A non-discoverable party is reported exactly like a number nobody holds — 404
     * with the same body — so this route cannot be used as an existence oracle either.
     */
    @POST
    @Path("/directory/payee")
    @Authorize(action = "customer.directory.lookup")
    @Blocking
    fun directoryPayee(body: String): Response {
        val customer = customer()
        val phoneHash = extractTextField(objectMapper, body, "phoneHash")
            ?.takeIf { it.matches(SHA256_HEX) }
            ?: return badRequest("phoneHash (hex sha-256) is required")

        // Re-run the directory lookup rather than trusting anything the caller carried over. This is
        // the opt-in gate: party-service answers only for parties who are currently discoverable.
        val lookup = upstream.post(
            "$partyServiceUrl/api/v1/parties/directory/lookup",
            customer.partyId.toString(),
            """{"phoneHashes":["$phoneHash"]}""",
        )
        if (lookup.status != 200) return notFoundPayee()
        val match = runCatching { objectMapper.readTree(lookup.entity?.toString() ?: "") }.getOrNull()
            ?.path("matches")?.takeIf { it.isArray && it.size() > 0 }?.get(0)
            ?: return notFoundPayee()
        val payeePartyId = match.path("partyId").asText(null) ?: return notFoundPayee()
        val payeeName = match.path("legalName").asText(null) ?: return notFoundPayee()

        // Paying yourself through the contact picker is a mistake, not a feature: it would create a
        // self-transfer that looks like a payment to someone else in the history.
        if (payeePartyId == customer.partyId.toString()) return badRequest("That is your own number")

        val target = resolvePayableAccount(payeePartyId) ?: return notFoundPayee()
        val token = sessions.create(
            creditorAccountId = target.first,
            creditorPartyId = payeePartyId,
            displayName = payeeName,
            requestedAmount = null,
            creditorMasked = PaymentSessionStore.maskIban(target.second),
        )
        val out = objectMapper.createObjectNode()
        out.put("paymentSessionToken", token)
        out.put("displayName", payeeName)
        out.put("creditorMasked", PaymentSessionStore.maskIban(target.second))
        out.put("expiresInSeconds", PaymentSessionStore.TTL_MS / MILLIS_PER_SECOND)
        return Response.ok(objectMapper.writeValueAsString(out)).type(MediaType.APPLICATION_JSON).build()
    }

    /** Same answer for "no such number", "not discoverable" and "nothing payable" — see [directoryPayee]. */
    private fun notFoundPayee(): Response = Response.status(Response.Status.NOT_FOUND)
        .entity("""{"error":"no payee for that number"}""")
        .type(MediaType.APPLICATION_JSON).build()

    /**
     * The account a contact payment lands on, as (accountId, iban), or null when the party has none
     * worth crediting.
     *
     * Deterministic on purpose: a payee with several accounts must always receive on the same one,
     * or the payer's history and the payee's expectations drift apart. The rule is the narrowest
     * that can be stated honestly — an ACTIVE, CURRENT, CZK account, oldest first, since the account
     * someone has held longest is the one they think of as theirs. SAVINGS is deliberately excluded:
     * crediting a savings account from a stranger's payment is not what either side means, and some
     * savings products restrict inbound transfers.
     */
    private fun resolvePayableAccount(payeePartyId: String): Pair<String, String>? {
        val resp = upstream.get("$accountServiceUrl/api/v1/accounts?partyId=$payeePartyId", payeePartyId)
        if (resp.status != 200) return null
        val accounts = runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray } ?: return null
        return accounts
            .filter { it.path("status").asText() == "ACTIVE" }
            .filter { it.path("accountType").asText() == "CURRENT" }
            .filter { it.path("currencyCode").asText() == "CZK" }
            .sortedBy { it.path("openedAt").asText("") }
            .firstNotNullOfOrNull { node ->
                val id = node.path("id").asText(null) ?: return@firstNotNullOfOrNull null
                val iban = node.path("accountNumber").asText(null) ?: return@firstNotNullOfOrNull null
                id to iban
            }
    }

    // --- Transactions ---

    /**
     * Transaction history for one of the caller's own accounts. transaction-service scopes only by
     * accountId (not party), so ownership is enforced HERE: we resolve the account and confirm its
     * partyId matches the JWT party before proxying — otherwise a customer could read another party's
     * history by guessing an account id (IDOR). Read-only; pagination is passed through.
     */
    @GET
    @Path("/transactions")
    @Authorize(action = "customer.transactions.read")
    @Blocking
    fun listTransactions(
        @QueryParam("accountId") accountIdOrNull: UUID?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        val customer = customer()
        val accountId = accountIdOrNull ?: return badRequest("Missing required query parameter 'accountId'")
        if (!mayReadAccount(accountId, customer.partyId, "ACCOUNT_READ_TRANSACTIONS")) {
            return forbidden("Account does not belong to caller")
        }
        val query = buildTransactionsQuery(accountId, limit, cursor)
        val resp = upstream.get("$transactionServiceUrl/api/v1/transactions$query", customer.partyId.toString())
        return enrichWithCounterpartyIban(resp, accountId, customer.partyId)
    }

    /**
     * Add `counterpartyIban` to each transaction in a page.
     *
     * transaction-service keys both sides by ACCOUNT ID and carries no IBAN, so a client reading
     * the history has no way to address the other side — which is why "repeat this payment" and
     * "pay the sender back" could not be built on it. Resolving the id here, once, is cheaper and
     * far less leaky than teaching every client to walk /accounts itself.
     *
     * Only the IBAN is taken from the counterparty account — never the owner's name, party or
     * balance. An IBAN the customer already transacted with is on their statement anyway; the rest
     * of that account is none of their business.
     *
     * The field is ABSENT, not empty, when it cannot be resolved: a counterparty at another bank
     * has no account here to look up, and an account this caller may not read stays unreadable.
     * Absent means "we could not say", which the app renders as no repeat button — a wrong IBAN
     * would address money at a stranger.
     *
     * Lookups are memoised per request, so a page of twenty payments to one payee costs one call.
     */
    private fun enrichWithCounterpartyIban(resp: Response, accountId: UUID, partyId: UUID): Response {
        if (resp.status != 200) return resp
        val body = (resp.entity as? String)?.takeIf { it.isNotBlank() } ?: return resp
        return runCatching {
            val root = objectMapper.readTree(body)
            val items = root.path("data").takeIf { it.isArray } ?: return resp
            val ibanByAccount = mutableMapOf<String, String?>()
            items.forEach { item ->
                val obj = item as? com.fasterxml.jackson.databind.node.ObjectNode ?: return@forEach
                val source = obj.path("sourceAccountId").asText(null)
                val target = obj.path("targetAccountId").asText(null)
                // The side that is NOT the account being read. A transfer between two of the
                // caller's own accounts still has a counterparty — the other one.
                val other = when (accountId.toString()) {
                    target -> source
                    source -> target
                    else -> null
                } ?: return@forEach
                val iban = ibanByAccount.getOrPut(other) {
                    runCatching { UUID.fromString(other) }.getOrNull()
                        ?.let { fetchAccount(it, partyId) }
                        ?.let { extractTextField(objectMapper, it, "accountNumber") }
                }
                if (iban != null) obj.put("counterpartyIban", iban)
            }
            Response.ok(root.toString()).build()
        }.getOrElse { resp }
    }

    // Fetch an account from account-service by id (with the M2M token + party header). Returns the raw
    // JSON body on 200, or null on any non-200 / empty (not found / upstream error). The single source
    // for both the ownership check and the debtor IBAN used to enrich a payment.
    private fun fetchAccount(accountId: UUID, partyId: UUID): String? {
        val resp = upstream.get("$accountServiceUrl/api/v1/accounts/$accountId", partyId.toString())
        if (resp.status != 200) return null
        return resp.entity?.toString()
    }

    // Ownership oracle: compare the account's owning partyId to the caller's party. Any non-200 (not
    // found / upstream error) is treated as "not owned".
    private fun ownsAccount(accountId: UUID, partyId: UUID): Boolean {
        val body = fetchAccount(accountId, partyId) ?: return false
        return extractOwnerPartyId(body) == partyId.toString()
    }

    /**
     * May this caller read this account — as its owner, or as someone it was shared with?
     *
     * Until now the answer was ownership and nothing else, which made delegated access a ceremony
     * with no consequence: a grantor could share an account, the grantee could accept and pass SCA,
     * and then every read still answered 403 because no route outside CustomerDelegationResource
     * consulted a grant (ADR-0232 "no production caller", issue #3615). This is the caller.
     *
     * Ownership is still checked FIRST and locally — the common case must not depend on another
     * service being up. Only a non-owner pays for a delegation check.
     *
     * **Fail closed.** A delegation-service that is down, slow or unparseable denies. The failure
     * mode of guessing "probably allowed" is disclosing someone's balance to a stranger; the
     * failure mode of denying is a shared account that temporarily reads as unavailable.
     */
    private fun mayReadAccount(accountId: UUID, partyId: UUID, capability: String): Boolean =
        ownsAccount(accountId, partyId) || hasGrant(partyId, "ACCOUNT", accountId, capability)

    /**
     * Ask delegation-service whether an ACTIVE grant covers [capability] on this resource.
     *
     * Deliberately no local projection and no cache: a revoked or suspended grant must stop working
     * on the next read, not at the end of a TTL. Revocation that takes effect "soon" is not
     * revocation, and this is the read path of someone else's money.
     */
    private fun hasGrant(granteePartyId: UUID, resourceType: String, resourceId: UUID, capability: String): Boolean {
        val body = """
            {"granteePartyId":"$granteePartyId","resourceType":"$resourceType",
            "resourceId":"$resourceId","capability":"$capability"}
        """.trimIndent().replace("\n", "")
        val resp = runCatching {
            upstream.post("$delegationServiceUrl/api/v1/delegations/check", granteePartyId.toString(), body)
        }.getOrNull() ?: return false
        if (resp.status != 200) return false
        return runCatching {
            objectMapper.readTree(resp.entity?.toString() ?: "").path("granted").asBoolean(false)
        }.getOrDefault(false)
    }

    /**
     * The accounts shared WITH this caller, as account JSON, for appending to their own list.
     *
     * Read capability decides visibility: an account someone may see the balance of belongs in the
     * list. A grant that carries no read capability (nothing does today, but the vocabulary allows
     * it) is not a reason to show the account.
     */
    private fun sharedAccountsFor(partyId: UUID): List<com.fasterxml.jackson.databind.JsonNode> {
        val resp = runCatching {
            upstream.get("$delegationServiceUrl/api/v1/delegations/grantee/$partyId", partyId.toString())
        }.getOrNull() ?: return emptyList()
        if (resp.status != 200) return emptyList()
        val grants = runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray } ?: return emptyList()
        return grants.asSequence()
            .filter { it.path("status").asText() == "ACTIVE" }
            .filter { it.path("resourceType").asText() == "ACCOUNT" }
            .filter { g ->
                g.path("capabilities").any { it.asText() in ACCOUNT_READ_CAPABILITIES }
            }
            .mapNotNull { it.path("resourceId").asText(null) }
            .distinct()
            .mapNotNull { id -> runCatching { UUID.fromString(id) }.getOrNull() }
            // Fetch as the OWNER's party: account-service scopes by id and the header is advisory,
            // but sending the grantee's party id on someone else's account would be a lie in the
            // audit trail. The grant is the authority, and it was just checked above.
            .mapNotNull { id -> fetchAccount(id, partyId) }
            .mapNotNull { body -> runCatching { objectMapper.readTree(body) }.getOrNull() }
            .map { node ->
                // Mark it, so the app can say "shared with you" rather than presenting someone
                // else's account as the customer's own. Silently blending the two would let a
                // delegate believe they own what they were merely lent.
                (node as com.fasterxml.jackson.databind.node.ObjectNode).put("sharedWithMe", true)
            }
            .toList()
    }

    /**
     * Who is allowed to debit this account, and under what authority (ADR-0232 D3/D5, #2990 AC9).
     *
     * Before this existed the answer was "the owner, or nobody": the route 403'd on any account the
     * JWT party did not own, so a delegation grant carrying `ACCOUNT_INITIATE_PAYMENT` was
     * enforceable everywhere except the one place it means anything. The grant lifecycle, the
     * events, the projection and `AuthorizationService`'s amount-aware guard were all live and had
     * zero callers on the money path.
     *
     * **The decision is NOT made here.** The edge asks account-service, which is the only service
     * holding both the delegation projection and the account's true owner, and which re-evaluates
     * it on every request instead of trusting a verdict reached once at offer time. The edge's job
     * is the part only the edge can do: establish WHO is asking, from the validated JWT and never
     * from the body ([customer] is resolved from the `party_id` claim upstream of this call).
     *
     * On the delegated path the account is then re-fetched **as the grantor**. That is not the edge
     * self-authorizing: account-service's `X-Customer-Party-Id` guard is an OWNERSHIP guard and
     * 404s a delegate by design, so the only way to read the account the delegate was just
     * authoritatively told they may debit is to ask as its owner — whose identity came from that
     * same authoritative answer, one call earlier, and is never client-supplied.
     */
    private fun resolveDebitAuthority(
        customer: CustomerIdentity,
        debtorAccountId: UUID,
        amount: String,
        currency: String,
    ): DebitAuthorityResult {
        // Direct path first and unchanged: the owner's own payment costs exactly one call, as before.
        val ownJson = fetchAccount(debtorAccountId, customer.partyId)
        if (ownJson != null && extractOwnerPartyId(ownJson) == customer.partyId.toString()) {
            return DebitAuthorityResult.Allowed(DebitAuthority(ownJson, customer.partyId))
        }
        val decision = fetchDelegatedPaymentDecision(debtorAccountId, customer.partyId, amount, currency)
        val grantor = decision?.grantorPartyId
        if (decision?.authorized != true || grantor == null) {
            // One refusal for "not yours", "no grant", "grant expired" and "over the ceiling" — the
            // classified outcome goes to the audit trail, never to the caller, so this route cannot
            // be used to enumerate other people's accounts or grants.
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = customer.partyId.toString(),
                operation = "payments.domestic",
                result = "DENIED",
                resourceId = debtorAccountId.toString(),
                details = mapOf(
                    "reason" to "DEBIT_NOT_AUTHORIZED",
                    "delegationOutcome" to (decision?.outcome ?: "UNAVAILABLE"),
                    "amount" to amount,
                    "currency" to currency,
                ),
            )
            return DebitAuthorityResult.Refused(forbidden("Debtor account does not belong to caller"))
        }
        val ownerJson = fetchAccount(debtorAccountId, grantor)
            ?: return DebitAuthorityResult.Refused(forbidden("Debtor account does not belong to caller"))
        // Belt and braces: the account we are about to debit must actually be owned by the party
        // account-service named as the grantor. If those ever disagree, something is wrong enough
        // that refusing is the only safe move.
        if (extractOwnerPartyId(ownerJson) != grantor.toString()) {
            return DebitAuthorityResult.Refused(forbidden("Debtor account does not belong to caller"))
        }
        return DebitAuthorityResult.Allowed(
            DebitAuthority(
                accountJson = ownerJson,
                accountOwnerPartyId = grantor,
                onBehalfOf = grantor,
                delegationId = decision.delegationId,
            ),
        )
    }

    /**
     * Ask account-service whether [partyId] may debit [accountId] for this amount, and under which
     * grant. A null return means the question could not be answered (upstream down, non-200,
     * unparseable) and MUST be treated as a refusal by the caller — never as a pass. `partyId` is
     * a query parameter and not `X-Customer-Party-Id`, because that header is the ownership guard
     * and would 404 exactly the delegate being asked about.
     */
    // `internal`, not private: this method IS the outgoing request in the consumer pact
    // (CustomerEdgeDelegatedPaymentPactConsumerTest), which drives it against the Pact mock server
    // so the request under contract is the one production builds. Reflecting the request off the
    // real code while the expectation stays a literal is the asymmetry that makes the pact able to
    // fail (#2290); a test that built the URL itself would agree with itself and prove nothing.
    internal fun fetchDelegatedPaymentDecision(
        accountId: UUID,
        partyId: UUID,
        amount: String,
        currency: String,
    ): DelegatedPaymentDecision? {
        val url = "$accountServiceUrl/api/v1/accounts/$accountId/delegation/payment-authorization" +
            "?partyId=$partyId&amount=${URLEncoder.encode(amount, StandardCharsets.UTF_8)}" +
            "&currency=${URLEncoder.encode(currency, StandardCharsets.UTF_8)}"
        val resp = upstream.get(url, partyId.toString())
        if (resp.status != 200) return null
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: return null) }.getOrNull()
            ?: return null

        // `.takeIf { it.isTextual }` and not `asText(null)`: Jackson answers the STRING "null" for
        // an explicit JSON null, which would turn an absent grant id into a stored literal "null".
        fun text(field: String) = node.path(field).takeIf { it.isTextual }?.asText()
        return DelegatedPaymentDecision(
            authorized = node.path("authorized").asBoolean(false),
            outcome = text("outcome"),
            delegationId = text("delegationId"),
            grantorPartyId = text("grantorPartyId")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        )
    }

    /**
     * Reserve cumulative headroom against the grant before a delegated payment is initiated
     * (ADR-0249 D3). Returns [SpendReservationOutcome.NotDelegated] untouched for an owner's own
     * payment — the counter exists to bound what a DELEGATE may spend, and an owner has no ceiling
     * to count against.
     *
     * Reserve-then-confirm, never count-after: counting settled payments lets two concurrent
     * requests both pass a check that neither would pass alone, and "we noticed afterwards" is not
     * a limit. delegation-service owns the arithmetic because one grant can be spent through
     * domestic, SEPA, instant and cards — a counter per rail cannot see the others.
     *
     * The reservation carries the PAYMENT's idempotency key, not a per-attempt one, so a rail
     * replay takes the headroom exactly once. When the caller supplied no key there is nothing
     * stable to key on, so each attempt reserves separately: that over-counts a retry rather than
     * under-counting a ceiling, and over-counting is the direction a limit may safely fail in.
     *
     * A reservation that cannot be established at all — upstream down, unparseable answer — is a
     * refusal. The ceiling is not advisory, so being unable to consult it must stop the payment.
     */
    private fun reserveDelegatedSpend(
        debit: DebitAuthority,
        customer: CustomerIdentity,
        amount: String,
        currency: String,
        idempotencyKey: String?,
    ): SpendReservationOutcome {
        val grantId = debit.delegationId ?: return SpendReservationOutcome.NotDelegated
        val body = objectMapper.createObjectNode().apply {
            put("amount", amount)
            put("currency", currency)
            put("idempotencyKey", idempotencyKey?.takeIf { it.isNotBlank() } ?: Ids.randomId().toString())
            put("operationType", "DOMESTIC_PAYMENT")
        }
        val resp = runCatching {
            upstream.post(
                "$delegationServiceUrl/api/v1/delegations/$grantId/reservations",
                customer.partyId.toString(),
                objectMapper.writeValueAsString(body),
            )
        }.getOrNull()
        val reservationId = resp
            ?.takeIf { it.statusInfo.family == Response.Status.Family.SUCCESSFUL }
            ?.let { extractTextField(objectMapper, (it.entity as? String).orEmpty(), "reservationId") }
        if (reservationId == null) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = customer.partyId.toString(),
                operation = "payments.domestic",
                result = "DENIED",
                resourceId = debit.delegationId,
                details = mapOf(
                    "reason" to "DELEGATED_SPEND_CEILING",
                    "upstreamStatus" to (resp?.status?.toString() ?: "UNAVAILABLE"),
                    "amount" to amount,
                    "currency" to currency,
                ),
            )
            // The delegate already knows they hold this grant — the authorization decision said so
            // one call ago — so naming their own ceiling is not an oracle about anyone else's
            // account. How much headroom is left is deliberately NOT echoed: that is the grantor's
            // configuration, and the 409 body carrying it stays in the audit trail.
            return SpendReservationOutcome.Refused(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(
                        """{"error":"This payment is over the spending limit set for you on this account",""" +
                            """"code":"DELEGATED_SPEND_LIMIT_EXCEEDED"}""",
                    )
                    .type(MediaType.APPLICATION_JSON)
                    .build(),
            )
        }
        return SpendReservationOutcome.Held(SpendReservation(grantId, reservationId))
    }

    /**
     * Settle a held reservation: [confirmed] keeps the headroom consumed, otherwise it comes back.
     *
     * Every failure branch after a successful reserve must reach this with `confirmed = false`. A
     * leaked reservation silently shrinks the delegate's ceiling until it expires, which is a
     * defect the customer experiences as their limit quietly shrinking for no stated reason.
     *
     * "Confirmed" here means the instruction was ACCEPTED by the rail, not that it settled in
     * clearing. Tracking true settlement would need an async outcome this synchronous route does
     * not have; a payment that is accepted and later fails in clearing therefore leaves the
     * headroom consumed. That over-counts rather than under-counts, which is the safe direction
     * for a ceiling, and it is stated here rather than implied.
     */
    private fun settleDelegatedSpend(reservation: SpendReservation?, customer: CustomerIdentity, confirmed: Boolean) {
        if (reservation == null) return
        val verb = if (confirmed) "confirm" else "release"
        runCatching {
            upstream.post(
                "$delegationServiceUrl/api/v1/delegations/${reservation.delegationId}" +
                    "/reservations/${reservation.reservationId}/$verb",
                customer.partyId.toString(),
                "{}",
            )
        }
    }

    // Resolve the caller's legal name from party-service (for the debtorName a domestic payment needs).
    // Party-scoped by the JWT party; null on any non-200 / missing field.
    private fun fetchPartyLegalName(partyId: UUID): String? {
        val resp = upstream.get("$partyServiceUrl/api/v1/parties/$partyId", partyId.toString())
        if (resp.status != 200) return null
        return extractTextField(objectMapper, resp.entity?.toString() ?: return null, "legalName")
    }

    // --- Statements (closed-period records for one of the caller's own accounts) ---

    /**
     * List the retained period-close statement records for one of the caller's own accounts.
     * statement-service scopes only by accountId, so ownership is enforced HERE: the accountId must
     * resolve to the JWT party (403 otherwise) — same IDOR guard as the transactions read. Read-only;
     * JSON list of period-close records (the binary document render is a sibling route below).
     */
    @GET
    @Path("/statements/{accountId}")
    @Authorize(action = "customer.statements.read", resource = "#accountId")
    @Blocking
    fun listStatements(@PathParam("accountId") accountId: UUID): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        return upstream.get("$statementServiceUrl/api/v1/statements/$accountId", customer.partyId.toString())
    }

    /**
     * Render a single closed statement on demand as a document (camt.053 XML / MT940 / PDF text) for
     * one of the caller's OWN accounts. statement-service holds only the period-close record and
     * renders the document deterministically on demand (ADR-0035) — nothing is stored. Ownership is
     * enforced HERE (same IDOR guard as the list). The currency and format are validated against
     * fixed allow-lists before building the upstream URL (deny-by-default; no arbitrary passthrough),
     * and the document is streamed back with the upstream's own Content-Type.
     */
    @GET
    @Path("/statements/{accountId}/{currency}/{legalSequence}")
    @Produces(MediaType.WILDCARD)
    @Authorize(action = "customer.statements.read", resource = "#accountId")
    @Blocking
    fun renderStatement(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @PathParam("legalSequence") legalSequence: Long,
        @QueryParam("format") format: String?,
    ): Response {
        val customer = customer()
        if (!ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        if (!isValidCurrency(currency)) return badRequest("Invalid currency")
        val fmt = normalizeStatementFormat(format) ?: return badRequest("Unsupported format")
        // Send Accept: */* upstream. statement-service's resource is class-@Produces(application/json), so
        // a specific Accept (application/xml/text/plain) trips JAX-RS content negotiation → 406 before the
        // method runs. The render method sets the real Content-Type on its Response (.type(contentType)),
        // which getRaw preserves — so */* matches, the document renders, and the client still gets the
        // correct application/xml or text/plain back.
        return upstream.getRaw(
            "$statementServiceUrl/api/v1/statements/$accountId/$currency/$legalSequence?format=$fmt",
            customer.partyId.toString(),
            MediaType.WILDCARD,
        )
    }

    // --- Domestic payments (initiate; settlement is a separate, SCA-gated step) ---

    /**
     * Initiate a domestic payment from one of the caller's OWN accounts. The app sends a lightweight
     * body (debtorAccountId, amount, currency, creditorAccountNumber "number/bankcode", creditorName,
     * symbols, reference); domestic-payment-service needs the full instruction, so the edge ENRICHES it:
     * it resolves the debtor's own account number + bank code (from the account's Czech IBAN) and the
     * debtor's legal name (party-service), and splits the creditor "number/bankcode". Ownership is
     * enforced HERE — the debtorAccountId must belong to the JWT party (IDOR guard). This step creates
     * and screens the instruction; it does NOT move money (settlement is a later, SCA-gated step). The
     * caller's Idempotency-Key is forwarded so an app retry replays rather than duplicates.
     */
    @POST
    @Path("/domestic-payments")
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun createDomesticPayment(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        // Read the debtor with Jackson (same last-wins semantics as the upstream) so the value we
        // ownership-check is exactly the one we forward — closing the double-`debtorAccountId` IDOR bypass.
        val debtor = parseDebtorAccountId(objectMapper, body)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return forbidden("Missing or malformed debtorAccountId")
        // The amount is read BEFORE the debit guard, not just before the SCA gate: the delegation
        // ceiling is per-transaction, so an authorization asked without the amount is a different
        // (and weaker) question than the one this route has to ask.
        val amount = extractAmountField(objectMapper, body) ?: return badRequest("Missing amount")
        val currency = extractTextField(objectMapper, body, "currency") ?: "CZK"
        // Owner OR delegate (ADR-0232 D3/D5). `resolveDebitAuthority` returns the account JSON, the
        // party whose money is moving, and — when this is a delegated debit — the grant that
        // permitted it, or an audited refusal.
        val debit = when (val authority = resolveDebitAuthority(customer, debtor, amount, currency)) {
            is DebitAuthorityResult.Refused -> return authority.response
            is DebitAuthorityResult.Allowed -> authority.authority
        }
        val accountJson = debit.accountJson
        val debtorIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Cannot resolve debtor account number")
        val (debtorAcctNo, debtorBank) = czechIbanToBban(debtorIban)
            ?: return badRequest("Debtor account is not a Czech IBAN")
        // The debtor NAME on the instruction is the account HOLDER's, not the initiator's. On a
        // delegated payment those differ, and getting it wrong would put the delegate's name on the
        // grantor's outgoing transfer — wrong on the counterparty's statement, and wrong for every
        // downstream screening/AML party resolution that reads it.
        val debtorName = fetchPartyLegalName(debit.accountOwnerPartyId)
            ?: return badRequest("Cannot resolve debtor name")
        // NearbyPay (ADR-0095): if a paymentSessionToken is present, resolve the real creditor from
        // the in-edge session store — the payer never SUPPLIES the true account, they only hold a
        // token and a mask; for SCA dynamic-linking we compare against that masked form (which is
        // what the app signed).
        //
        // Request-side only, and say so: the confirmation below is the upstream
        // DomesticPaymentResponse returned verbatim, and that DTO declares creditorAccountNumber /
        // creditorBankCode / creditorName as required fields — so the payer's device DOES receive
        // the account once the payment is made, here and again on every `getDomesticPaymentStatus`
        // poll. That is intended (it is their own payment; `enrichWithCounterpartyIban` re-adds the
        // counterparty IBAN on the next /transactions page anyway, and ADR-0095 — which formalises
        // and supersedes this rail — hands the payer the full SPAYD descriptor by design). What is
        // not intended is reading the masking as a response-side control: it is not one, and
        // anything built on that reading is built on nothing (issue #3890, #3176).
        // Pinned by NearbyPayCreditorDisclosureTest.
        val sessionTokenRaw = extractTextField(objectMapper, body, "paymentSessionToken")
        val nearbySession = sessionTokenRaw?.let { sessions.resolve(it) }
        if (sessionTokenRaw != null && nearbySession == null) {
            return badRequest("Payment session expired or unknown")
        }
        val creditorRaw: String
        val creditorForSca: String
        if (nearbySession != null) {
            val creditorAcctJson = fetchAccount(
                UUID.fromString(nearbySession.creditorAccountId),
                UUID.fromString(nearbySession.creditorPartyId),
            ) ?: return badRequest("Payment session: cannot resolve creditor account")
            creditorRaw = extractTextField(objectMapper, creditorAcctJson, "accountNumber")
                ?: return badRequest("Payment session: creditor account has no IBAN")
            creditorForSca = nearbySession.creditorMasked
        } else {
            creditorRaw = extractTextField(objectMapper, body, "creditorAccountNumber")
                ?: return badRequest("Missing creditorAccountNumber")
            creditorForSca = creditorRaw
        }
        val (creditorAcctNo, creditorBank) = resolveCreditorBban(creditorRaw)
            ?: return badRequest("Malformed creditor account (expected number/bankcode or Czech IBAN)")
        val enriched = buildDomesticRequest(
            objectMapper,
            body,
            debtor.toString(),
            debtorAcctNo,
            debtorBank,
            debtorName,
            creditorAcctNo,
            creditorBank,
        ) ?: return badRequest("Malformed or incomplete payment body")
        // Cumulative ceiling (ADR-0249 D3) BEFORE the SCA gate, not after: a payment that the
        // delegate's monthly limit will refuse must not first cost them a biometric prompt and a
        // single-use challenge they cannot get back. Every return below this point releases.
        val reservation = when (val held = reserveDelegatedSpend(debit, customer, amount, currency, idempotencyKey)) {
            is SpendReservationOutcome.Refused -> return held.response
            is SpendReservationOutcome.Held -> held.reservation
            SpendReservationOutcome.NotDelegated -> null
        }
        // Settlement gate (ADR-0021): no money path without a device-signed, amount+payee-bound,
        // single-use SCA approval. The compare-and-consume happens in sca-service, atomically.
        // The challenge belongs to the INITIATOR (the delegate's own device), not to the account
        // holder — a delegate authenticates as themselves; the grant is what makes it their debit
        // to make. So `customer` here stays the delegate on the delegated path, deliberately.
        scaGate(scaChallengeId, customer, amount, currency, creditorForSca, "payments.domestic")?.let {
            settleDelegatedSpend(reservation, customer, confirmed = false)
            return it
        }
        val resp = upstream.post(
            "$domesticPaymentServiceUrl/api/v1/domestic-payments",
            customer.partyId.toString(),
            enriched,
            idempotencyKey,
        )
        settleDelegatedSpend(
            reservation,
            customer,
            confirmed = resp.statusInfo.family == Response.Status.Family.SUCCESSFUL,
        )
        // Receiver-side honesty (ADR-0108): a 2xx here means the instruction was ACCEPTED, not that
        // the money settled. Bind the created payment id to the session so the receiver's status poll
        // can reconcile true settlement, and only flip to PAID now if the create already reached the
        // terminal SETTLED state (the synchronous happy path); otherwise the session stays PROCESSING.
        if (nearbySession != null && resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
            val respBody = (resp.entity as? String).orEmpty()
            extractTextField(objectMapper, respBody, "id")?.let { sessions.attachPayment(sessionTokenRaw, it) }
            if (extractTextField(objectMapper, respBody, "status") == "SETTLED") sessions.markPaid(sessionTokenRaw)
        }
        auditPayment(resp, customer, "payments.domestic", amount, currency, creditorRaw, scaChallengeId, debit)
        return resp
    }

    /**
     * Read-only status of one of the caller's OWN domestic payments — the completion of ADR-0108 for
     * the app (PR openbank-app#137 follow-up). The Send screen polls this after a create returns merely
     * "accepted" (RECEIVED/VALIDATED/SENT_TO_CLEARING) so the green success screen only appears once the
     * payment is irrevocably in clearing / credited (SETTLED), never on instruction acceptance alone.
     * Proxies domestic-payment GET /api/v1/domestic-payments/{id}, whose DomesticPaymentResponse carries
     * `status` (and timestamps).
     *
     * Ownership (IDOR guard): domestic-payment's GET is not party-scoped, so the edge enforces it HERE —
     * the payment's debtorAccountId must belong to the JWT party. A malformed id, an upstream miss, and
     * a payment owned by someone else all collapse to an indistinguishable 404, so a caller can never
     * probe another party's payment ids.
     */
    @GET
    @Path("/domestic-payments/{paymentId}")
    @Authorize(action = "customer.payments.read")
    @Blocking
    fun getDomesticPaymentStatus(@PathParam("paymentId") paymentId: String): Response {
        val customer = customer()
        val id = runCatching { UUID.fromString(paymentId) }.getOrNull()
            ?: return notFound("Payment not found")
        val resp = upstream.get("$domesticPaymentServiceUrl/api/v1/domestic-payments/$id", customer.partyId.toString())
        if (resp.status != 200) return notFound("Payment not found")
        val payJson = (resp.entity as? String).orEmpty()
        val debtorAccountId = extractTextField(objectMapper, payJson, "debtorAccountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return notFound("Payment not found")
        if (!ownsAccount(debtorAccountId, customer.partyId)) return notFound("Payment not found")
        return resp
    }

    // --- SEPA payments (cross-border / EUR-area credit transfer; initiate only) ---

    /**
     * Initiate a SEPA credit transfer from one of the caller's OWN accounts. Like the domestic route
     * the app sends a lightweight body (debtorAccountId, amount, currency, creditorIban, creditorName,
     * optional creditorBic, reference); sepa-payment-service needs the debtor IBAN + legal name + a
     * payment type, so the edge enriches: the debtor IBAN is the account's own IBAN (SEPA is
     * IBAN-native — no BBAN conversion), the debtor legal name comes from party-service, and `type`
     * defaults to SCT (a standard credit transfer). Ownership is enforced HERE (debtorAccountId must
     * belong to the JWT party). Initiation creates + screens the instruction only; money movement
     * stays SCA-gated. The caller's Idempotency-Key is forwarded.
     */
    @POST
    @Path("/sepa-payments")
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    fun createSepaPayment(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val debtor = parseDebtorAccountId(objectMapper, body)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return forbidden("Missing or malformed debtorAccountId")
        val accountJson = fetchAccount(debtor, customer.partyId)
            ?: return forbidden("Debtor account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Debtor account does not belong to caller")
        }
        val debtorIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Cannot resolve debtor IBAN")
        val debtorName = fetchPartyLegalName(customer.partyId)
            ?: return badRequest("Cannot resolve debtor name")
        val enriched = buildSepaRequest(objectMapper, body, debtor.toString(), debtorIban, debtorName)
            ?: return badRequest("Malformed or incomplete payment body (need creditorIban, creditorName, amount)")
        val amount = extractAmountField(objectMapper, body) ?: return badRequest("Missing amount")
        val currency = extractTextField(objectMapper, body, "currency") ?: "EUR"
        val creditorIban = extractTextField(objectMapper, body, "creditorIban")
        scaGate(scaChallengeId, customer, amount, currency, creditorIban, "payments.sepa")?.let { return it }
        val resp = upstream.post(
            "$sepaPaymentServiceUrl/api/v1/sepa-payments",
            customer.partyId.toString(),
            enriched,
            idempotencyKey,
        )
        auditPayment(resp, customer, "payments.sepa", amount, currency, creditorIban, scaChallengeId)
        return resp
    }

    /**
     * Initiate a SEPA Instant (SCT Inst) credit transfer — sub-10s settlement, from one of the
     * caller's OWN accounts. Same shape and guards as [createSepaPayment] (ownership, debtor
     * IBAN/name resolution, SCA gate), but routed to sepa-instant on the instant rail. The debtor
     * fields are resolved server-side; the app sends only creditorIban/creditorName/amount/reference.
     */
    @POST
    @Path("/sepa-instant")
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    fun createSepaInstant(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val debtor = parseDebtorAccountId(objectMapper, body)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return forbidden("Missing or malformed debtorAccountId")
        val accountJson = fetchAccount(debtor, customer.partyId)
            ?: return forbidden("Debtor account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Debtor account does not belong to caller")
        }
        val debtorIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Cannot resolve debtor IBAN")
        val debtorName = fetchPartyLegalName(customer.partyId)
            ?: return badRequest("Cannot resolve debtor name")
        val creditorIban = extractTextField(objectMapper, body, "creditorIban")
            ?: return badRequest("Missing creditorIban")
        val creditorName = extractTextField(objectMapper, body, "creditorName")
            ?: return badRequest("Missing creditorName")
        val amount = extractAmountField(objectMapper, body) ?: return badRequest("Missing amount")
        val currency = extractTextField(objectMapper, body, "currency") ?: "EUR"
        scaGate(scaChallengeId, customer, amount, currency, creditorIban, "payments.sepaInstant")?.let { return it }
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: "scti-$debtor-$creditorIban-$amount"
        val request = buildSctInstRequest(
            body, key, debtor.toString(), debtorIban, debtorName, creditorIban, creditorName, amount, currency,
        )
        val resp = upstream.post(
            "$sepaInstantServiceUrl/api/v1/sepa-instant",
            customer.partyId.toString(),
            request,
            key,
        )
        auditPayment(resp, customer, "payments.sepaInstant", amount, currency, creditorIban, scaChallengeId)
        return resp
    }

    /**
     * List the caller's SCT Inst payments for ONE of their OWN accounts. accountId is required and
     * ownership-checked (IDOR guard); sepa-instant's by-debtor list is account-scoped, so this can
     * never surface another party's payments. Read-only.
     */
    @GET
    @Path("/sepa-instant")
    @Authorize(action = "customer.payments.read")
    @Blocking
    fun listSepaInstant(@QueryParam("accountId") accountIdOrNull: UUID?): Response {
        val customer = customer()
        val accountId = accountIdOrNull ?: return badRequest("Missing required query parameter 'accountId'")
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        return upstream.get(
            "$sepaInstantServiceUrl/api/v1/sepa-instant/debtor/$accountId",
            customer.partyId.toString(),
        )
    }

    /**
     * Request a recall (camt.056) of a settled SCT Inst payment the caller sent from one of their
     * OWN accounts — "I sent this by mistake". sepa-instant's recall/getById are NOT party-scoped,
     * so the edge enforces ownership HERE: the owned account's IBAN (accountNumber) must equal the
     * payment's debtorIban, else the payment is rejected as not-found (no existence oracle). Recall
     * is a scheme REQUEST, not a guaranteed reversal — the counterparty bank may decline.
     */
    @POST
    @Path("/sepa-instant/{paymentId}/recall")
    @Authorize(action = "customer.payments.update", resource = "#paymentId")
    @Blocking
    fun recallSepaInstant(
        @PathParam("paymentId") paymentId: UUID,
        @QueryParam("accountId") accountIdOrNull: UUID?,
        body: String,
    ): Response {
        val customer = customer()
        val accountId = accountIdOrNull ?: return badRequest("Missing required query parameter 'accountId'")
        val accountJson = fetchAccount(accountId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val ownedIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Cannot resolve account IBAN")
        val payResp = upstream.get(
            "$sepaInstantServiceUrl/api/v1/sepa-instant/$paymentId",
            customer.partyId.toString(),
        )
        if (payResp.status != 200) return forbidden("Payment not found")
        val payNode = runCatching { objectMapper.readTree((payResp.entity as? String).orEmpty()) }.getOrNull()
            ?: return forbidden("Payment not found")
        // Compare IBANs normalised (spaces stripped, upper-cased) so a formatting difference between
        // account-service and sepa-instant can never false-reject a legitimate owner's recall.
        val normOwned = ownedIban.replace(" ", "").uppercase()
        val normDebtor = payNode.get("debtorIban")?.asText()?.replace(" ", "")?.uppercase()
        if (normDebtor != normOwned) {
            return forbidden("Payment does not belong to caller")
        }
        val reason = extractTextField(objectMapper, body, "reason")?.takeIf { it.isNotBlank() }
            ?: "REQUESTED_BY_CUSTOMER"
        return upstream.post(
            "$sepaInstantServiceUrl/api/v1/sepa-instant/$paymentId/recall",
            customer.partyId.toString(),
            "{\"reason\":\"$reason\"}",
            null,
            emptyMap(),
        )
    }

    @Suppress("LongParameterList")
    private fun buildSctInstRequest(
        body: String,
        key: String,
        debtorAccountId: String,
        debtorIban: String,
        debtorName: String,
        creditorIban: String,
        creditorName: String,
        amount: String,
        currency: String,
    ): String {
        val out = objectMapper.createObjectNode()
        out.put("idempotencyKey", key)
        out.put("debtorAccountId", debtorAccountId)
        out.put("debtorIban", debtorIban)
        out.put("debtorName", debtorName)
        out.put("creditorIban", creditorIban)
        out.put("creditorName", creditorName)
        extractTextField(objectMapper, body, "creditorBic")?.let { out.put("creditorBic", it) }
        out.put("amount", amount)
        out.put("currency", currency)
        extractTextField(objectMapper, body, "reference")?.let { out.put("remittanceInfo", it) }
        // endToEndId is capped at the SEPA max; derive deterministically from the idempotency key (no random).
        out.put("endToEndId", ("E2E" + key.filter { it.isLetterOrDigit() }).take(E2E_ID_MAX_LEN))
        return objectMapper.writeValueAsString(out)
    }

    /**
     * Initiate an international SWIFT (MT103) credit transfer from one of the caller's OWN accounts.
     * The customer supplies only the beneficiary (name, account/IBAN, receiver BIC), amount, currency
     * and reference; the edge assembles the bank-operational MT103 fields — senderBic (the bank's own
     * BIC), message type, transaction reference, value date, the ordering-customer identity resolved
     * from the debtor account/party, and the amount in minor units. Ownership + SCA gate as for the
     * other payment rails.
     */
    @POST
    @Path("/swift")
    @Authorize(action = "customer.payments.initiate")
    @Blocking
    fun createSwift(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val debtor = parseDebtorAccountId(objectMapper, body)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return forbidden("Missing or malformed debtorAccountId")
        val accountJson = fetchAccount(debtor, customer.partyId)
            ?: return forbidden("Debtor account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Debtor account does not belong to caller")
        }
        val debtorIban = extractTextField(objectMapper, accountJson, "accountNumber")
            ?: return badRequest("Cannot resolve debtor IBAN")
        val debtorName = fetchPartyLegalName(customer.partyId) ?: return badRequest("Cannot resolve debtor name")
        val beneficiaryIban = extractTextField(objectMapper, body, "creditorIban")
            ?: return badRequest("Missing creditorIban")
        val beneficiaryName = extractTextField(objectMapper, body, "creditorName")
            ?: return badRequest("Missing creditorName")
        val receiverBic = extractTextField(objectMapper, body, "bic")
            ?: return badRequest("Missing beneficiary BIC")
        val amount = extractAmountField(objectMapper, body) ?: return badRequest("Missing amount")
        val currency = extractTextField(objectMapper, body, "currency") ?: "EUR"
        scaGate(scaChallengeId, customer, amount, currency, beneficiaryIban, "payments.swift")?.let { return it }
        val key = idempotencyKey?.takeIf { it.isNotBlank() } ?: "swift-$debtor-$beneficiaryIban-$amount"
        val request = buildSwiftRequest(
            key, debtor.toString(), debtorIban, debtorName, beneficiaryIban, beneficiaryName,
            receiverBic, amount, currency, extractTextField(objectMapper, body, "reference"),
        )
        val resp = upstream.post("$swiftServiceUrl/api/v1/swift", customer.partyId.toString(), request, key)
        auditPayment(resp, customer, "payments.swift", amount, currency, beneficiaryIban, scaChallengeId)
        return resp
    }

    @Suppress("LongParameterList")
    private fun buildSwiftRequest(
        key: String,
        debtorAccountId: String,
        debtorIban: String,
        debtorName: String,
        beneficiaryIban: String,
        beneficiaryName: String,
        receiverBic: String,
        amount: String,
        currency: String,
        reference: String?,
    ): String {
        val minor = runCatching { java.math.BigDecimal(amount).movePointRight(2).toLong() }.getOrDefault(0L)
        val out = objectMapper.createObjectNode()
        out.put("idempotencyKey", key)
        out.put("messageType", "MT103")
        out.put("senderBic", bankBic)
        out.put("receiverBic", receiverBic)
        // SWIFT transaction reference is Max16Text; strip non-alphanumerics and cap.
        out.put("transactionReference", key.filter { it.isLetterOrDigit() }.take(SWIFT_REF_MAX_LEN))
        // swift-service validates valueDate as YYYYMMDD (BASIC_ISO_DATE), not ISO with dashes.
        out.put("valueDate", LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE))
        out.put("currency", currency)
        out.put("amountMinorUnits", minor)
        out.put("orderingCustomerAccount", debtorIban)
        out.put("orderingCustomerAccountId", debtorAccountId)
        out.put("orderingCustomerName", debtorName)
        out.put("beneficiaryAccount", beneficiaryIban)
        out.put("beneficiaryName", beneficiaryName)
        reference?.let { out.put("remittanceInfo", it) }
        out.put("chargeCode", "SHA")
        out.put("priority", "NORMAL")
        return objectMapper.writeValueAsString(out)
    }

    /**
     * Read the current status of one of the caller's own SEPA payments (settlement-honest, ADR-0108).
     * The twin of [getDomesticPaymentStatus]: the app polls this after a create returns merely accepted
     * (RECEIVED/VALIDATED/PROCESSING) so the green success screen only appears once the payment is
     * COMPLETED (irrevocably settled), never on instruction acceptance. Proxies sepa-payment's
     * GET /api/v1/sepa-payments/{id}, whose SepaPaymentResponse carries `status` and `debtorAccountId`.
     *
     * Ownership (IDOR guard): sepa-payment's GET is not party-scoped, so the edge enforces it here —
     * the payment's debtorAccountId must belong to the JWT party; a malformed id, an upstream miss, and
     * another party's payment all collapse to an indistinguishable 404 (no existence oracle).
     */
    @GET
    @Path("/sepa-payments/{paymentId}")
    @Authorize(action = "customer.payments.read")
    @Blocking
    fun getSepaPaymentStatus(@PathParam("paymentId") paymentId: String): Response {
        val customer = customer()
        val id = runCatching { UUID.fromString(paymentId) }.getOrNull()
            ?: return notFound("Payment not found")
        val resp = upstream.get("$sepaPaymentServiceUrl/api/v1/sepa-payments/$id", customer.partyId.toString())
        if (resp.status != 200) return notFound("Payment not found")
        val payJson = (resp.entity as? String).orEmpty()
        val debtorAccountId = extractTextField(objectMapper, payJson, "debtorAccountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return notFound("Payment not found")
        if (!ownsAccount(debtorAccountId, customer.partyId)) return notFound("Payment not found")
        return resp
    }

    // --- Transfers (between the caller's OWN accounts) ---

    /**
     * Move money between two of the caller's OWN accounts (e.g. current → savings deposit from the
     * app's drag-and-drop). Both legs are ownership-checked against the JWT party — this route can
     * never reach a third party's account, which is what lets it run as a plain transaction-service
     * TRANSFER (full money path: hold → ledger journal → debit source → credit target) without the
     * payment-rail screening that an external payment goes through. The caller's Idempotency-Key
     * becomes the saga's idempotency key so an app retry replays rather than duplicates.
     *
     * Body: {"sourceAccountId":"...","targetAccountId":"...","amount":"250.00","currency":"CZK",
     *        "description":"..."} (currency defaults to CZK, description optional).
     */
    @POST
    @Path("/transfers")
    @Authorize(action = "customer.transfers.initiate")
    @Blocking
    fun createTransfer(body: String, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val customer = customer()
        val transfer = parseTransferRequest(objectMapper, body)
            ?: return badRequest("Need sourceAccountId, targetAccountId and a positive amount")
        if (transfer.sourceAccountId == transfer.targetAccountId) {
            return badRequest("Source and target accounts must differ")
        }
        if (!ownsAccount(transfer.sourceAccountId, customer.partyId)) {
            return forbidden("Source account does not belong to caller")
        }
        if (!ownsAccount(transfer.targetAccountId, customer.partyId)) {
            return forbidden("Target account does not belong to caller")
        }
        val out = objectMapper.createObjectNode()
        out.put("idempotencyKey", idempotencyKey?.takeIf { it.isNotBlank() } ?: "transfer-${UUID.randomUUID()}")
        out.put("type", "TRANSFER")
        out.put("sourceAccountId", transfer.sourceAccountId.toString())
        out.put("targetAccountId", transfer.targetAccountId.toString())
        out.put("amount", transfer.amount)
        out.put("currencyCode", transfer.currency)
        out.put("description", transfer.description ?: "Převod mezi vlastními účty")
        out.put("valueDate", java.time.LocalDate.now(clock).toString())
        // Identity threading + documented SCA exemption: both legs are ownership-checked above,
        // so this is a same-person, same-PSP transfer — PSD2 RTS 2018/389 Art. 15 exempts it
        // from SCA. transaction-service refuses customer-initiated movements without either a
        // consumed challenge or this exemption marker, so the gate stays closed by default.
        out.put("initiatedByPartyId", customer.partyId.toString())
        out.put("scaExemption", SCA_EXEMPTION_OWN_ACCOUNT)
        val resp = upstream.post(
            "$transactionServiceUrl/api/v1/transactions",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(out),
        )
        audit.emit(
            eventType = "CUSTOMER_TRANSFER_INITIATED",
            partyId = customer.partyId.toString(),
            operation = "transfers.create",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "id"),
            details = mapOf(
                "amount" to transfer.amount.toPlainString(),
                "currency" to transfer.currency,
                "sourceAccountId" to transfer.sourceAccountId.toString(),
                "targetAccountId" to transfer.targetAccountId.toString(),
                "scaExemption" to SCA_EXEMPTION_OWN_ACCOUNT,
            ),
        )
        return resp
    }

    // --- Standing orders (recurring payments / trvalý příkaz) ---

    /**
     * List the caller's standing orders. Party-scoped by the JWT (never a client id), so a
     * customer only ever sees their own — no IDOR. standing-order-service exposes a
     * party-keyed list route which is exactly this.
     */
    @GET
    @Path("/standing-orders")
    @Authorize(action = "customer.standing-orders.read")
    @Blocking
    fun listStandingOrders(): Response {
        val customer = customer()
        return upstream.get(
            "$standingOrderServiceUrl/api/v1/standing-orders/party/${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /**
     * Create a standing order from one of the caller's OWN accounts. The debit account is
     * ownership-checked against the JWT party (IDOR guard, same as payments) and the partyId
     * is injected from the token — the client cannot set up a recurring debit from an account
     * it does not own. A standing order is a future-dated mandate, not an immediate money
     * movement, so it is not itself SCA-gated here (each execution settles server-side); the
     * mandate creation is audited.
     */
    @POST
    @Path("/standing-orders")
    @Authorize(action = "customer.standing-orders.create")
    @Blocking
    fun createStandingOrder(body: String, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val customer = customer()
        val debit = extractTextField(objectMapper, body, "debitAccountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return badRequest("Missing or malformed debitAccountId")
        if (!ownsAccount(debit, customer.partyId)) {
            return forbidden("Debit account does not belong to caller")
        }
        var enriched = injectField(objectMapper, body, "partyId", customer.partyId.toString())
            ?: return badRequest("Malformed standing-order body")
        enriched = injectField(
            objectMapper,
            enriched,
            "idempotencyKey",
            idempotencyKey?.takeIf { it.isNotBlank() } ?: "so-${UUID.randomUUID()}",
        ) ?: enriched
        // The app's create form has no date picker; default the upstream-required startDate to
        // today when the app omits it (or sends it blank), so a standing order can be created
        // from amount + payee alone.
        if (extractTextField(objectMapper, enriched, "startDate") == null) {
            enriched =
                injectField(objectMapper, enriched, "startDate", java.time.LocalDate.now(clock).toString()) ?: enriched
        }
        val resp = upstream.post(
            "$standingOrderServiceUrl/api/v1/standing-orders",
            customer.partyId.toString(),
            enriched,
        )
        audit.emit(
            eventType = "STANDING_ORDER_CREATED",
            partyId = customer.partyId.toString(),
            operation = "standingOrders.create",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "id"),
            details = mapOf(
                "creditorIban" to extractTextField(objectMapper, body, "creditorIban"),
                "frequency" to extractTextField(objectMapper, body, "frequency"),
            ),
        )
        return resp
    }

    @POST
    @Path("/standing-orders/{id}/pause")
    @Authorize(action = "customer.standing-orders.update", resource = "#id")
    @Blocking
    fun pauseStandingOrder(@PathParam("id") id: UUID): Response = standingOrderLifecycle(id, "pause") {
        upstream.post("$standingOrderServiceUrl/api/v1/standing-orders/$id/pause", it, "")
    }

    @POST
    @Path("/standing-orders/{id}/resume")
    @Authorize(action = "customer.standing-orders.update", resource = "#id")
    @Blocking
    fun resumeStandingOrder(@PathParam("id") id: UUID): Response = standingOrderLifecycle(id, "resume") {
        upstream.post("$standingOrderServiceUrl/api/v1/standing-orders/$id/resume", it, "")
    }

    @DELETE
    @Path("/standing-orders/{id}")
    @Authorize(action = "customer.standing-orders.cancel", resource = "#id")
    @Blocking
    fun cancelStandingOrder(@PathParam("id") id: UUID): Response = standingOrderLifecycle(id, "cancel") {
        upstream.delete("$standingOrderServiceUrl/api/v1/standing-orders/$id", it)
    }

    // --- Notifications (in-app feed) ---

    /**
     * The caller's own notification feed. Ownership is implicit: the upstream query is scoped by the
     * JWT party (never a client-supplied id), so a customer can only ever read their own notifications.
     * Read-only; `limit` maps to the upstream `size`, page 0 (cursor paging is a follow-up).
     */
    @GET
    @Path("/notifications")
    @Authorize(action = "customer.notifications.read")
    @Blocking
    fun listNotifications(@QueryParam("limit") @DefaultValue("20") limit: Int): Response {
        val customer = customer()
        val size = limit.coerceIn(1, 100)
        return upstream.get(
            "$notificationServiceUrl/api/v1/notifications?partyId=${customer.partyId}&page=0&size=$size",
            customer.partyId.toString(),
        )
    }

    /** Mark one notification read. partyId injected from the JWT — the service scopes by it (IDOR). */
    @PATCH
    @Path("/notifications/{id}/read")
    @Authorize(action = "customer.notifications.mark-read", resource = "#id")
    @Blocking
    fun markNotificationRead(@PathParam("id") id: UUID): Response {
        val customer = customer()
        return upstream.patch(
            "$notificationServiceUrl/api/v1/notifications/$id/read?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /** Mark ALL of the caller's notifications read. */
    @PATCH
    @Path("/notifications/read-all")
    @Authorize(action = "customer.notifications.mark-read")
    @Blocking
    fun markAllNotificationsRead(): Response {
        val customer = customer()
        return upstream.patch(
            "$notificationServiceUrl/api/v1/notifications/read-all?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /**
     * One notification's full detail, including the rendered [body] (the list route omits it). This
     * is the message read-view for the in-app inbox — operator-initiated messages (ADR-0176) carry
     * their content here. notification-service's detail route resolves by UUID and is NOT party-
     * scoped, so the edge enforces ownership HERE: any notification whose `partyId` differs from the
     * caller is rejected as if absent (no existence leak) — the same IDOR guard the account routes
     * use. The body is already secret-redacted upstream (bodyForRead / TemplateSensitivity); the
     * upstream `partyId`/`recipient` fields are stripped from the customer-facing response.
     */
    @GET
    @Path("/notifications/{id}")
    @Authorize(action = "customer.notifications.read", resource = "#id")
    @Blocking
    fun getNotification(@PathParam("id") id: UUID): Response {
        val customer = customer()
        val resp = upstream.get(
            "$notificationServiceUrl/api/v1/notifications/$id",
            customer.partyId.toString(),
        )
        if (resp.status != 200) return forbidden("Notification not found")
        val node = runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?: return forbidden("Notification not found")
        if (node.get("partyId")?.asText() != customer.partyId.toString()) {
            return forbidden("Notification does not belong to caller")
        }
        val out = objectMapper.createObjectNode()
        out.put("id", node.get("id")?.asText())
        node.get("template")?.asText()?.let { out.put("template", it) }
        node.get("subject")?.asText()?.let { out.put("subject", it) }
        out.put("body", node.get("body")?.asText() ?: "")
        node.get("status")?.asText()?.let { out.put("status", it) }
        node.get("readAt")?.asText()?.let { out.put("readAt", it) }
        node.get("createdAt")?.asText()?.let { out.put("createdAt", it) }
        return Response.ok(out).type(MediaType.APPLICATION_JSON).build()
    }

    /** The caller's push-notification preferences (#2). Party is taken from the JWT, never the client. */
    @GET
    @Path("/notification-preferences")
    @Authorize(action = "customer.notifications.read", resource = "")
    @Blocking
    fun getNotificationPreferences(): Response {
        val customer = customer()
        return upstream.get(
            "$notificationServiceUrl/api/v1/preferences/party/${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /** Set the caller's push-notification preferences (#2). Body: {paymentsPush,productPush,marketingPush}. */
    @PUT
    @Path("/notification-preferences")
    @Authorize(action = "customer.notifications.update", resource = "")
    @Blocking
    fun setNotificationPreferences(body: String): Response {
        val customer = customer()
        return upstream.put(
            "$notificationServiceUrl/api/v1/preferences/party/${customer.partyId}",
            customer.partyId.toString(),
            body,
        )
    }

    // --- In-app engagement surfaces ---

    /**
     * Resolve one named mobile surface for the authenticated customer. The app receives only a
     * typed, catalogue-backed payload; it owns presentation, accessibility and local navigation.
     * The party id is derived from the JWT and injected only at this boundary, never accepted from
     * the device, so changing a query parameter cannot render another customer's campaign.
     */
    @GET
    @Path("/surfaces/{slot}")
    @Authorize(action = "customer.engagement.read", resource = "#slot")
    @Blocking
    fun getSurface(@PathParam("slot") slot: String): Response {
        if (slot !in SURFACE_SLOTS) return Response.status(Response.Status.BAD_REQUEST).build()
        val customer = customer()
        return upstream.get(
            "$engagementServiceUrl/api/v1/surfaces/$slot?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /**
     * Record what the app actually observed: impression, click or dismissal. The edge overwrites
     * any supplied partyId with the JWT party id before forwarding, which makes the feedback loop
     * meaningful without turning it into an IDOR write primitive. Content/slot/type validation is
     * owned by engagement-service, alongside the catalogue it validates against.
     *
     * When the app includes an interactionRef from a PUSH payload, it is validated against the
     * campaign send log for this JWT party before it can become an attribution datum. A reference
     * for another party, a non-PUSH send, or an unknown id all receive the same public error — the
     * endpoint must never disclose which of those cases happened.
     */
    @POST
    @Path("/surfaces/events")
    @Authorize(action = "customer.engagement.record-event")
    @Blocking
    fun recordSurfaceEvent(body: String): Response {
        val customer = customer()
        val event = runCatching { objectMapper.readTree(body) as? ObjectNode }.getOrNull()
            ?: return badRequest("Malformed engagement event")
        // A phone can report attention, never a product outcome. Campaign-service already derives
        // conversions from authoritative account/card events; accepting this value here would let
        // a client turn a click into revenue attribution.
        if (event.path("type").asText() == "CONVERSION") {
            return badRequest("Conversions must originate from authoritative product events")
        }
        // These fields are server-owned. Remove any client attempt before optionally filling them
        // from campaign-service's validated send-log row below.
        event.remove(listOf("campaignId", "stepOrder", "channel"))
        val interactionRefNode = event.get("interactionRef")
        if (interactionRefNode != null) {
            if (!interactionRefNode.isTextual) return badRequest("Invalid interaction reference")
            val interactionRef = runCatching { UUID.fromString(interactionRefNode.asText()) }.getOrNull()
                ?: return badRequest("Invalid interaction reference")
            val validation = upstream.get(
                "$campaignServiceUrl/api/v1/campaigns/interactions/$interactionRef/attribution",
                customer.partyId.toString(),
            )
            if (validation.status != Response.Status.OK.statusCode) {
                // 400/403/404 are deliberately indistinguishable to the mobile client. Upstream
                // failure stays a 502 so the client can safely retry instead of discarding an event.
                return if (validation.status >= UPSTREAM_SERVER_ERROR_MIN) {
                    Response.status(Response.Status.BAD_GATEWAY).build()
                } else {
                    badRequest("Invalid interaction reference")
                }
            }
            val attribution = runCatching {
                objectMapper.readTree(validation.entity as? String ?: "")
            }.getOrNull() ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            val campaignId = attribution.path("campaignId").asText()
                .takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            val stepOrder = attribution.path("stepOrder").asInt(0)
                .takeIf { it >= 0 && attribution.has("stepOrder") }
                ?: return Response.status(Response.Status.BAD_GATEWAY).build()
            val channel = attribution.path("channel").asText()
            if (channel !in setOf("PUSH", "BANNER")) {
                return Response.status(Response.Status.BAD_GATEWAY).build()
            }
            event.put("campaignId", campaignId)
            event.put("stepOrder", stepOrder)
            event.put("channel", channel)
        }
        event.put("partyId", customer.partyId.toString())
        val enriched = objectMapper.writeValueAsString(event)
        return upstream.post(
            "$engagementServiceUrl/api/v1/surfaces/events",
            customer.partyId.toString(),
            enriched,
        )
    }

    // --- Theme preferences (ADR-0190) — edge-local, party-keyed, roams the app look ---

    /** The caller's stored ThemeSpec, 404 when none. Party is taken from the JWT, never the client. */
    @GET
    @Path("/preferences/theme")
    @Authorize(action = "customer.preferences.theme.read", resource = "")
    @Blocking
    fun getThemePreference(): Response {
        val spec = themePrefs.get(customer().partyId)
            ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(spec).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Store the caller's ThemeSpec. The edge only gates shape (valid JSON object, size cap) —
     * the deterministic guardrail validation runs on-device on every read (ADR-0190 §3), so a
     * hand-crafted spec can never render an illegible or spoofed UI.
     */
    @PUT
    @Path("/preferences/theme")
    @Authorize(action = "customer.preferences.theme.update", resource = "")
    @Blocking
    fun setThemePreference(body: String): Response {
        if (body.length > THEME_SPEC_MAX_BYTES) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build()
        }
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
        if (node == null || !node.isObject) {
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
        themePrefs.put(customer().partyId, node.toString())
        return Response.noContent().build()
    }

    // --- SCA device enrollment (ADR-0021) ---

    @POST
    @Path("/sca/parties/{partyId}/devices")
    @Authorize(action = "customer.sca.enroll", resource = "#partyId")
    @Blocking
    fun enrollDevice(@PathParam("partyId") partyId: UUID, body: String): Response {
        val customer = customer()
        if (customer.partyId != partyId) return forbidden("Cannot enrol device for another party")
        val resp = upstream.post(
            "$scaServiceUrl/api/v1/sca/parties/$partyId/devices",
            customer.partyId.toString(),
            body,
        )
        audit.emit(
            eventType = "SCA_DEVICE_ENROLLED",
            partyId = customer.partyId.toString(),
            operation = "sca.enrollDevice",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
        )
        return resp
    }

    // --- SCA challenge (ADR-0021) ---

    /**
     * Initiate an SCA challenge (e.g. to authorise a payment). partyId is injected from the JWT —
     * never read from the body — so a customer can only ever raise a challenge for themselves (the
     * same IDOR-prevention pattern as device enrolment).
     */
    @POST
    @Path("/sca/challenges")
    @Authorize(action = "customer.sca.challenge")
    @Blocking
    fun initiateChallenge(body: JsonNode, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val customer = customer()
        // Deserialise as JsonNode (not String) so RESTEasy Reactive uses Jackson's object reader —
        // a 'body: String' parameter with @Consumes(APPLICATION_JSON) and a co-located @HeaderParam
        // triggers a Quarkus REST body-reader resolution bug that produces an empty-body 400 before
        // the method is invoked.  JsonNode is unambiguous to Jackson and avoids the conflict.
        val node = (body as? ObjectNode) ?: return forbidden("Malformed challenge body")
        node.put("partyId", customer.partyId.toString())
        return upstream.post(
            "$scaServiceUrl/api/v1/sca/challenges",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(node),
            idempotencyKey,
        )
    }

    @GET
    @Path("/sca/challenges/{id}")
    @Authorize(action = "customer.sca.challenge", resource = "#id")
    @Blocking
    fun getChallenge(@PathParam("id") id: UUID): Response {
        val customer = customer()
        return upstream.get("$scaServiceUrl/api/v1/sca/challenges/$id", customer.partyId.toString())
    }

    /**
     * The caller's live SCA challenges awaiting a decision (#8 push/decoupled approval list). The
     * edge scopes the sca-service query by the JWT partyId, so a customer only ever sees their own
     * pending approvals — the path partyId is never taken from the client.
     */
    @GET
    @Path("/sca/pending")
    @Authorize(action = "customer.sca.challenge", resource = "")
    @Blocking
    fun listPendingSca(): Response {
        val customer = customer()
        return upstream.get(
            "$scaServiceUrl/api/v1/sca/parties/${customer.partyId}/challenges/pending",
            customer.partyId.toString(),
        )
    }

    @POST
    @Path("/sca/challenges/{id}/decision")
    @Authorize(action = "customer.sca.decision", resource = "#id")
    @Blocking
    fun recordDecision(@PathParam("id") id: UUID, body: String): Response {
        val customer = customer()
        val resp = upstream.post("$scaServiceUrl/api/v1/sca/challenges/$id/decision", customer.partyId.toString(), body)
        audit.emit(
            eventType = "SCA_DECISION_RECORDED",
            partyId = customer.partyId.toString(),
            operation = "sca.decision",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = id.toString(),
            details = mapOf("decision" to extractTextField(objectMapper, body, "decision")),
        )
        return resp
    }

    // --- Push device registration ---

    /**
     * Register this app instance's push token (FCM/APNs) so the party can receive push
     * notifications. partyId is injected from the JWT, never read from the body (IDOR
     * prevention) — a customer can only register a device for themselves.
     *
     * Body: {"platform":"FCM|APNS","token":"...","appInstance":"...","appVersion":"...","osVersion":"..."}
     */
    @POST
    @Path("/devices")
    @Authorize(action = "customer.devices.register")
    @Blocking
    fun registerDevice(body: String): Response {
        val customer = customer()
        // Inject partyId via Jackson (overwriting any client value) — string surgery on the closing
        // brace would corrupt a body with nested objects into invalid JSON.
        val enriched = injectField(objectMapper, body, "partyId", customer.partyId.toString())
            ?: return forbidden("Malformed device registration body")
        return upstream.post("$notificationServiceUrl/api/v1/devices", customer.partyId.toString(), enriched)
    }

    /** List the calling customer's registered push devices (no tokens returned). */
    @GET
    @Path("/devices")
    @Authorize(action = "customer.devices.read")
    @Blocking
    fun listDevices(): Response {
        val customer = customer()
        return upstream.get(
            "$notificationServiceUrl/api/v1/devices?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    /**
     * Revoke (deactivate) one of the caller's OWN registered devices (#3) — sign it out so it stops
     * receiving push. The partyId is forced from the JWT onto the notification-service call, which
     * scopes the deactivation to that party's tokens, so a customer can never sign out someone
     * else's device even with a guessed device id.
     */
    @DELETE
    @Path("/devices/{id}")
    @Authorize(action = "customer.devices.delete", resource = "#id")
    @Blocking
    fun revokeDevice(@PathParam("id") id: UUID): Response {
        val customer = customer()
        return upstream.delete(
            "$notificationServiceUrl/api/v1/devices/$id?partyId=${customer.partyId}",
            customer.partyId.toString(),
        )
    }

    // --- Onboarding (ADR-0069) ---

    // NOTE: POST /onboarding/start lives in [OnboardingResource], a separate resource
    // class WITHOUT a class-level @RolesAllowed. On this class the class-level
    // @RolesAllowed("ROLE_CUSTOMER") pre-empts a method-level @PermitAll — Quarkus
    // raises a 401 OIDC challenge (www-authenticate: Bearer) before the method
    // annotation is evaluated, even with lazy auth (quarkus.http.auth.proactive=false).
    // Keeping the unauthenticated start route in its own un-annotated class is the only
    // reliable way to make it truly public. /onboarding/account stays here: it REQUIRES
    // an authenticated ROLE_CUSTOMER, so the class-level annotation is correct for it.

    /**
     * Self-service onboarding, Phase 2 (ADR-0069): create the caller's party record bound to
     * their Keycloak identity. The flow is registration-first: the customer self-registers in
     * the openbank-customers realm (Keycloak's registration flow enrols the passkey in the same
     * step), logs in, and calls this route with the fresh token. The party is created with
     * id == JWT `sub`, so the §B1 principal-binding invariant (party id == sub) holds with NO
     * Keycloak admin client — [customer] resolves `sub` when the `party_id` claim is absent.
     *
     * Idempotent: party-service returns the existing record for a repeated id. KYC/AML stay
     * untouched: the party starts PENDING_KYC and goes ACTIVE only through the two-key gate
     * (auto-approve in sandbox), which in turn opens + activates the onboarding accounts.
     *
     * Body: {"legalName":"...","email":"...","taxId":"..."} — legalName/email fall back to the
     * token's `name`/`email` claims (both present after KC self-registration).
     */
    // JAX-RS routing lives in OnboardingResource (same path, more-specific class @Path wins).
    // Called from OnboardingResource#registerParty via CDI proxy so @Authorize fires.
    @Authorize(action = "customer.onboarding.register")
    @Blocking
    fun registerParty(body: String): Response {
        val customer = customer()
        val legalName = extractTextField(objectMapper, body, "legalName")
            ?: jwt.getClaim<String>("name")?.takeIf { it.isNotBlank() }
            ?: return badRequest("legalName missing (not in body nor token)")
        val email = extractTextField(objectMapper, body, "email")
            ?: jwt.getClaim<String>("email")?.takeIf { it.isNotBlank() }
            ?: return badRequest("email missing (not in body nor token)")
        val out = objectMapper.createObjectNode()
        out.put("partyType", "INDIVIDUAL")
        out.put("legalName", legalName)
        out.put("email", email)
        out.put("id", customer.partyId.toString())
        extractTextField(objectMapper, body, "taxId")?.let { out.put("taxId", it) }
        extractTextField(objectMapper, body, "phone")?.let { out.put("phone", it) }
        extractTextField(objectMapper, body, "dateOfBirth")?.let { out.put("dateOfBirth", it) }
        extractTextField(objectMapper, body, "nationality")?.let { out.put("nationality", it) }
        // Onboarding consent capture (mobile app "Agreement" step) — previously accepted by the
        // app's request model but silently dropped here before reaching party-service.
        extractBooleanField(objectMapper, body, "consentGdpr")?.let { out.put("consentGdpr", it) }
        extractBooleanField(objectMapper, body, "consentMarketing")?.let { out.put("consentMarketing", it) }

        // Identity-resolution dedup gate (ADR-0072 §6 / ADR-0094): short-circuits to an existing
        // party (reuse) or a neutral pending state when pid resolves this applicant; null = proceed.
        identityResolutionGate(legalName, body, customer.partyId.toString())?.let { return it }

        val resp = upstream.post(
            "$partyServiceUrl/api/v1/parties",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(out),
            "onboarding-register-${customer.partyId}",
        )
        val respJson = (resp.entity as? String).orEmpty()
        if (resp.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            return Response.status(resp.status).entity(respJson).type(MediaType.APPLICATION_JSON).build()
        }
        val status = extractTextField(objectMapper, respJson, "status") ?: "PENDING_KYC"
        // Populate the pid resolver index with this onboarded identity (incl. the RČ carried as
        // taxId) so tier-1 dedup has data going forward (issue #1294). Best-effort, flag-gated;
        // never fails the registration. party_id == sub here (a fresh NO_MATCH party).
        registerIdentityInPid(customer.partyId.toString(), legalName, body)
        audit.emit(
            eventType = "CUSTOMER_REGISTERED",
            partyId = customer.partyId.toString(),
            operation = "onboarding.register",
            result = "SUCCESS",
            details = mapOf("partyStatus" to status),
        )
        return Response.status(Response.Status.CREATED)
            .entity("""{"partyId":"${customer.partyId}","status":"$status"}""")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    /**
     * Open a customer's first account after KYC is approved (ADR-0069).
     *
     * Requires an authenticated ROLE_CUSTOMER JWT (i.e. customer already has a Keycloak
     * session after passkey registration). Enforces the KYC gate: party must be ACTIVE
     * (kyc-service has approved the case) before an account is opened. This prevents an
     * un-verified party from obtaining an IBAN — AML compliance gate (PSD2/AML6D).
     *
     * Request body mirrors account-service OpenAccountRequest:
     *   {"productId": "...", "accountType": "CURRENT", "currencyCode": "EUR"}
     * partyId is taken from the JWT party_id claim, not the request body (no IDOR risk).
     */
    @POST
    @Path("/onboarding/account")
    @Authorize(action = "customer.onboarding.open-account")
    @Blocking
    fun openAccount(body: String): Response {
        val customer = customer()

        // KYC gate: verify party is ACTIVE before opening account (AML / PSD2 gate)
        val partyResponse = upstream.get(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}",
            customer.partyId.toString(),
        )
        if (partyResponse.status != 200) {
            return Response.status(404)
                .entity("""{"error":"Party not found"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        // getEntity(), not readEntity(): partyResponse is a server-built Response whose
        // body upstream.get() already buffered into a String; readEntity() is client-side.
        // Parse with Jackson (not substringAfter) so the KYC/AML gate reads the real `status`
        // field rather than the first `status:` substring anywhere in the payload.
        val partyJson = (partyResponse.entity as? String).orEmpty()
        val partyStatus = extractTextField(objectMapper, partyJson, "status").orEmpty()
        if (partyStatus != "ACTIVE") {
            return Response.status(422)
                .entity("""{"error":"KYC not approved — party status: $partyStatus"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }

        // Inject partyId from JWT (IDOR prevention) AND the legalName from the party record we
        // just fetched — sanctions screening (ADR-0032 §C) must run on the authoritative name,
        // not one the client could spoof. Jackson-based so a nested body stays valid JSON.
        val legalName = extractTextField(objectMapper, partyJson, "legalName")
            ?: return Response.status(422)
                .entity("""{"error":"Party has no legal name"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        var accountBody = injectField(objectMapper, body, "partyId", customer.partyId.toString())
            ?: return Response.status(400)
                .entity("""{"error":"Malformed account-open body"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        accountBody = injectField(objectMapper, accountBody, "legalName", legalName) ?: accountBody
        val resp = upstream.post("$accountServiceUrl/api/v1/accounts", customer.partyId.toString(), accountBody)
        audit.emit(
            eventType = "CUSTOMER_ACCOUNT_OPENED",
            partyId = customer.partyId.toString(),
            operation = "onboarding.openAccount",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "id"),
            details = mapOf(
                "accountType" to extractTextField(objectMapper, body, "accountType"),
                "currency" to extractTextField(objectMapper, body, "currencyCode"),
            ),
        )
        return resp
    }

    // --- FX rates (read-only; authenticated but not party-scoped) ---

    /**
     * The published FX rate sheet (kurzovní lístek) for the customer app. A read: the edge GETs
     * fx-service's full current-rate list and projects each rich upstream record down to the app's
     * lightweight {base, quote, rate, bid, ask, timestamp} shape — `rate` is the mid-price
     * (bid+ask)/2, with bid/ask kept so the app can render a buy/sell sheet. Authenticated
     * (ROLE_CUSTOMER) but not party-scoped: rates are the same for everyone.
     */
    @GET
    @Path("/fx/rates")
    @Authorize(action = "customer.fx.read")
    @Blocking
    fun fxRates(): Response {
        val customer = customer()
        val resp = upstream.get("$fxServiceUrl/api/v1/fx/rates", customer.partyId.toString())
        val upstreamBody = (resp.entity as? String).orEmpty()
        if (resp.status != 200) {
            return Response.status(resp.status).entity(upstreamBody).type(MediaType.APPLICATION_JSON).build()
        }
        val mapped = mapFxRateList(objectMapper, upstreamBody)
            ?: return Response.status(Response.Status.BAD_GATEWAY).entity("""{"error":"malformed fx rates"}""")
                .type(MediaType.APPLICATION_JSON).build()
        return Response.ok(mapped).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Live FX rate for a currency pair, for the app's in-form currency conversion. The app POSTs
     * (mirroring its other write-shaped calls) but this is a read: the edge GETs fx-service and
     * projects the rich upstream record (bid/ask/source/validity) down to {base, quote, rate,
     * timestamp}. `rate` is the mid-price (bid+ask)/2. base/quote are shape-validated first.
     */
    @POST
    @Path("/fx/rates/{base}/{quote}")
    @Authorize(action = "customer.fx.read")
    @Blocking
    fun fxRate(@PathParam("base") base: String, @PathParam("quote") quote: String): Response {
        val customer = customer()
        if (!isValidCurrency(base) || !isValidCurrency(quote)) return badRequest("Invalid currency")
        val resp = upstream.get("$fxServiceUrl/api/v1/fx/rates/$base/$quote", customer.partyId.toString())
        val upstreamBody = (resp.entity as? String).orEmpty()
        if (resp.status != 200) {
            return Response.status(resp.status).entity(upstreamBody).type(MediaType.APPLICATION_JSON).build()
        }
        val mapped = mapFxRate(objectMapper, upstreamBody, base, quote)
            ?: return Response.status(Response.Status.BAD_GATEWAY).entity("""{"error":"malformed fx rate"}""")
                .type(MediaType.APPLICATION_JSON).build()
        return Response.ok(mapped).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Historical ČNB reference mid-rates for a currency pair (newest-first). When the caller omits
     * bounds, the edge supplies an exact three-calendar-month UTC window. This is deliberately a
     * reference trend, not a promise that a historical commercial quote can be recreated.
     */
    @GET
    @Path("/fx/rates/{base}/{quote}/history")
    @Authorize(action = "customer.fx.read")
    @Blocking
    fun fxRateHistory(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("offset") offset: Int?,
    ): Response {
        val customer = customer()
        if (!isValidCurrency(base) || !isValidCurrency(quote)) return badRequest("Invalid currency")
        if (from != null && !isValidInstant(from)) return badRequest("Invalid 'from' instant: $from")
        if (to != null && !isValidInstant(to)) return badRequest("Invalid 'to' instant: $to")
        val safeLimit = (limit ?: 90).coerceIn(1, 365)
        val safeOffset = (offset ?: 0).coerceAtLeast(0)
        val windowEnd = to ?: java.time.Instant.now().toString()
        val windowStart = from ?: threeMonthWindowStart(java.time.Instant.parse(windowEnd)).toString()
        val url = buildString {
            append("$fxServiceUrl/api/v1/fx/rates/$base/$quote/history?source=CNB&limit=$safeLimit&offset=$safeOffset")
            append("&from=${java.net.URLEncoder.encode(windowStart, "UTF-8")}")
            append("&to=${java.net.URLEncoder.encode(windowEnd, "UTF-8")}")
        }
        val resp = upstream.get(url, customer.partyId.toString())
        val upstreamBody = (resp.entity as? String).orEmpty()
        if (resp.status != 200) {
            return Response.status(resp.status).entity(upstreamBody).type(MediaType.APPLICATION_JSON).build()
        }
        val mapped = mapFxHistoryList(objectMapper, upstreamBody)
            ?: return Response.status(Response.Status.BAD_GATEWAY)
                .entity("""{"error":"malformed fx history"}""")
                .type(MediaType.APPLICATION_JSON).build()
        return Response.ok(mapped).type(MediaType.APPLICATION_JSON).build()
    }

    // --- Cards (lifecycle: list, freeze/unfreeze, block, cancel, limits, controls, issue, reveal) ---
    // PCI: every card READ is masked-PAN only, with exactly one exception — POST /cards/{id}/details,
    // the SCA-gated reveal for a virtual/single-use card. That one response carries PAN/CVV, is never
    // logged, never cached (no-store) and never audited by value.

    /**
     * List the caller's cards (masked PAN only): their own, plus any card shared with them under an
     * ACTIVE `CARD_VIEW` grant (ADR-0249 D2), marked `sharedWithMe`.
     *
     * The caller's own cards are fetched first and returned even if the delegation lookup fails —
     * an unreachable delegation-service must not blank out a customer's own wallet. A shared card is
     * only ever ADDED, never substituted, and never presented as the caller's own.
     */
    @GET
    @Path("/cards")
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun listCards(): Response {
        val customer = customer()
        val own = upstream.get(
            "$cardIssuanceServiceUrl/api/v1/cards/party/${customer.partyId}",
            customer.partyId.toString(),
        )
        if (own.status != 200) return own
        val ownArray = runCatching { objectMapper.readTree((own.entity as? String).orEmpty()) }.getOrNull()
            ?.takeIf { it.isArray } ?: return own
        val shared = sharedCardsFor(customer.partyId)
        if (shared.isEmpty()) return own
        val merged = objectMapper.createArrayNode().apply {
            addAll(ownArray.toList())
            // A card the caller both holds and was granted must appear once, as their own.
            val ownIds = ownArray.mapNotNull { it.path("id").asText(null) }.toSet()
            addAll(shared.filter { it.path("id").asText(null) !in ownIds })
        }
        return Response.ok(merged.toString()).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Freeze (temporarily suspend) a card the caller controls — the reversible self-service lock.
     *
     * Open to a delegate holding `CARD_MANAGE_LIMITS` (ADR-0249 D2): freezing is one of the controls
     * a real disponent expects, it is reversible, and it can only ever REDUCE what the card may do.
     */
    @POST
    @Path("/cards/{id}/freeze")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun freezeCard(@PathParam("id") id: UUID): Response = cardAction(id, "suspend")

    /** Unfreeze (resume) a card the caller controls. Same authority as [freezeCard]. */
    @POST
    @Path("/cards/{id}/unfreeze")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun unfreezeCard(@PathParam("id") id: UUID): Response = cardAction(id, "resume")

    /**
     * Permanently block one of the caller's OWN cards — the report-lost/stolen action. Unlike
     * freeze (a reversible suspend), block is TERMINAL: card-issuance moves the card to BLOCKED and
     * it cannot be resumed, so the app gates this behind an explicit confirm. Ownership is enforced
     * here (same IDOR guard as freeze); the card-issuance audit reason is fixed to LOST_OR_STOLEN
     * since that is the only customer-initiated block reason.
     */
    @POST
    @Path("/cards/{id}/block")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun blockCard(@PathParam("id") id: UUID): Response {
        val customer = customer()
        if (!ownsCard(id, customer.partyId)) return forbidden("Card does not belong to caller")
        return upstream.post(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/block",
            customer.partyId.toString(),
            "{\"reason\":\"LOST_OR_STOLEN\"}",
            null,
            mapOf("X-Operator-Id" to "customer:${customer.partyId}"),
        )
    }

    // Map the customer freeze/unfreeze to card-issuance's suspend/resume. card-issuance requires an
    // X-Operator-Id audit header; for a self-service freeze the actor IS the customer, so the party id
    // is the audit subject. Ownership is enforced here (the card must belong to the JWT party).
    private fun cardAction(id: UUID, action: String): Response {
        val customer = customer()
        if (!mayControlCard(id, customer.partyId, CAP_CARD_MANAGE_LIMITS)) {
            return forbidden("Card does not belong to caller")
        }
        return upstream.post(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/$action",
            customer.partyId.toString(),
            "",
            null,
            mapOf("X-Operator-Id" to "customer:${customer.partyId}"),
        )
    }

    /**
     * Permanently CANCEL one of the caller's OWN cards. Terminal in card-issuance (a cancelled card
     * can never be resumed), so unlike freeze this is SCA-gated: an attacker with a stolen session
     * must still produce a device-signed, card-bound approval before they can destroy a card.
     */
    @POST
    @Path("/cards/{id}/cancel")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun cancelCard(@PathParam("id") id: UUID, @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?): Response {
        val customer = customer()
        if (!ownsCard(id, customer.partyId)) return forbidden("Card does not belong to caller")
        scaCardGate(scaChallengeId, customer, id.toString(), "CANCEL", "cards.cancel")?.let { return it }
        val resp = upstream.post(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/cancel",
            customer.partyId.toString(),
            """{"reason":"CUSTOMER_REQUEST"}""",
            null,
            mapOf("X-Operator-Id" to "customer:${customer.partyId}"),
        )
        auditCard(resp, customer, "cards.cancel", "CUSTOMER_CARD_CANCELLED", id.toString())
        return resp
    }

    /**
     * Reveal the full card details (PAN / CVV / expiry) of one of the caller's OWN cards — the
     * SCA-gated "show my card number" action for a VIRTUAL or SINGLE_USE card, which has no plastic
     * to read the number off. POST (not GET) because it is a state-changing, single-use SCA spend and
     * must never land in a URL, a browser history or an access log.
     *
     * PCI: the response is passed straight through and NEVER logged, cached or audited by value —
     * the audit record carries who/which card/when only. `Cache-Control: no-store` + `Pragma:
     * no-cache` stop any intermediary or the client HTTP stack retaining it. A physical, blocked,
     * cancelled or expired card is refused upstream with 403 and surfaces here as a machine-readable
     * CARD_DETAILS_UNAVAILABLE, not a generic 500.
     */
    @POST
    @Path("/cards/{id}/details")
    @Authorize(action = "customer.cards.details.read", resource = "#id")
    @Blocking
    fun revealCardDetails(
        @PathParam("id") id: UUID,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        // HOLDER ONLY, deliberately not ownsCard: that predicate was widened to the account owner
        // so a grantor can freeze or re-limit a delegate's card, which is right. Reading the full
        // PAN and CVV is not the same act — D5 refuses a delegated PAN reveal on PCI grounds, and
        // the account-owner arm would grant exactly that, retroactively, for every card whose
        // holder differs from the account owner.
        if (!isCardHolder(id, customer.partyId)) return forbidden("Card does not belong to caller")
        scaCardGate(scaChallengeId, customer, id.toString(), "REVEAL_DETAILS", "cards.details")?.let { return it }
        val resp = upstream.get(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/secure-details",
            customer.partyId.toString(),
        )
        // Audit BEFORE returning, and by reference only (party + card + outcome) — the body holds
        // PAN/CVV and must not reach the audit topic any more than it may reach a log line.
        auditCard(resp, customer, "cards.details", "CUSTOMER_CARD_DETAILS_REVEALED", id.toString())
        if (resp.status == FORBIDDEN_STATUS) {
            return cardError(
                FORBIDDEN_STATUS,
                "Card details are not available for this card",
                "CARD_DETAILS_UNAVAILABLE",
            )
        }
        if (resp.statusInfo.family != Response.Status.Family.SUCCESSFUL) return resp
        return Response.status(resp.status)
            .entity(resp.entity)
            .type(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .build()
    }

    /**
     * What the caller is still entitled to issue — quota, remaining cards, allowed types/networks and
     * the per-card monthly fee, as product-catalog defines it for the account's product. Drives the
     * app's "issue a card" screen so it can grey out an exhausted quota rather than discovering it as
     * a 409 on submit. Read-only, no SCA. `accountId` is optional (and ownership-checked when given);
     * without it the upstream answers with its own product-less default.
     */
    @GET
    @Path("/cards/entitlements")
    @Authorize(action = "customer.cards.read")
    @Blocking
    fun cardEntitlements(@QueryParam("accountId") accountId: String?): Response {
        val customer = customer()
        val query = if (accountId.isNullOrBlank()) {
            ""
        } else {
            val acct = runCatching { UUID.fromString(accountId) }.getOrNull()
                ?: return cardError(BAD_REQUEST_STATUS, "Malformed accountId", "CARD_ACCOUNT_INVALID")
            if (!ownsAccount(acct, customer.partyId)) return forbidden("Account does not belong to caller")
            resolveCardProductCode(acct, customer.partyId)
                ?.let { "?productCode=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) }
                ?: ""
        }
        return upstream.get(
            "$cardIssuanceServiceUrl/api/v1/cards/party/${customer.partyId}/entitlements$query",
            customer.partyId.toString(),
        )
    }

    /**
     * Set daily/monthly spending limits on one of the caller's OWN cards. Body:
     * {"dailyLimitMinorUnits":N,"monthlyLimitMinorUnits":M} — parsed and validated HERE (both
     * required, non-negative, daily <= monthly) rather than forwarded raw, so a malformed body is a
     * clear 400 at the edge instead of an upstream 500.
     *
     * SCA is CONDITIONAL and risk-proportionate (PSD2 RTS Art. 4 — friction where risk is): raising
     * either limit widens the blast radius of a stolen session and needs a device-signed approval;
     * leaving them or LOWERING them strictly reduces risk, so it must never be gated behind SCA. The
     * X-SCA-Challenge-Id header is therefore optional on this route — a missing header on an increase
     * is the standard 403 SCA_REQUIRED (the app then raises a challenge and retries), never a 400.
     */
    @PUT
    @Path("/cards/{id}/limits")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun updateCardLimits(
        @PathParam("id") id: UUID,
        body: String,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val requested = parseLimits(objectMapper, body)
            ?: return cardError(
                BAD_REQUEST_STATUS,
                "dailyLimitMinorUnits and monthlyLimitMinorUnits are required, non-negative, and daily <= monthly",
                "CARD_LIMITS_INVALID",
            )
        val cardJson = fetchCard(id, customer.partyId) ?: return forbidden("Card does not belong to caller")
        // ADR-0249 D2: the holder, the account owner, or a delegate holding CARD_MANAGE_LIMITS. The
        // one already-fetched body serves the authority check AND the current-limits comparison, so
        // honouring a grant costs at most one extra call, and only for a caller who is neither.
        if (!isHolderOrAccountOwner(cardJson, customer.partyId) &&
            !hasGrant(customer.partyId, "CARD", id, CAP_CARD_MANAGE_LIMITS)
        ) {
            return forbidden("Card does not belong to caller")
        }
        val current = parseLimits(objectMapper, cardJson)
        if (current == null || requested.first > current.first || requested.second > current.second) {
            // Unknown current limits count as an increase — fail closed, never silently un-gate.
            scaCardGate(scaChallengeId, customer, id.toString(), "LIMIT_INCREASE", "cards.limits")?.let { return it }
        }
        val payload = objectMapper.createObjectNode().apply {
            put("dailyLimitMinorUnits", requested.first)
            put("monthlyLimitMinorUnits", requested.second)
        }
        val resp = upstream.put(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/limits",
            customer.partyId.toString(),
            payload.toString(),
            null,
            mapOf("X-Operator-Id" to "customer:${customer.partyId}"),
        )
        auditCard(resp, customer, "cards.limits", "CUSTOMER_CARD_LIMITS_UPDATED", id.toString())
        return resp
    }

    /**
     * Set channel controls on one of the caller's OWN cards. Body:
     * {"contactlessEnabled":bool,"onlineEnabled":bool,"atmEnabled":bool,"abroadEnabled":bool} —
     * all four required, parsed and validated here.
     *
     * Deliberately NOT SCA-gated: every toggle is reversible by the customer at any time and can only
     * ever narrow or restore what the card may do — no money moves and no limit widens beyond what
     * /limits already governs, so the friction would buy nothing.
     */
    @PUT
    @Path("/cards/{id}/controls")
    @Authorize(action = "customer.cards.update", resource = "#id")
    @Blocking
    fun updateCardControls(@PathParam("id") id: UUID, body: String): Response {
        val customer = customer()
        val controls = parseControls(objectMapper, body)
            ?: return cardError(
                BAD_REQUEST_STATUS,
                "contactlessEnabled, onlineEnabled, atmEnabled and abroadEnabled (booleans) are all required",
                "CARD_CONTROLS_INVALID",
            )
        if (!mayControlCard(id, customer.partyId, CAP_CARD_MANAGE_LIMITS)) {
            return forbidden("Card does not belong to caller")
        }
        val resp = upstream.put(
            "$cardIssuanceServiceUrl/api/v1/cards/$id/controls",
            customer.partyId.toString(),
            controls.toString(),
            null,
            mapOf("X-Operator-Id" to "customer:${customer.partyId}"),
        )
        auditCard(resp, customer, "cards.controls", "CUSTOMER_CARD_CONTROLS_UPDATED", id.toString())
        return resp
    }

    // The card as card-issuance sees it (JSON on 200, null otherwise). One read serves both the
    // ownership oracle and the current-limits comparison, so a limits update costs one upstream GET.
    private fun fetchCard(id: UUID, partyId: UUID): String? {
        val resp = upstream.get("$cardIssuanceServiceUrl/api/v1/cards/$id", partyId.toString())
        if (resp.status != 200) return null
        return (resp.entity as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Unconditional control over a card: the card's HOLDER, or the OWNER of the account it draws on
     * (ADR-0249 D1).
     *
     * The account-owner arm is not a widening — it is what stops an additional cardholder ("dodatková
     * karta") from being a card the account owner cannot touch. On such a card `partyId` is the
     * DELEGATE, so a holder-only check would hand the grantor's own account to the grantee and lock
     * the grantor out of blocking, cancelling and re-limiting the instrument they paid for. The
     * grantor must keep every control, unconditionally and without a grant of their own.
     */
    private fun ownsCard(id: UUID, partyId: UUID): Boolean {
        val cardJson = fetchCard(id, partyId) ?: return false
        return isHolderOrAccountOwner(cardJson, partyId)
    }

    /** Holder only — see [revealCardDetails] for why the account-owner arm must not apply there. */
    private fun isCardHolder(id: UUID, partyId: UUID): Boolean {
        val cardJson = fetchCard(id, partyId) ?: return false
        return extractOwnerPartyId(cardJson) == partyId.toString()
    }

    private fun isHolderOrAccountOwner(cardJson: String, partyId: UUID): Boolean {
        if (extractOwnerPartyId(cardJson) == partyId.toString()) return true
        val accountId = extractTextField(objectMapper, cardJson, "accountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return false
        return ownsAccount(accountId, partyId)
    }

    /**
     * May this caller exercise [capability] on this card — as its holder, as the owner of the
     * account behind it, or as someone the card was shared with (ADR-0249 D2)?
     *
     * The delegated arm is the same `hasGrant` call the delegated ACCOUNT reads use, against the
     * same delegation-service, with the same fail-closed and no-cache properties: a revoked grant
     * stops working on the very next request rather than at the end of a TTL. Holder and account
     * owner are checked FIRST and never pay for a delegation round-trip.
     *
     * Callers pass the capability the ACTION needs, not the weakest one that would let the caller
     * in — `CARD_VIEW` must never be enough to move a limit.
     */
    private fun mayControlCard(id: UUID, partyId: UUID, capability: String): Boolean {
        val cardJson = fetchCard(id, partyId) ?: return false
        if (isHolderOrAccountOwner(cardJson, partyId)) return true
        return hasGrant(partyId, "CARD", id, capability)
    }

    /**
     * The cards shared WITH this caller, as card JSON, for appending to their own list — the
     * read half of ADR-0249 D2. Mirrors [sharedAccountsFor] exactly, including the `sharedWithMe`
     * marking: a delegate must never be shown someone else's instrument as their own.
     *
     * `CARD_VIEW` decides visibility. A card the caller may re-limit but not view is not a state the
     * vocabulary can express today; if it ever is, the limit screen still needs the card in the list,
     * so this deliberately asks for the read capability only.
     */
    private fun sharedCardsFor(partyId: UUID): List<com.fasterxml.jackson.databind.JsonNode> {
        val resp = runCatching {
            upstream.get("$delegationServiceUrl/api/v1/delegations/grantee/$partyId", partyId.toString())
        }.getOrNull() ?: return emptyList()
        if (resp.status != 200) return emptyList()
        val grants = runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray } ?: return emptyList()
        return grants.asSequence()
            .filter { it.path("status").asText() == "ACTIVE" }
            .filter { it.path("resourceType").asText() == "CARD" }
            .filter { g -> g.path("capabilities").any { it.asText() == CAP_CARD_VIEW } }
            .mapNotNull { it.path("resourceId").asText(null) }
            .distinct()
            .mapNotNull { id -> runCatching { UUID.fromString(id) }.getOrNull() }
            .mapNotNull { id -> fetchCard(id, partyId) }
            .mapNotNull { body -> runCatching { objectMapper.readTree(body) }.getOrNull() }
            .filterIsInstance<com.fasterxml.jackson.databind.node.ObjectNode>()
            .map { node -> node.put("sharedWithMe", true) }
            .toList()
    }

    /**
     * The id of an ACTIVE grant from [grantorPartyId] to [granteePartyId] over this exact account,
     * or null. This is the authority ADR-0249 D1 requires before a card may be issued to a third
     * party, and the id is what the card is then linked to, so that revoking the grant blocks the
     * card (D2).
     *
     * The grantee's own grant list is the source, filtered by grantor — the same endpoint and the
     * same ACTIVE-only rule as [sharedAccountsFor], so there is one notion of "a live share" at this
     * edge rather than two. A missing, dead or unparseable delegation-service yields null, i.e. the
     * issue is refused: minting a payment instrument on a guess is not a failure mode worth having.
     */
    private fun activeAccountGrantId(granteePartyId: UUID, grantorPartyId: UUID, accountId: UUID): String? {
        val resp = runCatching {
            upstream.get(
                "$delegationServiceUrl/api/v1/delegations/grantee/$granteePartyId",
                grantorPartyId.toString(),
            )
        }.getOrNull() ?: return null
        if (resp.status != 200) return null
        val grants = runCatching { objectMapper.readTree(resp.entity?.toString() ?: "") }.getOrNull()
            ?.takeIf { it.isArray } ?: return null
        return grants.asSequence()
            .filter { it.path("status").asText() == "ACTIVE" }
            .filter { it.path("resourceType").asText() == "ACCOUNT" }
            .filter { it.path("resourceId").asText() == accountId.toString() }
            .filter { it.path("grantorPartyId").asText() == grantorPartyId.toString() }
            // A card is a spending instrument, so the grant behind it must actually authorise
            // spending. Without this the four filters above admit a read-only grant — e.g. one
            // carrying ACCOUNT_READ_BALANCES alone — and mint a live payment card from it. That
            // also routes around ADR-0232 D5, whose EXECUTION_CAPABILITIES => KycLevel.FULL rule
            // only engages when an execution capability is present.
            .filter { grant ->
                grant.path("capabilities").any { it.asText() == ACCOUNT_INITIATE_PAYMENT_CAPABILITY }
            }
            .mapNotNull { it.path("id").asText(null)?.takeIf { id -> id.isNotBlank() } }
            .firstOrNull()
    }

    /**
     * How many VIRTUAL cards on [accountId] have reached a terminal state. This is the generation
     * counter in the virtual-card idempotency key — see [issueCard] for why the key cannot simply
     * be constant.
     *
     * Counting DEAD cards rather than all cards is the whole trick: the number is unchanged by a
     * successful issue, so a retry after a dropped response still replays instead of minting a
     * duplicate, and it only moves when a card is blocked or cancelled — which is exactly when a
     * fresh card must become mintable.
     *
     * On an upstream failure this returns 0, i.e. the previous constant-key behaviour: replaying an
     * existing card is a safe answer to "I could not tell", whereas guessing high would mint a card
     * the customer did not ask for.
     */
    private fun terminalVirtualCardCount(partyId: UUID, accountId: UUID): Int {
        val resp = upstream.get("$cardIssuanceServiceUrl/api/v1/cards/party/$partyId", partyId.toString())
        if (resp.status != 200) return 0
        val body = (resp.entity as? String)?.takeIf { it.isNotBlank() } ?: return 0
        return runCatching {
            objectMapper.readTree(body).count { card ->
                card.path("accountId").asText() == accountId.toString() &&
                    card.path("cardType").asText().uppercase() == CARD_TYPE_VIRTUAL &&
                    card.path("status").asText().uppercase() in TERMINAL_CARD_STATUSES
            }
        }.getOrDefault(0)
    }

    /**
     * Issue a VIRTUAL or SINGLE_USE card on one of the caller's OWN accounts (self-service, #4b). The
     * app sends { "accountId": "...", "cardType": "VIRTUAL"|"SINGLE_USE" }; the edge forces the
     * partyId from the JWT, verifies the account belongs to the caller, and resolves the cardholder
     * name from party-service — the customer can never mint a card on someone else's account or under
     * someone else's name. SCA-gated: minting a card is a new payment instrument, so it needs the same
     * device-signed approval a payment does (bound to the ACCOUNT, since no card id exists yet).
     */
    @POST
    @Path("/cards")
    @Authorize(action = "customer.cards.create", resource = "")
    @Blocking
    fun issueCard(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val parsed = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return cardError(BAD_REQUEST_STATUS, "Malformed request body", "CARD_REQUEST_MALFORMED")
        val acct = parsed.get("accountId")?.takeIf { it.isTextual }?.asText()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return cardError(BAD_REQUEST_STATUS, "Missing or malformed accountId", "CARD_ACCOUNT_INVALID")
        val cardType = parsed.get("cardType")?.takeIf { it.isTextual }?.asText()?.trim()?.uppercase()
            ?: CARD_TYPE_VIRTUAL
        if (cardType !in SELF_SERVICE_CARD_TYPES) {
            return cardError(
                BAD_REQUEST_STATUS,
                "cardType must be one of ${SELF_SERVICE_CARD_TYPES.joinToString(", ")}",
                "CARD_TYPE_INVALID",
            )
        }
        if (!ownsAccount(acct, customer.partyId)) return forbidden("Account does not belong to caller")
        scaCardGate(scaChallengeId, customer, acct.toString(), "ISSUE", "cards.issue")?.let { return it }
        // The product the ACCOUNT actually runs on is what product-catalog keys card entitlements by;
        // the historical hardcoded "VIRTUAL_DEBIT" matches no catalogue product, so every upstream
        // entitlement lookup fell through to its fallback. Resolve for real, hardcode only as a
        // last resort (and say so in the log, so the fallback is never silent).
        val productCode = resolveCardProductCode(acct, customer.partyId) ?: run {
            Log.warn(
                "card issue: cannot resolve product code for account $acct " +
                    "— falling back to $FALLBACK_CARD_PRODUCT_CODE (entitlements will use the upstream default)",
            )
            FALLBACK_CARD_PRODUCT_CODE
        }
        val name = fetchPartyLegalName(customer.partyId) ?: "OpenBank Customer"
        val req = objectMapper.createObjectNode()
        req.put("partyId", customer.partyId.toString())
        req.put("accountId", acct.toString())
        req.put("productCode", productCode)
        req.put("cardType", cardType)
        req.put("network", "VISA")
        req.put("cardholderName", name)
        req.put("embossedName", name.uppercase())
        req.put("currency", "CZK")
        // Idempotency is per card TYPE, not per request, on purpose:
        //  - VIRTUAL: one durable virtual card per (party, account), so a stable key makes a re-tap
        //    (or a retry after a dropped response) replay the same card instead of minting duplicates.
        //  - SINGLE_USE: the whole point is a fresh, burn-after-use card per request — a stable key
        //    would make the SECOND one impossible (it would replay the first forever). Honour a
        //    client-supplied Idempotency-Key so a genuine network retry still de-duplicates, and
        //    generate one otherwise.
        //
        // The VIRTUAL key carries a generation suffix because a FULLY constant key does not just
        // de-duplicate retries — it permanently caps the account at one virtual card ever. Once
        // that card is blocked or cancelled, card-issuance's `findByIdempotencyKey(...)?.let {
        // return it }` keeps replaying the DEAD row: the customer taps "issue", gets 200 and a
        // card that cannot pay, and no amount of retrying can ever produce a live one. (The
        // `idempotency_key` column is NOT NULL UNIQUE, so the key has to move for a new row to
        // exist at all.) The suffix counts TERMINAL cards, which a successful issue leaves
        // unchanged — retry de-duplication survives, the dead end does not.
        val key = if (cardType == CARD_TYPE_SINGLE_USE) {
            idempotencyKey?.takeIf { it.isNotBlank() } ?: Ids.randomId().toString()
        } else {
            "vcard-${customer.partyId}-$acct-r${terminalVirtualCardCount(customer.partyId, acct)}"
        }
        val resp = upstream.post(
            "$cardIssuanceServiceUrl/api/v1/cards",
            customer.partyId.toString(),
            req.toString(),
            key,
        )
        auditCard(resp, customer, "cards.issue", "CUSTOMER_CARD_ISSUED", acct.toString(), cardType)
        return resp
    }

    /**
     * Issue an ADDITIONAL CARDHOLDER card — a "dodatková karta" (ADR-0249 D1). The caller is the
     * GRANTOR: the card is minted on THEIR account, in the GRANTEE's name, with its own PAN and its
     * own daily/monthly ceilings, and the grantee becomes its holder.
     *
     * Body: `{"accountId":"…","granteePartyId":"…","cardType":"VIRTUAL"|"SINGLE_USE",
     * "dailyLimitMinorUnits":N,"monthlyLimitMinorUnits":M}`.
     *
     * Four things must all hold, and each fails closed:
     *  1. the caller OWNS the account — nobody mints a card on an account they do not hold;
     *  2. an ACTIVE delegation grant already runs from the caller to the grantee over that exact
     *     account — the standing relationship, which the grantee accepted and can renounce;
     *  3. the caller passes SCA, bound to the ACCOUNT (no card id exists yet), exactly as
     *     self-service issue is gated — a new payment instrument in someone else's hands is at
     *     least as consequential as one in your own (ADR-0021, ADR-0249 D4);
     *  4. BOTH ceilings are supplied and valid. They are mandatory here although optional on a
     *     self-service card, because ADR-0249 D5 refuses "unlimited access to someone else's
     *     account" — and unlike a delegation ceiling, a CARD ceiling is one the card rail actually
     *     counts, so it is a promise the platform can keep.
     *
     * The issued card is linked to the grant, which is what makes revocation bite: when the grant
     * ends, card-issuance blocks every card carrying its id (ADR-0249 D2).
     */
    @POST
    @Path("/cards/delegated")
    @Authorize(action = "customer.cards.create", resource = "")
    @Blocking
    fun issueDelegatedCard(
        body: String,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @HeaderParam("X-SCA-Challenge-Id") scaChallengeId: String?,
    ): Response {
        val customer = customer()
        val req = when (val parsed = parseDelegatedCardRequest(body, customer.partyId)) {
            is DelegatedCardParse.Bad -> return parsed.response
            is DelegatedCardParse.Ok -> parsed.request
        }
        if (!ownsAccount(req.accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        val grantId = activeAccountGrantId(req.granteePartyId, customer.partyId, req.accountId)
            ?: return forbidden("No active delegation grant to that party on this account")
        scaCardGate(scaChallengeId, customer, req.accountId.toString(), "ISSUE_DELEGATED", "cards.issue.delegated")
            ?.let { return it }
        // The card is embossed in the GRANTEE's name — it is their instrument, and a card carrying
        // the grantor's name would misrepresent who is presenting it at a terminal. No usable name
        // means no card: an additional card reading "OpenBank Customer" is not one anybody can use.
        val name = fetchPartyLegalName(req.granteePartyId)
            ?: return cardError(
                BAD_REQUEST_STATUS,
                "Cannot resolve the cardholder name for that party",
                "CARD_GRANTEE_UNKNOWN",
            )
        // Same generation-suffix trick as the self-service virtual card, keyed on the GRANTEE: a
        // retry after a dropped response replays instead of minting a second card, but a card that
        // was blocked or cancelled — including by this grant's own revocation — can be re-issued.
        val key = idempotencyKey?.takeIf { it.isNotBlank() }
            ?: "dcard-${req.granteePartyId}-${req.accountId}-r" +
            terminalVirtualCardCount(req.granteePartyId, req.accountId)
        val resp = upstream.post(
            "$cardIssuanceServiceUrl/api/v1/cards",
            customer.partyId.toString(),
            delegatedCardPayload(req, grantId, name, customer.partyId).toString(),
            key,
        )
        auditCard(
            resp,
            customer,
            "cards.issue.delegated",
            "CUSTOMER_DELEGATED_CARD_ISSUED",
            req.accountId.toString(),
            req.cardType,
        )
        return resp
    }

    /** What a delegated-card issue asks for, once it has been proved well-formed. */
    private data class DelegatedCardRequest(
        val accountId: UUID,
        val granteePartyId: UUID,
        val cardType: String,
        val dailyLimitMinorUnits: Long,
        val monthlyLimitMinorUnits: Long,
    )

    private sealed interface DelegatedCardParse {
        data class Ok(val request: DelegatedCardRequest) : DelegatedCardParse
        data class Bad(val response: Response) : DelegatedCardParse
    }

    /**
     * Validate a delegated-card request BEFORE any authority is consulted. Split out from the route
     * so that shape-checking and authorisation stay separately readable: each refusal keeps its own
     * machine-readable code, and none of them can be confused with a 403.
     */
    @Suppress("ReturnCount") // one early return per refusal — each carries a distinct error code
    private fun parseDelegatedCardRequest(body: String, callerPartyId: UUID): DelegatedCardParse {
        fun bad(message: String, code: String) = DelegatedCardParse.Bad(cardError(BAD_REQUEST_STATUS, message, code))

        val parsed = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return bad("Malformed request body", "CARD_REQUEST_MALFORMED")
        val accountId = parsed.uuidField("accountId")
            ?: return bad("Missing or malformed accountId", "CARD_ACCOUNT_INVALID")
        val grantee = parsed.uuidField("granteePartyId")
            ?: return bad("Missing or malformed granteePartyId", "CARD_GRANTEE_INVALID")
        if (grantee == callerPartyId) {
            // Not a hair-split: this route skips the self-service quota and naming path, so letting
            // it address the caller would be a second, weaker way to mint your own card.
            return bad(
                "granteePartyId must be another party — use POST /cards to issue your own card",
                "CARD_GRANTEE_IS_SELF",
            )
        }
        val cardType = parsed.get("cardType")?.takeIf { it.isTextual }?.asText()?.trim()?.uppercase()
            ?: CARD_TYPE_VIRTUAL
        if (cardType !in SELF_SERVICE_CARD_TYPES) {
            return bad("cardType must be one of ${SELF_SERVICE_CARD_TYPES.joinToString(", ")}", "CARD_TYPE_INVALID")
        }
        // Mandatory here although optional on a self-service card: ADR-0249 D5 refuses "unlimited
        // access to someone else's account". parseLimits enforces non-negative and daily <= monthly.
        val limits = parseLimits(objectMapper, body) ?: return bad(
            "dailyLimitMinorUnits and monthlyLimitMinorUnits are required, non-negative, and daily <= monthly",
            "CARD_LIMITS_REQUIRED",
        )
        return DelegatedCardParse.Ok(
            DelegatedCardRequest(accountId, grantee, cardType, limits.first, limits.second),
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.uuidField(field: String): UUID? =
        get(field)?.takeIf { it.isTextual }?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** The card-issuance issue payload for a delegated card. `partyId` is the GRANTEE, by design. */
    private fun delegatedCardPayload(
        req: DelegatedCardRequest,
        grantId: String,
        cardholderName: String,
        grantorPartyId: UUID,
    ) = objectMapper.createObjectNode().apply {
        val productCode = resolveCardProductCode(req.accountId, grantorPartyId) ?: run {
            Log.warn(
                "delegated card issue: cannot resolve product code for account ${req.accountId} " +
                    "— falling back to $FALLBACK_CARD_PRODUCT_CODE (entitlements will use the upstream default)",
            )
            FALLBACK_CARD_PRODUCT_CODE
        }
        put("partyId", req.granteePartyId.toString())
        put("accountId", req.accountId.toString())
        put("productCode", productCode)
        put("cardType", req.cardType)
        put("network", "VISA")
        put("cardholderName", cardholderName)
        put("embossedName", cardholderName.uppercase())
        put("currency", "CZK")
        put("dailyLimitMinorUnits", req.dailyLimitMinorUnits)
        put("monthlyLimitMinorUnits", req.monthlyLimitMinorUnits)
        put("delegationGrantId", grantId)
    }

    /**
     * The product code the account actually runs on: account-service carries a productId (UUID),
     * product-catalog turns that into the `code` card entitlements are keyed by. Null on any miss
     * (catalog down, product gone) so the caller can decide its own fallback.
     */
    private fun resolveCardProductCode(accountId: UUID, partyId: UUID): String? {
        val accountJson = fetchAccount(accountId, partyId) ?: return null
        val productId = extractTextField(objectMapper, accountJson, "productId") ?: return null
        val resp = upstream.get("$productCatalogUrl/api/v1/products/$productId", partyId.toString())
        if (resp.status != 200) return null
        return extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "code")?.takeIf { it.isNotBlank() }
    }

    /**
     * The card-management SCA gate (ADR-0021, purpose CARD_MANAGEMENT): the same atomic
     * compare-and-consume the payment gate uses, but the dynamic-linking data binds the challenge to
     * THIS card and THIS action instead of an amount/creditor — so an approval signed to reveal one
     * card's PAN can never be spent to cancel another, or to raise a limit. Returns null when the
     * gate is open; an audited 403 otherwise.
     */
    private fun scaCardGate(
        scaChallengeId: String?,
        customer: CustomerIdentity,
        cardId: String,
        cardAction: String,
        operation: String,
    ): Response? {
        val challengeId = scaChallengeId?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        if (challengeId == null) {
            audit.emit(
                eventType = "CUSTOMER_CARD_ACTION_REFUSED",
                partyId = customer.partyId.toString(),
                operation = operation,
                result = "DENIED",
                resourceId = cardId,
                details = mapOf("reason" to "SCA_REQUIRED", "cardAction" to cardAction),
            )
            return Response.status(Response.Status.FORBIDDEN)
                .entity("""{"error":"Strong customer authentication required","code":"SCA_REQUIRED"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        val consumeBody = objectMapper.createObjectNode().apply {
            put("partyId", customer.partyId.toString())
            put("cardId", cardId)
            put("cardAction", cardAction)
        }
        val consume = upstream.post(
            "$scaServiceUrl/api/v1/sca/challenges/$challengeId/consume",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(consumeBody),
        )
        if (consume.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            audit.emit(
                eventType = "CUSTOMER_CARD_ACTION_REFUSED",
                partyId = customer.partyId.toString(),
                operation = operation,
                result = "DENIED",
                resourceId = cardId,
                details = mapOf(
                    "reason" to "SCA_REJECTED",
                    "cardAction" to cardAction,
                    "scaChallengeId" to challengeId.toString(),
                    "scaError" to (consume.entity as? String)?.take(AUDIT_DETAIL_MAX_CHARS),
                ),
            )
            return Response.status(Response.Status.FORBIDDEN)
                .entity("""{"error":"Strong customer authentication failed","code":"SCA_REJECTED"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        return null
    }

    // Card lifecycle audit: who / which card / what outcome. Never carries a response body — a card
    // response holds a masked PAN at best and the full PAN at worst (secure-details).
    private fun auditCard(
        resp: Response,
        customer: CustomerIdentity,
        operation: String,
        eventType: String,
        resourceId: String,
        cardType: String? = null,
    ) = audit.emit(
        eventType = eventType,
        partyId = customer.partyId.toString(),
        operation = operation,
        result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
        resourceId = resourceId,
        details = mapOf("status" to resp.status.toString(), "cardType" to cardType),
    )

    private fun cardError(status: Int, message: String, code: String): Response = Response.status(status)
        .entity("""{"error":"$message","code":"$code"}""")
        .type(MediaType.APPLICATION_JSON)
        .build()

    // --- Nearby payments (payment sessions, ADR-0095) ---

    /**
     * Create a nearby-pay session bound to one of the caller's OWN accounts (the receiver). Returns an
     * opaque token to broadcast over BLE; the real creditor account stays in the edge (only a masked
     * form is ever resolved to a payer). Ownership: creditorAccountId must belong to the JWT party.
     * Body: {"creditorAccountId":"...","requestedAmount":"250"?}.
     */
    @POST
    @Path("/payment-sessions")
    @Authorize(action = "customer.payment-sessions.create")
    @Blocking
    fun createPaymentSession(body: String): Response {
        val customer = customer()
        val creditorId = extractTextField(objectMapper, body, "creditorAccountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return badRequest("Missing or malformed creditorAccountId")
        val accountJson = fetchAccount(creditorId, customer.partyId)
            ?: return forbidden("Account does not belong to caller")
        if (extractOwnerPartyId(accountJson) != customer.partyId.toString()) {
            return forbidden("Account does not belong to caller")
        }
        val displayName = fetchPartyLegalName(customer.partyId) ?: "OpenBank"
        val masked = PaymentSessionStore.maskIban(extractTextField(objectMapper, accountJson, "accountNumber"))
        val amount = extractTextField(objectMapper, body, "requestedAmount")
        val token = sessions.create(creditorId.toString(), customer.partyId.toString(), displayName, amount, masked)
        return Response.ok("""{"token":"$token"}""").type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Resolve a discovered nearby-pay token to what the payer is shown before signing: the receiver's
     * display name, the requested amount and a MASKED account. Any authenticated customer may resolve
     * (the token is the capability); the real creditor account never leaves the edge. 404 if unknown
     * or expired.
     */
    @GET
    @Path("/payment-sessions/{token}")
    @Authorize(action = "customer.payment-sessions.read")
    @Blocking
    fun resolvePaymentSession(@PathParam("token") token: String): Response {
        customer()
        val session = sessions.resolve(token)
            ?: return Response.status(404).entity("""{"error":"session not found"}""")
                .type(MediaType.APPLICATION_JSON).build()
        val out = objectMapper.createObjectNode()
        out.put("displayName", session.displayName)
        session.requestedAmount?.let { out.put("requestedAmount", it) }
        out.put("creditorMasked", session.creditorMasked)
        return Response.ok(objectMapper.writeValueAsString(out)).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Receiver-side session status poll (ADR-0095 phase 2, settlement-honest per ADR-0108). Returns:
     *   - "ACTIVE"     — live token, no payer has initiated yet;
     *   - "PROCESSING" — a payer has initiated but the payment is not yet settled (accepted, in flight);
     *   - "PAID"       — the payer's payment actually SETTLED (money irrevocably credited);
     *   - "REJECTED"   — the payer's payment reached a terminal failure (REJECTED/RETURNED/CANCELLED);
     *   - "EXPIRED"    — timed-out / unknown token.
     * Crucially PAID is no longer reported on mere instruction acceptance — it is reconciled against
     * domestic-payment's real status. Only the session owner (the receiver, matched by creditorPartyId)
     * may poll — any other caller gets 403 rather than a side-channel into someone else's session.
     */
    @GET
    @Path("/payment-sessions/{token}/status")
    @Authorize(action = "customer.payment-sessions.read")
    @Blocking
    fun paymentSessionStatus(@PathParam("token") token: String): Response {
        val customer = customer()
        val session = sessions.resolve(token)
        if (session == null) {
            return Response.ok("""{"status":"EXPIRED"}""").type(MediaType.APPLICATION_JSON).build()
        }
        if (session.creditorPartyId != customer.partyId.toString()) {
            return forbidden("Not the session owner")
        }
        val status = when {
            session.paid -> "PAID"
            session.paymentId != null -> reconcileSessionStatus(session, customer.partyId, token)
            else -> "ACTIVE"
        }
        return Response.ok("""{"status":"$status"}""").type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Best-effort reconciliation of a session awaiting settlement against the payer's real
     * domestic-payment status. SETTLED promotes the session to PAID (sticky, via [markPaid]); a
     * terminal failure surfaces as REJECTED; any non-terminal state or an unreadable upstream stays
     * PROCESSING so a transient blip never falsely reports settlement. The upstream GET is not
     * party-scoped, so the receiver's own party header is sufficient to read the status.
     */
    private fun reconcileSessionStatus(session: PaymentSessionStore.Session, partyId: UUID, token: String): String {
        val paymentId = session.paymentId ?: return "PROCESSING"
        val resp = upstream.get("$domesticPaymentServiceUrl/api/v1/domestic-payments/$paymentId", partyId.toString())
        if (resp.status != 200) return "PROCESSING"
        return when (extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "status")) {
            "SETTLED" -> {
                sessions.markPaid(token)
                "PAID"
            }
            "REJECTED", "RETURNED", "CANCELLED" -> "REJECTED"
            else -> "PROCESSING"
        }
    }

    // --- Disputes (file + list on one of the caller's own accounts) ---

    /**
     * File a dispute on a transaction in one of the caller's OWN accounts. The app sends
     * {transactionId, accountId, disputeType, amount, currency?, transactionDate, description?,
     * merchantName?}; the edge injects partyId from the JWT and ownership-checks accountId (IDOR
     * guard) before forwarding.
     */
    @POST
    @Path("/disputes")
    @Authorize(action = "customer.disputes.create")
    @Blocking
    fun createDispute(body: String): Response {
        val customer = customer()
        val accountId = extractTextField(objectMapper, body, "accountId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return badRequest("Missing or malformed accountId")
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        val enriched = injectField(objectMapper, body, "partyId", customer.partyId.toString())
            ?: return badRequest("Malformed dispute body")
        return upstream.post("$disputeServiceUrl/api/v1/disputes", customer.partyId.toString(), enriched)
    }

    /**
     * List the disputes the caller has filed on one of their OWN accounts. accountId is a required
     * query param and ownership-checked (IDOR guard); the dispute-service list is account-scoped.
     */
    @GET
    @Path("/disputes")
    @Authorize(action = "customer.disputes.read")
    @Blocking
    fun listDisputes(@QueryParam("accountId") accountIdOrNull: UUID?): Response {
        val customer = customer()
        val accountId = accountIdOrNull ?: return badRequest("Missing required query parameter 'accountId'")
        if (!ownsAccount(accountId, customer.partyId)) return forbidden("Account does not belong to caller")
        return upstream.get("$disputeServiceUrl/api/v1/disputes/account/$accountId", customer.partyId.toString())
    }

    /**
     * File a regulatory complaint (ADR-0085) from the app. dispute-service owns the statutory
     * deadline clock and returns the case reference + dueDate, which the app shows the customer.
     * The edge forces channel=APP; when an accountId is supplied it is ownership-checked (IDOR
     * guard). A complaint carries no partyId upstream (only an optional accountId), so customer-
     * facing complaint LISTING is a deliberate follow-up: dispute-service today exposes only an
     * operator-wide list, and a per-party read needs a scoped query there (issue to follow).
     */
    @POST
    @Path("/complaints")
    @Authorize(action = "customer.complaints.create")
    @Blocking
    fun fileComplaint(body: String): Response {
        val customer = customer()
        val accountId = extractTextField(objectMapper, body, "accountId")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: return badRequest("Malformed accountId") }
        if (accountId != null && !ownsAccount(accountId, customer.partyId)) {
            return forbidden("Account does not belong to caller")
        }
        val enriched = injectField(objectMapper, body, "channel", "APP")
            ?: return badRequest("Malformed complaint body")
        return upstream.post("$disputeServiceUrl/api/v1/complaints", customer.partyId.toString(), enriched)
    }

    // --- Internal helpers ---

    /**
     * Ownership-guarded standing-order lifecycle (pause/resume/cancel). The upstream routes
     * are party-unaware (id-only), so the edge resolves the order, confirms it belongs to the
     * JWT party (403 otherwise — no existence oracle), runs [action], and audits the outcome.
     */
    private fun standingOrderLifecycle(id: UUID, operation: String, action: (String) -> Response): Response {
        val customer = customer()
        val orderJson = upstream.get("$standingOrderServiceUrl/api/v1/standing-orders/$id", customer.partyId.toString())
            .takeIf { it.statusInfo.family == Response.Status.Family.SUCCESSFUL }
            ?.let { it.entity as? String }
            ?: return forbidden("Standing order does not belong to caller")
        if (extractTextField(objectMapper, orderJson, "partyId") != customer.partyId.toString()) {
            return forbidden("Standing order does not belong to caller")
        }
        val resp = action(customer.partyId.toString())
        audit.emit(
            eventType = "STANDING_ORDER_${operation.uppercase()}",
            partyId = customer.partyId.toString(),
            operation = "standingOrders.$operation",
            result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
            resourceId = id.toString(),
        )
        return resp
    }

    /**
     * The tamper-evident record of a customer payment (ADR-0086/0133).
     *
     * `partyId` is the INITIATOR and stays the initiator on a delegated payment: who moved the
     * money does not change because they were permitted to. What a delegated payment adds is
     * [debit] — `onBehalfOf` (the account holder whose money moved) and `delegationId` (the grant
     * that permitted it). Both are omitted entirely for a direct payment rather than written as
     * empty strings, so `on_behalf_of IS NOT NULL` is a true predicate for "this was delegated"
     * and audit-service's partial index stays selective.
     *
     * The grant id in particular is only knowable HERE. It is revocable, and a revoked grant's
     * projection row is closed, so once the money has moved nothing can reconstruct which grant
     * was live at the time. Not recording it would leave the grantor's transparency view able to
     * say "someone you shared with paid" and never "under the arrangement you agreed to".
     */
    @Suppress("LongParameterList")
    private fun auditPayment(
        resp: Response,
        customer: CustomerIdentity,
        operation: String,
        amount: String,
        currency: String,
        creditor: String?,
        scaChallengeId: String?,
        debit: DebitAuthority? = null,
    ) = audit.emit(
        eventType = "CUSTOMER_PAYMENT_INITIATED",
        partyId = customer.partyId.toString(),
        operation = operation,
        result = if (resp.statusInfo.family == Response.Status.Family.SUCCESSFUL) "SUCCESS" else "FAILURE",
        resourceId = extractTextField(objectMapper, (resp.entity as? String).orEmpty(), "id"),
        details = mapOf(
            "amount" to amount,
            "currency" to currency,
            "creditor" to creditor,
            "scaChallengeId" to scaChallengeId,
            // EdgeAuditPublisher drops null-valued details, so a direct payment emits neither key.
            "onBehalfOf" to debit?.onBehalfOf?.toString(),
            "delegationId" to debit?.delegationId,
        ),
    )

    /**
     * The settlement gate (ADR-0021): refuse the payment unless the caller presents an SCA
     * challenge that sca-service can atomically VERIFY (approved + device-signed + dynamic
     * linking matches THIS amount/currency/creditor) and CONSUME (single-use). Returns null
     * when the gate is open; an error Response (403, with an audited refusal) otherwise.
     */
    @Suppress("LongParameterList")
    private fun scaGate(
        scaChallengeId: String?,
        customer: CustomerIdentity,
        amount: String,
        currency: String,
        creditor: String?,
        operation: String,
    ): Response? {
        val challengeId = scaChallengeId?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        if (challengeId == null) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = customer.partyId.toString(),
                operation = operation,
                result = "DENIED",
                details = mapOf("reason" to "SCA_REQUIRED", "amount" to amount, "currency" to currency),
            )
            return Response.status(Response.Status.FORBIDDEN)
                .entity("""{"error":"Strong customer authentication required","code":"SCA_REQUIRED"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        val consumeBody = objectMapper.createObjectNode().apply {
            put("partyId", customer.partyId.toString())
            put("amount", amount)
            put("currency", currency)
            creditor?.let { put("creditor", it) }
        }
        val consume = upstream.post(
            "$scaServiceUrl/api/v1/sca/challenges/$challengeId/consume",
            customer.partyId.toString(),
            objectMapper.writeValueAsString(consumeBody),
        )
        if (consume.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
            audit.emit(
                eventType = "CUSTOMER_PAYMENT_REFUSED",
                partyId = customer.partyId.toString(),
                operation = operation,
                result = "DENIED",
                resourceId = challengeId.toString(),
                details = mapOf(
                    "reason" to "SCA_REJECTED",
                    "amount" to amount,
                    "currency" to currency,
                    "scaError" to (consume.entity as? String)?.take(AUDIT_DETAIL_MAX_CHARS),
                ),
            )
            return Response.status(Response.Status.FORBIDDEN)
                .entity("""{"error":"Strong customer authentication failed","code":"SCA_REJECTED"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        return null
    }

    /**
     * Extract the authenticated customer's party identity from the JWT.
     *
     * Prefers the `party_id` custom claim (set via Keycloak user attribute mapper,
     * customers-realm-template.json). Falls back to `sub` for tokens issued before
     * the mapper was configured. In production `party_id` is always set because user
     * creation enforces the invariant: Keycloak user.id == partyId AND user attribute
     * party_id == partyId (see scripts/seed-test-customer.sh and ADR-0069 §2).
     */
    private fun customer(): CustomerIdentity {
        val partyIdStr = resolvePartyIdClaim(
            partyIdClaim = jwt.getClaim<String>("party_id"),
            sub = jwt.subject,
        ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = try {
            UUID.fromString(partyIdStr)
        } catch (e: IllegalArgumentException) {
            throw ForbiddenException("party_id claim is not a valid party UUID: $partyIdStr")
        }
        // ADR-0179: the token still carries the id of a party that may since have been merged
        // away. Follow `merged_into` HERE, once, so every downstream proxy call below is made
        // with the surviving id — otherwise a merged customer sees an empty bank (no accounts,
        // no loans, no KYC case) while their data sits under the survivor. Fail-open: on any
        // upstream trouble the resolver hands back `claimed` unchanged. See PartyMergeResolver.
        val human = partyMergeResolver.resolve(claimed)
        // ADR-0284 D4: `X-Acting-For: <entityPartyId>` switches every downstream call to a legal
        // entity the human holds an ACTIVE mandate for — verified against party-service and
        // FAIL-CLOSED (403), the opposite of the merge resolver above: an unverified switch would
        // show someone else's company, an unhonoured merge only shows the customer less.
        val actingFor = if (this::requestHeaders.isInitialized) {
            requestHeaders.getHeaderString(
                ACTING_FOR_HEADER,
            )
        } else {
            null
        }
        // `isInitialized`: tests that build this resource by hand set only what they exercise; in
        // a CDI context both are always injected, so a missing resolver never reaches production.
        val effective = if (this::actingForResolver.isInitialized) {
            actingForResolver.resolve(
                human,
                actingFor,
            )
        } else {
            human
        }
        return CustomerIdentity(effective)
    }

    private sealed interface ActivePartyResult {
        data class Approved(val legalName: String) : ActivePartyResult
        data class Rejected(val response: Response) : ActivePartyResult
    }

    /** Shared KYC gate for products opened from the authenticated customer surface. */
    private fun activeParty(customer: CustomerIdentity): ActivePartyResult {
        val partyResponse = upstream.get(
            "$partyServiceUrl/api/v1/parties/${customer.partyId}",
            customer.partyId.toString(),
        )
        if (partyResponse.status != 200) {
            return ActivePartyResult.Rejected(
                Response.status(404).entity("{\"error\":\"Party not found\"}").type(MediaType.APPLICATION_JSON).build(),
            )
        }
        val partyJson = (partyResponse.entity as? String).orEmpty()
        val partyStatus = extractTextField(objectMapper, partyJson, "status").orEmpty()
        if (partyStatus != "ACTIVE") {
            return ActivePartyResult.Rejected(
                Response.status(422)
                    .entity("{\"error\":\"KYC not approved — party status: $partyStatus\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build(),
            )
        }
        val legalName = extractTextField(objectMapper, partyJson, "legalName")
            ?: return ActivePartyResult.Rejected(
                Response.status(422).entity("{\"error\":\"Party has no legal name\"}")
                    .type(MediaType.APPLICATION_JSON).build(),
            )
        return ActivePartyResult.Approved(legalName)
    }

    private sealed interface TermDepositResolution {
        data class Found(val offer: ObjectNode) : TermDepositResolution
        data object NotFound : TermDepositResolution
        data object Unavailable : TermDepositResolution
    }

    private fun resolvePublicTermDeposit(customer: CustomerIdentity, productId: UUID): TermDepositResolution {
        val catalog = upstream.get("$productCatalogUrl/api/v1/products/$productId", customer.partyId.toString())
        if (catalog.status == 404) return TermDepositResolution.NotFound
        if (catalog.status != 200) return TermDepositResolution.Unavailable
        return parseJson(catalog)?.let(::termDepositOffer)?.let(TermDepositResolution::Found)
            ?: TermDepositResolution.NotFound
    }

    /** Maps the rich operator product into only the terms a retail customer needs to decide. */
    private fun termDepositOffer(product: JsonNode): ObjectNode? {
        val today = LocalDate.now(clock)
        if (!isDiscoverableTermDeposit(product) || !isCurrentlyValid(product, today)) return null
        val configuration = product.get("termDepositConfig")?.takeIf { it.isObject } ?: return null
        return objectMapper.createObjectNode().apply {
            put("id", product.path("id").asText())
            put("code", product.path("code").asText())
            put("name", product.path("name").asText())
            product.get("shortDescription")?.takeIf { !it.isNull }?.let { set<JsonNode>("shortDescription", it) }
            product.get("description")?.takeIf { !it.isNull }?.let { set<JsonNode>("description", it) }
            put("currency", product.path("currency").asText())
            product.get("minBalance")?.takeIf { !it.isNull }?.let { set<JsonNode>("minimumDeposit", it) }
            product.get("maxBalance")?.takeIf { !it.isNull }?.let { set<JsonNode>("maximumDeposit", it) }
            put("annualRate", configuration.path("interestRateAnnual").asDouble())
            set<JsonNode>("term", configuration)
            set<JsonNode>("termsAndConditions", product.path("termsAndConditions"))
        }
    }

    /**
     * Customer-safe projection of one catalogue product, or null when it is not discoverable.
     *
     * Carries only what a customer needs to choose: identity, copy, currency, limits, and the
     * price IN THE SHAPE THE CATALOGUE PRICES IT — `annualRate` for a term deposit (which has one
     * fixed rate for one fixed term), `interestTiers` for savings (which prices by balance), and
     * neither for a current account. Internal fields — version history, eligibility segments,
     * draft state, operator notes — never cross.
     */
    private fun productOffer(product: JsonNode, today: LocalDate): ObjectNode? {
        if (!isDiscoverableProduct(product) || !isCurrentlyValid(product, today)) return null
        val type = product.path("type").asText()
        return objectMapper.createObjectNode().apply {
            put("id", product.path("id").asText())
            put("code", product.path("code").asText())
            put("name", product.path("name").asText())
            put("type", type)
            put("currency", product.path("currency").asText())
            product.get("shortDescription")?.takeIf { !it.isNull }?.let { set<JsonNode>("shortDescription", it) }
            product.get("description")?.takeIf { !it.isNull }?.let { set<JsonNode>("description", it) }
            product.get("minBalance")?.takeIf { !it.isNull }?.let { set<JsonNode>("minBalance", it) }
            product.get("maxBalance")?.takeIf { !it.isNull }?.let { set<JsonNode>("maxBalance", it) }
            // The monthly account fee, when the catalogue states one. Zero IS a price here — "no
            // fee, forever" is the current account's whole pitch — so unlike a rate it is copied
            // even when it is 0.
            product.get("fee")?.takeIf { !it.isNull }?.let { set<JsonNode>("fee", it) }
            // Price, in the catalogue's own shape. Never flattened, never interpolated.
            product.get("termDepositConfig")?.takeIf { it.isObject }?.let { configuration ->
                put("annualRate", configuration.path("interestRateAnnual").asDouble())
                set<JsonNode>("term", configuration)
            }
            product.get("savingsConfig")?.takeIf { it.isObject }?.let { configuration ->
                set<JsonNode>("savings", configuration)
            }
            set<JsonNode>("termsAndConditions", product.path("termsAndConditions"))
        }
    }

    /**
     * Discoverability, deliberately identical to [isDiscoverableTermDeposit] apart from the type
     * gate: ACTIVE, public, identifiable, priced in a currency. A product failing any of these is
     * invisible rather than greyed out — an offer the customer cannot take is not an offer.
     */
    private fun isDiscoverableProduct(product: JsonNode): Boolean =
        product.path("type").asText() in CUSTOMER_PRODUCT_TYPES &&
            product.path("status").asText() == "ACTIVE" &&
            product.path("isPublic").asBoolean(false) &&
            product.path("id").asText().isNotBlank() &&
            product.path("currency").asText().isNotBlank()

    private fun productCatalogueUnavailable(): Response = Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .entity(mapOf("error" to "Product catalogue unavailable"))
        .build()

    private fun isDiscoverableTermDeposit(product: JsonNode): Boolean =
        product.path("type").asText() == "TERM_DEPOSIT" &&
            product.path("status").asText() == "ACTIVE" &&
            product.path("isPublic").asBoolean(false) &&
            product.path("id").asText().isNotBlank() &&
            product.path("currency").asText().isNotBlank()

    private fun isCurrentlyValid(product: JsonNode, today: LocalDate): Boolean {
        val validFrom = product.optionalDate("validFrom")
        val validTo = product.optionalDate("validTo")
        return !(validFrom?.isAfter(today) == true || validTo?.isBefore(today) == true)
    }

    private fun JsonNode.optionalDate(field: String): LocalDate? =
        path(field).asText().takeIf { it.isNotBlank() }?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()
        }

    private fun parseJson(response: Response): JsonNode? = runCatching {
        objectMapper.readTree(response.entity?.toString().orEmpty())
    }.getOrNull()

    private fun termDepositCatalogueUnavailable(): Response = Response.status(Response.Status.BAD_GATEWAY)
        .entity("{\"error\":\"Term deposit offers are temporarily unavailable\"}")
        .type(MediaType.APPLICATION_JSON)
        .build()

    private fun termDepositNotFound(): Response = Response.status(404)
        .entity("{\"error\":\"Term deposit offer not found\"}")
        .type(MediaType.APPLICATION_JSON)
        .build()

    /** Accepts "number/bankcode" BBAN or Czech IBAN — contacts store the IBAN form. */
    private fun resolveCreditorBban(raw: String): Pair<String, String>? =
        parseCreditorAccount(raw) ?: czechIbanToBban(raw)

    companion object {

        /**
         * The product types a customer may discover and open from the app.
         *
         * An allow-list, not a deny-list: MORTGAGE, CREDIT_CARD, OVERDRAFT and INVESTMENT exist in
         * the catalogue and are deliberately absent — each needs its own suitability and disclosure
         * journey, and surfacing one here would let the app offer a regulated product with no path
         * to take it. A new type becomes customer-visible by being added here, on purpose.
         */
        internal val CUSTOMER_PRODUCT_TYPES = setOf("CURRENT", "SAVINGS", "TERM_DEPOSIT")

        /**
         * The ADR-0269 credit consents, as the app's field name → the consent-service scope.
         *
         * One map, so the read and the write cannot disagree about which switch means which scope —
         * the failure that would look like a customer turning offers off and still being offered.
         */
        private val CREDIT_SCOPES: Map<String, String> = linkedMapOf(
            "offers" to "CREDIT_OFFERS",
            "profileUse" to "CREDIT_PROFILE_USE",
            "aiAgent" to "CREDIT_AI_AGENT",
        )

        /** First-party consent: the bank itself is the grantee, not a TPP. */
        private const val BANK_GRANTEE = "openbank"

        /** The non-AISP validity ceiling. A ceiling, not a promise — revocation is immediate. */
        private const val CONSENT_DAYS = 365L

        /** Closed at the edge as well as upstream: a path is never an app-controlled URL. */
        private val SURFACE_SLOTS: Set<String> = setOf(
            "HOME_BANNER",
            "HOME_CAROUSEL",
            "STORIES",
            "PRODUCT_FEED",
            "REWARDS_HUB",
        )
        private val INCENTIVE_CLAIM_FIELDS = setOf("interactionRef", "code", "productId")
        private val TERM_DEPOSIT_OPEN_FIELDS = setOf("productId", "incentiveReservationId")
        private val TERMINAL_ACCOUNT_REJECTION_STATUSES = setOf(422)
        private const val MIN_PROMO_CODE_LENGTH = 8
        private const val MAX_PROMO_CODE_LENGTH = 128
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

        // A ThemeSpec is a small token document; 8 KiB leaves headroom for future fields
        // while keeping Redis abuse-proof (ADR-0190).
        /**
         * The one ACCOUNT capability that authorises spending. Named here rather than inlined
         * because the delegation capability vocabulary lives in DelegationGrant.CAPABILITY_MATRIX
         * and this edge only ever sees it as a JSON string.
         */
        private const val ACCOUNT_INITIATE_PAYMENT_CAPABILITY = "ACCOUNT_INITIATE_PAYMENT"

        private const val THEME_SPEC_MAX_BYTES = 8 * 1024

        // Decimal scale for the FX mid-price projected to the app (rate = (bid+ask)/2).
        private const val FX_RATE_SCALE = 6

        // Minor-unit scale for the buy leg of a pocket conversion (ADR-0107). All currencies the
        // product allow-list offers (CZK, EUR, USD, GBP, PLN) use 2 fraction digits.
        private const val PRIMARY_MINOR_UNIT_SCALE = 2

        // Extracts the owning party id from an account-service account JSON payload, used for the
        // edge-side ownership check on transaction reads.
        private val OWNER_PARTY_REGEX = Regex("\"partyId\"\\s*:\\s*\"([0-9a-fA-F-]+)\"")

        // --- Cards ---

        private const val BAD_REQUEST_STATUS = 400
        private const val FORBIDDEN_STATUS = 403

        /**
         * Capabilities that make a shared ACCOUNT worth listing. Read access is what "you can see
         * this account" means; execution capabilities are deliberately not here, because this edge
         * does not yet honour them on the money path (see [mayReadAccount]).
         */
        internal val ACCOUNT_READ_CAPABILITIES = setOf("ACCOUNT_READ_BALANCES", "ACCOUNT_READ_TRANSACTIONS")

        /** Upper bound on address-book hashes forwarded in one directory lookup. */
        internal const val MAX_DIRECTORY_HASHES = 500

        /** A directory hash is a hex sha-256 and nothing else — anything looser is a bad request. */
        internal val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        private const val MILLIS_PER_SECOND = 1000L

        internal const val CARD_TYPE_VIRTUAL = "VIRTUAL"
        internal const val CARD_TYPE_SINGLE_USE = "SINGLE_USE"

        // Card states from which there is no way back — the card will never transact again,
        // whatever the customer does. SUSPENDED is deliberately absent: a frozen card is alive
        // and must NOT free up a new virtual-card generation.
        internal val TERMINAL_CARD_STATUSES = setOf("BLOCKED", "CANCELLED", "EXPIRED")

        /** The only card types a customer may mint themselves — plastic stays an operator flow. */
        internal val SELF_SERVICE_CARD_TYPES = setOf(CARD_TYPE_VIRTUAL, CARD_TYPE_SINGLE_USE)

        /**
         * The CARD-scoped delegation capabilities (ADR-0232 D2's vocabulary, honoured here per
         * ADR-0249 D2). There is deliberately no delegated capability for BLOCK, CANCEL or the PAN
         * reveal: the first two are terminal and belong to the grantor alone, and the third is
         * refused outright by ADR-0249 D5 on PCI grounds — the delegate has their own card with
         * their own credentials.
         */
        internal const val CAP_CARD_VIEW = "CARD_VIEW"
        internal const val CAP_CARD_MANAGE_LIMITS = "CARD_MANAGE_LIMITS"

        /**
         * Last-resort product code when the account's real one cannot be resolved. Historically this
         * was hardcoded for EVERY issue; it matches no product-catalog product, so upstream
         * entitlement lookups silently fall back. Kept only so an unavailable catalogue degrades to
         * the previous behaviour instead of failing the issue outright.
         */
        internal const val FALLBACK_CARD_PRODUCT_CODE = "VIRTUAL_DEBIT"

        /**
         * Parse + validate a card-limits document: both fields required, integral, non-negative, and
         * daily <= monthly. Returns (daily, monthly) or null when any of that fails. Also used to read
         * the card's CURRENT limits out of a card-issuance card JSON (same field names).
         * Package-visible for unit tests.
         */
        internal fun parseLimits(mapper: ObjectMapper, json: String): Pair<Long, Long>? {
            val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
            val daily = node.get("dailyLimitMinorUnits")?.takeIf { it.isIntegralNumber }?.asLong() ?: return null
            val monthly = node.get("monthlyLimitMinorUnits")?.takeIf { it.isIntegralNumber }?.asLong() ?: return null
            if (daily < 0 || monthly < 0) return null
            if (daily > monthly) return null
            return daily to monthly
        }

        /**
         * Parse + validate a card-controls document: all four channel toggles required and boolean.
         * Returns the normalised body to forward, or null when the body is malformed.
         * Package-visible for unit tests.
         */
        internal fun parseControls(mapper: ObjectMapper, json: String): ObjectNode? {
            val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
            val out = mapper.createObjectNode()
            for (field in CARD_CONTROL_FIELDS) {
                val value = node.get(field)?.takeIf { it.isBoolean } ?: return null
                out.put(field, value.asBoolean())
            }
            return out
        }

        private val CARD_CONTROL_FIELDS =
            listOf("contactlessEnabled", "onlineEnabled", "atmEnabled", "abroadEnabled")

        /**
         * Pull the `partyId` from an account-service account JSON (a trusted upstream response with a
         * single partyId field) — regex is fine here. Package-visible for unit tests.
         */
        internal fun extractOwnerPartyId(accountJson: String): String? =
            OWNER_PARTY_REGEX.find(accountJson)?.groupValues?.getOrNull(1)

        /**
         * ISO-4217 alpha-code shape check for the statement render path param. The upstream is the
         * source of truth for existence; this only blocks path-injection / nonsense before the edge
         * builds the upstream URL. Package-visible for unit tests.
         */
        internal fun isValidCurrency(currency: String): Boolean =
            currency.length == 3 && currency.all { it in 'A'..'Z' }

        internal fun isValidInstant(s: String): Boolean = runCatching { java.time.Instant.parse(s) }.isSuccess

        /**
         * Allow-list + normalise the statement render format (deny-by-default). Case-insensitive;
         * null/blank defaults to PDF to match statement-service's own default; anything else → null
         * (the route rejects with 400). Package-visible for unit tests.
         */
        internal fun normalizeStatementFormat(format: String?): String? = when (format?.trim()?.uppercase()) {
            null, "" -> "PDF"
            "CAMT_053", "CAMT053" -> "CAMT_053"
            "MT940" -> "MT940"
            "PDF" -> "PDF"
            else -> null
        }

        /**
         * Read `debtorAccountId` from a client payment body with Jackson, so the edge sees the SAME
         * value the upstream will (the LAST value on a duplicate key). A regex first-match could pass
         * the ownership check on one value while the upstream parses another — an IDOR bypass.
         */
        internal fun parseDebtorAccountId(mapper: ObjectMapper, body: String): String? = runCatching {
            (mapper.readTree(body) as? ObjectNode)?.get("debtorAccountId")?.takeIf { it.isTextual }?.asText()
        }.getOrNull()

        /** Upper bound for upstream error text carried into an audit detail field. */
        private const val AUDIT_DETAIL_MAX_CHARS = 300

        /** HTTP status classes start at this value; named to keep upstream-retry policy legible. */
        private const val UPSTREAM_SERVER_ERROR_MIN = 500

        /** PSD2 RTS 2018/389 Art. 15: same-person, same-PSP transfers are SCA-exempt. */
        internal const val SCA_EXEMPTION_OWN_ACCOUNT = "PSD2_RTS_ART15_OWN_ACCOUNT"

        /** SEPA endToEndId max length (ISO 20022 pain.001 Max35Text). */
        private const val E2E_ID_MAX_LEN = 35

        /** SWIFT transactionReference is a Max16Text field. */
        private const val SWIFT_REF_MAX_LEN = 16

        /** Amount may arrive as JSON number or string; normalise to its decimal text form. */
        internal fun extractAmountField(mapper: ObjectMapper, json: String): String? = runCatching {
            (mapper.readTree(json) as? ObjectNode)?.get("amount")
                ?.let { if (it.isTextual) it.asText() else it.decimalValue().toPlainString() }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** Pull a single textual, non-blank field from a JSON object body. Package-visible for tests. */
        internal fun extractTextField(mapper: ObjectMapper, json: String, field: String): String? = runCatching {
            (mapper.readTree(json) as? ObjectNode)?.get(field)?.takeIf {
                it.isTextual
            }?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /**
         * Pull a single boolean field from a JSON object body, or null if absent/wrong type.
         * Package-visible for tests.
         */
        internal fun extractBooleanField(mapper: ObjectMapper, json: String, field: String): Boolean? = runCatching {
            (mapper.readTree(json) as? ObjectNode)?.get(field)?.takeIf { it.isBoolean }?.asBoolean()
        }.getOrNull()

        /**
         * Split a Czech 24-char IBAN into (accountNumber, bankCode). Layout: CZ + 2 check + 4 bank +
         * 6 prefix + 10 base. The bank code is the routing-relevant part; the account number is the
         * conventional Czech form ("prefix-base", prefix omitted when zero, leading zeros trimmed) and
         * is descriptive (the upstream stores it for the statement/camt reference). Returns null if the
         * input is not a 24-digit CZ IBAN. Package-visible for unit tests.
         */
        internal fun czechIbanToBban(rawIban: String): Pair<String, String>? {
            val iban = rawIban.replace(" ", "").uppercase()
            if (iban.length != 24 || !iban.startsWith("CZ") || !iban.drop(2).all { it.isDigit() }) return null
            val bban = iban.substring(4)
            val bankCode = bban.substring(0, 4)
            val prefix = bban.substring(4, 10).trimStart('0')
            val base = bban.substring(10, 20).trimStart('0').ifEmpty { "0" }
            val accountNumber = if (prefix.isEmpty()) base else "$prefix-$base"
            return accountNumber to bankCode
        }

        /**
         * Parse a creditor account in Czech "number/bankcode" form (e.g. "2000145399/0800" or
         * "19-2000145399/0800") into (accountNumber, bankCode). The bank code must be exactly 4 digits;
         * the account part allows digits and a single conventional prefix dash. Returns null if
         * malformed (the route rejects with 400 rather than forwarding a bad instruction). Tests only.
         */
        internal fun parseCreditorAccount(raw: String): Pair<String, String>? {
            val parts = raw.trim().split("/")
            if (parts.size != 2) return null
            val account = parts[0].trim()
            val bank = parts[1].trim()
            if (account.isEmpty() || bank.length != 4 || !bank.all { it.isDigit() }) return null
            if (!account.all { it.isDigit() || it == '-' }) return null
            return account to bank
        }

        /**
         * Build the full CreateDomesticPaymentRequest the upstream needs from the app's lightweight body
         * plus the edge-resolved debtor/creditor parts. Amount is emitted as a JSON number; the optional
         * symbols and reference (→ messageForPayee) are carried through when present; priority is read
         * from the body (STANDARD | URGENT | INSTANT) and defaults to STANDARD when absent or unknown.
         * Returns null if the body is malformed or a required field (amount, creditorName) is missing.
         * Package-visible for unit tests.
         */
        @Suppress("LongParameterList")
        internal fun buildDomesticRequest(
            mapper: ObjectMapper,
            appBody: String,
            debtorAccountId: String,
            debtorAccountNumber: String,
            debtorBankCode: String,
            debtorName: String,
            creditorAccountNumber: String,
            creditorBankCode: String,
        ): String? = runCatching {
            val app = mapper.readTree(appBody) as? ObjectNode ?: return null
            fun txt(f: String) = app.get(f)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            val amountRaw = app.get("amount")?.let { if (it.isTextual) it.asText() else it.toString() } ?: return null
            val amount = java.math.BigDecimal(amountRaw)
            val creditorName = txt("creditorName") ?: return null
            val currency = txt("currency") ?: "CZK"
            val out = mapper.createObjectNode()
            out.put("debtorAccountId", debtorAccountId)
            out.put("debtorAccountNumber", debtorAccountNumber)
            out.put("debtorBankCode", debtorBankCode)
            out.put("debtorName", debtorName)
            out.put("creditorAccountNumber", creditorAccountNumber)
            out.put("creditorBankCode", creditorBankCode)
            out.put("creditorName", creditorName)
            out.put("amount", amount)
            out.put("currency", currency)
            txt("variableSymbol")?.let { out.put("variableSymbol", it) }
            txt("specificSymbol")?.let { out.put("specificSymbol", it) }
            txt("constantSymbol")?.let { out.put("constantSymbol", it) }
            txt("reference")?.let { out.put("messageForPayee", it) }
            val priority = txt("priority")?.uppercase()
                ?.takeIf { it in setOf("STANDARD", "URGENT", "INSTANT") } ?: "STANDARD"
            out.put("priority", priority)
            mapper.writeValueAsString(out)
        }.getOrNull()

        /**
         * Build the full CreateSepaPaymentRequest from the app's lightweight body plus the edge-resolved
         * debtor IBAN + name. SEPA is IBAN-native (no BBAN), so the creditor IBAN passes through as-is.
         * `type` defaults to SCT; amount is a JSON number; reference → remittanceInfo; creditorBic is
         * carried when present. Returns null if the body is malformed or a required field (amount,
         * creditorIban, creditorName) is missing. Package-visible for unit tests.
         */
        @Suppress("LongParameterList")
        internal fun buildSepaRequest(
            mapper: ObjectMapper,
            appBody: String,
            debtorAccountId: String,
            debtorIban: String,
            debtorName: String,
        ): String? = runCatching {
            val app = mapper.readTree(appBody) as? ObjectNode ?: return null
            fun txt(f: String) = app.get(f)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            val amountRaw = app.get("amount")?.let { if (it.isTextual) it.asText() else it.toString() } ?: return null
            val amount = java.math.BigDecimal(amountRaw)
            val creditorIban = txt("creditorIban") ?: return null
            val creditorName = txt("creditorName") ?: return null
            val currency = txt("currency") ?: "EUR"
            val out = mapper.createObjectNode()
            out.put("type", "SCT")
            out.put("debtorAccountId", debtorAccountId)
            out.put("debtorIban", debtorIban)
            out.put("debtorName", debtorName)
            out.put("creditorIban", creditorIban)
            out.put("creditorName", creditorName)
            out.put("amount", amount)
            out.put("currency", currency)
            txt("reference")?.let { out.put("remittanceInfo", it) }
            txt("creditorBic")?.let { out.put("creditorBic", it) }
            mapper.writeValueAsString(out)
        }.getOrNull()

        /** Parsed own-account transfer request (see createTransfer). */
        internal data class TransferRequest(
            val sourceAccountId: UUID,
            val targetAccountId: UUID,
            val amount: java.math.BigDecimal,
            val currency: String,
            val description: String?,
        )

        /**
         * Parse + validate the app's transfer body with Jackson (same last-wins semantics as the
         * upstream — the ownership check must see exactly the ids that get forwarded). Returns null
         * on malformed JSON, missing/malformed ids, or a non-positive amount.
         */
        internal fun parseTransferRequest(mapper: ObjectMapper, body: String): TransferRequest? = runCatching {
            val node = mapper.readTree(body) as? ObjectNode ?: return null
            fun txt(f: String) = node.get(f)?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            val source = txt("sourceAccountId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val target = txt("targetAccountId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val amountRaw = node.get("amount")?.let { if (it.isTextual) it.asText() else it.toString() } ?: return null
            val amount = java.math.BigDecimal(amountRaw)
            if (amount.signum() <= 0) return null
            TransferRequest(source, target, amount, txt("currency") ?: "CZK", txt("description"))
        }.getOrNull()

        /**
         * Set [key]=[value] on a JSON object body (overwriting any client value) and re-serialise.
         * Jackson-based so a nested-object body stays valid JSON. Returns null on a non-object body.
         */
        internal fun injectField(mapper: ObjectMapper, body: String, key: String, value: String): String? =
            runCatching {
                val node = mapper.readTree(body) as? ObjectNode ?: return null
                node.put(key, value)
                mapper.writeValueAsString(node)
            }.getOrNull()

        /**
         * Project fx-service's rich rate record down to the app's {base, quote, rate, timestamp} shape.
         * `rate` is the mid-price (bid+ask)/2 — a neutral indicative display rate — falling back to
         * whichever side is present, or a plain `rate` field. Returns null on a malformed body or when
         * no usable rate field is present. Package-visible for unit tests.
         */
        internal fun mapFxRate(mapper: ObjectMapper, upstreamJson: String, base: String, quote: String): String? =
            runCatching {
                val node = mapper.readTree(upstreamJson) as? ObjectNode ?: return null
                fun dec(f: String): java.math.BigDecimal? = node.get(f)
                    ?.let { if (it.isTextual) it.asText() else it.toString() }
                    ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
                val bid = dec("bidRate")
                val ask = dec("askRate")
                val rate = when {
                    bid != null && ask != null -> bid.add(ask).divide(
                        java.math.BigDecimal(2),
                        FX_RATE_SCALE,
                        java.math.RoundingMode.HALF_UP,
                    )
                    ask != null -> ask
                    bid != null -> bid
                    else -> dec("rate") ?: return null
                }
                val ts = node.get("validFrom")?.takeIf { it.isTextual }?.asText()
                    ?: node.get("createdAt")?.takeIf { it.isTextual }?.asText()
                val out = mapper.createObjectNode()
                out.put("base", base)
                out.put("quote", quote)
                out.put("rate", rate.stripTrailingZeros().toPlainString())
                if (ts != null) out.put("timestamp", ts)
                mapper.writeValueAsString(out)
            }.getOrNull()

        /**
         * Project fx-service's full current-rate list (the rate-sheet endpoint) down to the app's
         * array of {base, quote, rate, bid, ask, timestamp, refMid?, spreadPct?}.
         *
         * fx-service returns rates from multiple sources in one list (`source` field). This function
         * partitions them: `source=CNB` rows build a reference-mid map (the ČNB daily fixing);
         * all other rows are treated as the bank's published commercial rates. Each bank row is then
         * enriched with `refMid` (the CNB mid for that pair) and `spreadPct` ((ask−refMid)/refMid×100)
         * so the app can show how the bank's sell price compares to the central-bank reference.
         * If no CNB rate is available for a pair the fields are simply absent (best-effort).
         *
         * Rows missing currency codes or any usable rate are skipped. Returns null only on a non-array
         * body. Package-visible for unit tests.
         */
        internal fun mapFxRateList(mapper: ObjectMapper, upstreamJson: String): String? = runCatching {
            val arr = mapper.readTree(upstreamJson) as? com.fasterxml.jackson.databind.node.ArrayNode ?: return null

            // Pass 1 — collect CNB reference mids keyed by "BASE/QUOTE"
            val cnbRef = mutableMapOf<String, java.math.BigDecimal>()
            val bankNodes = mutableListOf<ObjectNode>()
            arr.forEach { node ->
                val obj = node as? ObjectNode ?: return@forEach
                val source = obj.get("source")?.takeIf { it.isTextual }?.asText()
                val base = obj.get("baseCurrency")?.takeIf { it.isTextual }?.asText() ?: return@forEach
                val quote = obj.get("quoteCurrency")?.takeIf { it.isTextual }?.asText() ?: return@forEach
                fun dec(f: String): java.math.BigDecimal? = obj.get(f)
                    ?.let { if (it.isTextual) it.asText() else it.toString() }
                    ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
                if (source == "CNB") {
                    // fx-service serialises the computed midRate property; fall back to bid/ask
                    val mid = dec("midRate") ?: midOf(dec("bidRate"), dec("askRate")) ?: return@forEach
                    cnbRef["$base/$quote"] = mid
                } else {
                    bankNodes.add(obj)
                }
            }

            // Pass 2 — map bank rows, enriching each with the CNB reference when available
            val out = mapper.createArrayNode()
            bankNodes.forEach { obj -> mapFxRateRow(mapper, obj, cnbRef)?.let(out::add) }
            mapper.writeValueAsString(out)
        }.getOrNull()

        /** History keeps ČNB rows (unlike the commercial rate-sheet projection) and removes
         * duplicate snapshots for the same business timestamp before returning newest-first. */
        internal fun mapFxHistoryList(mapper: ObjectMapper, upstreamJson: String): String? = runCatching {
            val arr = mapper.readTree(upstreamJson) as? com.fasterxml.jackson.databind.node.ArrayNode ?: return null
            val rows = arr.mapNotNull { it as? ObjectNode }
                .mapNotNull { mapFxRateRow(mapper, it) }
                .distinctBy { it.get("timestamp")?.asText() ?: return@distinctBy it.toString() }
                .sortedByDescending { it.get("timestamp")?.asText().orEmpty() }
            mapper.writeValueAsString(mapper.createArrayNode().addAll(rows))
        }.getOrNull()

        internal fun threeMonthWindowStart(now: java.time.Instant): java.time.Instant =
            java.time.ZonedDateTime.ofInstant(now, java.time.ZoneOffset.UTC).minusMonths(3).toInstant()

        /**
         * Project one upstream rate record to the app row {base, quote, rate, bid?, ask?,
         * timestamp?, refMid?, spreadPct?}, or null to skip the row (missing currency codes or
         * no usable rate). [cnbRef] provides the ČNB reference mid for the pair (may be absent).
         * `spreadPct` = (ask − refMid) / refMid × 100, rounded to 2 d.p., so the app can display
         * how much the bank's sell price exceeds the central-bank fixing.
         */
        @Suppress("CyclomaticComplexMethod", "MagicNumber")
        private fun mapFxRateRow(
            mapper: ObjectMapper,
            obj: ObjectNode,
            cnbRef: Map<String, java.math.BigDecimal> = emptyMap(),
        ): ObjectNode? {
            val base = obj.get("baseCurrency")?.takeIf { it.isTextual }?.asText() ?: return null
            val quote = obj.get("quoteCurrency")?.takeIf { it.isTextual }?.asText() ?: return null
            fun dec(f: String): java.math.BigDecimal? = obj.get(f)
                ?.let { if (it.isTextual) it.asText() else it.toString() }
                ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
            val bid = dec("bidRate")
            val ask = dec("askRate")
            val rate = midOf(bid, ask) ?: dec("rate") ?: return null
            val ts = obj.get("validFrom")?.takeIf { it.isTextual }?.asText()
                ?: obj.get("createdAt")?.takeIf { it.isTextual }?.asText()
            val refMid = cnbRef["$base/$quote"]
            val spreadPct = if (refMid != null && refMid.signum() > 0 && ask != null) {
                ask.subtract(refMid)
                    .divide(refMid, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal("100"))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            } else {
                null
            }
            return mapper.createObjectNode().apply {
                put("base", base)
                put("quote", quote)
                put("rate", rate.stripTrailingZeros().toPlainString())
                if (bid != null) put("bid", bid.stripTrailingZeros().toPlainString())
                if (ask != null) put("ask", ask.stripTrailingZeros().toPlainString())
                if (ts != null) put("timestamp", ts)
                if (refMid != null) put("refMid", refMid.stripTrailingZeros().toPlainString())
                if (spreadPct != null) put("spreadPct", spreadPct)
            }
        }

        /** Mid-price (bid+ask)/2, or whichever single side is present, or null if neither is. */
        private fun midOf(bid: java.math.BigDecimal?, ask: java.math.BigDecimal?): java.math.BigDecimal? = when {
            bid != null && ask != null -> bid.add(ask).divide(
                java.math.BigDecimal(2),
                FX_RATE_SCALE,
                java.math.RoundingMode.HALF_UP,
            )
            else -> ask ?: bid
        }

        /**
         * Build the upstream transaction-list query (package-visible for unit tests). The client-
         * supplied cursor is URL-encoded so a raw `&`/`=` cannot append extra params to the upstream
         * URL (e.g. a second `accountId=` that would override the ownership-checked account). The
         * accountId (UUID) and limit (Int) are typed by JAX-RS, so they need no escaping.
         */
        internal fun buildTransactionsQuery(accountId: UUID, limit: Int, cursor: String?): String = buildString {
            append("?accountId=").append(accountId).append("&limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(java.net.URLEncoder.encode(cursor, Charsets.UTF_8))
            }
        }

        /**
         * Resolve the party UUID string from JWT claims (package-visible for unit tests).
         * Returns null if both claims are absent or blank.
         */
        internal fun resolvePartyIdClaim(partyIdClaim: String?, sub: String?): String? =
            partyIdClaim?.takeIf { it.isNotBlank() } ?: sub?.takeIf { it.isNotBlank() }

        /** ADR-0284 D4: the profile-switch header. Honoured only through [ActingForResolver]. */
        const val ACTING_FOR_HEADER = "X-Acting-For"
    }

    private fun forbidden(message: String): Response = Response.status(403)
        .entity("""{"error":"$message"}""")
        .type(MediaType.APPLICATION_JSON)
        .build()

    private fun badRequest(message: String): Response = Response.status(400)
        .entity("""{"error":"$message"}""")
        .type(MediaType.APPLICATION_JSON)
        .build()

    private fun notFound(message: String): Response = Response.status(404)
        .entity("""{"error":"$message"}""")
        .type(MediaType.APPLICATION_JSON)
        .build()

    /**
     * The identity-resolution dedup gate (ADR-0072 §6 / ADR-0094). Returns a short-circuit Response
     * — reuse of an existing party, or a neutral pending state — when pid resolves this applicant to
     * an existing or ambiguous identity; returns null to proceed with creation. A no-op (null) when
     * the flag is off, no birthdate is present, or the resolver is unavailable (fail open).
     */
    private fun identityResolutionGate(legalName: String, body: String, callerPartyId: String): Response? {
        if (!identityResolutionEnabled) return null
        val birthdate = extractTextField(objectMapper, body, "dateOfBirth") ?: return null
        val outcome = resolveIdentityBeforeCreate(
            legalName = legalName,
            birthdate = birthdate,
            birthNumberRaw = extractTextField(objectMapper, body, "taxId"),
            callerPartyId = callerPartyId,
        )
        return when (outcome) {
            is ResolveOutcome.MatchExisting -> {
                // Existing party (a different Keycloak sub, same person) — reuse it, never create a
                // duplicate, and link the new sub into the golden record so the two map to one party
                // (ADR-0072 §5). Best-effort: a link hiccup must not fail the already-resolved response.
                val subLinked = linkKeycloakSub(outcome.partyId, callerPartyId)
                // PR3 (issue #1270): set the party_id attribute on the Keycloak user so the *next*
                // token carries the correct party_id claim (picked up by the realm protocol mapper).
                // The current session still has party_id==sub; requireTokenRefresh signals the mobile
                // client to re-authenticate before proceeding.
                val attributeSet = keycloakAdmin.setPartyIdAttribute(callerPartyId, outcome.partyId)
                audit.emit(
                    eventType = "CUSTOMER_IDENTITY_MATCHED",
                    partyId = outcome.partyId,
                    operation = "onboarding.resolve",
                    result = "MATCH_EXISTING",
                    details = mapOf(
                        "newSub" to callerPartyId,
                        "subLinked" to subLinked.toString(),
                        "partyIdAttributeSet" to attributeSet.toString(),
                    ),
                )
                Response.ok(
                    """{"partyId":"${outcome.partyId}","status":"MATCHED_EXISTING","requireTokenRefresh":true}""",
                ).type(MediaType.APPLICATION_JSON).build()
            }

            is ResolveOutcome.NeedsVerification -> {
                parkPendingOnboarding(outcome.caseId, callerPartyId, legalName, body)
                audit.emit(
                    eventType = "CUSTOMER_IDENTITY_PENDING",
                    partyId = callerPartyId,
                    operation = "onboarding.resolve",
                    result = "NEEDS_MANUAL_VERIFICATION",
                    details = outcome.caseId?.let { mapOf("caseId" to it) } ?: emptyMap(),
                )
                // Neutral pending — never leak that an identity already exists (ADR-0072 §6).
                Response.status(Response.Status.ACCEPTED)
                    .entity("""{"status":"VERIFICATION_PENDING"}""")
                    .type(MediaType.APPLICATION_JSON).build()
            }

            ResolveOutcome.NoMatch, ResolveOutcome.Unavailable -> null
        }
    }

    /**
     * Ask pid `/api/v1/parties/resolve` whether this applicant already exists, mapping the verdict
     * to a [ResolveOutcome]. The plaintext RČ (passed as birthNumberRaw over the M2M leg) is never
     * logged here; pid reduces it to a blind index. Any non-200 / unparseable response maps to
     * [ResolveOutcome.Unavailable] so onboarding fails open (a resolver outage never blocks it).
     */
    private fun resolveIdentityBeforeCreate(
        legalName: String,
        birthdate: String,
        birthNumberRaw: String?,
        callerPartyId: String,
    ): ResolveOutcome {
        val (givenName, familyName) = splitLegalName(legalName)
        val req = objectMapper.createObjectNode()
        req.put("givenName", givenName)
        req.put("familyName", familyName)
        req.put("birthdate", birthdate)
        birthNumberRaw?.takeIf { it.isNotBlank() }?.let { req.put("birthNumberRaw", it) }
        val resp = upstream.post(
            "$pidServiceUrl/api/v1/parties/resolve",
            callerPartyId,
            objectMapper.writeValueAsString(req),
            null,
        )
        if (resp.status != 200) return ResolveOutcome.Unavailable
        val json = (resp.entity as? String).orEmpty()
        return when (extractTextField(objectMapper, json, "decision")) {
            "NO_MATCH" -> ResolveOutcome.NoMatch
            "MATCH_EXISTING" ->
                extractTextField(objectMapper, json, "partyId")
                    ?.let { ResolveOutcome.MatchExisting(it) }
                    ?: ResolveOutcome.Unavailable
            "NEEDS_MANUAL_VERIFICATION" -> ResolveOutcome.NeedsVerification(
                extractTextField(objectMapper, json, "caseId"),
            )
            else -> ResolveOutcome.Unavailable
        }
    }

    /**
     * Link the applicant's new Keycloak sub (KEYCLOAK_ID) to the matched existing party in pid, so
     * one human arriving through another channel maps to one golden-record party (ADR-0072 §5
     * identity unification).
     *
     * This is an external-id ATTACHMENT, not a party merge: it creates no `MERGED` party, moves no
     * accounts and retires no row. The real merge is ADR-0179's `POST /api/v1/parties/{id}/merge`
     * on party-service (four-eyes gated), whose `merged_into` pointer this service follows at
     * request time in [PartyMergeResolver] — do not confuse the two (issue #1984).
     *
     * Best-effort: returns true on a 2xx; failures are audited via the caller but never block
     * onboarding. The pid endpoint is idempotent and 409s a sub already linked elsewhere.
     */
    private fun linkKeycloakSub(existingPartyId: String, newSub: String): Boolean = runCatching {
        val body = """{"type":"KEYCLOAK_ID","value":"$newSub"}"""
        val resp = upstream.post(
            "$pidServiceUrl/api/v1/parties/$existingPartyId/external-ids",
            existingPartyId,
            body,
            "relink-$newSub",
        )
        resp.statusInfo.family == Response.Status.Family.SUCCESSFUL
    }.getOrDefault(false)

    /**
     * Populate the pid resolver index after a NO_MATCH create (issue #1294): register the onboarded
     * identity (name/DOB + the RČ carried as taxId + the Keycloak sub) under the new party id so
     * tier-1 dedup has data going forward. Flag-gated + best-effort; only fires when a birthdate is
     * present, and a failure is swallowed so it never breaks the registration.
     */
    private fun registerIdentityInPid(partyId: String, legalName: String, body: String) {
        if (!identityResolutionEnabled) return
        val birthdate = extractTextField(objectMapper, body, "dateOfBirth") ?: return
        runCatching {
            val (givenName, familyName) = splitLegalName(legalName)
            val req = objectMapper.createObjectNode()
            req.put("partyId", partyId)
            req.put("givenName", givenName)
            req.put("familyName", familyName)
            req.put("birthdate", birthdate)
            req.put("keycloakSub", partyId)
            extractTextField(objectMapper, body, "taxId")?.takeIf { it.isNotBlank() }
                ?.let { req.put("birthNumberRaw", it) }
            upstream.post(
                "$pidServiceUrl/api/v1/parties/register-identity",
                partyId,
                objectMapper.writeValueAsString(req),
                "register-$partyId",
            )
        }
    }

    /**
     * Park a NEEDS_MANUAL_VERIFICATION onboarding for four-eyes auto-resume (ADR-0072), keyed by the
     * pid caseId. Stores only non-sensitive applicant attributes — the plaintext RČ is never persisted
     * outside pid. No-op unless resume is enabled and a caseId was returned. Best-effort.
     */
    private fun parkPendingOnboarding(caseId: String?, callerPartyId: String, legalName: String, body: String) {
        if (!identityResumeEnabled || caseId.isNullOrBlank()) return
        val email = extractTextField(objectMapper, body, "email")
            ?: jwt.getClaim<String>("email")?.takeIf { it.isNotBlank() }
            ?: return
        runCatching {
            pendingStore.save(
                PendingOnboarding(
                    caseId = caseId,
                    callerPartyId = callerPartyId,
                    legalName = legalName,
                    email = email,
                    dateOfBirth = extractTextField(objectMapper, body, "dateOfBirth"),
                    nationality = extractTextField(objectMapper, body, "nationality"),
                    phone = extractTextField(objectMapper, body, "phone"),
                ),
            )
        }.onFailure { Log.warn("parkPendingOnboarding failed for case $caseId: ${it.message}") }
    }

    /** Best-effort (given, family) split: the last whitespace token is the family name. */
    private fun splitLegalName(legalName: String): Pair<String, String> {
        val parts = legalName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts.dropLast(1).joinToString(" ") to parts.last()
            parts.size == 1 -> parts[0] to parts[0]
            else -> legalName to legalName
        }
    }

    /** Outcome of the pid identity-resolution gate. */
    private sealed interface ResolveOutcome {
        /** An existing party matched — reuse it, do not create a duplicate. */
        data class MatchExisting(val partyId: String) : ResolveOutcome

        /** Ambiguous — route to manual verification (neutral pending to the customer). Carries the
         *  pid case id so the onboarding can be parked for four-eyes auto-resume (ADR-0072). */
        data class NeedsVerification(val caseId: String?) : ResolveOutcome

        /** No duplicate — proceed to create. */
        data object NoMatch : ResolveOutcome

        /** Resolver unavailable or unparseable — fail open (proceed to create). */
        data object Unavailable : ResolveOutcome
    }

    /** Plan for a pocket-to-primary conversion (ADR-0107): a priced deal or a precise rejection. */
    private sealed interface SweepPlan {
        data class Ready(
            val sellCurrency: String,
            val sellAmount: java.math.BigDecimal,
            val primaryCurrency: String,
            val buyAmount: java.math.BigDecimal,
            val rate: java.math.BigDecimal,
        ) : SweepPlan {
            fun toJson(): Map<String, Any> = mapOf(
                "sellCurrency" to sellCurrency,
                "sellAmount" to sellAmount.toPlainString(),
                "buyCurrency" to primaryCurrency,
                "buyAmount" to buyAmount.toPlainString(),
                "rate" to rate.stripTrailingZeros().toPlainString(),
            )
        }

        data class Rejected(val status: Response.Status, val code: String, val message: String) : SweepPlan
    }
}

/**
 * Under what authority the debit leg of a payment is being made (ADR-0232 D3/D5).
 *
 * [accountOwnerPartyId] is whose money moves — the initiator on a direct payment, the grantor on a
 * delegated one. [onBehalfOf] and [delegationId] are non-null ONLY on a delegated payment, and are
 * exactly what the audit chain needs to record it as one.
 */
internal data class DebitAuthority(
    val accountJson: String,
    val accountOwnerPartyId: UUID,
    val onBehalfOf: UUID? = null,
    val delegationId: String? = null,
)

/** Cumulative headroom held against a grant while one delegated payment is in flight (ADR-0249 D3). */
internal data class SpendReservation(val delegationId: String, val reservationId: String)

/**
 * The three ways asking for headroom can end. [NotDelegated] is not a failure — it is an owner
 * paying from their own account, where there is no grant and so no ceiling to count against, and
 * it is kept distinct from a refusal so the two can never be collapsed by accident.
 */
internal sealed interface SpendReservationOutcome {
    data class Held(val reservation: SpendReservation) : SpendReservationOutcome
    data class Refused(val response: Response) : SpendReservationOutcome
    data object NotDelegated : SpendReservationOutcome
}

/** Allowed-with-authority, or an already-audited refusal to hand straight back to the caller. */
internal sealed interface DebitAuthorityResult {
    data class Allowed(val authority: DebitAuthority) : DebitAuthorityResult
    data class Refused(val response: Response) : DebitAuthorityResult
}

/**
 * account-service's answer to "may this party debit this account for this amount".
 *
 * [outcome] is carried for the audit record only (NO_GRANT vs LIMIT_EXCEEDED vs ACCOUNT_NOT_FOUND);
 * it must never reach a customer response, or the route becomes an oracle for other people's
 * accounts and grants.
 */
internal data class DelegatedPaymentDecision(
    val authorized: Boolean,
    val outcome: String?,
    val delegationId: String?,
    val grantorPartyId: UUID?,
)
