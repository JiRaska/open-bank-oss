# Architektura treasury služby

Treasury vlastní vlastní obchody banky a jejich životní cyklus. Agregát eviduje produkt, protistranu, měnu, částku, data, stav a osoby odpovědné za řízené přechody. Aktuálně implementované endpointy určuje `src/main/resources/openapi.yaml`; ADR-0315 obsahuje i plánované schopnosti a je označeno jako částečně dodané.

## Životní cyklus obchodu

Dealer vytvoří návrh a předá jej ke schválení; schvaluje jiná fyzická osoba. Doména zakazuje samoschválení i schválení nelidskou identitou nezávisle na autorizační politice. Limity protistrany a produktu se kontrolují znovu při schválení, protože expozice se od podání mohla změnit. Seniorní výjimka s odůvodněním může pokrýt překročení limitu protistrany, nikoli produktového mandátu. Jen zaúčtovaný obchod pokračuje k potvrzení, vypořádání a splatnosti. Zrušení a reverzace mají odlišná stavová pravidla.

Pohyb hodnoty se účtuje přes API ledgeru s idempotentním klíčem odvozeným od obchodu a přechodu. Treasury nepíše do tabulek ledgeru. Vlastní migrace PostgreSQL ukládají stav obchodů, limity a záznamy párování; transakční outbox publikuje události o obchodech pro risk engine. Ten obchod vyhodnocuje jako instrument, ale nevlastní jeho zaúčtování.

## Externí a sandboxové hranice

Import a párování nostro výpisů porovnává očekávané vypořádání se skutečnými položkami a ukazuje rozdíly k prověření. Sandboxové protistrany a kotace jsou simulace; úspěšné simulované potvrzení není důkazem spojení s živým trhem. Přehledy minimálních rezerv a časové rozlišení používají datovaná zdrojová fakta; chybějící fakta nesmějí splynout s výchozí sazbou.
