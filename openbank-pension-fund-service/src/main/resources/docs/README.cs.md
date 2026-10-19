# openbank-pension-fund-service — Dokumentace

> **Co to je:** strana poskytovatele (fondů) penzijní platformy ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). Spravuje oddělené fondy, jejich strategie a glide path, NAV a registr podílových jednotek pro každou penzijní smlouvu. **Co to NENÍ:** bankovní kniha. Majetek fondů patří účastníkům. Nic se odsud neúčtuje do hlavní knihy banky ani do treasury.

Dokumentaci publikuje služba na management endpointu `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-fund-db`, CNPG)
- **Porty:** 8162 (aplikace), 8090 (management)
- **Události:** zatím žádné
- **Autentizace:** správu fondů provádějí jen zaměstnanci (`ROLE_OPERATOR`/`ROLE_ADMIN`, nikdy service-account). Držby, pokyny a transakce účastníků mohou číst zaměstnanci s `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR` nebo `ROLE_COMPLIANCE` a vlastní klient pension-service s `ROLE_API`. Chrání je akce `pension-fund.holding.inspect` (ne `.read`), takže obecná operátorská ani compliance čtecí práva nepustí sdílený service-account.
- **Čtyři oči:** kdo NAV spočítá nebo navrhne změnu strategie, ten ji nemůže zveřejnit ani schválit (403). Vynucuje to doména i DB CHECK.
- **Forward pricing:** pokyn se přijme bez ceny (202). Vypořádá se za PŘÍŠTÍ zveřejněnou NAV fondu.

## API

| Metoda a cesta | Co dělá |
|---|---|
| `POST/GET /api/v2/funds`, `GET/PUT/DELETE /api/v2/funds/{id}` | Správa fondů; zrušení fondu se odmítne, dokud existují podílové jednotky |
| `POST/GET /api/v2/funds/{id}/navs` | Výpočet NAV (maker) nebo jejich seznam |
| `POST /api/v2/navs/{id}/approve` · `/reject` | Zveřejnění (checker): vypořádá čekající pokyny, u opravy znovu ocení transakce |
| `POST/GET /api/v2/strategies`, `GET /api/v2/strategies/{id}` | Strategie: cílová alokace, pásma, glide path |
| `GET /api/v2/strategies/{id}/allocation?yearsToRetirement=` | Alokace podle glide path pro účastníka |
| `POST /api/v2/strategies/{id}/changes`, `POST /api/v2/strategy-changes/{id}/approve` · `/reject` · `/apply` | Řízená změna: druhý schvalovatel a výpovědní lhůta před účinností |
| `POST /api/v2/contracts/{id}/orders` | Nákup, prodej nebo přestup. Vyžaduje `Idempotency-Key` |
| `GET /api/v2/contracts/{id}/holdings` · `/orders` · `/transactions` | Držba oceněná poslední zveřejněnou NAV a její historie |

## Čísla

Každá částka je `BigDecimal` s explicitní škálou:

- peníze: 2 desetinná místa, HALF_EVEN
- NAV: 6 desetinných míst, HALF_EVEN
- vydané jednotky: zaokrouhlení DOLŮ
- jednotky zrušené za poplatek: zaokrouhlení NAHORU

Poplatek za správu se počítá z hrubých aktiv, ACT/365, za dny od předchozí NAV.

## Postupné nasazení interního TLS

GitOps přidává TLS 1.3 na portu 8443 vedle stávajícího HTTP listeneru. Cert-manager
vystavuje serverový certifikát interní CA; PEM certifikát a klíč se načítají
z připojeného adresáře a obnovují každou hodinu.

Nejprve nasaďte Certificate a serverový listener. Před přepnutím klienta ověřte
Certificate Ready, DNS SAN služby, důvěryhodný TLS handshake a síťové politiky pro
port 8443. HTTP zachovejte během souběhu verzí; jeho odstranění vyžaduje samostatnou
inventuru klientů a nasazení. Při chybě nového listeneru ponechte původní klientskou
URL a vraťte přidanou serverovou konfiguraci. Tato fáze ověřuje identitu serveru,
neposkytuje vzájemné TLS.

