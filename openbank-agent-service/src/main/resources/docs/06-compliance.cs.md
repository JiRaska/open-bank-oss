# Compliance

`openbank-agent-service` **není** money-path služba (`rules.yaml: money_path_services` ji neuvádí) a nedrží **žádná vlastní bankovní data**. Její compliance postoj je proto o **řízení AI přístupu k bance**, ne o ochraně stavu účtů. Řídícím rozhodnutím je [ADR-0031 (governance a provoz AI agentů)](../../../../docs/adr/0031-ai-agent-governance-and-operations.md); autorizace je [ADR-0018](../../../../docs/adr/0018-opa-for-fine-grained-authz.md) / [ADR-0034](../../../../docs/adr/0034-unified-opa-authz-mcp-and-rest.md).

> **Řídící princip (ADR-0031): agenti navrhují, governance rozhoduje.** Agent nikdy nedrží více oprávnění než člověk — drží jich méně. Default je DENY; povolení vyžaduje odpovídající charter pravidlo.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **EU AI Act** | AI systém operující uvnitř banky — potřebuje lidský dohled, logování, transparentnost | deny-by-default policy gate; AI-atribuovaný audit každého volání modelu i nástroje; HITL přes detekci návrhů (D4); per-agent kill-switch (D7, přes `agents.yaml`/OPA) |
| **DORA** (Nař. (EU) 2022/2554) | ICT/operační odolnost interní služby | health probes, fail-closed PDP, ladná degradace při výpadku modelu, audit důkazy, SLO/runbooky ([05 — Provoz](./05-operations.md)) |
| **GDPR** | Asistent může číst zákaznická data přes nástroje | charter je `pii: masked`; gateway ukládá jen SHA-256 `prompt_hash`, nikdy syrové prompty; žádné PII perzistované v klidu (žádné úložiště) |
| **PSD2 / AML** | Asistent může *číst* AML/sanctions/payments povrchy | pouze read-only capabilities (`query.compliance.readonly`, `query.payments.readonly`); nikdy zapisovací/rozhodovací nástroj; **není** systém záznamu pro SAR/screening |
| **NIS2** | Síťová a informační bezpečnost | OIDC příchozí, least-privilege `openbank-services` Bearer odchozí, mTLS v clusteru (Istio), bezpečnostní response hlavičky |
| **ADR-0030 SSDLC** | Dodavatelský řetězec / bezpečný SDLC | podepsané commity, governance závislostí, žádný lock-in na third-party agent SDK (otevřený runtime, ADR-0031 D6) |

## Řídící kontroly AI (ADR-0031)

| Rozhodnutí | Kontrola v této službě |
|---|---|
| **D2 — policy gate** | `AgentPolicyGate` (PEP) → OPA PDP. Každé `tools/call` je autorizováno proti charteru z `agents.yaml`; nenamapovaný nástroj → deny-by-default. |
| **D2 — charter limity** | `CharterRateLimiter`: `tokens_per_run` (100k) a `runs_per_day` (500) pro `ui-assistant`. |
| **D4 — human-in-the-loop** | `ProposalDetector` označuje odpovědi doporučující akci; admin UI je vykreslí jako návrhy ke schválení. Asistent sám nikdy nejedná. |
| **D5 — AI atribuce** | `ModelGateway` a `AgentPolicyGate` emitují `AuditEvent {actorType=AI_AGENT}` pro každý completion / rozhodnutí (model_id, model_version, prompt_hash, tokeny, policy_decision, reason). |
| **D6 — model gateway** | jediná hranice důvěry, kterou prochází každé volání modelu; provider-agnostická, citlivý kontext připnut k self-hosted modelu. |
| **D9 — fázované vynucení** | `EnforcementMode` ADVISORY (jen audit, default) → BLOCK; fail-closed PDP s bezpečnostním fallbackem `pdpError`, takže mrtvý OPA nikdy nezamkne asistenta. |

## Charter `ui-assistant` (agents.yaml)

