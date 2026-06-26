# Compliance

> **Klasifikace money-path:** `openbank-api-gateway` **NENÍ** v `rules.yaml: money_path_services`. Nevlastní žádný peněžní stav a neprovádí transakce — pouze přeposílá requesty na money-path služby (ledger, transaction, balance, sepa, domestic, …), které nesou povinnost 2 schválení + threat-model. Jako **edge komponenta** je ale brána plně v rozsahu *dostupnostní* a *síťově-bezpečnostní* stránky regulačního rámce.

## Regulační rámec

| Regulace | Vztah ke komponentě | Implementace |
|---|---|---|
| **DORA** (Nař. (EU) 2022/2554) | Provozní odolnost vstupních dveří; ICT dostupnost | Always-on T0 tier (ADR-0057), `restart: unless-stopped` / `minReplicas ≥ 1`, retries + timeouty, Admin `/status` proba, runbooky v [05](./05-operations.md) |
| **NIS2** | Síťová a informační bezpečnost na perimetru | Jediné north-south hrdlo; passthrough zachovává end-to-end bearer autentizaci; TLS terminace na edge; Admin API v prod síťově omezené |
| **PSD2** (Nař. (EU) 2015/2366) | Přístup TPP k Open Banking prochází branou | `/api/v1/psd2` → `psd2-service` (`:8107`), `/api/v1/consents` → `consent-service` (`:8106`); brána nerozhoduje o consentu — směruje; consent/PSD2 logika je dále |
| **GDPR** | Brána přenáší PII, ale žádné neukládá | Žádná perzistence (DB-less); těla streamována, neukládána; `Authorization` jako tajemství, nelogováno |
| **AMLD** | Pouze směrování AML | `/api/v1/aml` → `aml-service` (`:8117`); brána sama screening neprovádí |

Na této vrstvě **není žádná service-specifická bankovní regulace** (žádná vyhláška ČNB o vedení účtů se neuplatní — brána nevede žádné účty).

## GDPR mapování

Brána je **zpracovatel/vodič, ne správce** osobních dat. Osobní data neukládá, neindexuje ani netransformuje.

| Aspekt | Pozice |
|---|---|
| Právní základ | N/A na této vrstvě — základ drží navazující vlastník služby (čl. 6(1)(b) smlouva / (c) právní povinnost) |
| Uložená data | **Žádná** — bezstavová, DB-less |
| Práva subjektu údajů (přístup/výmaz/přenositelnost) | **Zde neuplatnitelná** — uplatňují se vůči navazujícím službám, které vlastní záznamy (viz např. account-service `06-compliance`) |
| Logování | Access logy obsahují metodu/cestu/status/IP klienta, **ne těla**; bearer tokeny se nesmí logovat |
| Přeshraniční přenos | Brána nepřemísťuje data mimo platformu; všechny upstreamy jsou intra-OpenBank (EU/EHP) |

### Datové toky

```
klient/TPP ──Bearer──► api-gateway ──(doslovně)──► navazující vlastník služby
                          │                              │
                   bez uložení, bez logu těla       správce PII
                   předá token + tělo               (právní základ, retence)
```

Brána je **intra-platformní skok v rámci téhož správce**: osobní data ani neoriginuje, ani neukončuje; předává je správcovské službě.

## DORA mapování (Nař. (EU) 2022/2554)

| Článek | Téma | Implementace na bráně |
|---|---|---|
| Čl. 5 | Řízení ICT rizik | Edge komponenta je součástí provozního registru platformy |
| Čl. 9 | Ochrana a prevence | Passthrough zachovává navazující OIDC; Admin API omezeno; připnutý image `kong:3.7.1` |
| Čl. 10 | Detekce | Access/error logy Kongu na stdout/stderr; `/status` health; pass-through proby upstreamu |
| Čl. 11 | Reakce a obnova | `restart: unless-stopped` (lokálně) / `minReplicas ≥ 1` (cluster); runbooky pro `502`/`504`/reload konfigurace v [05](./05-operations.md) |
| Čl. 28 | Riziko třetích stran | Kong OSS, self-hosted; připnutí verze řízeno `rules.yaml: finops` lifecyklem managed verzí |

## PSD2 (Open Banking) — role brány

PSD2 přístup branou **prochází**, ale je **rozhodován dále**:

```
TPP → api-gateway (jen směrování, token předán)
    → consent-service  (validace consentu)
    → psd2-service     (překlad na interní)
    → account/balance/… (čtení)
    → odpověď zpět skrz bránu
```

Brána nepřidává consent logiku; musí zůstat transparentní, aby navazující řetězec viděl původní bearer token a hlavičky.

## Bezpečnostní kontroly

- ✅ **Jediný north-south vstupní bod** — jedno místo pro budoucí připojení authn/z, rate-limitingu, observability (směr ADR-0051).
- ✅ **Passthrough autentizace** — `Authorization` předán beze změny; navazující Quarkus služby validují Keycloak RS256 JWT (OIDC). Zachována end-to-end integrita tokenu.
- ✅ **Bezstavová / DB-less** — žádné úložiště brány k narušení, zálohování či úniku.
- ✅ **Read-only mount konfigurace** — `kong/kong.yml` je za běhu neměnný; změny jdou přes git/PR.
- ✅ **Výchozí resilience** — `retries: 2`, `connect 5s`, `read/write 30s` per upstream; omezené failure módy (`502`/`504`).
- ✅ **Připnutý image** — `kong:3.7.1`, žádný `latest`; upgrady recenzovány v rámci FinOps lifecyklu verzí.
- ⚠️ **Ověřování JWT na úrovni brány nezapnuto** — passthrough zcela spoléhá na navazující OIDC. Kong OSS `jwt` plugin je dokumentovaná, *zatím nezapnutá* možnost (placeholdery v `.env.example`). Vedeno jako položka maturity, ne jako zneužitelná mezera v local-first nastavení.
- ⚠️ **Dnes žádný rate-limiting na bráně** — záměrně odloženo; rate-limiting nyní žije v navazujících službách (`libs.web.RateLimitFilter`). Centralizace na edge je položka roadmapy.
- ⚠️ **Expozice Admin API** — `:8001` musí být v jakémkoli ne-lokálním nasazení síťově omezeno; v DB-less OSS Kongu je neautentizované.

## Audit

Brána neemituje **žádné doménové audit události** (to je `audit-service`, dostupný přes `/api/v1/audit` → `:8113`). Jejím audit důkazem je provozní: access/error logy a historie změn konfigurace v gitu. Byznysové audit traily produkují navazující služby, před nimiž brána stojí.
