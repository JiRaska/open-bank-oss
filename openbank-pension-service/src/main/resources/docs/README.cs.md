# openbank-pension-service — Dokumentace

> **Co to je:** účastnická strana penzijní platformy: životní cyklus `PensionContract`, volba strategie, plán příspěvků, obmyšlení a vyhodnocení časově platných jurisdikčních balíčků ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). **Co to NENÍ (řez S1):** evidence podílových jednotek ani platební engine. Zatím nevybírá příspěvky ani nic nevyplácí.

Dokumentaci služba publikuje na management endpointu `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Technologie:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-db`, CNPG)
- **Porty:** 8171 (aplikace), 8090 (management)
- **Události:** zatím žádné.
- **Autorizace:** `ROLE_API`, `ROLE_OPERATOR` nebo `ROLE_ADMIN`. Volající bez role zaměstnance musí poslat `X-Customer-Party-Id` a vidí jen smlouvy tohoto účastníka; cizí smlouva vrací 404. Zaměstnanci pouze čtou.
- **Idempotence:** hlavička `Idempotency-Key` je povinná u každého POST.
- **Jurisdikční balíčky:** `jurisdiction-packs/*.json`, načítané a validované při startu. Smlouva si fixuje verzi balíčku platnou ke dni vzniku. Balíčky CZ/DPS a CZ/DIP jsou referenční data čekající na právní revizi.

## API

| Metoda a cesta | Co dělá |
|---|---|
| `POST /api/v1/pension/contracts` | Založí smlouvu ve stavu DRAFT podle dnes platného balíčku |
| `GET /api/v1/pension/contracts/{id}` | Načte smlouvu včetně historie strategií |
| `POST /api/v1/pension/contracts/{id}/submit` | DRAFT → PENDING_ACTIVATION |
| `POST /api/v1/pension/contracts/{id}/activate` | PENDING_ACTIVATION → ACTIVE |
| `PUT /api/v1/pension/contracts/{id}/strategy` | Zvolí nebo změní strategii (historie se zachová) |
| `POST /api/v1/pension/contracts/{id}/suspend` / `resume` | Přeruší / obnoví placení příspěvků |
| `POST /api/v1/pension/contracts/{id}/incentive-evaluation` | Pobídky fixovaného balíčku pro jeden příspěvek |
| `POST /api/v1/pension/contracts/{id}/early-termination` | Náhled odbytného; `confirm=true` přepne do TERMINATING |
