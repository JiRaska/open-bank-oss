// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The Lípa console's teaching content (ADR-0282), kept as data rather than JSX.
//
// Two reasons it lives here. The console is bilingual by rule — every user-facing string goes
// through `t(cs, en)` — so copy as `{ cs, en }` pairs keeps the page free of hardcoded text and
// the i18n guard green. And the claims below are checkable: `loyalty-console.guard.test.ts`
// asserts the ones that must not drift, in particular that nothing in this file describes a
// granted benefit as delivered, and that the AI section keeps its red lines.

export interface Bilingual {
  cs: string
  en: string
}

export interface Principle {
  id: string
  title: Bilingual
  /** What the rule is. */
  rule: Bilingual
  /** Why the bank holds it — the regulatory or commercial consequence. */
  why: Bilingual
  /** What would break it. Written so a reviewer can recognise the change that costs it. */
  breaks: Bilingual
}

/**
 * ADR-0282 D1's four absences. Each is a property of the code, not a policy someone remembers:
 * `Leaves` has no conversion toward money, `Benefit` has no currency field, no path moves value
 * between parties, and Lístky are minted only by the earn use case. The service asserts all four
 * by test; this is the operator-facing explanation of the same four facts.
 */
export const PRINCIPLES: Principle[] = [
  {
    id: 'no-cash-out',
    title: { cs: 'Lístek nelze vyplatit', en: 'A Lístek cannot be cashed out' },
    rule: {
      cs: 'Lístky nelze převést na koruny ani na jinou měnu. V doméně neexistuje žádná konverze k peněžnímu typu.',
      en: 'Lístky cannot be turned into korunas or any other currency. The domain carries no conversion to a monetary type.',
    },
    why: {
      cs: 'Vyplatitelná jednotka je elektronickými penězi ve smyslu EMD2 a vyžaduje licenci, kapitál a oddělení prostředků klientů.',
      en: 'A redeemable-for-cash unit is electronic money under EMD2 and requires a licence, capital and safeguarding of customer funds.',
    },
    breaks: {
      cs: 'Metoda toMoney(), pole s částkou na benefitu nebo výplata zůstatku při zrušení účtu.',
      en: 'A toMoney() method, an amount field on a benefit, or paying a balance out when an account closes.',
    },
  },
  {
    id: 'no-transfer',
    title: { cs: 'Lístek nepřechází mezi klienty', en: 'A Lístek does not move between customers' },
    rule: {
      cs: 'Žádná cesta v systému nepřesune hodnotu z jednoho partyId na druhé. Rodinný Háj (D9) bude jediná výjimka a zatím není postavený.',
      en: 'No path moves value from one partyId to another. The household Háj (D9) will be the single exception and is not built.',
    },
    why: {
      cs: 'Převoditelnost dělá z věrnostní jednotky platební prostředek. Uzavřená smyčka je to, co drží Lípu mimo platební regulaci.',
      en: 'Transferability turns a loyalty unit into a means of payment. The closed loop is what keeps Lípa outside payments regulation.',
    },
    breaks: {
      cs: 'Darování mezi libovolnými klienty, tržiště s Lístky, nebo Lístek jako odměna třetí strany.',
      en: 'Gifting between arbitrary customers, a marketplace in Lístky, or a Lístek as a third party reward.',
    },
  },
  {
    id: 'no-fiat-price',
    title: { cs: 'Lístek nemá cenu v měně', en: 'A Lístek has no price in any currency' },
    rule: {
      cs: 'Benefit má cenu v Lístcích a nic jiného. Datový typ benefitu nemá pole pro měnu ani pro částku.',
      en: 'A benefit is priced in Lístky and in nothing else. The benefit type has no currency and no amount field.',
    },
    why: {
      cs: 'Jednotka s kurzem k měně je zúčtovací jednotka. Kurz by také znamenal, že klient si Lístky může koupit.',
      en: 'A unit with an exchange rate to a currency is a unit of account. A rate would also mean a customer could buy Lístky.',
    },
    breaks: {
      cs: 'Ceník "1 Lístek = X Kč", nákup Lístků, nebo doplatek penězi u benefitu.',
      en: 'A "1 Lístek = X CZK" rate card, buying Lístky, or topping a benefit up with money.',
    },
  },
  {
    id: 'no-credit-reward',
    title: { cs: 'Lípa neodměňuje úvěr ani útratu', en: 'Lípa rewards neither credit nor spend' },
    rule: {
      cs: 'Katalog důvodů zisku je uzavřený výčet a žádná jeho položka nesmí odkazovat na objem útraty, použití karty, čerpání úvěru ani kontokorent.',
      en: 'The earn catalogue is a sealed set and no entry may reference spend volume, card usage, credit drawdown or an overdraft.',
    },
    why: {
      cs: 'ADR-0220 D3 pravidlo 1 to zakazuje. Odměna za útratu táhne klienta k chování, které rizikový útvar banky chce méně, a je to i pozice, kterou má každá konkurence.',
      en: 'ADR-0220 D3 rule 1 forbids it. Rewarding spend pulls the customer toward behaviour the bank risk function wants less of, and every competitor already occupies that position.',
    },
    breaks: {
      cs: 'Nová položka katalogu se slovem SPEND, CARD nebo CREDIT v identifikátoru. Test to zachytí v den, kdy ji někdo napíše.',
      en: 'A new catalogue entry with SPEND, CARD or CREDIT in its identifier. A test catches it on the day someone writes it.',
    },
  },
]

