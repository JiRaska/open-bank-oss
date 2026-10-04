# Čtení IRRBB ze snapshotu

`GET /api/v1/risk/snapshots/{id}/irrbb` odvozuje úrokové riziko bankovní knihy z odsouhlaseného snapshotu. Výsledek se počítá při čtení a neukládá se. Přístup vyžaduje `risk.snapshot.read` pro daný snapshot.

`curveSetId` je nepovinné. Bez něj služba vybere nejnovější sadu křivek zaznamenanou pro vlastní datum `asOf` snapshotu; sadu ze sousedního dne nepoužije. Pro reprodukci výpočtu lze předat konkrétní ID sady. Neznámý snapshot nebo sada vrací 404, neodsouhlasený snapshot 409, chybějící sada pro stejné datum nebo sada z jiného dne 400. Nepovinné `tier1Capital` musí být kladné desetinné číslo. Bez něj může test odlehlosti použít kapitál Tier 1 z vlastních zdrojů snapshotu v CZK; `tier1Source` a `tier1Gap` uvádějí zdroj nebo důvod chybějící hodnoty.

Spolu s čísly čtěte `outlierTest.status`: `OK`, `EARLY_WARNING`, `BREACH` nebo `NOT_EVALUABLE`. Poslední stav znamená chybějící vstup či porovnatelnou hodnotu, nikoli splnění limitu. Test používá deklarovaný limit `irrbb-eve-outlier`, je-li k dispozici, a uvádí jeho `limitId`, `threshold` a `earlyWarning`. Poměry mají smysl pouze při stejné měně čitatele a Tier 1.

Před použitím ΔEVE nebo ΔNII k rozhodnutí zkontrolujte `dataGaps`. Odpověď označuje plochou extrapolaci za poslední bod každé křivky včetně dotčených peněžních toků a jejich základní současné hodnoty. Dále uvádí zjednodušené chování vkladů, nemodelované předčasné splacení, zahrnutou obchodní marži a dosud nepromítnuté nástroje. Peněžnětržní obchody treasury zatím do této projekce IRRBB nevstupují. Detail snapshotu v administrátorském rozhraní zobrazuje scénáře, stav testu, předpoklady i tyto mezery.

Scénáře a model peněžních toků popisují ADR-0313 a ADR-0314. Šest šokových scénářů a jejich nastavené velikosti uvádějí předpoklady v odpovědi; úspěšná HTTP odpověď sama o sobě neprokazuje úplnost modelu.
