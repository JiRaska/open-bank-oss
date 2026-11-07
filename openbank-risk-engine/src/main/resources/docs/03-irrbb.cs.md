# Čtení IRRBB ze snapshotu

`GET /api/v1/risk/snapshots/{id}/irrbb` odvozuje úrokové riziko bankovní knihy z odsouhlaseného snapshotu. Výsledek se počítá při čtení a neukládá se. Přístup vyžaduje `risk.snapshot.read` pro daný snapshot.

`curveSetId` je nepovinné. Bez něj služba vybere nejnovější sadu křivek zaznamenanou pro vlastní datum `asOf` snapshotu; sadu ze sousedního dne nepoužije. Pro reprodukci výpočtu lze předat konkrétní ID sady. Neznámý snapshot nebo sada vrací 404, neodsouhlasený snapshot 409, chybějící sada pro stejné datum nebo sada z jiného dne 400. Nepovinné `tier1Capital` musí být kladné desetinné číslo. Bez něj může test odlehlosti použít kapitál Tier 1 z vlastních zdrojů snapshotu v CZK; `tier1Source` a `tier1Gap` uvádějí zdroj nebo důvod chybějící hodnoty.

Spolu s čísly čtěte `outlierTest.status`: `OK`, `EARLY_WARNING`, `BREACH` nebo `NOT_EVALUABLE`. Poslední stav znamená chybějící vstup či porovnatelnou hodnotu, nikoli splnění limitu. Test používá deklarovaný limit `irrbb-eve-outlier`, je-li k dispozici, a uvádí jeho `limitId`, `threshold` a `earlyWarning`. Poměry mají smysl pouze při stejné měně čitatele a Tier 1.

Před použitím ΔEVE nebo ΔNII k rozhodnutí zkontrolujte `dataGaps`. Odpověď označuje plochou extrapolaci za poslední bod každé křivky včetně dotčených peněžních toků a jejich základní současné hodnoty. Dále uvádí zjednodušené chování vkladů, nemodelované předčasné splacení, zahrnutou obchodní marži a dosud nepromítnuté nástroje. Peněžnětržní obchody treasury zatím do této projekce IRRBB nevstupují. Detail snapshotu v administrátorském rozhraní zobrazuje scénáře, stav testu, předpoklady i tyto mezery.

Scénáře a model peněžních toků popisují ADR-0313 a ADR-0314. Šest šokových scénářů a jejich nastavené velikosti uvádějí předpoklady v odpovědi; úspěšná HTTP odpověď sama o sobě neprokazuje úplnost modelu.

## Ocenění, citlivost, VaR a pre-trade pricing (ADR-0344)

`GET /api/v1/risk/snapshots/{id}/valuation | sensitivity | var | fx-position` čtou uzel knihy sesouhlaseného snapshotu; `POST /api/v1/risk/pricing` ohodnotí kandidátní obchod bezstavově. Křivky se bootstrapují z depozit, FRA a par swapů a každý vstup musí být přeceněn na par s přesností 1e-8; nemonotónní diskontní faktory skončí typovanou chybou. VaR se počítá historickou simulací s expected shortfall a pod minimálním oknem odpověď nese explicitní mezeru v datech místo čísla. Do měr vstupují jen obchody `ACTUAL`; `PLANNED` pouze do předpovědi likvidity a `HYPOTHETICAL` nikdy. Každý výsledek uvádí původ a hash vstupů ocenění; verze modelů se zapisují do běhu snapshotu.