export interface Connection {
  id: string
  system: Bilingual
  href: string | null
  what: Bilingual
  /** The boundary — what this connection deliberately does NOT do. */
  limit: Bilingual
}

/** How Lípa is wired to the rest of the platform, and where each wire stops. */
export const CONNECTIONS: Connection[] = [
  {
    id: 'catalog',
    system: { cs: 'Katalog produktů a Produktové studio', en: 'Product Catalog and Product Studio' },
    href: '/product-studio',
    what: {
      cs: 'Benefit se doručuje existujícími motory: odpuštění poplatku přes billing, bonusová úroková hladina přes katalogovou sazbu, konverze referenčním kurzem přes FX.',
      en: 'A benefit is delivered by engines that already exist: a fee waiver through billing, a bonus interest tier through the catalog rate, a reference-rate conversion through FX.',
    },
    limit: {
      cs: 'Lípa žádný z těchto motorů zatím nevolá. Uděluje závazek, nikoli plnění.',
      en: 'Lípa calls none of those engines yet. It records the obligation, not the delivery.',
    },
  },
  {
    id: 'segments',
    system: { cs: 'Segmenty a Kampaně', en: 'Segments and Campaigns' },
    href: '/segments',
    what: {
      cs: 'Mikrosegmenty budou umět cílit podle věrnostní hladiny a chování, aby nabídka odpovídala tomu, jak klient skutečně hospodaří.',
      en: 'Micro-segments will target on loyalty tier and behaviour, so an offer matches how the customer actually manages money.',
    },
    limit: {
      cs: 'Segment nikdy neurčuje cenu benefitu ani podmínku produktu. To rozhoduje deterministická politika se schválením ve dvou lidech.',
      en: 'A segment never sets a benefit price or a product term. Deterministic policy with two-person approval decides those.',
    },
  },
  {
    id: 'customer360',
    system: { cs: 'Customer 360', en: 'Customer 360' },
    href: '/customer-360',
    what: {
      cs: 'Panel Lípa ukazuje zůstatek, historii a nejbližší expiraci. Je to přesně to, co vidí klient v aplikaci.',
      en: 'The Lípa panel shows balance, history and the nearest expiry. It is exactly what the customer sees in the app.',
    },
    limit: {
      cs: 'Operátor nemá žádné pole navíc. Reciproční průhlednost je smyslem D8, ne vedlejším efektem.',
      en: 'The operator gets no extra field. Reciprocal transparency is the point of D8, not a side effect.',
    },
  },
  {
    id: 'billing',
    system: { cs: 'Billing a Účetní kniha', en: 'Billing and the Ledger' },
    href: '/ledger',
    what: {
      cs: 'Nesplněný závazek v Lístcích se denně publikuje. Billing z něj zaúčtuje vyrovnaný zápis na rezervu, expirace ji uvolní.',
      en: 'The outstanding obligation in Lístky is published daily. Billing posts a balanced journal to the provision from it, and an expiry releases it.',
    },
    limit: {
      cs: 'Loyalty nezaúčtuje nic samo. Kdyby ano, byla by druhou účetní knihou.',
      en: 'Loyalty posts nothing itself. If it did, it would be a second ledger.',
    },
  },
  {
    id: 'engagement',
    system: { cs: 'Zapojení a gamifikace', en: 'Engagement and gamification' },
    href: null,
    what: {
      cs: 'Body zapojení zůstávají počítadlem aktivity. Loyalty z jejich události razí Lístky podle recenzovaného pravidla s zmrazenou verzí.',
      en: 'Engagement points stay an activity counter. Loyalty mints Lístky from their event by a reviewed rule with a frozen version.',
    },
    limit: {
      cs: 'Body samy nejsou směnitelné a žádnou konverzní metodu nedostanou.',
      en: 'The points themselves are not exchangeable and will get no conversion method.',
    },
  },
]

