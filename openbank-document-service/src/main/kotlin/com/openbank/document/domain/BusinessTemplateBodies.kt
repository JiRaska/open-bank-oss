// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

// ---------------------------------------------------------------------------------------------
// Business (legal-entity) onboarding documents — the framework agreement a company signs plus the
// three disclosures handed over before it signs. Kept out of DocumentTemplateSeed.kt only because
// that file is already long; they are seeded from the same list, the same way, and go through the
// same lifecycle (PUBLISHED, versioned, editable in admin-ui, rendered and sealed for real).
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// RAMCOVA_SMLOUVA_PO — framework agreement + current-account agreement for a legal entity
// ---------------------------------------------------------------------------------------------

internal const val BUSINESS_AGREEMENT_CS_BODY = """$LETTERHEAD_CS
<h1>Rámcová smlouva o poskytování platebních služeb a o vedení běžného účtu pro podnikatele</h1>
<p style="color:#64748b;font-size:12px;">Číslo smlouvy {{agreement.number}} &middot; ze dne {{agreement.date}}</p>

<h2>Smluvní strany</h2>
<p><strong>{{bank.name}}</strong>, se sídlem {{bank.seat}}, IČO {{bank.ico}}, zapsaná v obchodním rejstříku
vedeném Městským soudem v Praze (dále jen &bdquo;Banka&ldquo;)</p>
<p>a</p>
<p><strong>{{entity.name}}</strong>, {{entity.legalForm}}, se sídlem {{entity.seat}}, IČO {{entity.ico}}
(dále jen &bdquo;Klient&ldquo;), za niž jednají:</p>
<ul>
{{#each representatives}}<li>{{name}} &ndash; {{role}}</li>
{{/each}}
</ul>
<p>Způsob jednání za Klienta dle veřejného rejstříku: <em>{{signingRule}}</em></p>
<p>Za Klienta tuto Smlouvu podepisují:</p>
<ul>
{{#each signers}}<li>{{name}} &ndash; {{role}}</li>
{{/each}}
</ul>
<p>uzavírají níže uvedeného dne tuto smlouvu (dále jen &bdquo;Smlouva&ldquo;):</p>

<h2>Článek 1 &ndash; Předmět Smlouvy</h2>
<p>Banka se zavazuje zřídit a vést pro Klienta běžný účet a poskytovat mu platební služby, zejména
tuzemské a přeshraniční platební transakce v rámci SEPA a okamžité platby. Klient se zavazuje platit
Bance poplatky podle Sazebníku poplatků pro podnikatele.</p>

<h2>Článek 2 &ndash; Účet</h2>
<p>Banka zřizuje Klientovi běžný účet v rámci produktu &bdquo;{{product.name}}&ldquo; (kód {{product.code}})
vedený v měně <strong>{{product.currency}}</strong>. Číslo účtu (IBAN) Banka Klientovi sdělí bez zbytečného
odkladu po jeho zřízení; účet bude zřízen po dokončení identifikace a kontroly Klienta a jeho skutečných
majitelů.</p>

<h2>Článek 3 &ndash; Oprávněné osoby a autorizace</h2>
<p>S prostředky na účtu jsou oprávněny nakládat osoby jednající za Klienta v souladu se způsobem jednání
zapsaným ve veřejném rejstříku, případně osoby, jimž Klient udělil zmocnění. Platební transakce se
autorizují silným ověřením klienta (SCA). Klient je povinen Bance bez zbytečného odkladu oznámit každou
změnu statutárního orgánu, způsobu jednání nebo skutečného majitele.</p>

<h2>Článek 4 &ndash; Povinnosti Klienta podle AML předpisů</h2>
<p>Klient prohlašuje, že údaje poskytnuté Bance v dotazníku a v prohlášeních při uzavírání Smlouvy jsou
pravdivé a úplné, a zavazuje se poskytnout Bance součinnost potřebnou k plnění povinností podle zákona
č. 253/2008 Sb., o některých opatřeních proti legalizaci výnosů z trestné činnosti a financování
terorismu, a předpisů o automatické výměně informací (FATCA/CRS).</p>

<h2>Článek 5 &ndash; Poplatky a komunikace</h2>
<p>Poplatky stanoví Sazebník poplatků pro podnikatele. Banka komunikuje s Klientem prostřednictvím
internetového a mobilního bankovnictví; oznámení doručená tímto způsobem se považují za doručená
okamžikem jejich zpřístupnění.</p>

<h2>Článek 6 &ndash; Doba trvání a ukončení</h2>
<p>Smlouva se uzavírá na dobu neurčitou. Klient ji může vypovědět kdykoli s účinností ke dni doručení
výpovědi, Banka s výpovědní dobou dva měsíce, nebo okamžitě v případech stanovených VOP.</p>

<h2>Článek 7 &ndash; Přílohy a závěrečná ustanovení</h2>
<p>Nedílnou součástí Smlouvy jsou následující dokumenty, které Klient před jejím podpisem obdržel,
seznámil se s nimi a souhlasí s nimi:</p>
<ol>
{{#each disclosures}}<li>{{title}} ({{code}}, verze {{version}})</li>
{{/each}}
</ol>
<p>Smlouva se řídí právním řádem České republiky. Smlouva je uzavřena elektronicky; nabývá účinnosti
okamžikem, kdy ji podepíší všechny níže uvedené osoby jednající za Klienta, a je opatřena elektronickou
pečetí Banky.</p>

<h2>Podpisy</h2>
<p>Za Banku: {{bank.name}} &ndash; elektronická pečeť</p>
{{#each signers}}<p>Za Klienta: _________________________ {{name}}, {{role}} &ndash; podepsáno elektronicky se silným ověřením</p>
{{/each}}
"""

