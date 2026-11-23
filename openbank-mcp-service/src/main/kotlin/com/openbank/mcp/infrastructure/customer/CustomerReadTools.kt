// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.AuthzQuery
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.authz.Principal
import com.openbank.mcp.application.McpInputBounds
import com.openbank.mcp.application.McpPiiMasker
import com.openbank.mcp.application.McpUntrustedData
import com.openbank.mcp.application.protocol.ToolCallResult
import com.openbank.mcp.application.protocol.ToolContent
import com.openbank.mcp.application.protocol.ToolDefinition
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The customer channel's tool surface (ADR-0383 D3/D4): three READS, each offered only while one of
 * the caller's active `MCP_CLIENT` grants carries the delegation capability it needs, and each call
 * checked against the grant of the ONE account it names. Nothing here writes; `propose_payment` is
 * not on this channel in this slice.
 *
 * Order on a call, cheapest refusal first: unknown tool → argument shape → is this account in a
 * grant that carries this tool's capability → mcp-service's PDP → exchange the customer's token for a 60-second token
 * scoped to that one downstream service and read scope → read. No step falls back to a staff or
 * service credential; a failed exchange is a refusal.
 *
 * Results go through the same two response controls as the staff path (`McpToolRegistry.call`):
 * [McpPiiMasker] then [McpUntrustedData.wrap], wrapping last.
 */