/**
 * The lifecycle, as a Mermaid graph.
 *
 * Two syntax traps this repository has already paid for, both invisible until someone opens the
 * page: a bare `@` in an unquoted node label is read as the node-metadata shorthand and fails to
 * parse, with the caret pointing at unrelated earlier text; and a literal `;` inside a
 * sequenceDiagram message is a statement separator. Every label here is quoted.
 */
export const LIFECYCLE_DIAGRAM = `graph LR
  A["Achievement<br/>observed"] --> B{"Annual cap"}
  B -->|"fits"| C["EARN lot<br/>expires in 24 months"]
  B -->|"exceeded"| D["CAPPED<br/>nothing written"]
  C --> E{"Redemption"}
  E -->|"affordable"| F["BURN oldest lots first"]
  E -->|"not affordable"| G["Refused<br/>nothing burned"]
  F --> H["Grant recorded<br/>benefit owed"]
  H --> I["Delivering engine<br/>applies it"]
  C --> J["EXPIRE<br/>obligation released"]
`

export interface LegalTopic {
  id: string
  regime: Bilingual
  position: Bilingual
  /** What makes the position hold today. */
  holds: Bilingual
  /** Honest statement of what is still open. */
  open: Bilingual | null
}

/** The regulatory and accounting reasoning, in the console rather than only in the ADR. */
export const LEGAL: LegalTopic[] = [
  {
    id: 'emd2',
    regime: { cs: 'Elektronické peníze (EMD2)', en: 'Electronic money (EMD2)' },
    position: {
      cs: 'Lístek elektronickými penězi není. Stojí to na uzavřené smyčce, ne na výkladu.',
      en: 'A Lístek is not electronic money. That rests on the closed loop, not on an interpretation.',
    },
    holds: {
      cs: 'Žádná výplata, žádný převod mezi klienty, žádná cena v měně, žádný nákup. Všechny čtyři jsou vlastností kódu a jsou pokryté testem.',
      en: 'No cash-out, no transfer between customers, no price in a currency, no purchase. All four are properties of the code and are covered by a test.',
    },
    open: {
      cs: 'Právní posouzení omezené sítě je podmínkou spuštění a zatím neproběhlo.',
      en: 'The limited-network legal review is a precondition for going live and has not happened.',
    },
  },
  {
    id: 'mica',
    regime: { cs: 'Kryptoaktiva (MiCA)', en: 'Crypto-assets (MiCA)' },
    position: {
      cs: 'Mimo rozsah. Lístek není kryptoaktivum a nežije na distribuované evidenci.',
      en: 'Out of scope. A Lístek is not a crypto-asset and does not live on a distributed ledger.',
    },
    holds: {
      cs: 'Evidence je běžná databázová tabulka jedné banky a jednotka není převoditelná.',
      en: 'The ledger is an ordinary database table owned by one bank, and the unit is not transferable.',
    },
    open: null,
  },
  {
    id: 'ifrs15',
    regime: { cs: 'Účetnictví (IFRS 15)', en: 'Accounting (IFRS 15)' },
    position: {
      cs: 'Nespotřebované Lístky jsou závazkem banky a drží se jako rezerva.',
      en: 'Unspent Lístky are an obligation of the bank and are carried as a provision.',
    },
    holds: {
      cs: 'Loyalty denně publikuje nesplněný závazek, billing z něj zaúčtuje vyrovnaný zápis a expirace rezervu uvolní.',
      en: 'Loyalty publishes the outstanding obligation daily, billing posts a balanced journal from it, and an expiry releases the provision.',
    },
    open: {
      cs: 'Politiku uznání a délku expirace vlastní finanční útvar. Číslo níže je vstup, ne zaúčtování.',
      en: 'Finance owns the recognition policy and the expiry period. The figure below is an input, not a posting.',
    },
  },
  {
    id: 'gdpr',
    regime: { cs: 'Ochrana údajů (GDPR čl. 22)', en: 'Data protection (GDPR Art. 22)' },
    position: {
      cs: 'Profilování je deterministické, vysvětlené a vypnutelné jedním klepnutím.',
      en: 'Profiling is deterministic, explained, and switchable off in one tap.',
    },
    holds: {
      cs: 'Segment je verzovaný kód, důvod nabídky se zobrazuje klientovi i operátorovi, a odhlášení ponechává už získanou hodnotu.',
      en: 'A segment is versioned code, the reason for an offer is shown to customer and operator alike, and opting out keeps value already earned.',
    },
    open: {
      cs: 'Výmaz na událost o smazání klienta zatím není zapojený a patří do stejné fáze jako producenti zisku.',
      en: 'Erasure on the party-erased event is not wired yet and belongs with the same phase as the earn producers.',
    },
  },
  {
    id: 'ai-act',
    regime: { cs: 'Akt o umělé inteligenci', en: 'EU AI Act' },
    position: {
      cs: 'Žádná umělá inteligence nerozhoduje o podmínce, ceně, nároku ani o udělení benefitu.',
      en: 'No AI decides a term, a price, an entitlement or the granting of a benefit.',
    },
    holds: {
      cs: 'Model smí nanejvýš seřadit sdělení z katalogu. Návratový typ toho rozhraní neumí vyjádřit úvěrové rozhodnutí.',
      en: 'A model may at most rank catalogue messages. The return type of that port cannot express a credit decision.',
    },
    open: null,
  },
  {
    id: 'consumer-credit',
    regime: { cs: 'Spotřebitelský úvěr (z. č. 257/2016 Sb.)', en: 'Consumer credit (Act No. 257/2016 Coll.)' },
    position: {
      cs: 'Úvěrové produkty jsou mimo zisk Lístků i mimo personalizované podmínky.',
      en: 'Credit products are excluded from earning Lístky and from personalised terms.',
    },
    holds: {
      cs: 'Katalog důvodů zisku je uzavřený a úvěr v něm není. Předschválená nabídka zůstává samostatným režimem s povinným RPSN.',
      en: 'The earn catalogue is sealed and credit is not in it. A pre-approved offer stays its own regime with the mandatory APR example.',
    },
    open: null,
  },
]

