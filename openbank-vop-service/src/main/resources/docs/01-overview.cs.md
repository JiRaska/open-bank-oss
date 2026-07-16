# 01 — Přehled

## Co služba dělá

`openbank-vop-service` odpovídá na jedinou otázku:

> Je jméno příjemce, které plátce zadal, skutečně jméno vedené na IBANu příjemce?

Odpovídá jedním ze čtyř výsledků — `match`, `close_match`, `no_match`, `no_data` — a plátce se ho dozví **dřív**, než převod autorizuje. To je Verification of Payee (VoP) a pro PSP v eurozóně je povinná od **9. října 2025** podle nařízení (EU) 2024/886 (Instant Payments Regulation, čl. 5c).

Účel je úzký a stojí za přesné vymezení: VoP chrání plátce před **jeho vlastním omylem** — překlepem v IBANu, zastaralým číslem účtu, fakturou, které někdo přepsal bankovní spojení. Nedetekuje podvod, nescreenuje sankce a nehodnotí, jestli je platba dobrý nápad. Říká plátci, *kdo skutečně drží účet, na který se chystá poslat peníze*.

## Proč existuje (poctivá verze)

Než tahle služba vznikla, stránka plateb v admin UI vykreslovala panel ověření příjemce, jehož výsledek pocházel ze `setTimeout` + `Math.random()` — a odesílací cesta SCT Inst už na tu vymyšlenou hodnotu podmiňovala odeslání. Platforma tedy **vypadala**, že vynucuje kontrolu, která neexistovala: zelená, za kterou nic není, což je horší než žádná kontrola. [Audit platformy](../../../../docs/audits/2026-07-16-platform-audit.md) to označil za nejnaléhavější regulatorní mezeru. [ADR-0171](../../../../docs/adr/0171-verification-of-payee-for-outbound-credit-transfers.md) je rozhodnutí, tahle služba je jeho realizace.

## Kde to sedí

VoP má dvě strany a tahle služba je upřímná v tom, kterou z nich skutečně implementuje:

| Strana | Význam | Stav tady |
|---|---|---|
| **Responder** | Jiná PSP se ptá *nás* na IBAN, který vedeme | **Reálné.** IBAN → account-service → `partyId` → party-service → `legalName`. Tuhle stranu volají ostatní PSP a tuhle umíme udělat poctivě. |
| **Requester** | *My* se ptáme PSP příjemce na cizí IBAN | **Seam, ne schopnost.** Napojení na EPC VoP scheme v této platformě neexistuje. Zahraniční IBAN vrací `no_data` / `NO_SCHEME_CONNECTIVITY` přes `VopSchemeRoutingPort`, kam by se skutečný adaptér zapojil. |

To odpovídá samotným rails: `openbank-sepa-instant` dosáhne jen na `openbank-clearing-simulator`, ne na živé schéma. Vymyslet si verdikt pro IBAN, na který se nemáme koho zeptat, by bylo horší než přiznat, že to ověřit neumíme — a podle čl. 5c pravdivé „neověřeno“ povinnost informovat **splní** způsobem, jakým vymyšlený „match“ nikdy.

## Kdo to volá

- **`openbank-admin-ui`** — tlačítko Ověřit na platební konzoli, přes BFF proxy (`/api/svc/vop-service`), s vlastním bearer tokenem přihlášeného operátora.
- **Platební rails** (sepa-instant, sepa-payment, domestic-payment, psd2) — jako M2M volající, před provedením platby. *Toto zapojení je přirozený další krok; sdílený port existuje právě proto, aby se všechny čtyři napojily na jednu sadu prahů místo čtyř rozjetých kopií.*

## Co vědomě nedělá

- **Neblokuje platbu.** Čl. 5c po PSP vyžaduje plátce na neshodu *upozornit* a nechat ho rozhodnout. `no_match` je varování, které plátce musí vzít na vědomí, ne zamítnutí.
- **Neselhává closed.** Výpadek dohledání vrací `no_data` + varování. Odmítnout každou platbu během výpadku VoP by porušilo lhůtu pro provedení, kterou totéž nařízení ukládá. Viz [02 — Architektura](./02-architecture.md).
- **Neimplementuje náhradu škody z podvodu.** Přenos odpovědnosti podle IPR/PSD3 (čl. 5d) — kde PSP nese ztrátu, pokud neupozornila — potřebuje proces reklamací a cestu pro spory. VoP produkuje *důkaz*, který by takový proces potřeboval, ale **samotné VoP nelze číst jako splnění čl. 5d**. Viz [06 — Compliance](./06-compliance.md).
- **Necachuje jméno majitele účtu.** Autoritativní jméno žije v party-service a dohledává se živě. Lokální kopie by byla druhé místo, kde může zestárnout.

## Napětí v jádru téhle služby

VoP je ze své podstaty **orákulum nad jmény majitelů účtů**. Kdokoli, kdo ho může volat, se může ptát „drží jméno X IBAN Y?“ a dostane pravdivou odpověď. Přesně to po něm nařízení chce — a přesně to by chtěl někdo, kdo mapuje klienty banky.

Autorizace to nevyřeší: plátce musí smět ověřit příjemce, kterého nevlastní, takže volat to smí kdokoli s read rolí. Obranou jsou tedy:

1. **Asymetrie vyzrazení** — `no_match` vrací *pouze* výsledek, takže špatný odhad útočníkovi neřekne nic než to, že se spletl. Skutečné jméno vrací jen `close_match`, a jen tomu, kdo ho už *skoro* znal (což je právě případ, kdy schéma vyžaduje umožnit plátci opravu).
2. **Rate limit** — 60/min na volajícího. Vyzrazení u `close_match` neodstraní; omezuje, kolikrát se o něj kdokoli může pokusit.

Obojí je nosné, ne kosmetika. [Threat model](../../../../docs/threat-models/openbank-vop-service.md) považuje vyzrazení informace za primární hrozbu — opak většiny money-path služeb, kde je nebezpečím schválit něco, co se mělo odmítnout.