@ApplicationScoped
class CustomerReadTools @Inject constructor(
    private val pdp: PolicyDecisionPoint,
    private val exchange: CustomerTokenExchange,
    private val downstream: CustomerDownstream,
    private val masker: McpPiiMasker,
    private val mapper: ObjectMapper,
) {
    /** The authenticated caller, as admission verified it. */
    data class Caller(val subject: String, val rawToken: String, val clientId: String)

    sealed interface Outcome {
        /** [grantId] is the grant the call ran under; null for `list_accounts` (it reads no account). */
        data class Allowed(val result: ToolCallResult, val capability: String, val grantId: UUID?) : Outcome

        /** [invalidateGrant]: the caller drops the cached grant (the read failed after the grant was used). */
        data class Refused(
            val reason: String,
            val capability: String?,
            val unavailable: Boolean = false,
            val invalidateGrant: Boolean = false,
        ) : Outcome

        /** The downstream refused the exchanged token: the grant may have changed. Caller invalidates. */
        data class DownstreamRejected(val reason: String, val capability: String) : Outcome
    }

    /** [action] is the PDP action (McpToolRegistry's capability name); [capability] the delegation one. */
    private class Tool(val definition: ToolDefinition, val capability: String, val action: String)

    private val tools: Map<String, Tool> = listOf(
        Tool(
            ToolDefinition(
                name = LIST_ACCOUNTS,
                description = "List the accounts you have shared with this assistant.",
                inputSchema = schema(emptyMap(), emptyList()),
                service = "openbank-delegation-service",
            ),
            CustomerCapabilities.VIEW_DETAILS,
            "query.account.readonly",
        ),
        Tool(
            ToolDefinition(
                name = GET_BALANCE,
                description = "Get the balance of an account you have shared for balance reading.",
                inputSchema = schema(mapOf(ACCOUNT_ID to uuidProp()), listOf(ACCOUNT_ID)),
                service = "openbank-balance-service",
            ),
            CustomerCapabilities.READ_BALANCES,
            "query.balance.readonly",
        ),
        Tool(
            ToolDefinition(
                name = LIST_TRANSACTIONS,
                description = "List recent transactions of an account you have shared for transaction reading.",
                inputSchema = schema(
                    mapOf(
                        ACCOUNT_ID to uuidProp(),
                        "limit" to mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to McpInputBounds.MAX_TX_LIMIT,
                        ),
                    ),
                    listOf(ACCOUNT_ID),
                ),
                service = "openbank-transaction-service",
            ),
            CustomerCapabilities.READ_TRANSACTIONS,
            "query.transaction.readonly",
        ),
    ).associateBy { it.definition.name }

    /** Tools whose capability at least one active grant carries. No grant ⇒ no tools. */
    fun list(grants: List<CustomerGrant>): List<ToolDefinition> =
        tools.values.filter { t -> grants.any { it.allows(t.capability) } }.map { it.definition }

    val totalTools: Int get() = tools.size

    fun capabilityOf(tool: String): String? = tools[tool]?.capability

    fun call(tool: String, arguments: JsonNode, caller: Caller, grants: List<CustomerGrant>): Outcome {
        val spec = tools[tool] ?: return Outcome.Refused("tool not available on the customer channel", null)
        val capability = spec.capability
        if (tool == LIST_ACCOUNTS) {
            val shared = grants.filter { it.allows(capability) }
            if (shared.isEmpty()) return Outcome.Refused("no active grant carries $capability", capability)
            policy(spec, caller, shared.flatMap { it.capabilities }.toSet())?.let { return it }
            return Outcome.Allowed(render(accounts(shared)), capability, null)
        }
        val read = readOf(tool, arguments) ?: return Outcome.Refused("invalid arguments", capability)
        val grant = grants.firstOrNull { it.accountId == read.accountId && it.allows(capability) }
            ?: return Outcome.Refused("account is not shared with this client for $capability", capability)
        policy(spec, caller, grant.capabilities)?.let { return it }
        return exchangeAndRead(read, caller, capability, grant)
    }

    /**
     * ADR-0383 D4.1: mcp-service's own PDP, consulted for every customer call AFTER the grant check
     * and BEFORE any token is exchanged. The principal is the customer (HUMAN, not a service
     * account, no roles) on channel `mcp-customer`; `grantedCapabilities` are the server-resolved
     * capabilities of the grant in question. `mcp_rest_ext.rego` admits only the read tools under
     * their own action and capability and vetoes everything else on this channel. A deny or a PDP
     * error is a refusal. Returns null when allowed.
     */
    @Suppress("TooGenericExceptionCaught") // a PDP transport error of any type must fail closed
    private fun policy(tool: Tool, caller: Caller, granted: Set<String>): Outcome? {
        val query = AuthzQuery(
            principal = Principal(id = caller.subject, type = HUMAN, roles = emptyList(), serviceAccount = false),
            action = tool.action,
            attributes = mapOf(
                "channel" to CustomerChannelAdmission.PDP_CHANNEL,
                "client" to caller.clientId,
                "tool" to tool.definition.name,
                "grantedCapabilities" to granted.sorted(),
            ),
        )
        val decision = try {
            runBlocking { pdp.allow(query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Refused("pdp unavailable: ${e.javaClass.simpleName}", tool.capability, unavailable = true)
        }
        return if (decision.allow) {
            null
        } else {
            Outcome.Refused(
                "denied by policy: ${decision.reason ?: "no matching allow rule"}",
                tool.capability,
            )
        }
    }

    private class Read(val tool: String, val accountId: UUID, val limit: Int)

    private fun readOf(tool: String, arguments: JsonNode): Read? {
        val accountId = accountIdOf(arguments) ?: return null
        val limit = if (tool == LIST_TRANSACTIONS) {
            runCatching { McpInputBounds.transactionLimit(arguments.path("limit")) }.getOrNull() ?: return null
        } else {
            0
        }
        return Read(tool, accountId, limit)
    }

    @Suppress("TooGenericExceptionCaught") // every downstream failure must become one audited refusal
    private fun exchangeAndRead(read: Read, caller: Caller, capability: String, grant: CustomerGrant): Outcome {
        val balance = read.tool == GET_BALANCE
        val audience = if (balance) DownstreamAudience.BALANCE else DownstreamAudience.TRANSACTION
        val token = try {
            exchange.exchange(caller.rawToken, caller.subject, audience)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenExchangeRefused) {
            return Outcome.Refused(e.message ?: "token exchange refused", capability, unavailable = true)
        } catch (e: Exception) {
            return Outcome.Refused("token exchange failed: ${e.javaClass.simpleName}", capability, unavailable = true)
        }
        val body = try {
            if (balance) {
                downstream.balances(
                    token,
                    read.accountId,
                )
            } else {
                downstream.transactions(token, read.accountId, read.limit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownstreamRefused) {
            return Outcome.DownstreamRejected(e.message ?: "downstream refused", capability)
        } catch (e: Exception) {
            // A 5xx, a timeout or a malformed body: refused, audited by the caller, and the cached
            // grant dropped so the next call re-resolves it rather than retrying on a stale answer.
            return Outcome.Refused(
                "downstream read failed: ${e.javaClass.simpleName}",
                capability,
                unavailable = true,
                invalidateGrant = true,
            )
        }
        return Outcome.Allowed(render(body), capability, grant.grantId)
    }

    private fun accounts(grants: List<CustomerGrant>): JsonNode {
        val root = mapper.createObjectNode()
        val list = root.putArray("accounts")
        grants.forEach { g ->
            val entry = list.addObject().put(ACCOUNT_ID, g.accountId.toString())
            val names = entry.putArray("tools")
            readTools(g).forEach { names.add(it) }
        }
        return root
    }

    private fun readTools(g: CustomerGrant): List<String> = tools.values.filter {
        it.definition.name != LIST_ACCOUNTS && g.allows(it.capability)
    }.map { it.definition.name }

    private fun render(result: JsonNode): ToolCallResult {
        val masked = mapper.writeValueAsString(masker.mask(result))
        return ToolCallResult(listOf(ToolContent(text = McpUntrustedData.wrap(masked))))
    }

    private fun accountIdOf(arguments: JsonNode): UUID? = arguments.path(ACCOUNT_ID)
        .takeIf { it.isTextual && UUID_SHAPE.matches(it.asText()) }
        ?.let { UUID.fromString(it.asText()) }

    companion object {
        private const val HUMAN = "HUMAN"
        const val LIST_ACCOUNTS = "list_accounts"
        const val GET_BALANCE = "get_balance"
        const val LIST_TRANSACTIONS = "list_transactions"
        private const val ACCOUNT_ID = "accountId"
        const val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        private val UUID_SHAPE = Regex(UUID_PATTERN)
    }
}

private fun uuidProp() = mapOf(
    "type" to "string",
    "pattern" to CustomerReadTools.UUID_PATTERN,
    "description" to "accountId from list_accounts",
)

private fun schema(props: Map<String, Any>, required: List<String>): Map<String, Any> = buildMap {
    put("type", "object")
    put("properties", props)
    if (required.isNotEmpty()) put("required", required)
}