export interface AiRole {
  id: string
  name: Bilingual
  does: Bilingual
  /** The guardrail. Always phrased as what the role cannot do. */
  cannot: Bilingual
  /** Who or what decides instead. */
  decides: Bilingual
  status: 'proposed' | 'available'
}

/**
 * Where AI fits, and where it stops.
 *
 * Every role below proposes; none disposes. That split is not caution for its own sake — it is the
 * same arrangement ADR-0259 already uses for catalog authoring, where AI may analyse and draft
 * while deterministic policy and human maker-checker remain the only authority.
 */
export const AI_ROLES: AiRole[] = [
  {
    id: 'catalogue-drafter',
    name: { cs: 'Návrhář katalogu', en: 'Catalogue drafter' },
    does: {
      cs: 'Ze skutečného chování portfolia navrhne změnu katalogu: novou položku zisku, jinou cenu benefitu, jinou délku expirace, vždy s odůvodněním a odhadem dopadu na závazek.',
      en: 'From real portfolio behaviour it drafts a catalogue change: a new earn entry, a different benefit price, a different expiry, always with a rationale and an estimated effect on the obligation.',
    },
    cannot: {
      cs: 'Nemůže katalog změnit. Katalogy jsou kód, takže výstupem je návrh k revizi, ne zápis.',
      en: 'It cannot change the catalogue. The catalogues are code, so the output is a draft for review, not a write.',
    },
    decides: {
      cs: 'Člověk v pull requestu, se schválením druhým člověkem.',
      en: 'A human in a pull request, approved by a second human.',
    },
    status: 'proposed',
  },
  {
    id: 'explainer',
    name: { cs: 'Vysvětlovač', en: 'Explainer' },
    does: {
      cs: 'Přeloží kódy důvodů do věty, kterou klient pochopí: proč má tento zůstatek, proč mu vyprší tolik Lístků, proč dostal právě tuhle nabídku.',
      en: 'Turns reason codes into a sentence a customer understands: why this balance, why this many Lístky expire, why this particular offer.',
    },
    cannot: {
      cs: 'Nesmí důvod vymyslet. Píše jen z kódů důvodů, které rozhodnutí skutečně vyprodukovalo.',
      en: 'It may not invent a reason. It writes only from the reason codes the decision actually produced.',
    },
    decides: {
      cs: 'Text jde klientovi až po schválení operátorem, stejnou cestou jako ostatní servisní sdělení.',
      en: 'The text reaches the customer only after operator approval, the same path as any other service message.',
    },
    status: 'proposed',
  },
  {
    id: 'watch',
    name: { cs: 'Hlídač', en: 'Watch' },
    does: {
      cs: 'Sleduje stavy, které nikdo nehlídá, protože vypadají jako klid: nasycení ročního stropu, vlna expirací, důvod zisku, který nikdo nezískal, benefit, který si nikdo nevyměnil.',
      en: 'Watches the states nobody watches because they look like quiet: annual cap saturation, an expiry wave, an earn source nobody earned, a benefit nobody redeemed.',
    },
    cannot: {
      cs: 'Nezasahuje. Zakládá nález s daty, na kterých stojí.',
      en: 'It does not intervene. It files a finding with the data it rests on.',
    },
    decides: {
      cs: 'Operátor, který nález vezme nebo zamítne.',
      en: 'The operator who takes the finding or rejects it.',
    },
    status: 'proposed',
  },
  {
    id: 'nba',
    name: { cs: 'Řazení sdělení', en: 'Message ranking' },
    does: {
      cs: 'Seřadí, které z existujících katalogových sdělení je pro klienta nejrelevantnější.',
      en: 'Ranks which of the existing catalogue messages is most relevant to a customer.',
    },
    cannot: {
      cs: 'Nesmí se dotknout ceny, podmínky ani nároku na úvěr. Běží nejdřív ve stínovém režimu, kde se jeho výstup zahodí.',
      en: 'It may not touch a price, a term or credit eligibility. It runs in shadow first, where its output is discarded.',
    },
    decides: {
      cs: 'Deterministické pořadí vyhrává, dokud stínový režim neprokáže shodu a nevznikne karta modelu.',
      en: 'The deterministic order wins until shadow mode shows agreement and a model card exists.',
    },
    status: 'proposed',
  },
]

/** The line that must survive every future edit of the AI section. */
export const AI_RED_LINES: Bilingual[] = [
  {
    cs: 'Umělá inteligence navrhuje, nikdy nerozhoduje.',
    en: 'AI proposes, it never decides.',
  },
  {
    cs: 'Žádný model neurčuje cenu benefitu, podmínku produktu ani nárok na úvěr.',
    en: 'No model sets a benefit price, a product term or credit eligibility.',
  },
  {
    cs: 'Každý návrh prochází schválením dvou lidí a je dohledatelný.',
    en: 'Every proposal goes through two-person approval and is auditable.',
  },
  {
    cs: 'Zranitelný klient je z cílení vyloučen, ale získávat a vyměňovat může dál.',
    en: 'A vulnerable customer is excluded from targeting, but keeps earning and redeeming.',
  },
]
