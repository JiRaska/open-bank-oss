# openbank-vop-service — Dokumentace

> **Co to je:** služba **Verification of Payee** (ověření příjemce, ADR-0171) — odpovídá na jedinou otázku, „je tohle jméno příjemce skutečně jméno vedené na tomto IBANu?“, výsledkem `match` / `close_match` / `no_match` / `no_data`, aby to plátce věděl **dřív**, než platbu autorizuje (nařízení (EU) 2024/886 čl. 5c, účinné od 9. 10. 2025). **Co to NENÍ:** nepohybuje penězi, **neblokuje** platbu (pouze *informuje*; rozhoduje plátce), není fraud engine (→ `openbank-fraud-service`), není sankční brána (→ `openbank-sanctions-service`, která selhává *closed* tam, kde tato selhává *open*), a **neimplementuje** přenos odpovědnosti za podvod (IPR čl. 5d — vědomě mimo rozsah).

Tuto dokumentaci publikuje služba sama na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI si ji vyzvedne při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co tam najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, proč existuje, co vědomě nedělá |
| [02 — Architektura](./02-architecture.md) | Vývoj, tech leads | Hexagonální vrstvy, dvouskokové dohledání jména, rozhodnutí fail-open |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, čtyři výsledky, pravidlo pro vyzrazení jména |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, proč ukládá hashe, retence |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release | Build, deploy, runbooky, SLO, rate limit |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | IPR čl. 5c, GDPR, DORA, co pokryto NENÍ |

## TL;DR

- **Stack:** Kotlin / Quarkus (RESTEasy Reactive) / JDK 25 / PostgreSQL / Hibernate Reactive (Panache) / Valkey — reaktivně (`Uni`), ne suspend. Bez Kafky: VoP nepublikuje žádné události.
- **Porty:** 8149 (aplikace), 8086 (management — root path `/q`).
- **Perzistence:** PostgreSQL databáze `openbank_vop`, Flyway `V1` (jedna tabulka, `vop_verification`). CNPG `instances: 2` (ADR-0159).
- **Idempotence:** žádná, a není potřeba — `POST /vop/verify` je čtení převlečené za POST (IBAN a jméno jsou osobní údaje a nesmí skončit v URL). Nemění nic než evidenční záznam.
- **Autentizace:** Keycloak OIDC (RS256 JWT) + OPA `vop.verify`. Čtení povoleno pro `ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS`; M2M volající se pouštějí přes konvenci `service-account-*`.
- **Rate limit:** 60/min na volajícího, nad Valkey, **fail-closed**. Je to bezpečnostní kontrola, ne řízení propustnosti — viz §05.
- **Money-path:** **Ano** — `rules.yaml: money_path_services`. Leží na pre-execution cestě každé eurové úhrady, takže potřebuje 2 schválení a [threat model](../../../../docs/threat-models/openbank-vop-service.md).

## Tři věci, které je dobré vědět před čtením kódu

1. **VoP selhává OPEN — záměrně opačně než jeho soused.** Sankční brána (ADR-0032) platbu při výpadku screeningu **drží**, protože propuštěná sankce je porušení zákona. VoP to dělat nesmí: IPR čl. 5c po PSP vyžaduje **varovat**, a odmítnout každou platbu během výpadku VoP by samo porušilo lhůtu pro provedení, kterou totéž nařízení ukládá. Obě brány stojí vedle sebe ve stejném toku s opačnou sémantikou selhání **záměrně**. Neopravujte to kvůli konzistenci.

2. **`no_match` nikdy nevrací jméno; `close_match` smí.** VoP je ze své podstaty **orákulum nad jmény majitelů účtů** — přesně to po něm nařízení chce. Autorizace to omezit nemůže (plátce musí smět ověřit příjemce, kterého nevlastní), takže obranou jsou **rate limit** plus tato **asymetrie vyzrazení**, vynucená v `init` bloku `VopVerification`, ne ponechaná na volajících.

3. **Requester strana je poctivá, ne hotová.** Napojení na EPC VoP scheme tu neexistuje a v referenční implementaci ani nebude — stejně jako platební rails dosáhnou jen na `openbank-clearing-simulator`. Zahraniční IBAN vrací `no_data` / `NO_SCHEME_CONNECTIVITY` přes skutečný seam (`VopSchemeRoutingPort`), místo vymyšleného verdiktu.
