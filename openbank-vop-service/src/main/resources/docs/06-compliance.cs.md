# 06 — Compliance

Rozhodnutí: [ADR-0171](../../../../docs/adr/0171-verification-of-payee-for-outbound-credit-transfers.md). Threat model: [`openbank-vop-service.md`](../../../../docs/threat-models/openbank-vop-service.md) (ADR-0030 D2, povinný — money-path).

## Instant Payments Regulation (EU) 2024/886, čl. 5c — hnací síla

**Pro PSP v eurozóně účinné od 9. října 2025.** Není to budoucí termín.

| Povinnost | Stav |
|---|---|
| Ověřit jméno příjemce proti IBANu dřív, než plátce autorizuje | **Dodáno** — responder strana, skutečné dohledání |
| Sdělit plátci výsledek | **Dodáno** — čtyři výsledky na drátě; admin UI je vykresluje |
| Při neshodě neodmítat — varovat a nechat plátce rozhodnout | **Dodáno návrhem** — VoP nikdy neblokuje; `no_match` je varování |
| Odpovídat na naše vlastní IBANy, když se ptá jiná PSP | **Dodáno** — to je responder strana |
| Ptát se PSP příjemce na zahraniční IBANy | **Jen seam** — napojení na EPC VoP scheme tu neexistuje (stejně jako rails dosáhnou jen na `openbank-clearing-simulator`). Zahraniční IBANy vrací `no_data` / `NO_SCHEME_CONNECTIVITY`. |

Podle čl. 5c pravdivé *„nedokázali jsme ověřit“* splní povinnost informovat způsobem, jakým vymyšlený „match“ nikdy. Proto requester strana odpovídá poctivě místo hádání.

## Čl. 5d — náhrada škody z podvodu — **NEŘEŠENO**

Přenos odpovědnosti podle IPR/PSD3, kde PSP nese ztrátu, pokud neupozornila, je **vědomě mimo rozsah** (ADR-0171 §8). `grep reimburs` nevrací napříč flotilou nic: neexistuje proces reklamací ani cesta pro spory.

VoP produkuje *důkaz*, který by takový proces potřeboval (`vop_verification`). **Nesplňuje čl. 5d a nesmí se tak číst.** Tohle je poctivé pojmenování skutečné zbývající mezery, ne opomenutí.

## GDPR

Živým omezením je **čl. 5 odst. 1 písm. c) — minimalizace údajů**, a formuje schéma:

| Rozhodnutí | Odůvodnění |
|---|---|
| Evidence ukládá `sha256(iban)` + `sha256(jméno)`, nikdy plaintext | Prokázat, že kontrola proběhla, nevyžaduje uchovávat každé jméno napsané do platebního formuláře. Stěžovatel při reklamaci vstupy dodá, takže hashe na jedinou kladenou otázku pořád odpovídají. |
| Retence **13 měsíců**, ne 7letý účetní default | VoP záznam je důkaz, že kontrola proběhla, **ne účetní záznam** (ADR-0118). |
| `POST`, ne `GET` | IBAN a jméno nesmí skončit v URL, access logu ani referer hlavičce. |
| `PartySummary` zrcadlí jen `legalName` / `tradingName` | VoP porovnává jména. Nesmí tahat identifikátory, data narození ani kontakty, které k ničemu nepotřebuje. Přebírá scope note z `V7__party_name_search_trgm.sql` party-service. |
| Žádná cache jména majitele účtu | Žádná druhá kopie osobních údajů k zabezpečení ani k zestárnutí. |
| Klasifikace `confidential` | `governance.yaml`. |

**Právní základ podle čl. 6:** zpracování je nezbytné pro splnění právní povinnosti (čl. 6 odst. 1 písm. c)) — konkrétně povinnosti podle IPR čl. 5c.

**Otevřená položka:** 13měsíční retence **zatím nemá scheduler**. Retence je momentálně deklarovaná politika, ne vynucená. Následujte vzor `*RetentionScheduler` z ADR-0118.

