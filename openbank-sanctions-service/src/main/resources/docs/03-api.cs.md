# API & kontrakty

## Base path

- **Produkční base pro prověření:** `http://openbank-sanctions-service:8123/api/v3` (in-cluster)
- **Správa listin a schvalování:** zůstávají pod `/api/v1` a `/api/v2` podle dokumentace níže
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8123/q/openapi)
- **Swagger UI (dev):** [`/q/swagger-ui`](http://localhost:8123/q/swagger-ui)

### Migrace prověření z v1 do v3

`CZ_TERRORISTS` je nová hodnota v požadavcích a odpovědích screeningů, proto má šest operací
vracejících `SanctionsCheck` paralelní kontrakt `/api/v3`: seznam prověření, screening, přezkum,
hity, čekající přezkumy a detail podle ID. Tyto volání přesuňte do v3; URL správy listin, obnovy
a schvalování se nemění.

Nahrazené operace v1 vracejí `Deprecation: true`, hlavičku `Sunset` podle RFC 8594 a `Link` na v3
s `rel="successor-version"`. Během migračního okna zůstává jejich kontrakt neměnný:

- vynechané, null nebo prázdné `listTypes` znamená původních sedm listin bez `CZ_TERRORISTS`;
  ve v3 znamená všech osm;
- v1 odmítne explicitní požadavek na `CZ_TERRORISTS` i idempotentní replay mezi major verzemi s 400;
- prověření obsahující novou listinu je skryté z kolekcí v1 a detail/přezkum v1 odpoví 404 před
  jakoukoli změnou. Pro čtení nebo přezkum použijte stejnou operaci ve v3.

## Autentizace

Všechny endpointy vyžadují **Keycloak Bearer token** s realm `openbank`. Mutující operace navíc vyžadují `ROLE_OPERATOR`:

| Role | Oprávnění |
|---|---|
| `ROLE_VIEWER` | Pouze GET (seznam prověření, hitů, čekajících, listin) |
| `ROLE_OPERATOR` | GET + screen + review + správa listin |
| `ROLE_COMPLIANCE` | GET + screen + review (primární role pro compliance důstojníky) |
| `ROLE_ADMIN` | vše |

## Idempotence

Všechny **POST** screeningové požadavky vyžadují `idempotencyKey` v těle požadavku (ne v headeru jako u jiných služeb):

```json
{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  ...
}
```

Pravidla:
- Klient generuje UUID v4 pro každý logický screeningový požadavek.
- Stejný klíč → vrátí původní výsledek `SanctionsCheck` (replay-safe, cache v Redis).
- Klíč je uložen v `sanctions_checks.idempotency_key` (UNIQUE constraint).

## Klíčové endpointy

### Prověřit entitu

```http
POST /api/v3/sanctions/screen
Content-Type: application/json
Authorization: Bearer <token>

{
  "idempotencyKey": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "entityType": "INDIVIDUAL",
  "name": "Jan Novák",
  "aliases": ["J. Novák"],
  "dateOfBirth": "1975-03-14",
  "nationality": "CZ",
  "identifiers": {
    "passport": "123456789"
  }
}
```

**Rozhodovací logika pro volající:**
- `CLEAR` nebo `WHITELISTED` → pokračuj s platbou/operací na účtu
- `POTENTIAL_HIT` → blokuj do ručního compliance přezkumu
- `HIT` nebo `ESCALATED` → blokuj; nepokračuj

**Clearance vázané na subjekt (#11694):**
- Stabilní identifikátor strany posílej jako volitelné `subjectId`, odděleně od `idempotencyKey`
  pro konkrétní pokus. Nový pokus o otevření účtu použije nový klíč a projde aktuálními listinami;
  pouze stejný klíč vrátí původní výsledek.
- Přezkum `WHITELISTED` platí 90 dní (`clearanceExpiresAt`) pouze pro přesně stejné `subjectId`,
  typ entity, jméno, aliasy, datum narození, národnost a identifikátory.
- Clearance pokrývá jen posouzené identity zásahů: `(listType, matchedId)`, případně
  `(listType, matchedName)`, pokud záznam nemá externí ID. Nový nepokrytý zásah, změněný subjekt
  nebo expirované clearance ponechají čerstvý blokující výsledek.
- Staré záznamy bez `subjectId` nelze nově označit `WHITELISTED`. Subjekt znovu prověř s explicitním
  ID a přezkum proveď nad tímto novým záznamem.

### Profily prověření, země a identifikátor banky (v3)

`POST /api/v3/sanctions/screen` přijímá tři volitelné vstupy. Požadavek bez nich se chová
a odpovídá přesně jako dosud.

| Pole | Význam |
|---|---|
| `profile` | `PAYMENT` nebo `CUSTOMER`. Určuje sadu listin; nelze kombinovat s `listTypes` (400). |
| `country` | Země protistrany, ISO 3166-1 alpha-2. |
| `bankIdentifier` | BIC (8 nebo 11 znaků) banky protistrany; kód země v něm se kontroluje stejně jako `country`. |

| Profil | Prověřované listiny | PEP |
|---|---|---|
| `PAYMENT` | `OFAC_SDN`, `EU_CONSOLIDATED`, `UN_CONSOLIDATED`, `HM_TREASURY`, `CNB_DOMESTIC`, `CZ_TERRORISTS`, data jurisdikcí | neprověřuje se — jméno PEP platbu nezastaví |
| `CUSTOMER` | sada `PAYMENT` a navíc `PEP_GLOBAL` | hlásí se zvlášť: `matchKind: PEP` a `pepStatus`; nikdy nezvyšuje `status` ani `overallScore` |
| *(žádný)* | `listTypes`, nebo všechny povolené listiny | počítá se do `status` jako dosud |

U prověření s profilem nese každá shoda `matchKind` (`SANCTIONS`, `TERRORIST`, `PEP`,
`JURISDICTION`).

**Jurisdikce.** `country` (nebo země uvnitř `bankIdentifier`), která je v referenčních datech
jurisdikcí, je shodou bez ohledu na jméno: záznam `EMBARGO` dává `HIT`, záznam `HIGH_RISK`
`POTENTIAL_HIT` se skóre 0,65, které volající se sdílenou politikou prověřování pošlou k ručnímu
přezkumu. `programs` u shody nesou opatření s verzí dat a vydání, ze kterého řádek pochází
(například `HIGH_RISK:v1`, `EU_HIGH_RISK_THIRD_COUNTRIES`). `matchedId` je `country:<kód>:<vydání>:<opatření>:v<verze>`. Rozhodnutí analytika
(`WHITELISTED`) shodu jurisdikce nikdy nepokrývá: může vyřadit shody jména téhož prověření a
stav pak určuje sama shoda jurisdikce. Tato data **nemají automatický
zdroj** — co je načteno, viz `04-data` — a země, která v nich není, se nikdy neshoduje. BIC se
neporovnává s entitami na listinách, protože importované zdroje identifikátory bank neobsahují.

**Přibližná shoda jména.** Jméno se skóre alespoň 0,85 vůči záznamu listiny je `HIT` v každém
profilu. Jen profil `CUSTOMER` vrací i jména se skóre 0,65–0,85, jako `POTENTIAL_HIT` (nebo
`pepStatus: POTENTIAL_HIT` u záznamu PEP): onboarding má pracovníka, kterému lze podobné jméno
poslat k přezkumu. `PAYMENT` a prověření bez profilu pod 0,85 nehledají, takové jméno tam vyjde
`CLEAR` — platba zatím nemá stav, ve kterém by na přezkum počkala (#15724).

`/api/v1` je zmrazené: všechny tři vstupy odmítá s 400 a prověření vytvořené s nimi přes
`/api/v1` nelze číst.

### Odeslat rozhodnutí o ručním přezkumu

```http
POST /api/v3/sanctions/review
Content-Type: application/json
Authorization: Bearer <token>

{
  "checkId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reviewedBy": "compliance@openbank.example",
  "note": "Jiná osoba potvrzena kontrolou pasu — čisto.",
  "newStatus": "CLEAR"
}
```

`newStatus` ∈ `CLEAR | HIT | POTENTIAL_HIT | WHITELISTED | ESCALATED`

### Získat konkrétní prověření

```http
GET /api/v3/sanctions/{id}
```

### Zobrazit všechny potvrzené hity

```http
GET /api/v3/sanctions/hits
```

### Zobrazit čekající přezkumy

```http
GET /api/v3/sanctions/pending
```

### Zobrazit konfigurace sankcičních listin

```http
GET /api/v1/sanctions/lists
```

### Aktualizovat konfiguraci sankční listiny

```http
PUT /api/v1/sanctions/lists/{id}
Content-Type: application/json

{
  "enabled": true,
  "sourceUrl": "https://www.treasury.gov/ofac/downloads/sdn.xml",
  "cronHour": 6,
  "cronMinute": 0,
  "cronDays": "MON,TUE,WED,THU,FRI"
}
```

### Spustit manuální obnovu listiny

```http
POST /api/v1/sanctions/lists/{listType}/refresh
```

v1 importuje listinu přímo v požadavku a vrátí 200 s obnovenou listinou. Pokud už obnova stejné
listiny běží (jiný požadavek nebo plánovač), druhý import nezařadí do fronty a hned odpoví **409**
s `Retry-After: 30`.

```http
POST /api/v2/sanctions/lists/{listType}/refresh
Idempotency-Key: <uuid>
```

v2 listinu jen označí a odpoví **202** `{"requested": "<listType>"}`, aniž by cokoli stahovala;
import proběhne při příštím běhu plánovače stejnou chráněnou obnovou. Průběh je vidět na
`lastUpdatedAt` listiny v `GET /api/v1/sanctions/lists`. Chybějící `Idempotency-Key` vrací 400,
neznámá listina 404 a vypnutá listina 422. Role (`ROLE_OPERATOR`, `ROLE_ADMIN`) i OPA akce
(`sanctions.trigger`) jsou stejné jako u v1. Pro velké zdroje, např. `PEP_GLOBAL`, používejte v2.

### Obnovit všechny povolené listiny

```http
POST /api/v1/sanctions/lists/refresh-all
```

## Typy entit

| EntityType | Popis |
|---|---|
| `INDIVIDUAL` | Fyzická osoba |
| `ORGANIZATION` | Právnická osoba, společnost |
| `VESSEL` | Loď nebo námořní plavidlo |
| `AIRCRAFT` | Letadlo dle čísla ocasu nebo označení ICAO |

## Typy sankcičních listin

| ListType | Autorita | Rozsah |
|---|---|---|
| `OFAC_SDN` | US Treasury OFAC | Specially Designated Nationals — globální |
| `EU_CONSOLIDATED` | EU Rada | Konsolidovaný seznam sankcí EU |
| `UN_CONSOLIDATED` | Rada bezpečnosti OSN | Konsolidovaný seznam OSN |
| `HM_TREASURY` | UK HM Treasury | UK finanční sankce |
| `FATF_HIGH_RISK` | FATF | Vysoce rizikové a sledované jurisdikce — bez zdroje entit; země jsou vedeny jako referenční data jurisdikcí |
| `CNB_DOMESTIC` | Ministerstvo zahraničních věcí (MZV) | Vnitrostátní sankční seznam ČR (zákon č. 1/2023 Sb.) |
| `CZ_TERRORISTS` | Česká e-Sbírka | Protiteroristická označení (nařízení vlády č. 210/2008 Sb.), import z oficiálních SPARQL otevřených dat |

## Error model

| HTTP | kód | Kdy |
|---|---|---|
| 400 | `validation-failed` | Chybí povinná pole (idempotencyKey, name, entityType) |
| 401 | `unauthorized` | Chybí / neplatný Keycloak token |
| 403 | `forbidden` | Chybí role pro endpoint |
| 404 | `sanctions-check-not-found` | ID prověření neexistuje |
| 404 | `sanctions-list-not-found` | ID listiny neexistuje |
| 409 | `idempotency-key-conflict` | Stejný klíč, jiný payload |
| 500 | `internal-error` | Neočekávaná chyba |

## Eventy

Topic: `openbank.sanctions.screening.event` (CloudEvents binding, JSON).

| Typ eventu | Spuštění | Klíčová pole payloadu |
|---|---|---|
| `sanctions.check.completed.v1` | POST /screen | id, entityType, name, status, overallScore, checkedLists, checkedAt |
| `sanctions.review.submitted.v1` | POST /review | checkId, reviewedBy, newStatus, reviewNote, reviewedAt |

Eventy jsou append-only; následný přezkum vytváří nový event `sanctions.review.submitted.v1`, nepřepisuje původní event prověření.

## Průběžný monitoring (ADR-0376)

Dostupné od verze API kontraktu 3.3.0. Chráněno stejně jako `POST /api/v3/sanctions/screen`: role `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_API` a akce `sanctions.create`.

### Registrace nebo aktualizace sledovaného subjektu

```
PUT /api/v3/sanctions/monitored-subjects/{subjectRef}
```

Vlastníkem registrace je volající služba, určená klientem, kterému byl vydán její service-account token (například `openbank-kyc`); žádná hodnota požadavku ho neurčuje, takže služba registruje, nahrazuje a maže jen své vlastní subjekty. `subjectRef` je hodnota, kterou tato služba posílá jako `subjectId` při kontrole subjektu, a platí v rámci volajícího: stejná reference u dvou volajících jsou dva subjekty. Tělo nese `entityType`, `name` a volitelně `aliases`, `dateOfBirth`, `nationality`, `country`: stejné hodnoty jako při živé kontrole subjektu a žádné identifikátory dokladů.

| Stav | Význam |
|---|---|
| 201 | Subjekt je nově sledován |
| 200 | Už byl registrován; stejné atributy nic nemění, jiné nahradí uložené |
| 400 | Neplatná hodnota v cestě nebo v těle (prázdné jméno, alias `null`, neplatný formát země, znak NUL) |
| 403 | Volající není služba pod vlastním service accountem: token osoby, sdílený klient flotily, nebo žádný ověřený token |
| 503 | Monitoring není v tomto prostředí zapnut; nic nebylo uloženo |

Odpověď popisuje registraci (`ownerService`, `subjectRef`, `registeredAt`, `updatedAt`). Nikdy neříká, zda subjekt něčemu odpovídá.

### Odregistrace sledovaného subjektu

```
DELETE /api/v3/sanctions/monitored-subjects/{subjectRef}
```

Smaže řádek registru volajícího i jeho nálezy. 204, ať volající subjekt registrován měl, nebo ne, a funguje i při vypnutém monitoringu; 403 jako u registrace. Subjektu, který pod stejnou referencí registrovala jiná služba, se nedotkne. Volá se při ukončení vztahu a při výmazu.

### Událost nálezu

`MONITORED_SUBJECT_MATCHED` na `openbank.sanctions.screening.event` (typ v hlavičce `ce-type`), verze schématu 1: `findingId`, `ownerService`, `subjectRef`, `entityType`, `matchKind` (`SANCTIONS`, `TERRORIST`, `PEP`, `JURISDICTION`), `listType`, `listEntryRef`, `listEntryVersion`, `matchScore`, `matchStatus`, `detection` (`LIST_DELTA`, `BASELINE_SWEEP`, `PERIODIC_SWEEP`), `occurredAt`, `sourceService`. Jméno klienta se nepřenáší. Žádná REST operace nálezy nevrací.
