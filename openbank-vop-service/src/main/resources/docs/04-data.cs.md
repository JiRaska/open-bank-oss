# 04 — Data

Databáze `openbank_vop`, jedna tabulka. CNPG cluster `vop-db`, `instances: 2` (ADR-0159). Viz [ER diagram](../diagrams/02-er-schema.mmd).

## `vop_verification` — důkaz, že kontrola proběhla

```sql
CREATE TABLE vop_verification (
    id                 UUID         NOT NULL,
    iban_hash          CHAR(64)     NOT NULL,   -- sha256 hex
    supplied_name_hash CHAR(64)     NOT NULL,   -- sha256 hex
    outcome            VARCHAR(16)  NOT NULL,
    no_data_reason     VARCHAR(32),
    requested_by       VARCHAR(255) NOT NULL,
    verified_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_vop_verification PRIMARY KEY (id),
    CONSTRAINT ck_vop_no_data_reason CHECK (
        (outcome = 'NO_DATA' AND no_data_reason IS NOT NULL)
        OR (outcome <> 'NO_DATA' AND no_data_reason IS NULL)
    )
);
```

Migrace `V1__init_vop.sql`. Rollback: `DROP TABLE vop_verification;` — nemá závislé objekty; historie evidence se rollbackem ztratí.

`CHECK` constraint zrcadlí `init` blok ve `VopVerification`. Invariant je uveden dvakrát záměrně: doména ho vynucuje pro kódové cesty, databáze pro všechno ostatní (migraci, ruční zásah, budoucího zapisovatele).

## Co tu NENÍ — a proč je to ten návrh

**Žádný IBAN v plaintextu. Žádné jméno příjemce v plaintextu.** Pouze `sha256` hashe.

Prokázat, že kontrola proběhla (IPR čl. 5c), nevyžaduje uchovávat každé jméno, které kdy někdo napsal do platebního formuláře. To je doslovně aplikovaná minimalizace údajů podle GDPR čl. 5 odst. 1 písm. c). Hashe pořád odpovídají na jedinou otázku, kterou reklamace podvodu skutečně klade — *„ověřili jsme tohle jméno proti tomuhle IBANu, a co jsme odpověděli?“* — protože vstupy **dodá stěžovatel**; my je zahashujeme a dohledáme. Důkaz zůstává; trvalá odpovědnost za osobní údaje nevzniká.

Přebírá to disciplínu, kterou party-service nastavila ve `V7__party_name_search_trgm.sql`: *„indexovány/prohledatelné jsou POUZE sloupce se jménem. Rodné číslo zde záměrně prohledatelné NENÍ.“*

**Žádná cache jména majitele účtu.** Autoritativní jméno je v party-service a dohledává se živě při každém požadavku. Lokální kopie by byla druhé místo, kde může zestárnout — přesně ten drift, kvůli kterému party-service existuje. Pokud si latence někdy cache vyžádá, bude to explicitní rozhodnutí s explicitním rozpočtem na zastarání, ne tichá denormalizace.

**Žádné cizí klíče.** vop-service nevlastní žádný účet ani party. Oba čte přes REST s M2M tokenem a neukládá o nich nic.

## Klasifikace PII a retence

| | |
|---|---|
| Klasifikace | `confidential` (`governance.yaml`) |
| Retence | **13 měsíců** — okno pro reklamaci podvodu |
| **Ne** | 7letý účetní default (ADR-0118) |

VoP záznam je důkaz, že kontrola proběhla, **ne účetní záznam**. Aplikovat na něj účetní retenci by byla přesně ta nadměrná retence, kterou čl. 5 odst. 1 písm. c) zakazuje.

> **Otevřená položka:** 13měsíční úklid **zatím nemá scheduler**. Až se bude přidávat, následujte vzor `*RetentionScheduler` z ADR-0118 (`KycRetentionScheduler`, `CardPiiRetentionScheduler`). Do té doby je retence deklarovaná politika, ne vynucená — sledováno v [threat modelu](../../../../docs/threat-models/openbank-vop-service.md) §4.

## Indexy

| Index | Slouží |
|---|---|
| `ix_vop_verification_lookup` (`iban_hash`, `supplied_name_hash`, `verified_at DESC`) | Dotaz při reklamaci: „co jsme odpověděli pro tenhle IBAN + jméno?“, nejnovější první. |
| `ix_vop_verification_verified_at` (`verified_at`) | Retenční úklid a přezkum enumerace po volajících — obojí skenuje podle času. |

Druhý index existuje částečně pro detektor, který **zatím neexistuje**: principal, jemuž skokově roste podíl `no_match`, enumeruje, neplatí. Index je levný a dotaz, který umožňuje, je přesně ten, co by incident potřeboval ve tři ráno.

## Data, která VoP čte, ale neukládá

| Zdroj | Pole | Proč jen tohle |
|---|---|---|
| account-service | `partyId` | Jen ten odkaz, nic víc. |
| party-service | `legalName`, `tradingName` | `PartySummary` zrcadlí **pouze** dvě pole se jménem. VoP porovnává jména; nesmí tahat identifikátory, data narození ani kontakty, které k ničemu nepotřebuje. |

Oba DTO jsou `@JsonIgnoreProperties(ignoreUnknown = true)` **lokální zrcadla**, nikdy sdílené typy — DTO nadřazených služeb se mohou vyvíjet, aniž nás rozbijí, a my nemůžeme omylem rozšířit to, co si taháme.
