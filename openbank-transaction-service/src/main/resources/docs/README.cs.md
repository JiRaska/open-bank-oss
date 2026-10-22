# openbank-transaction-service — Dokumentace

> **Co to je:** služba na peněžní cestě (money-path), která **iniciuje a eviduje transakce** — orchestruje platební ságu (rezervace prostředků → zaúčtování v ledgeru → zachycení → dokončení) a je systémem záznamu pro historii transakcí a vyhledávání transakcí podle BIAN. **Co to NENÍ:** není to podvojná účetní kniha (to je `openbank-ledger-service`), ani engine zůstatků (`openbank-balance-service`), ani adaptér platebního schématu (`sepa-payment`, `domestic-payment`, `swift-service`, … zde transakce zakládají).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýring, tech leadi | C4 diagramy, hexagonální vrstvy, platební sága, outbox tok |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive (Panache) / reaktivní PostgreSQL / SmallRye Kafka — viz `build.gradle.kts`
- **Port:** 8102 (aplikace), 8085 (management, `/q`)
- **Persistence:** databáze PostgreSQL `openbank_transactions`, range-partitionovaná tabulka `transactions`, Flyway migrace V1..V7
- **Outbox:** `transaction_outbox` → Kafka topic `openbank.transactions.transaction.initiated` (a typy událostí `.completed` / `.failed`)
- **Idempotence:** `idempotencyKey` od volajícího na iniciačním příkazu → unique constraint na `(idempotency_key, booking_date)` a na platební sáze
- **Orchestrace:** synchronní platební sága (hold → zaúčtování → debet/kredit → dokončení, s kompenzací)
- **Auth:** Keycloak OIDC; čtení vyžaduje `ROLE_API`/`ROLE_VIEWER`/`ROLE_OPERATOR`/`ROLE_ADMIN`, iniciace vyžaduje `ROLE_OPERATOR`
- **Money-path služba** (viz `rules.yaml: money_path_services`) — 2 schválení + threat model u každé změny

## Zapojení při buildu

- **Úložiště schvalování čtyř očí.** `openbank.approval.store: redis` v `application.yaml` zapne v libs-runtime `RedisApprovalStoreProducer`, který dodá `ApprovalStore` nad Redisem. Čte se při buildu; po odebrání bean zmizí a služba neprojde `quarkusBuild`, místo aby tiše běžela bez čtyř očí. Nasazený host Redisu určuje `QUARKUS_REDIS_HOSTS` na workloadu, nikdy výchozí `localhost`.

## Globální idempotence a nasazení V22

Klíč zahájení platí přes všechna data zaúčtování. V22 jej rezervuje v
`transaction_idempotency_keys` databázovým triggerem BEFORE INSERT ve stejné
transakci jako zaúčtování a iniciační outbox. Souběžný zápis počká na první
transakci a vrátí její ID a uložený stav. Nový požadavek i opakování vracejí HTTP 201;
volající musí ověřit stav. PENDING/PROCESSING znamená probíhající zpracování,
FAILED/REVERSED neznamená úspěšné zaúčtování. Opakování nespouští další workflow.
Tato záruka se týká zahájení, nikoli všech vedlejších účinků endpointu storna.

V22 před kontrolou a doplněním historie zamkne rodičovskou tabulku i všechny
připojené oddíly pomocí ACCESS EXCLUSIVE. Nasazení vyžaduje plánovanou bariéru
zápisů a omezenou dobu čekání na zámek. Historické duplicitní klíče migraci přeruší
bez změny finančních řádků. Vlastník rekonciliace musí posoudit historii a schválit
nápravu před opakováním. Nevybírat vítěze automaticky, nemazat historii, neignorovat
kolize a nespouštět migraci mimo transakci. Před úspěšným dokončením V22 zůstávají
staré uzly nechráněné; selhání migrace samo nezastaví jejich další zápisy.

Trigger chrání i staré uzly a přímý zápis do připojených oddílů. Nové prázdné oddíly
jej zdědí. Připojení již naplněného oddílu vyžaduje samostatnou řízenou údržbu:
zastavit všechny zápisy, zamknout rodiče i oddíl, ověřit klíče proti historii a
registru, doplnit rezervace, připojit oddíl a ověřit triggery a jednoznačné ukazatele
před obnovením zápisů. Samotné připojení historii do registru nedoplní. Triggery
nevypínat. Klíč, ID a datum zaúčtování jsou neměnné; změny stavu jsou povolené.
Rezervace se při archivaci nebo smazání neuvolňuje. Chybějící nebo poškozený
ukazatel registru vyvolá chybu a vyžaduje rekonciliaci.

Rollback: vrátit aplikaci a zachovat V22, registr i triggery. Odstranění ochrany
vyžaduje samostatně schválenou údržbu, souhlas vlastníka a zastavení zápisů i
opakovaných požadavků na všech uzlech. Migrace ani bezpečný návrat aplikace nemaže
finanční historii.