`ServerTlsIT` ověřuje načtení PEM, vyjednání TLS 1.3, odmítnutí nedůvěryhodného
certifikátu a zachování HTTP s dočasnými testovacími klíči. Deklarace ani tento
test nedokazují připravenost certifikátu v clusteru.


## Výkazy za období

`GET /api/v2/reporting/funds/{fundId}/period-figures` vyžaduje datum
`periodStart` a `periodEnd`. Vrací agregované údaje fondu pro tax-reporting-service:
rozvahu, výsledek hospodaření od začátku roku, pohyb jednotek, portfolio a toky.
Přístup vyžaduje povolenou roli a autorizační politiku `pension-fund.reporting.inspect`.

Výpočet používá zveřejněné NAV a transakce oceněné těmito NAV podle data ocenění.
Období bez zveřejněné NAV není vykazatelné; nejde o nulovou aktivitu. S nově
vypočtenou NAV se ukládají její pozice. U starších NAV bez zaznamenaných pozic
jsou údaje portfolia neznámé, nikoli prázdné. Identifikátory podkladových NAV
a fingerprint určují vstupy výkazu. Tento model poskytuje údaje; nepodává
regulátorovi statutární výkaz.

### Výsledovka a klasifikace pozic pro reporting

Čtecí model period-figures zahrnuje kumulované zisky a ztráty z přecenění, ostatní
investiční výsledek a poplatky za správu od začátku roku. Před použitím jednotlivých
řádků zkontrolujte `profitAndLossYtd.linesUnavailableReason`; nedostupné členění
neznamená naměřenou nulu.

Zaznamenané pozice NAV nesou třídu instrumentu. Historické pozice a vstupy bez
klasifikace zůstávají `UNCLASSIFIED`. Návrh opravy vytvoří
`POST /api/v2/position-classification-corrections` (`positionId`, `toClass`, `reason`).
Jiný schvalovatel jej rozhodne přes `/{correctionId}/approve` nebo
`/{correctionId}/reject`. Návrh vyžaduje `pension-fund.nav.calculate`, rozhodnutí
`pension-fund.nav.approve`; autor návrhu vlastní opravu rozhodnout nesmí. Tyto
podklady slouží čtecímu modelu reportingu, nepodávají zákonný výkaz.


### Opakování požadavků na korekci klasifikace

POST operace návrhu, schválení a zamítnutí vyžadují neprázdný `Idempotency-Key` o nejvýše 128 znacích. Klíč je oddělen podle přihlášeného uživatele a operace. Opakování stejného pokynu vrátí původní odpověď i poté, co korekce změnila stav; změněný obsah se stejným klíčem vrátí 409. Původní odpověď a změna korekce se ukládají v jedné databázové transakci. Ze souběžného schválení a zamítnutí může být zapsáno jen jedno rozhodnutí.


## Integrační testy a příznak service-account (fail closed)

OPA považuje každého volajícího, jehož token neprokazuje osobu, za service account (`input.principal.service_account`, fail closed). Prostá identita `@TestSecurity` nenese JWT, takže se počítá jako stroj a přichází o oprávnění vyhrazená lidem. Integrační testy, které vystupují jako skuteční zaměstnanci, proto přidávají lidské claimy přes `@OidcSecurity`. Proto modul testuje s `quarkus-test-security-oidc`.

## Pozorovatelnost