### Asymetrie vyzrazení je kontrola GDPR, ne jen bezpečnostní

VoP je ze své podstaty orákulum nad jmény majitelů účtů — identifikuje, **kdo tu bankuje**. Bez kontroly je to jak porušení ochrany osobních údajů, tak předstupeň cíleného social engineeringu. Autorizace to omezit nemůže (plátce musí smět ověřit příjemce, kterého nevlastní). Takže:

- **`no_match` vrací pouze výsledek** — špatný odhad neprozradí nic než to, že byl špatný.
- **`close_match` jméno vrací** — ale jen volajícímu, který ho už *skoro* znal, což je právě ten případ opravy, který schéma vyžaduje.
- **Neznámý IBAN je 200 + `no_data`, nikdy 404** — stavový kód, který říká „není to náš účet“, je sám o sobě enumerační primitivum.
- **Rate limit (60/min)** omezuje, kolik pokusů kdokoli dostane.

Zbytkové riziko je skutečné a zdokumentované: `close_match` **je** vyzrazení tomu, kdo skoro uhodl. To je vlastní schématu — nařízení vyžaduje umožnit plátci opravu blízké neshody — a právě proto je rate limit nosný, ne kosmetický.

## DORA

Money-path (`rules.yaml: money_path_services`) ⇒ dědí ADR-0134 ICT-RM: tiering RTO/RPO, povinnost threat modelu (ADR-0030 D2) a obě Pyrra SLO.

Všimněte si netypické odolnostní pozice na money-path službu: **selhání VoP neshodí platbu.** CNPG HA (`instances: 2`) je tu proto, aby *evidenční záznam* přežil rolování uzlu, ne proto, že by se bez něj platební cesta zastavila. Výpadek VoP je incident **compliance** (plátci nejsou varováni), ne výpadek plateb — a nesmí se řešit držením plateb.

## PSD2 / SCA

**Přímo se neuplatní.** VoP je povinnost podle IPR, ne podle SCA. Nenahrazuje ani neoslabuje SCA bránu (ADR-0021): plátce, který projde přes varování `no_match`, se normálně autentizuje.

## AML / sankce

**Zde se neuplatní** — ale všimněte si záměrného kontrastu se sousední kontrolou. `openbank-sanctions-service` (ADR-0032) selhává **closed**, protože propuštěná sankce je porušení zákona. VoP selhává **open**, protože odmítnout každou platbu při výpadku VoP by samo porušilo lhůtu pro provedení podle IPR. Obě brány jsou ve stejném pre-execution toku s opačnou sémantikou, záměrně.

## PCI DSS

**Neuplatní se** — žádná data držitelů karet.

## ČNB

Žádná samostatná reportovací povinnost.

## Otevřené položky relevantní pro audit

Uvedeno tady, ne zahrabáno — podle konvence poctivosti tohoto repozitáře:

1. **Rate limiting je jen na aplikační vrstvě** — žádný WAF, žádná edge/objemová ochrana nikde v platformě ([audit](../../../../docs/audits/2026-07-16-platform-audit.md) §4.3).
2. **Není detektor enumerace** — index dotaz umožňuje; detektor neexistuje.
3. **Retenční úklid neimplementován** (viz výše).
4. **Prahy `close_match` jsou neověřené odhady.** Příliš volné uklidňují oběti podvodu; příliš přísné naučí plátce proklikávat varování. Laďte z metrik výsledků.
5. **`max-edit-distance` nemá four-eyes gate** — je to gitops konfigurace, takže ji pokrývá review PR. Pokud se někdy stane laditelnou operátorem, přidejte `vop.flip` do `four_eyes.verbs`: rozšíření tiše mění skutečné neshody v uklidňující oranžová varování.
6. **Requester strana je seam** (viz výše) — mezera v dodávce, ne bezpečnostní.