internal const val BUSINESS_AGREEMENT_EN_BODY = """$LETTERHEAD_EN
<h1>Framework Agreement for Payment Services and Business Current Account</h1>
<p style="color:#64748b;font-size:12px;">Agreement no. {{agreement.number}} &middot; dated {{agreement.date}}</p>

<h2>Parties</h2>
<p><strong>{{bank.name}}</strong>, with its registered office at {{bank.seat}}, Company ID {{bank.ico}},
registered in the Commercial Register kept by the Municipal Court in Prague (the &ldquo;Bank&rdquo;)</p>
<p>and</p>
<p><strong>{{entity.name}}</strong>, {{entity.legalForm}}, with its registered office at {{entity.seat}},
Company ID {{entity.ico}} (the &ldquo;Client&rdquo;), represented by:</p>
<ul>
{{#each representatives}}<li>{{name}} &ndash; {{role}}</li>
{{/each}}
</ul>
<p>Manner of acting on behalf of the Client as registered: <em>{{signingRule}}</em></p>
<p>This Agreement is signed on behalf of the Client by:</p>
<ul>
{{#each signers}}<li>{{name}} &ndash; {{role}}</li>
{{/each}}
</ul>
<p>enter into this agreement (the &ldquo;Agreement&rdquo;) as of the date set out below:</p>

<h2>Article 1 &ndash; Subject matter</h2>
<p>The Bank undertakes to open and maintain a current account for the Client and to provide it with
payment services, in particular domestic and SEPA cross-border payment transactions and instant
payments. The Client undertakes to pay the Bank the fees set out in the Business Schedule of Fees.</p>

<h2>Article 2 &ndash; Account</h2>
<p>The Bank opens for the Client a current account under the &ldquo;{{product.name}}&rdquo; product
(code {{product.code}}) held in <strong>{{product.currency}}</strong>. The Bank will notify the Client of the
account number (IBAN) without undue delay once opened; the account is opened after the identification
and screening of the Client and its beneficial owners has been completed.</p>

<h2>Article 3 &ndash; Authorised persons and authorisation</h2>
<p>Funds in the account may be disposed of by the persons acting for the Client in accordance with the
manner of acting entered in the public register, or by persons holding the Client's power of attorney.
Payment transactions are authorised through Strong Customer Authentication (SCA). The Client shall
notify the Bank without undue delay of any change to its statutory body, manner of acting or beneficial
owners.</p>

<h2>Article 4 &ndash; Anti-money-laundering obligations</h2>
<p>The Client declares that the information provided to the Bank in the questionnaire and declarations
when entering into this Agreement is true and complete, and undertakes to cooperate with the Bank as
required under Czech Act No. 253/2008 Coll. on certain measures against the legitimisation of proceeds
of crime and terrorist financing, and under the automatic exchange of information rules (FATCA/CRS).</p>

<h2>Article 5 &ndash; Fees and communication</h2>
<p>Fees are set out in the Business Schedule of Fees. The Bank communicates with the Client through
internet and mobile banking; notices delivered this way are deemed delivered when made available.</p>

<h2>Article 6 &ndash; Term and termination</h2>
<p>This Agreement is concluded for an indefinite period. The Client may terminate it at any time with
effect from delivery of the notice; the Bank may terminate it with two months' notice, or with
immediate effect in the cases set out in the General Terms and Conditions.</p>

<h2>Article 7 &ndash; Annexes and final provisions</h2>
<p>The following documents form an integral part of this Agreement; the Client received them before
signing, has read them and agrees with them:</p>
<ol>
{{#each disclosures}}<li>{{title}} ({{code}}, version {{version}})</li>
{{/each}}
</ol>
<p>This Agreement is governed by the laws of the Czech Republic. It is concluded electronically, takes
effect once signed by every person listed below acting for the Client, and bears the Bank's electronic
seal.</p>

<h2>Signatures</h2>
<p>For the Bank: {{bank.name}} &ndash; electronic seal</p>
{{#each signers}}<p>For the Client: _________________________ {{name}}, {{role}} &ndash; signed electronically with strong authentication</p>
{{/each}}
"""

