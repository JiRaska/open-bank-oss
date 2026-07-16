# 03 — API

Kontrakt: [`openapi.yaml`](../openapi.yaml), `info.version: 1.0.0`. Dvě nezávislé verzovací osy (ADR-0048): verze API kontraktu **není** release verze ve `version.txt`.

Base path `/api/v1/vop`. Port 8149.

## `POST /api/v1/vop/verify`

```http
POST /api/v1/vop/verify
Authorization: Bearer <jwt>
Content-Type: application/json

{ "creditorIban": "CZ6508000000192000145399", "creditorName": "Jiří Raška" }
```

```json
{ "status": "match", "verifiedAt": "2026-07-16T10:00:00Z" }
```

**Proč POST pro čtení?** IBAN a jméno příjemce jsou osobní údaje. Nesmí skončit v URL, access logu ani v referer hlavičce. To je jediný důvod — endpoint nemění nic než evidenční záznam a `Idempotency-Key` nepotřebuje.

### Požadavek

| Pole | Typ | Pravidla |
|---|---|---|
| `creditorIban` | string | povinné, ≤34 znaků, musí projít kontrolními číslicemi IBAN (`Iban.of`) |
| `creditorName` | string | povinné, ≤140 znaků (délka jména podle SEPA) |

Validace je explicitní (`VerifyPayeeRequest.validated()`), ne bean-validation — flotila `hibernate-validator` nemá. Selhání vyplave jako `IllegalArgumentException` → **400** přes sdílený mapper v libs-runtime. Pozor: **záměrně tu není service-local `ExceptionMapper<IllegalArgumentException>`** — ten typ vlastní `openbank-libs-runtime` a druhý mapper na stejný typ se vybírá nedeterministicky per request (issue #526, vynucuje `check-exception-mapper-collision.sh`).

### Odpověď

| Pole | Kdy | Poznámka |
|---|---|---|
| `status` | vždy | `match` \| `close_match` \| `no_match` \| `no_data` — `MTCH`/`CMTC`/`NMTC`/`NOAP` ze schématu EPC, a zároveň hodnoty, které union `VopStatus` v admin UI už čekal |
| `matchedName` | **pouze `close_match`** | Skutečné jméno majitele účtu. **Nikdy u `no_match`.** |
| `reason` | pouze `no_data` | `no_scheme_connectivity` \| `account_not_found` \| `name_not_available` \| `lookup_unavailable` |
| `verifiedAt` | vždy | |

## Čtyři výsledky — a co který vyzradí

Tahle tabulka **je** bezpečnostní model. Viz [rozhodovací diagram](../diagrams/03-outcome-decision.mmd).

| Výsledek | Význam | Vyzradí jméno? | Plátce by měl |
|---|---|---|---|
| `match` | Totéž jméno. | **Ne** — plátce ho sám zadal. | Pokračovat. |
| `close_match` | Blízká neshoda, kterou plátce může opravit: přehozené tokeny, iniciála, právní forma nebo překlep na jeden znak. | **Ano** — ale jen tomu, kdo ho už skoro znal, což je právě případ, kdy schéma vyžaduje umožnit opravu. | Zkontrolovat vrácené jméno a rozhodnout se. |
| `no_match` | Obě jména známa a nejsou totéž. | **Nikdy.** Špatný odhad útočníkovi neřekne nic než to, že se spletl. | Být varován. Smí pokračovat (čl. 5c: varovat, neodmítat). |
| `no_data` | Odpověď není k dispozici. **Nikdy nebrat jako match.** | Ne. | Být varován, že jsme neověřili. Smí pokračovat. |

### Pravidla porovnání (`VopNameMatchPolicy`)

Obě jména projdou `MatchKey.normalize` (NFD, odstranění diakritiky, lowercase, sražení mezer) — jediný normalizér flotily, ne jeho třetí kopie.

| Vstup | Výsledek | Proč |
|---|---|---|
| `Jiri Raska` vs `Jiří Raška` | `match` | Diakritika je prezentace. Plátce bez české klávesnice nedělá chybu. |
| `Acme s.r.o.` vs `Acme` | `match` | **Koncová** právní forma je prezentace, ne identita. |
| `SRO Praha` vs `Praha` | `no_match` | Ořezává se jen *koncová* právní forma — firma jménem „SRO Praha“ si první token ponechá. |
| `Raška Jiří` vs `Jiří Raška` | `close_match` | Naše pořadí polí není pořadí každé PSP. |
| `J. Raška` vs `Jiří Raška` | `close_match` | Jedna strana zkracuje křestní jméno. |
| `J. K.` vs `Jan Kovář` | `no_match` | Samotné iniciály jsou příliš slabé na to označit je za blízkou shodu jména příjemce. |
| `Jiří Raška` vs `Jiří Jan Raška` | `close_match` | Prostřední jméno, které jedna strana vynechává; dva tokeny to pořád potvrzují. |
| `Acme Praha` vs `Praha` | `no_match` | Pravidlo vypuštěného tokenu potřebuje ≥2 zbylé tokeny. S jedním se zvrhne v „sdílí libovolný token“ — dva různí příjemci. |
| `Jiri Raskb` vs `Jiří Raška` | `close_match` | Jeden znak — překlep, ne jiné jméno. |

`openbank.vop.max-edit-distance` (výchozí 1) ladí rozpočet na překlepy. **Tyhle prahy jsou odhady bez produkčních dat** — laďte je z metrik výsledků, ne podle citu. Jsou konstruktorovými parametry právě proto, aby se daly měnit bez zásahu do algoritmu.

## Stavové kódy

| Kód | Kdy |
|---|---|
| 200 | Verdikt — **včetně `no_data`**. Neznámý IBAN je 200, nikdy 404: 404 by řeklo „není to náš účet“, což je enumerační primitivum. |
| 400 | Vadný požadavek nebo IBAN neprošel kontrolními číslicemi. Zpráva se vrací, protože je to výrok o vlastním vstupu volajícího. |
| 401 / 403 | Neautentizováno / zamítnuto OPA (`vop.verify`). |
| 429 | Rate limit (60/min na volajícího), nebo je úložiště limitu nedostupné — **fail-closed**. Není to selhání platby: vykreslete `no_data`. |

## Autorizace

`@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")` + OPA `@Authorize(action = "vop.verify")`. Obojí je povinné — hrubé RBAC a pak jemný PEP.

Prefix akce je `vop`, shodný s názvem modulu `openbank-vop-service`, takže `money_path_scopes` v základním `rest.rego` odvodí `"vop"` a **skutečně matchne**. Srovnejte s `openbank-sepa-instant`, jehož reálný prefix je `sctInstPayment`, zatímco odvozený scope je `sepa-instant` — nesoulad, kvůli kterému se `four_eyes_required` pro tenhle rail tiše nikdy nespustí (issue #395). Pojmenovat to `vop.create` by tuhle třídu chyby vrátilo.

M2M volající se pouštějí přes konvenci Keycloaku `service-account-*` v `preferred_username` — **ne** přes `principal.type == "SERVICE"`, což je nedosažitelný mrtvý kód (`rules.yaml: authz_policy.principal_type_service_unreachable`). Pravidlo je omezené výhradně na `vop.verify`, nikdy na rodinný prefix `vop.`, aby budoucí zapisovací akce nebyla tiše předschválena.