Služba publikuje metriky `openbank.pension_fund.*`: zpoždění, stáří a opravy
publikace NAV, výsledky pokynů a změn strategie, vypořádané částky, opakované
pokyny, dotazy na tržní ceny a konflikty optimistického zamykání na držbách
jednotek (počítají je REST mappery před odpovědí 409). Stavové gauge (čistá
aktiva, NAV na jednotku, jednotky v oběhu, čekající pokyny a stáří front)
obnovuje každou minutu plánovaná úloha; neúspěšné obnovení ponechá předchozí
hodnoty a nezaznamená úspěch, takže gauge živosti workflow zestárne a spustí
alert. Pravidla alertů jsou v `prometheus-rules-pension-fund.yaml`, runbook v
`docs/runbooks/pension-alerts.md`.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

## Správní poplatek z připnuté revize katalogu (ADR-0341)

Fond se nyní zakládá proti publikované revizi produktového katalogu. Požadavek na založení nese `productRevisionId`; služba tuto revizi jednou při založení načte z product-catalog a z jejího poplatkového schématu odvodí roční správní poplatek. Fond ukládá id revize do `product_revision_id` (migrace V5, nullable) a odvozenou sazbu cachuje v `management_fee_rate`; poplatek se poté už neupravuje. Založení bez id revize vrací 400. Pole `managementFeeRate` je jako vstup zastaralé: pokud je zadáno, musí se rovnat poplatku připnuté revize, jinak je požadavek odmítnut s 400. Fondy založené před připnutím mají revizi null, ponechávají si uloženou sazbu jen pro čtení přes API a zůstávají tak, dokud není vytvořena grandfathering revize schválená maker-checker postupem a fondy nejsou znovu připnuty. Verze kontraktu API je 2.0.0; založení a změna mají oddělená schémata.

Vyhledání v katalogu používá vlastní strojovou identitu služby, klienta Keycloak `openbank-pension-fund` s oborem `catalog:read`, v produkci přes TLS konfiguraci `catalog-authority` (klientský certifikát, soukromá CA, TLS 1.3). Timeouty připojení a čtení jsou 2 s a 5 s. Pokud založení fondu selže s chybou katalogu, zkontrolujte společně URL product-catalog, klientské tajemství a TLS mount. OIDC klient je v testovacím profilu vypnutý. Zpětné vrácení schématu provedete zrušením sloupce `product_revision_id`; cachovaná sazba zůstává správná.


### Přechod na API v2 (návrh doručení)

V2 vyžaduje při založení explicitní `productRevisionId`; změna jej může vynechat nebo zopakovat existující pin. Žádný požadavek nevybere revizi skrytě. V6 rozšiřuje uloženou sazbu na osm desetinných míst, neopravuje však zpětně dříve zaokrouhlené hodnoty.

Všech 27 nezměněných operací v1 zůstává jako explicitní deprecated aliasy se stejnými rolemi, autorizačními akcemi, identitou, idempotencí a čtyřočkovou kontrolou. Odpovědi mají verzi v1 a hlavičky Deprecation, Sunset a odkaz na v2. Výchozí sunset je 30. června 2027; před schváleným nasazením musí skutečná konfigurace zachovat alespoň 180denní okno.

Vlastník schválil pouze návrh úzké výjimky pro původní `POST /api/v1/funds` a `PUT /api/v1/funds/{id}`: staré zápisy s ručním poplatkem nejsou registrované a vracejí 405. Nejde o povolení jejich provozního ukončení. Úplný seznam externích zapisujících klientů zatím nemáme. Známí klienti pension-service, tax-reporting a Admin UI přecházejí výslovně na v2; Pact soubory musí regenerovat nativní CI.

První aktivace vyžaduje samostatné schválení image, přechodu zapisujících klientů a stávajících změn TLS, certifikátů a sítí. Před přepnutím klientů musí v2 umět všechny aktivní backendy. Rollback nejprve vrátí klienty nebo zachová poskytovatele v2; ponechá sloupec revize i rozšířenou přesnost, nemaže piny a neobnovuje editaci jejich sazeb. Draft zůstává blokovaný a auto-merge vypnuté. Návrh nepovoluje merge, provisioning, deployment ani finanční operace.
