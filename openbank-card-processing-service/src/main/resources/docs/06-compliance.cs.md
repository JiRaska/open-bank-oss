# Compliance

> **Klasifikace money-path:** threat model považuje tuto službu za money-path od prvního commitu (ADR-0030, [`docs/threat-models/openbank-card-processing-service.md`](../../../../docs/threat-models/openbank-card-processing-service.md)). V době psaní ale **ještě není uvedena** v `rules.yaml: money_path_services`, takže pravidlo dvou schválení se na ni mechanicky nevynucuje.

## Regulatorní rámec

| Regulace | Vztah | Implementace |
|---|---|---|
| **PCI DSS** | Karetní transakce bez kartových dat | PAN, CVV ani kartové údaje se nepřijímají, neukládají, nelogují ani neemitují; karty se referencují id z card-issuance (ADR-0283 D7). Služba tak zůstává mimo prostředí dat držitelů karet. Přihlašovací údaje vendorů pocházejí z OpenBao, nikdy z repozitáře. |
| **PSD2** | Provádění karetních plateb | Autorizace, hold, clearing a uvolnění se zaznamenaným každým rozhodnutím včetně zamítnutí. SCA / 3-D Secure zde **není** implementováno. |
| **Účetní právo** | Karetní útrata je účetní záznam | Retence 7 let; každý přijatý clearing se zaúčtuje na railu `CARD`. |
| **GDPR** | Útratové chování identifikovatelných zákazníků | Klasifikace confidential; reference přes id; žádná volnotextová kartová data. |
| **DORA** | Provozní odolnost | Volání vydavatele fail closed, krátké timeouty, odolný outbox, liveness gauge, třístavové výsledky zaúčtování a skórování. |
| **Pravidla schémat (chargebacky)** | Řešení reklamací | Kód důvodu, stav a lhůta respond-by ze sítě se ukládají doslovně vedle bankovního stavu, takže se lhůta nikdy nepočítá z přeložené hodnoty. |
| **AML** | Vstup pro monitoring transakcí | Události v Kafce; fraud skórování je jen stínové a nic neblokuje. |

## Kontroly

| Kontrola | Kde |
|---|---|
| Žádný dvojí hold při opakování | UNIQUE `idempotency_key` |
| Žádné přečerpání clearingu | `AuthorizationLifecycle.clear` **a** CHECK omezení |
| Důvod zamítnutí jen u zamítnutí | CHECK omezení |
| Žádná tiše nenapojená integrace | `NOT_BOUND` z vendor vazeb bez přihlašovacích údajů nebo bez smlouvy |
| Sandbox acquirer nemůže pohybovat penězi v nasazeném prostředí | výchozí vypnuto, jen `ROLE_ADMIN`, 404 při vypnutí |
| Jeden živý chargeback na transakci | částečný UNIQUE index `ux_card_dispute_live_per_authorization` a kontrola v aplikaci |
| Žádná lokální reklamace bez případu v síti | otevření selhává uzavřeně; `network_case_id` je NOT NULL |
| Zastaralý seznam tokenů se nikdy neukáže jako aktuální | každý výpis nese `source` (`NETWORK` / `LOCAL_MIRROR`) |
| Autorizace přístupu | OIDC role + OPA akce `@Authorize` (poradní, dokud `AUTHZ_ENFORCE=false`; účinnou kontrolou je dnes kontrola role) |

## Známé mezery (uvedené, ne skryté)

- `AUTHZ_ENFORCE=false`: rozhodnutí OPA je poradní.
- Autorizační endpoint nemá rate limit (threat model §4).
- Clearing, jehož zaúčtování selže, zůstává zaznamenaný a nezaúčtovaný, dokud někdo nezareaguje na výsledek `FAILED`.
- Neexistuje napojení na schéma, 3-D Secure ani vendor vazba pro tokenizaci/reklamace; tokenový a reklamační desk běží jen proti simulátorům.
- Vyhraný ani prohraný chargeback v této službě nepohne penězi; případná refundace nebo odpis je dnes mimo ni.

## GDPR

- **Právní základ:** smlouva (čl. 6(1)(b)) a právní povinnost účetní retence (čl. 6(1)(c)).
- **Výmaz:** omezen sedmiletou účetní retencí.
- **Odchozí toky dat:** Kafka `openbank.card.processing.events` (v rámci platformy), transaction-service (zaúčtování), fraud-service (stínové skóre), card-issuance (rozhodnutí). Volitelné BIN lookupy do sandboxů Visa/Mastercard posílají pouze BIN, nikdy PAN.