// ---------------------------------------------------------------------------------------------
// SAZEBNIK_PO — business schedule of fees, rows rendered from the product catalogue fee list
// ---------------------------------------------------------------------------------------------

internal const val BUSINESS_FEES_CS_BODY = """$LETTERHEAD_CS
<h1>Sazebník poplatků pro podnikatele a právnické osoby</h1>
<p style="color:#64748b;font-size:12px;">{{bank.name}} &middot; platný od {{document.date}}</p>
<p>Produkt: <strong>{{product.name}}</strong> (kód {{product.code}}), měna účtu {{product.currency}}</p>
<table style="width:100%;border-collapse:collapse;font-size:12px;">
<thead><tr><th style="text-align:left;border-bottom:1px solid #cbd5e1;">Položka</th><th style="text-align:left;border-bottom:1px solid #cbd5e1;">Četnost</th><th style="text-align:right;border-bottom:1px solid #cbd5e1;">Poplatek</th></tr></thead>
<tbody>
{{#each fees}}<tr><td>{{name}}{{#if description}}<br/><span style="color:#64748b;">{{description}}</span>{{/if}}{{#if waiveCondition}}<br/><span style="color:#64748b;">Promíjí se: {{waiveCondition}}</span>{{/if}}</td><td>{{frequency}}</td><td style="text-align:right;">{{amount}}</td></tr>
{{/each}}
</tbody>
</table>
<p>Poplatky se účtují na vrub účtu Klienta, u něhož byly služby poskytnuty, v měně uvedené u každé položky.
Procentní poplatky se počítají z částky dané transakce. Služby neuvedené v tomto Sazebníku Banka za
poplatek neposkytuje. Banka může Sazebník měnit postupem podle Všeobecných obchodních podmínek.</p>
"""

internal const val BUSINESS_FEES_EN_BODY = """$LETTERHEAD_EN
<h1>Schedule of Fees for Businesses and Legal Entities</h1>
<p style="color:#64748b;font-size:12px;">{{bank.name}} &middot; effective from {{document.date}}</p>
<p>Product: <strong>{{product.name}}</strong> (code {{product.code}}), account currency {{product.currency}}</p>
<table style="width:100%;border-collapse:collapse;font-size:12px;">
<thead><tr><th style="text-align:left;border-bottom:1px solid #cbd5e1;">Item</th><th style="text-align:left;border-bottom:1px solid #cbd5e1;">Frequency</th><th style="text-align:right;border-bottom:1px solid #cbd5e1;">Fee</th></tr></thead>
<tbody>
{{#each fees}}<tr><td>{{name}}{{#if description}}<br/><span style="color:#64748b;">{{description}}</span>{{/if}}{{#if waiveCondition}}<br/><span style="color:#64748b;">Waived: {{waiveCondition}}</span>{{/if}}</td><td>{{frequency}}</td><td style="text-align:right;">{{amount}}</td></tr>
{{/each}}
</tbody>
</table>
<p>Fees are debited to the Client's account for which the service was provided, in the currency stated
for each item. Percentage fees are calculated from the amount of the transaction concerned. The Bank
charges no fee for a service not listed in this Schedule. The Bank may amend this Schedule following
the procedure in the General Terms and Conditions.</p>
"""

// ---------------------------------------------------------------------------------------------
// PREDSMLUVNI_INFORMACE_PO — pre-contractual information (Act No. 370/2017 Coll., §§ 132 ff.)
// ---------------------------------------------------------------------------------------------

