// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.libs.web.SyntheticTaintExternalBoundary
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

// Each downstream banking service validates a Keycloak bearer token (@RolesAllowed). The
// OidcClientRequestReactiveFilter acquires a client-credentials token for the `openbank-services`
// client (default quarkus.oidc-client) and attaches it as Authorization: Bearer on every call,
// so the assistant's read tools reach real data as a least-privilege service principal.
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountServiceClient {

    @GET
    @Path("/accounts/{id}")
    fun getAccount(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/accounts/{id}/balance")
    fun getBalance(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/accounts/iban/{iban}")
    fun getAccountByIban(@PathParam("iban") iban: String): JsonNode

    @GET
    @Path("/info")
    fun getInfo(): JsonNode
}

@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceClient {

    @GET
    @Path("/transactions/{id}")
    fun getTransaction(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/transactions")
    fun listTransactions(
        @QueryParam("accountId") accountId: String,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): JsonNode
}

@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceServiceClient {

    @GET
    @Path("/balances/{accountId}")
    fun getBalance(@PathParam("accountId") accountId: String): JsonNode

    @GET
    @Path("/balances/{accountId}/holds")
    fun getHolds(@PathParam("accountId") accountId: String): JsonNode
}

@RegisterRestClient(configKey = "consent-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ConsentServiceClient {

    @GET
    @Path("/consents/{id}")
    fun getConsent(@PathParam("id") id: String): JsonNode
}

// product-catalog now authenticates its callers (issue #401, #743): its reads require a valid
// token, so propagate the openbank-services bearer like every other client here. The MCP
// list_products/get_product/get_product_fees tools would otherwise 401 once #743 deploys.
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ProductCatalogClient {

    @GET
    @Path("/products")
    fun listProducts(@QueryParam("limit") @DefaultValue("50") limit: Int): JsonNode

    @GET
    @Path("/products/{id}")
    fun getProduct(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/products/{id}/fees")
    fun getProductFees(@PathParam("id") id: String): JsonNode
}

/**
 * Exact v2 revision read used by the governed catalog-review workflow (ADR-0259).
 *
 * This is deliberately a separate client from the legacy [ProductCatalogClient]: `/api/v1` and
 * `/api/v2` are distinct compatibility surfaces, and a method-level `@Path("/api/v2/...")` on
 * the v1 client would concatenate both prefixes. The service-to-service bearer is attached by the
 * same OIDC filter, and the interface exposes no mutation method.
 */
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface GenericCatalogReadClient {

    @GET
    @Path("/offerings/{offeringId}/revisions/{revisionId}")
    fun getRevision(@PathParam("offeringId") offeringId: String, @PathParam("revisionId") revisionId: String): JsonNode
}

@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerServiceClient {

    @GET
    @Path("/journals")
    fun listJournals(@QueryParam("limit") @DefaultValue("20") limit: Int): JsonNode

    @GET
    @Path("/journals/trial-balance")
    fun trialBalance(@QueryParam("asOf") asOf: String?): JsonNode
}

// ─────────────────────────────────────────────────────────────────────────────
// Read-only clients for the previously-uncovered services (aml, sanctions, fx,
// clearing, interest, dispute, sepa-instant). All GET-only — the assistant reads,
// it never screens/converts/submits (those mutate and are out of the read tier,
// ADR-0031). A downstream service that is not deployed yet simply makes the tool
// return an honest "Tool execution failed" via McpToolRegistry.call()'s catch.
// ─────────────────────────────────────────────────────────────────────────────

@RegisterRestClient(configKey = "aml-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/aml")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AmlServiceClient {

    @GET
    @Path("/cases")
    fun listCases(
        @QueryParam("status") status: String?,
        @QueryParam("partyId") partyId: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): JsonNode

    @GET
    @Path("/cases/{caseId}")
    fun getCase(@PathParam("caseId") caseId: String): JsonNode
}

@RegisterRestClient(configKey = "sanctions-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sanctions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SanctionsServiceClient {

    @GET
    fun listChecks(): JsonNode

    @GET
    @Path("/{id}")
    fun getCheck(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/pending")
    fun listPending(): JsonNode
}

@RegisterRestClient(configKey = "fx-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface FxServiceClient {

    @GET
    @Path("/rates")
    fun getRates(): JsonNode

    @GET
    @Path("/rates/{base}/{quote}")
    fun getRate(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") source: String?,
    ): JsonNode
}

@RegisterRestClient(configKey = "clearing-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/clearing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ClearingServiceClient {

    @GET
    @Path("/batches")
    fun listBatches(
        @QueryParam("status") status: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
    ): JsonNode

    @GET
    @Path("/batches/{id}")
    fun getBatch(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/batches/{id}/items")
    fun getBatchItems(@PathParam("id") id: String): JsonNode
}

@RegisterRestClient(configKey = "interest-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/interest")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface InterestServiceClient {

    @GET
    @Path("/accruals")
    fun listAccruals(): JsonNode

    @GET
    @Path("/accruals/{accountId}")
    fun getAccruals(
        @PathParam("accountId") accountId: String,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): JsonNode

    @GET
    @Path("/accruals/{accountId}/summary")
    fun getSummary(
        @PathParam("accountId") accountId: String,
        @QueryParam("from") @DefaultValue("") from: String,
        @QueryParam("to") @DefaultValue("") to: String,
    ): JsonNode
}

@RegisterRestClient(configKey = "dispute-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/disputes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DisputeServiceClient {

    @GET
    fun list(@QueryParam("status") status: String?): JsonNode

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): JsonNode

    @GET
    @Path("/account/{accountId}")
    fun listByAccount(@PathParam("accountId") accountId: String): JsonNode

    @GET
    @Path("/{id}/timeline")
    fun getTimeline(@PathParam("id") id: String): JsonNode
}

@RegisterRestClient(configKey = "sepa-instant-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sepa-instant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SepaInstantServiceClient {

    @GET
    fun listPayments(): JsonNode

    @GET
    @Path("/{paymentId}")
    fun getPayment(@PathParam("paymentId") paymentId: String): JsonNode

    @GET
    @Path("/debtor/{debtorAccountId}")
    fun listByDebtor(
        @PathParam("debtorAccountId") debtorAccountId: String,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("20") size: Int,
    ): JsonNode
}

// ─────────────────────────────────────────────────────────────────────────────
// Observability reads (Prometheus / Loki / Alertmanager) — the in-cluster LGTM(P)
// stack (ADR-0077). These are anonymous in-cluster endpoints, so — like
// product-catalog above — they carry NO OidcClientRequestReactiveFilter: attaching
// a Keycloak bearer would be pointless and some of them reject an unexpected one.
// All GET-only; the oversight agent reads telemetry, never writes/silences/deletes.
// ─────────────────────────────────────────────────────────────────────────────

@RegisterRestClient(configKey = "prometheus")
@SyntheticTaintExternalBoundary("Prometheus is an observability read endpoint, not a banking service edge")
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
interface PrometheusClient {

    @GET
    @Path("/query")
    fun query(@QueryParam("query") query: String, @QueryParam("time") time: String?): JsonNode

    @GET
    @Path("/query_range")
    fun queryRange(
        @QueryParam("query") query: String,
        @QueryParam("start") start: String,
        @QueryParam("end") end: String,
        @QueryParam("step") @DefaultValue("60s") step: String,
    ): JsonNode
}

@RegisterRestClient(configKey = "loki")
@SyntheticTaintExternalBoundary("Loki is an observability read endpoint, not a banking service edge")
@Path("/loki/api/v1")
@Produces(MediaType.APPLICATION_JSON)
interface LokiClient {

    @GET
    @Path("/query_range")
    fun queryRange(
        @QueryParam("query") query: String,
        @QueryParam("start") start: String?,
        @QueryParam("end") end: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("direction") @DefaultValue("backward") direction: String,
    ): JsonNode
}

@RegisterRestClient(configKey = "alertmanager")
@SyntheticTaintExternalBoundary("Alertmanager is an observability read endpoint, not a banking service edge")
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
interface AlertmanagerClient {

    @GET
    @Path("/alerts")
    fun listAlerts(
        @QueryParam("active") @DefaultValue("true") active: Boolean,
        @QueryParam("silenced") @DefaultValue("false") silenced: Boolean,
        @QueryParam("filter") filter: String?,
    ): JsonNode
}
