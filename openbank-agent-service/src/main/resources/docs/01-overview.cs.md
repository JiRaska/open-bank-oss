# Přehled

## Co služba dělá

`openbank-agent-service` je **AI agentní vrstva** platformy OpenBank. Implementuje [ADR-0031 (governance a provoz AI agentů)](../../../../docs/adr/0031-ai-agent-governance-and-operations.md) a vystavuje dva povrchy:

- **MCP server** (`POST /mcp`) — JSON-RPC server protokolu Model Context Protocol 2.0, který publikuje katalog **read-only** doménových operací OpenBank jako AI volatelné *nástroje* (`tools/list`, `tools/call`). Externí MCP klient (např. agent v IDE) je může vyjmenovat a vyvolat.
- **Asistent admin-UI** (`POST /agent/chat`) — **serverová** smyčka uvažování. Volání modelu nikdy neprobíhá v prohlížeči: admin UI pošle konverzaci, služba vyřeší model přes provider-agnostickou **model gateway**, spustí ohraničenou smyčku model↔nástroj, každé volání nástroje protáhne přes **OPA policy gate** a vrátí odpověď plus transparentní záznam každého volání nástroje a zda jej policy povolila.

Asistent běží pod jednou pevnou identitou control-plane, **`ui-assistant`** (charter v `openbank-libs/governance/agents.yaml`): **pouze pro čtení, pouze návrhy, nikdy money-path nástroj**.

## Co služba **NEDĚLÁ**

- ❌ Nedrží žádná bankovní data — nemá vlastní doménový agregát.
- ❌ Nemění stav — **neexistuje zapisovací nástroj**; charter zakazuje `money.*`, `*.write`, `gh.pr.*`, `secrets.read.raw`.
- ❌ Neprovádí platby, neúčtuje do ledgeru ani nepřesouvá peníze — **není** v `rules.yaml: money_path_services`.
- ❌ Nespouští LLM v prohlížeči — model se volá serverově, za hranicí důvěry gateway.
- ❌ **Není** vystavena externím TPP přes API Gateway — je to interní služba.
- ❌ Nejedná autonomně na změnách stavu — jakýkoli stav měnící následný krok, o který operátor požádá, je vystaven jako **návrh** k lidskému schválení (ADR-0031 D4), provede ho jiná služba, ne tato.

## Pozice v doméně

```
   ┌────────────┐  POST /agent/chat   ┌───────────────────────────────┐
   │  admin UI  │ ──────────────────► │       agent-service           │
   └────────────┘                     │  ┌─────────────────────────┐  │
   ┌────────────┐  POST /mcp          │  │ smyčka AgentChatService │  │
   │ MCP klient │ ──────────────────► │  │  model gateway ─┐       │  │
   └────────────┘                     │  │  policy gate ───┤       │  │
                                       │  └────────────────┼───────┘  │
                                       └───────────────────┼──────────┘
                  completion modelu ◄── (mock / openai-compat)
                                       │ read-only MCP nástroje (Bearer: openbank-services)
                                       ▼
        account · transaction · balance · ledger · product-catalog
        aml · sanctions · fx · clearing · interest · dispute · sepa-instant
                                       │ každé volání nástroje + každé volání modelu
                                       ▼
                              audit-service (AI-atribuovaný AuditEvent)
```

## Klíčové případy užití

| Případ užití | API | Governance |
|---|---|---|
| Vypsat nástroje, které agent smí volat | `POST /mcp` `{method: tools/list}` | — |
| Vyvolat read-only nástroj | `POST /mcp` `{method: tools/call}` | OPA gate + audit na volání |
| Položit dotaz admin asistentovi | `POST /agent/chat` | charter limity + per-tool OPA gate + AI-atribuovaný audit |
| Vypsat registrované modely | `GET /agent/models` | — |
| Handshake protokolu / liveness | `POST /mcp` `{method: initialize \| ping}` | — |

## Volající

- **admin-ui** — operátoři / compliance, přes serverový endpoint `/agent/chat` (Keycloak token).
- **Externí MCP klienti** — IDE nebo agent runtime hovořící MCP 2.0 přes `POST /mcp`, deklarující identitu `X-Agent-Id` (deny-by-default při absenci).

## Závislosti

- **Downstream bankovní služby** (read-only REST klienti): account (8100), ledger (8101), transaction (8102), balance (8103), product-catalog (8104), consent (8106), psd2 (8107), aml (8117), sanctions (8118), fx (8119), clearing (8124), interest (8125), dispute (8135), sepa-instant (8127).
- **OPA sidecar** (8181) — policy decision point pro agent policy gate.
- **Keycloak** — OIDC resource server (příchozí) a `openbank-services` client-credentials (odchozí service-to-service).
- **Model backendy** — provider-agnosticky přes gateway: offline `mock` provider (default) a `openai-compat` adaptér (např. Groq, nebo self-hosted vLLM/Ollama pro citlivý tier). Pro build/test není potřeba žádný backend.
- **openbank-libs** — `AuditEventPublisher` (AI-atribuovaný audit), `BuildInfo`, `DocsResource`, bezpečnostní instalatérství.

## Obchodní hodnota

- **Jediná řízená spára pro AI** — každé volání modelu i nástroje prochází jednou hranicí důvěry (gateway + policy gate), takže AI přístup k bance je least-privilege, auditovatelný a odvolatelný bez redeploye.
- **Nezávislost na poskytovateli** — přidání či výměna modelu je položka konfigurace, ne změna kódu; žádný lock-in na jednoho dodavatele (ADR-0031 D6).
- **Produktivita operátora** — admin asistent odpovídá na mezidoménové dotazy z živých read-only dat napříč účty, platbami, compliance, ledgerem a dalšími — bez jakéhokoli zapisovacího přístupu.
- **Transparentnost ze samé podstaty** — asistent vrací per-tool záznam allow/deny; každé rozhodnutí je AI-atribuovaný audit event pro regulátora.