internal const val BUSINESS_PRECONTRACT_CS_BODY = """$LETTERHEAD_CS
<h1>Předsmluvní informace k rámcové smlouvě o platebních službách</h1>
<p style="color:#64748b;font-size:12px;">podle § 132 a násl. zákona č. 370/2017 Sb., o platebním styku &middot; pro {{party.name}} &middot; {{document.date}}</p>

<h2>1. Poskytovatel</h2>
<p>{{bank.name}}, se sídlem {{bank.seat}}, IČO {{bank.ico}}, banka s licencí udělenou Českou
národní bankou, která vykonává dohled nad její činností (Na Příkopě 28, 115 03 Praha 1).</p>

<h2>2. Platební služby</h2>
<p>Vedení platebního účtu, provádění tuzemských, SEPA a okamžitých plateb, trvalých příkazů, inkas a
vydávání platebních karet. Platební příkaz se autorizuje silným ověřením klienta; lhůta pro provedení
tuzemské a SEPA platby je nejpozději konec následujícího pracovního dne, okamžité platby do několika sekund.</p>

<h2>3. Poplatky, úroky a směnné kurzy</h2>
<p>Poplatky jsou uvedeny v Sazebníku poplatků pro podnikatele. Zůstatek na běžném účtu se neúročí, není-li
pro produkt stanoveno jinak. Pro převody mezi měnami se použije aktuální kurz Banky zveřejněný v okamžiku
zpracování transakce.</p>

<h2>4. Komunikace</h2>
<p>Komunikace probíhá v českém nebo anglickém jazyce prostřednictvím internetového a mobilního bankovnictví.
Klient má právo kdykoli během trvání smlouvy požádat o poskytnutí smluvních podmínek a těchto informací.</p>

<h2>5. Ochranná a nápravná opatření</h2>
<p>Klient je povinen chránit své bezpečnostní prvky a bez zbytečného odkladu oznámit jejich ztrátu či
zneužití. Banka může zablokovat platební prostředek z bezpečnostních důvodů. Protože Klient není
spotřebitelem, ustanovení o odpovědnosti za neautorizované transakce se uplatní v rozsahu sjednaném ve VOP.</p>

<h2>6. Změny a ukončení smlouvy</h2>
<p>Banka navrhuje změny smlouvy nejpozději dva měsíce před jejich účinností. Smlouva se uzavírá na dobu
neurčitou; Klient ji může vypovědět kdykoli, Banka s dvouměsíční výpovědní dobou.</p>

<h2>7. Reklamace a mimosoudní řešení sporů</h2>
<p>Reklamace lze podat v internetovém bankovnictví nebo písemně na adrese Banky; Banka ji vyřídí do 15
pracovních dnů. Spory lze řešit u příslušného soudu; dohled vykonává Česká národní banka.</p>
"""

internal const val BUSINESS_PRECONTRACT_EN_BODY = """$LETTERHEAD_EN
<h1>Pre-contractual Information on the Framework Agreement for Payment Services</h1>
<p style="color:#64748b;font-size:12px;">under Sections 132 et seq. of Czech Act No. 370/2017 Coll., on Payments &middot; for {{party.name}} &middot; {{document.date}}</p>

<h2>1. Provider</h2>
<p>{{bank.name}}, registered office {{bank.seat}}, Company ID {{bank.ico}}, a bank licensed
and supervised by the Czech National Bank (Na Příkopě 28, 115 03 Prague 1).</p>

<h2>2. Payment services</h2>
<p>Payment account maintenance, domestic, SEPA and instant payments, standing orders, direct debits and
payment card issuance. Payment orders are authorised with Strong Customer Authentication; domestic and
SEPA payments are executed by the end of the following business day at the latest, instant payments
within seconds.</p>

<h2>3. Fees, interest and exchange rates</h2>
<p>Fees are set out in the Business Schedule of Fees. The current account balance bears no interest
unless the product provides otherwise. Currency conversions use the Bank's rate published at the moment
the transaction is processed.</p>

<h2>4. Communication</h2>
<p>Communication takes place in Czech or English through internet and mobile banking. The Client may at
any time during the agreement request the contractual terms and this information.</p>

<h2>5. Safeguards and corrective measures</h2>
<p>The Client must protect its security credentials and report their loss or misuse without undue delay.
The Bank may block a payment instrument for security reasons. As the Client is not a consumer, liability
for unauthorised transactions applies to the extent agreed in the GTC.</p>

<h2>6. Changes and termination</h2>
<p>The Bank proposes amendments at least two months before they take effect. The agreement is concluded
for an indefinite period; the Client may terminate it at any time, the Bank with two months' notice.</p>

<h2>7. Complaints and out-of-court redress</h2>
<p>Complaints may be filed in internet banking or in writing at the Bank's address; the Bank resolves
them within 15 business days. Disputes may be brought before the competent court; the Czech National
Bank is the supervisory authority.</p>
"""

