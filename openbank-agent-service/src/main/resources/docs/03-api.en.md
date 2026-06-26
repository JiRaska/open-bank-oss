# API

The REST/RPC contract is formalised in [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version` **1.5.0**). The service is **internal** — it is **not** exposed via the API Gateway to external TPPs.

Two version axes apply (ADR-0048): the release version (`version.txt` = `1.5.0`) and the API-contract version (`openapi.yaml: info.version` = `1.5.0`, `openbank.api.version = "1"`). They are independent.

## Surfaces

| Surface | Path | Protocol | Auth |
|---|---|---|---|
| MCP server | `POST /mcp` | JSON-RPC 2.0 over HTTP | `X-Agent-Id` identity (deny-by-default if absent) |
| Admin assistant | `POST /agent/chat` | JSON | Keycloak OIDC; runs as `ui-assistant` |
| Model registry | `GET /agent/models` | JSON | Keycloak OIDC |
| Health | `GET /q/health/live`, `/q/health/ready` | JSON | none (management port 8085) |

## MCP — `POST /mcp`

A single JSON-RPC 2.0 dispatcher. The `method` field selects the operation. Notification methods (e.g. `notifications/initialized`) return `204 No Content`; all other methods return `200` with a JSON-RPC envelope (success `result` or `error`).

| Method | Purpose |
|---|---|
| `initialize` | Protocol handshake — negotiates `protocolVersion` (`2024-11-05`) and exchanges capabilities; returns `serverInfo`. |
| `tools/list` | Enumerate the available tools (name, description, JSON-Schema `inputSchema`). |
| `tools/call` | Invoke a named tool with `arguments`. The call is authorized by the policy gate **before** execution; a denial returns a `ToolCallResult` with `isError=true` and the policy reason. |
| `ping` | Liveness — returns `{ "pong": true }`. |

### Tool catalogue (all read-only)

Tools are grouped by the charter capability they require (`McpToolRegistry`):

| Capability | Tools |
|---|---|
| `query.ledger.readonly` | `get_account`, `get_account_by_iban`, `get_account_balance`, `list_transactions`, `get_transaction`, `get_balance_holds`, `list_ledger_journals`, `get_trial_balance` |
| `read.catalog` | `list_products`, `get_product`, `get_product_fees` |
| `query.compliance.readonly` | `aml_list_cases`, `aml_get_case`, `sanctions_list_checks`, `sanctions_get_check`, `sanctions_list_pending` |
| `query.payments.readonly` | `fx_list_rates`, `fx_get_rate`, `clearing_list_batches`, `clearing_get_batch`, `clearing_get_batch_items`, `sepa_instant_list`, `sepa_instant_get`, `sepa_instant_list_by_debtor` |
| `query.interest.readonly` | `interest_list_accruals`, `interest_get_accruals`, `interest_accrual_summary` |
| `query.disputes.readonly` | `dispute_list`, `dispute_get`, `dispute_list_by_account`, `dispute_get_timeline` |

A tool **not** present in the capability map has no capability and is **denied by default** — registering a new tool without a mapping fails closed, it does not silently bypass governance.

> Schema note: numeric tool arguments (`limit`, `size`) are declared as JSON `string` on purpose. The Groq llama backend frequently emits numeric arguments as strings and rejects an `integer` schema; the handler coerces with `asInt()`, so a string schema is both accepted and correct.

### Example — `tools/call`

```json
{ "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": { "name": "get_account_balance",
              "arguments": { "accountId": "550e8400-e29b-41d4-a716-446655440000" } } }
```

## Admin assistant — `POST /agent/chat`

Runs one governed assistant turn. Request `ChatRequest`:

| Field | Type | Notes |
|---|---|---|
| `messages` | `ChatTurn[]` (required) | conversation history, oldest first; `role ∈ {system,user,assistant,tool}` (unknown → `user`) |
| `model` | string? | model id; omit to use the gateway default |
| `context` | string? | page context from the admin UI, e.g. `"admin page /accounts"` |

Response `ChatResponse`:

| Field | Type | Notes |
|---|---|---|
| `reply` | string | the assistant's final answer |
| `model` | string | model id that produced the reply |
| `toolCalls` | `ToolCallRecord[]` | transparent record per attempted tool: `{ tool, allowed, resultPreview }` — `resultPreview` carries the policy-denial reason when `allowed=false` |
| `isProposal` | bool | true when the reply contains a recommended action requiring human confirmation (ADR-0031 D4) |

The loop enforces the `ui-assistant` charter: a runs-per-day pre-flight check (over-limit → immediate message, model not billed) and a tokens-per-run post-run check (warning appended to the reply).

## `GET /agent/models`

Returns the default model id and every enabled model in the gateway registry: `{ default, models: [{ id, provider, sensitivity }] }` where `sensitivity ∈ {HOSTED, SELF_HOSTED}`.

## Error model

- **MCP / RPC:** JSON-RPC `error` object with standard codes — `-32700` parse, `-32600` invalid request, `-32601` method not found, `-32602` invalid params, `-32603` internal.
- **Tool-level:** errors *inside* a tool (bad arguments, downstream unreachable, policy deny) are returned as a `ToolCallResult` with `isError=true` and a human-readable `text`, distinct from RPC-level errors, so the model can recover.
- **Chat-level:** model rate-limit / outage degrades to a friendly `reply` (HTTP 200), never a raw 5xx.

## Versioning

`X-API-Version` / `X-Service-Version` headers and `/api/v1/info` are served by `openbank-libs`. The MCP protocol version (`2024-11-05`) is negotiated separately on `initialize` and is independent of the HTTP API version.
