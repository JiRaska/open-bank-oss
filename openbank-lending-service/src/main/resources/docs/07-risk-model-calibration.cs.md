# 07 — Kalibrace rizikového modelu (PD/LGD)

> **Publikum:** úvěrové riziko, model risk management, audit, vývoj.
> **Rozsah:** jak se verzují, přezkoumávají a přehrávají parametry pravděpodobnosti selhání
> (PD) a ztráty ze selhání (LGD), které ženou engine tvorby opravných položek dle IFRS 9, a jaké
> jsou jejich současné limity. Issue [#8364](https://github.com/open-bank-oss/open-bank-oss/issues/8364).

## 1. Odkud parametry pocházejí

Každý výpočet ECL vychází z `EclInputs`, které dodá port `RiskParameterSource`. Dnes je jedinou
vazbou `ConservativeRiskParameterSource` (`infrastructure/adapter/NoOpLendingAdapters.kt`),
který dodává **ploché, konzervativní zástupné hodnoty** — stejné PD/LGD pro každou expozici:

| Parametr | Aktuální hodnota | Význam |
|---|---|---|
| `pd12Month` | `DEFAULT_PD_12M` (0,02) | 12měsíční pravděpodobnost selhání (Stage 1) |
| `pdLifetime` | `DEFAULT_PD_LIFETIME` (0,20) | životní PD (Stage 2/3) |
| `lgd` | `DEFAULT_LGD` (0,45) | nezajištěná ztráta ze selhání před zástavou |

Jde o záměrně konzervativní zástupné hodnoty, aby se celý pipeline tvorby opravných položek
(zařazení do stadií, delta účtování, outbox události) dal postavit a otestovat end-to-end.
**Nejsou kalibrované na žádnou pozorovanou historii selhání a nesmí být tak prezentovány**
(viz §5).

## 2. Verzování — změna parametrů je přezkoumávaná událost

Každá sada parametrů nese řetězec `modelVersion`, vázaný na jediném místě:
`ConservativeRiskParameterSource.MODEL_VERSION` (dnes `noop-flat-v1`).

Konvence, vynucovaná code review a changelogem threat modelu:

1. **Jakákoli změna `DEFAULT_PD_12M` / `DEFAULT_PD_LIFETIME` / `DEFAULT_LGD` MUSÍ být součástí
   stejného commitu jako navýšení `MODEL_VERSION`.** KDoc u konstanty to dokumentuje; diff,
   který mění jedno bez druhého, je blokér review.
2. Verze protéká do `EclInputs.modelVersion` (validováno neprázdné), na každý
   `ProvisioningSnapshot` a **ukládá se na každý řádek `loan_provisioning`** (sloupec
   `model_version`, migrace V10). Každou částku ECL lze proto vždy dohledat zpět k přesné sadě
   parametrů, která ji vytvořila — auditní stopa, kterou regulátoři vyžadují (duch EBA IRB
   guide: identifikace modelu u každého odhadu).
3. Při startu zdroj zaloguje vázanou verzi modelu (`@Startup fun logBoundModel()`), takže log
   podu odpoví na otázku „který model vytvořil opravné položky tohoto období" bez dotazu do DB.

## 3. Kalibrační metoda (cílový proces)

Kalibrační smyčka, jakmile bude existovat interní historie selhání:

1. **Zdroj dat.** Interní historie selhání/ztrát z úvěrového portfolia (definice selhání:
   > 90 DPD, konzistentní se stagingem `Ifrs9.assess`). Dokud se nenahromadí dostatečná
   historie, slouží jako proxy benchmarky publikované míry selhání a ztrát bankovního sektoru
   ČNB — jasně označené jako proxy.
2. **Frekvence.** Čtvrtletní přezkum funkcí úvěrového rizika; mimořádný přezkum při podstatné
   změně portfolia (nový produkt, makro šok).
3. **Vlastník.** Hodnoty vlastní funkce úvěrového rizika; mechaniku vlastní vývoj (navýšení
   verze, migrace, replay). Změnu podepisují oba — stejný princip čtyř očí jako u origination.
4. **Kvantifikace před nasazením.** Každá kandidátní sada parametrů se PŘED mergem přehraje na
   syntetickém portfoliu (§4). Report z replay se připojí k PR, který navyšuje `MODEL_VERSION`.

## 4. Simulační replay

`LendingEclCalibrationScenario` v `openbank-simulation` je replay harness:

- `syntheticPortfolio(seed)` staví deterministické syntetické úvěrové portfolio pokrývající
  všechny stage buckety (čisté / watch / Stage 2 / Stage 3) s náhodně zajištěnou podmnožinou,
  takže se procvičuje i cesta LGD upravené o zástavu.
- `replay(portfolio, current, candidate)` přehrává portfolio přes **reálnou** doménovou
  matematiku `Ifrs9` (staging, LGD upravená o zástavu, součin PD · LGD · EAD) pod oběma sadami
  parametrů a vrací `CalibrationReport`: delta per expozice plus slučující se součty portfolia,
  každá hodnota přiřazená své verzi modelu.

Replay věrně kopíruje produkční sémantiku `LendingService.snapshotFor`/`applyCollateral` — je
to náhled toho, co by další cyklus tvorby opravných položek zaúčtoval pod kandidátními
parametry, spočtený bez dotyku databáze.

Spustitelné přes unit testy scénáře nebo libovolný Kotlin test harness v `openbank-simulation`.

## 5. Poctivé limity (co to NENÍ)

- **Ne regulatorní kapitál.** Ploché zástupné hodnoty nejsou parametry schválené v režimu IRB;
  čísla opravných položek, které produkují, jsou pipeline-korektní, ne rizikově kalibrovaná.
- **Ploché napříč portfoliem.** Žádné ratingové stupně, žádná segmentace podle
  produktu/zástavy/vintage — každá expozice dostává stejné PD/LGD, dokud se nenaváže reálný
  zdroj.
- **Stage 3 PD je dodané životní PD**, ne vynucená 1,0 — `Ifrs9.assess` věří sadě parametrů;
  kalibrovaný zdroj musí pro selhané expozice vracet PD ≈ 1.
- **Úprava o zástavu je první inkrement** (ADR-0028 D1): čte naposledy deklarovanou tržní
  hodnotu a haircut, bez revalvace v reálném čase a bez právního ověření zajištění. Viz KDoc
  `LendingService.applyCollateral`.
- **Bez diskontování na EIR uvnitř `Ifrs9`** — volající předává již diskontované EAD, pokud je
  podstatné.

Tyto limity jsou záměrně zdokumentované místo tiše pohltené: jsou to akceptační kritéria,
která musí reálná kalibrace vyřešit.