// ---------------------------------------------------------------------------------------------
// INFORMACE_POJISTENI_VKLADU — depositor information sheet (Directive 2014/49/EU, Annex I)
// ---------------------------------------------------------------------------------------------

internal const val DEPOSIT_INSURANCE_CS_BODY = """$LETTERHEAD_CS
<h1>Informace pro vkladatele o pojištění vkladů</h1>
<p style="color:#64748b;font-size:12px;">pro {{party.name}} &middot; {{document.date}}</p>
<table style="width:100%;border-collapse:collapse;font-size:12px;">
<tbody>
<tr><td style="width:40%;"><strong>Vklady u {{bank.name}} jsou chráněny</strong></td><td>Garančním systémem finančního trhu</td></tr>
<tr><td><strong>Limit ochrany</strong></td><td>100 000 EUR na vkladatele u jedné úvěrové instituce</td></tr>
<tr><td><strong>Máte-li více vkladů u téže banky</strong></td><td>Všechny vaše vklady u téže úvěrové instituce se sčítají a na souhrnnou částku se vztahuje limit 100 000 EUR</td></tr>
<tr><td><strong>Společný účet s jinou osobou</strong></td><td>Limit 100 000 EUR se uplatní samostatně na každého vkladatele</td></tr>
<tr><td><strong>Lhůta pro výplatu náhrady</strong></td><td>7 pracovních dnů</td></tr>
<tr><td><strong>Měna výplaty</strong></td><td>česká koruna (CZK)</td></tr>
<tr><td><strong>Kontakt</strong></td><td>Garanční systém finančního trhu, Na Florenci 1332/23, 110 00 Praha 1, www.gsft.cz</td></tr>
</tbody>
</table>
<h2>Další informace</h2>
<p>Pojištění se vztahuje i na vklady právnických osob, tedy i na vklady Klienta {{party.name}}, s výjimkou
vkladů institucí vyloučených zákonem (např. bank, pojišťoven a investičních fondů). Některé vklady mohou
být dočasně chráněny nad limit 100 000 EUR. Vklady se neposuzují jako pojištěné, pokud nebyly splněny
povinnosti identifikace podle AML předpisů.</p>
<p>Vkladatel potvrzuje převzetí tohoto informačního přehledu.</p>
"""

internal const val DEPOSIT_INSURANCE_EN_BODY = """$LETTERHEAD_EN
<h1>Depositor Information Sheet</h1>
<p style="color:#64748b;font-size:12px;">for {{party.name}} &middot; {{document.date}}</p>
<table style="width:100%;border-collapse:collapse;font-size:12px;">
<tbody>
<tr><td style="width:40%;"><strong>Deposits in {{bank.name}} are protected by</strong></td><td>the Financial Market Guarantee System</td></tr>
<tr><td><strong>Limit of protection</strong></td><td>EUR 100,000 per depositor per credit institution</td></tr>
<tr><td><strong>If you have more deposits at the same credit institution</strong></td><td>All your deposits at the same credit institution are aggregated and the total is subject to the EUR 100,000 limit</td></tr>
<tr><td><strong>If you have a joint account with other person(s)</strong></td><td>The EUR 100,000 limit applies to each depositor separately</td></tr>
<tr><td><strong>Reimbursement period</strong></td><td>7 working days</td></tr>
<tr><td><strong>Currency of reimbursement</strong></td><td>Czech koruna (CZK)</td></tr>
<tr><td><strong>Contact</strong></td><td>Financial Market Guarantee System, Na Florenci 1332/23, 110 00 Prague 1, www.gsft.cz</td></tr>
</tbody>
</table>
<h2>Additional information</h2>
<p>Protection also covers deposits of legal entities, including those of the Client {{party.name}}, except
deposits of institutions excluded by law (such as banks, insurers and investment funds). Some deposits
may be temporarily protected above EUR 100,000. Deposits are not treated as covered where the
identification duties under anti-money-laundering law have not been met.</p>
<p>The depositor acknowledges receipt of this information sheet.</p>
"""