| Aspekt | Hodnota |
|---|---|
| Plane | `control` |
| Rozsah čtení | account, transaction, balance, catalog, ledger, aml, sanctions, fx, clearing, interest, dispute, sepa-instant |
| PII | maskováno |
| Povolené capabilities | `query.ledger.readonly`, `read.catalog`, `query.compliance.readonly`, `query.payments.readonly`, `query.interest.readonly`, `query.disputes.readonly` |
| **Zakázané** | `money.*`, `gh.pr.*`, `*.write`, `secrets.read.raw` |
| Vyžaduje člověka | každý návrh (HITL) |
| Limity | tokens_per_run 100000, runs_per_day 500 |

Charter je jediný zdroj pravdy; kód čte limity z konfigurace a mapuje nástroje na capabilities, ale **co agent smí dělat je definováno jednou v `agents.yaml`**, nikdy neduplikováno jako próza.

## GDPR mapování

### Právní základ (čl. 6)
- **Oprávněný zájem** (čl. 6(1)(f)) — back-office operační podpora; asistent čte jen data, ke kterým jsou operátoři již oprávněni, s maskovaným PII.

### Práva subjektu údajů
Agent service **není** systém záznamu a neukládá žádná zákaznická data, takže žádosti o práva subjektu obsluhují **vlastnící** služby (account, transaction, …). Asistent sám nemá co exportovat, opravit ani vymazat.

### Toky dat
- **Dovnitř:** read-only volání nástrojů na downstream služby, nesoucí least-privilege `openbank-services` Bearer (ne operátorův token).
- **Ven:** completion modelu (na nakonfigurovaný backend — `mock` offline defaultně; hostovaný `openai-compat` backend nebo self-hosted model pro citlivý tier) a AI-atribuované audit eventy do `audit-service`.
- **Směrování citlivosti:** gateway připíná citlivý (PII / money-path) kontext k `self-hosted` modelu, je-li registrován (ADR-0031 D6) — drží citlivý obsah mimo third-party hostované API.
- Tato služba neperzistuje žádné zákaznické PII v klidu.

## DORA mapování (Nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5/6 | rámec řízení ICT rizik | závislost = openbank-libs (centralizováno); žádný third-party SaaS agent stack (in-cluster runtime, ADR-0031 D6) |
| Čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) na `/api/v1/info`; AI atribuce na každé akci |
| Čl. 10 | Detekce | metriky + alerting; WARN logy při BLOCK zamítnutích a fallbacku při nedosažitelném PDP |
| Čl. 11 | Odezva & obnova | ladná degradace při výpadku modelu; fail-closed PDP; runbooky v [05 — Provoz](./05-operations.md); bezstavová ⇒ levná obnova |
| Čl. 16/17 | Řízení incidentů & reporting | AI-atribuované audit eventy → důkazní pipeline audit-service |
| Čl. 28 | Riziko třetích stran | provider-agnostická model gateway — žádný lock-in na jednoho dodavatele; hostovaný model lze nahradit self-hosted změnou konfigurace |

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC (RS256 JWT) příchozí; `openbank-services` client-credentials Bearer odchozí.
- ✅ AuthZ: deny-by-default OPA policy gate na volání nástroje; nenamapovaný nástroj selže uzavřeně.
- ✅ Least privilege: read-only povrch nástrojů; charter zakazuje veškeré zápisy/peníze/secrets.
- ✅ Postoj k prompt-injection: systémový prompt instruuje model, aby s daty nástrojů nakládal jako s nedůvěryhodnými a nikdy nenásledoval instrukce uvnitř nich (guardraily ADR-0031 D6 — llama-guard / injection filter jsou runtime cíl).
- ✅ Audit: každé volání modelu + každé rozhodnutí policy je AI-atribuováno.
- ✅ Žádný syrový prompt v klidu: pouze SHA-256 `prompt_hash`.
- ✅ Response hlavičky: CSP, X-Frame-Options DENY, nosniff, HSTS, Referrer/Permissions-Policy.
- ✅ Rate limiting: `openbank.rate-limit` (max-concurrent) + per-agent charter limity.
- ⚠️ Distribuované vynucení charteru: rate-limit čítače jsou v paměti (reset při restartu); multi-replica distribuované vynucení je sledovaný follow-up.
- ⚠️ Default vynucení je `advisory` — přepněte na `block`, jakmile je v každém cíli přítomen OPA sidecar (ADR-0031 D9).
