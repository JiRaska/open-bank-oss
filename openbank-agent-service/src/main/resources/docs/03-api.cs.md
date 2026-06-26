# API

REST/RPC kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version` **1.5.0**). Služba je **interní** — **není** vystavena přes API Gateway externím TPP.

Platí dvě verzovací osy (ADR-0048): release verze (`version.txt` = `1.5.0`) a API-kontraktová verze (`openapi.yaml: info.version` = `1.5.0`, `openbank.api.version = "1"`). Jsou nezávislé.

## Povrchy

| Povrch | Cesta | Protokol | Auth |
|---|---|---|---|
| MCP server | `POST /mcp` | JSON-RPC 2.0 přes HTTP | identita `X-Agent-Id` (deny-by-default při absenci) |
| Admin asistent | `POST /agent/chat` | JSON | Keycloak OIDC; běží jako `ui-assistant` |
| Registr modelů | `GET /agent/models` | JSON | Keycloak OIDC |
| Health | `GET /q/health/live`, `/q/health/ready` | JSON | žádné (management port 8085) |

## MCP — `POST /mcp`

Jediný JSON-RPC 2.0 dispatcher. Pole `method` vybírá operaci. Notifikační metody (např. `notifications/initialized`) vracejí `204 No Content`; všechny ostatní metody vracejí `200` s JSON-RPC obálkou (úspěch `result` nebo `error`).

| Metoda | Účel |
|---|---|
| `initialize` | Handshake protokolu — vyjednává `protocolVersion` (`2024-11-05`) a vyměňuje capabilities; vrací `serverInfo`. |
| `tools/list` | Vyjmenuje dostupné nástroje (name, description, JSON-Schema `inputSchema`). |
| `tools/call` | Vyvolá pojmenovaný nástroj s `arguments`. Volání je autorizováno policy gate **před** provedením; zamítnutí vrací `ToolCallResult` s `isError=true` a důvodem policy. |
| `ping` | Liveness — vrací `{ "pong": true }`. |

### Katalog nástrojů (vše read-only)

Nástroje jsou seskupeny podle charter capability, kterou vyžadují (`McpToolRegistry`):

| Capability | Nástroje |
|---|---|
| `query.ledger.readonly` | `get_account`, `get_account_by_iban`, `get_account_balance`, `list_transactions`, `get_transaction`, `get_balance_holds`, `list_ledger_journals`, `get_trial_balance` |
| `read.catalog` | `list_products`, `get_product`, `get_product_fees` |
| `query.compliance.readonly` | `aml_list_cases`, `aml_get_case`, `sanctions_list_checks`, `sanctions_get_check`, `sanctions_list_pending` |
| `query.payments.readonly` | `fx_list_rates`, `fx_get_rate`, `clearing_list_batches`, `clearing_get_batch`, `clearing_get_batch_items`, `sepa_instant_list`, `sepa_instant_get`, `sepa_instant_list_by_debtor` |
| `query.interest.readonly` | `interest_list_accruals`, `interest_get_accruals`, `interest_accrual_summary` |
| `query.disputes.readonly` | `dispute_list`, `dispute_get`, `dispute_list_by_account`, `dispute_get_timeline` |

Nástroj, který **není** v mapě capabilities, nemá capability a je **zamítnut by default** — registrace nového nástroje bez mapování selže uzavřeně (fail closed), tiše neobejde governance.

> Poznámka ke schématu: číselné argumenty nástrojů (`limit`, `size`) jsou záměrně deklarovány jako JSON `string`. Groq llama backend často emituje číselné argumenty jako stringy a odmítá `integer` schéma; handler je koerzuje přes `asInt()`, takže string schéma je akceptováno i správné.

### Příklad — `tools/call`

```json
{ "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": { "name": "get_account_balance",
              "arguments": { "accountId": "550e8400-e29b-41d4-a716-446655440000" } } }
```

## Admin asistent — `POST /agent/chat`

Spustí jeden řízený tah asistenta. Požadavek `ChatRequest`:

| Pole | Typ | Poznámky |
|---|---|---|
| `messages` | `ChatTurn[]` (povinné) | historie konverzace, nejstarší první; `role ∈ {system,user,assistant,tool}` (neznámá → `user`) |
| `model` | string? | id modelu; vynechte pro default gateway |
| `context` | string? | kontext stránky z admin UI, např. `"admin page /accounts"` |

Odpověď `ChatResponse`:

| Pole | Typ | Poznámky |
|---|---|---|
| `reply` | string | finální odpověď asistenta |
| `model` | string | id modelu, který vyprodukoval odpověď |
| `toolCalls` | `ToolCallRecord[]` | transparentní záznam na pokus o nástroj: `{ tool, allowed, resultPreview }` — `resultPreview` nese důvod zamítnutí policy, když `allowed=false` |
| `isProposal` | bool | true, když odpověď obsahuje doporučenou akci vyžadující lidské potvrzení (ADR-0031 D4) |

Smyčka vynucuje charter `ui-assistant`: pre-flight kontrolu runs-per-day (přes limit → okamžitá zpráva, model se neúčtuje) a post-run kontrolu tokens-per-run (varování připojené k odpovědi).

## `GET /agent/models`

Vrací default id modelu a každý povolený model v registru gateway: `{ default, models: [{ id, provider, sensitivity }] }` kde `sensitivity ∈ {HOSTED, SELF_HOSTED}`.

## Chybový model

- **MCP / RPC:** JSON-RPC `error` objekt se standardními kódy — `-32700` parse, `-32600` neplatný požadavek, `-32601` metoda nenalezena, `-32602` neplatné params, `-32603` interní.
- **Na úrovni nástroje:** chyby *uvnitř* nástroje (špatné argumenty, nedostupný downstream, deny policy) se vracejí jako `ToolCallResult` s `isError=true` a čitelným `text`, odlišně od RPC chyb, aby se model mohl zotavit.
- **Na úrovni chatu:** rate-limit / výpadek modelu degraduje na přívětivou `reply` (HTTP 200), nikdy ne syrový 5xx.

## Verzování

Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`. Verze MCP protokolu (`2024-11-05`) se vyjednává zvlášť při `initialize` a je nezávislá na verzi HTTP API.
