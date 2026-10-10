# openbank-pension-fund-service — Dokumentace

> **Co to je:** strana poskytovatele (fondů) penzijní platformy ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). Spravuje oddělené fondy, jejich strategie a glide path, NAV a registr podílových jednotek pro každou penzijní smlouvu. **Co to NENÍ:** bankovní kniha. Majetek fondů patří účastníkům. Nic se odsud neúčtuje do hlavní knihy banky ani do treasury.

Dokumentaci publikuje služba na management endpointu `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-fund-db`, CNPG)
- **Porty:** 8162 (aplikace), 8090 (management)
- **Události:** zatím žádné
- **Autentizace:** správu fondů provádějí jen zaměstnanci (`ROLE_OPERATOR`/`ROLE_ADMIN`, nikdy service-account). Číst mohou také `ROLE_AUDITOR` a `ROLE_API`.
- **Čtyři oči:** kdo NAV spočítá nebo navrhne změnu strategie, ten ji nemůže zveřejnit ani schválit (403). Vynucuje to doména i DB CHECK.
- **Forward pricing:** pokyn se přijme bez ceny (202). Vypořádá se za PŘÍŠTÍ zveřejněnou NAV fondu.

## API

| Metoda a cesta | Co dělá |
|---|---|
| `POST/GET /api/v1/funds`, `GET/PUT/DELETE /api/v1/funds/{id}` | Správa fondů; zrušení fondu se odmítne, dokud existují podílové jednotky |
| `POST/GET /api/v1/funds/{id}/navs` | Výpočet NAV (maker) nebo jejich seznam |
| `POST /api/v1/navs/{id}/approve` · `/reject` | Zveřejnění (checker): vypořádá čekající pokyny, u opravy znovu ocení transakce |
| `POST/GET /api/v1/strategies`, `GET /api/v1/strategies/{id}` | Strategie: cílová alokace, pásma, glide path |
| `GET /api/v1/strategies/{id}/allocation?yearsToRetirement=` | Alokace podle glide path pro účastníka |
| `POST /api/v1/strategies/{id}/changes`, `POST /api/v1/strategy-changes/{id}/approve` · `/reject` · `/apply` | Řízená změna: druhý schvalovatel a výpovědní lhůta před účinností |
| `POST /api/v1/contracts/{id}/orders` | Nákup, prodej nebo přestup. Vyžaduje `Idempotency-Key` |
| `GET /api/v1/contracts/{id}/holdings` · `/orders` · `/transactions` | Držba oceněná poslední zveřejněnou NAV a její historie |

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


### Výsledovka a klasifikace pozic pro reporting

Čtecí model period-figures zahrnuje kumulované zisky a ztráty z přecenění, ostatní
investiční výsledek a poplatky za správu od začátku roku. Před použitím jednotlivých
řádků zkontrolujte `profitAndLossYtd.linesUnavailableReason`; nedostupné členění
neznamená naměřenou nulu.

Zaznamenané pozice NAV nesou třídu instrumentu. Historické pozice a vstupy bez
klasifikace zůstávají `UNCLASSIFIED`. Návrh opravy vytvoří
`POST /api/v1/position-classification-corrections` (`positionId`, `toClass`, `reason`).
Jiný schvalovatel jej rozhodne přes `/{correctionId}/approve` nebo
`/{correctionId}/reject`. Návrh vyžaduje `pension-fund.nav.calculate`, rozhodnutí
`pension-fund.nav.approve`; autor návrhu vlastní opravu rozhodnout nesmí. Tyto
podklady slouží čtecímu modelu reportingu, nepodávají zákonný výkaz.


### Opakování požadavků na korekci klasifikace

POST operace návrhu, schválení a zamítnutí vyžadují neprázdný `Idempotency-Key` o nejvýše 128 znacích. Klíč je oddělen podle přihlášeného uživatele a operace. Opakování stejného pokynu vrátí původní odpověď i poté, co korekce změnila stav; změněný obsah se stejným klíčem vrátí 409. Původní odpověď a změna korekce se ukládají v jedné databázové transakci. Ze souběžného schválení a zamítnutí může být zapsáno jen jedno rozhodnutí.
