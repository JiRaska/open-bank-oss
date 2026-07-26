# API

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (`info.version: 0.2.0`, OpenAPI 3.0.3). Je servírován na `/q/openapi`, Swagger UI na `/api/docs`. Všechny cesty jsou pod `/api/v1` — major verze je URL API verze (ADR-0048).

Base path: `/api/v1/sdd`. Content type: `application/json`.

## Endpointy

| Metoda | Cesta | Shrnutí | Role |
|---|---|---|---|
| `POST` | `/api/v1/sdd/mandates` | Registrace mandátu plátce (Core ⇒ ACTIVE, B2B ⇒ PENDING_CONFIRMATION). Idempotentní na `(CID, UMR)`. | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `GET` | `/api/v1/sdd/mandates?accountId={uuid}` | Výpis mandátů účtu | + VIEWER |
| `GET` | `/api/v1/sdd/mandates/{id}` | Načtení jednoho mandátu | + VIEWER |
| `PATCH` | `/api/v1/sdd/mandates/{id}` | Změna pole mandátu (zaznamená AMDT marker) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/confirm` | Potvrzení B2B mandátu (PENDING_CONFIRMATION → ACTIVE) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/suspend` | Pozastavení ACTIVE mandátu | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/resume` | Obnovení SUSPENDED mandátu | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `POST` | `/api/v1/sdd/mandates/{id}/cancel` | Zrušení mandátu (terminální) | OPERATOR/ADMIN/PAYMENTS/SERVICE |
| `GET` | `/api/v1/sdd/mandates/{id}/refund-assessment?debitDate={date}&asOf={date}` | Posouzení nároku na refund po vypořádání | + VIEWER |
| `POST` | `/api/v1/sdd/collections/authorise` | Fail-closed autorizace příchozího inkasa | OPERATOR/ADMIN/PAYMENTS/SERVICE |

## Registrace mandátu

`POST /api/v1/sdd/mandates`

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "debtorIban": "CZ6508000000192000145399",
  "creditorIdentifier": "DE98ZZZ09999999999",
  "umr": "UMR-2026-000123",
  "scheme": "CORE",
  "sequenceType": "RCUR",
  "creditorName": "Acme Utilities a.s.",
  "debtorName": "Jan Novák",
  "signatureDate": "2026-01-15"
}
```

- **Idempotentní na přirozený klíč.** Opětovná registrace stejného `(creditorIdentifier, umr)` vrátí **již uložený** mandát (HTTP `201`), místo aby vytvořila duplicitu. **Žádná hlavička `Idempotency-Key`** — idempotence je klíčována rulebookovou dvojicí, ne klientským tokenem.
- **Vznikový stav:** `CORE` ⇒ `ACTIVE`; `B2B` ⇒ `PENDING_CONFIRMATION` (musí být potvrzen, než může autorizovat inkaso).
- Vrací `201 Created` s tělem `Mandate`.

## Autorizace inkasa

`POST /api/v1/sdd/collections/authorise`

```json
{
  "creditorIdentifier": "DE98ZZZ09999999999",
  "umr": "UMR-2026-000123",
  "scheme": "CORE",
  "sequenceType": "RCUR",
  "amount": 49.90,
  "currency": "EUR",
  "dueDate": "2026-03-01",
  "controls": { "blockAll": false, "blockedCreditors": [], "maxAmountPerCollection": 100.00 }
}
```

Vrací `200` s `AuthorisationDecision`:

```json
{ "decision": "ACCEPT", "reasonCode": null, "reason": null }
```

Rozhodnutí je fail-closed a vyhodnocuje se v pořadí. Při ne-akceptaci jsou připojeny EPC reason kódy:

| Rozhodnutí | Význam | Příklad reason kódu |
|---|---|---|
| `ACCEPT` | Všechny kontroly prošly; inkaso je orazítkováno na mandátu a je emitováno `sdd.collection.authorised.v1` pro navazující zaúčtovací cestu. | — |
| `REJECT` | Technická / mandátová závada — žádný/neplatný mandát, není ACTIVE, neshoda schématu, jiná měna než EUR, nepotvrzený B2B, jednorázový mandát už použit. | `MD01`, `FF05` |
| `REFUSE` | Mandát je v pořádku, ale plátce uplatnil kontrolu — block-all, creditor na block-listu, částka nad limitem na inkaso. | `MS02` |

Pořadí kontrol: přítomnost mandátu & ACTIVE → shoda schématu → **pouze EUR** → ověřený B2B → reuse jednorázového → kontroly plátce (block-all / block-list / limit částky).

## Posouzení refundu

`GET /api/v1/sdd/mandates/{id}/refund-assessment?debitDate=2026-03-01&asOf=2026-04-01`

Vrací `200` s `RefundAssessment` (`asOf` defaultně dnešek):

```json
{ "eligible": true, "kind": "UNCONDITIONAL", "reasonCode": "MD06", "reason": null }
```

- **Autorizovaný Core:** `UNCONDITIONAL` refund do 8 týdnů (56 dní) od data odepsání; poté nezpůsobilý.
- **Autorizovaný B2B:** žádné právo na refund po vypořádání.
- **Neautorizovaný:** `UNAUTHORISED` refund do 13 měsíců (řeší se tam, kde mandát neexistuje; v v1 nemodelováno jako use case).

## Verzování

- **Verze API kontraktu:** `openapi.yaml:info.version = 0.2.0`; URL major `v1` (ADR-0048).
- **Release verze:** `version.txt` (nezávislá osa, vlastněná release-please).
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` servíruje `openbank-libs`.

## Model chyb

Chyby se vracejí jako malý JSON objekt. V v1 se nepoužívá problem+json obálka.

| Stav | Kdy | Tělo |
|---|---|---|
| `404 Not Found` | neznámé id mandátu | `{ "error": "No SDD mandate <id>", "mandateId": "<id>" }` |
| `409 Conflict` | nelegální přechod životního cyklu (např. confirm ne-PENDING mandátu, suspend ne-ACTIVE mandátu, amend terminálního/pending mandátu) | `{ "error": "Illegal mandate transition: <from> -> <to>" }` |
| `201 / 200` | úspěch | tělo `Mandate` / rozhodnutí |

Mandátové závady mapuje `MandateNotFoundMapper` (404) a `IllegalMandateTransitionMapper` (409). Endpointy `authorise` a `refund-assessment` nikdy nevyhazují výjimku na obchodní „ne" — vracejí strukturované rozhodnutí s EPC reason kódem.

## Autentizace a autorizace

- **AuthN:** Keycloak OIDC, RS256 JWT bearer token (`auth-server-url .../realms/openbank`, klient `openbank-services`).
- **AuthZ:** Quarkus `@RolesAllowed`. Mutace vyžadují jednu z `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_PAYMENTS`, `ROLE_API`; read endpointy (`GET` výpis/načtení, `GET refund-assessment`) navíc povolují `ROLE_VIEWER`.
- CORS je v dodávané konfiguraci omezen na `http://localhost:3000`; bezpečnostní hlavičky (CSP, HSTS, X-Frame-Options DENY, nosniff) jsou nastaveny na každé odpovědi.
