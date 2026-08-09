// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import java.time.Instant
import java.util.UUID

/**
 * The three canonical demo document templates, each in a Czech and English variant — the single
 * Kotlin source of truth for [DocumentTemplateSeeder]'s first-boot seed, mirroring
 * `openbank-product-catalog`'s `ProductSeed` pattern.
 *
 * Every code is locale-suffixed (`VOP_CS` / `VOP_EN`, …) rather than encoding locale as a separate
 * dimension of the (code, version) unique key — this needed zero schema change (V1's
 * `uq_document_templates_code_version UNIQUE (code, version)` is untouched) and matches how
 * `DocumentRenderUseCase.render` already looks a template up by `(templateCode, templateVersion)`
 * alone, with no separate locale parameter.
 *
 * All bodies are demo/reference legal text for this reference-implementation banking platform —
 * not reviewed or approved by counsel, and MUST be replaced with jurisdiction-reviewed wording
 * before any real-world use. Fixed IDs/timestamp keep the seed deterministic across environments.
 */
object DocumentTemplateSeed {

    private val SEEDED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private const val SEEDED_BY = "system"
    private const val DEMO_CLASSIFICATION = "restricted"

    // v1.1.0 (2026-07-14): added the OpenBank letterhead (inline-SVG logo). Published templates
    // are immutable (DocumentTemplate.publish()/domain rule), so this is a NEW version — new fixed
    // IDs, not an in-place edit of the v1.0.0 rows already seeded. Superseding the v1.0.0 rows is
    // now handled automatically: DocumentTemplateSeeder retires the current PUBLISHED sibling for
    // a code before inserting its replacement (ADR-0162 version-resolution policy — a code has at
    // most one PUBLISHED row at a time), and a one-time migration
    // (V5__enforce_one_published_template_per_code.sql) retired the v1.0.0 rows this list had
    // already left dangling as PUBLISHED before that policy existed.
    val templates: List<DocumentTemplate> = listOf(
        template(
            id = "1e575a01-0000-4000-9000-000000000011",
            code = "VOP_CS",
            version = "1.1.0",
            name = "Všeobecné obchodní podmínky",
            locale = "cs",
            bodyHtml = VOP_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000012",
            code = "VOP_EN",
            version = "1.1.0",
            name = "General Terms and Conditions",
            locale = "en",
            bodyHtml = VOP_EN_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000013",
            code = "RAMCOVA_SMLOUVA_CS",
            version = "1.1.0",
            name = "Rámcová smlouva o poskytování platebních služeb",
            locale = "cs",
            bodyHtml = FRAMEWORK_AGREEMENT_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000014",
            code = "RAMCOVA_SMLOUVA_EN",
            version = "1.1.0",
            name = "Framework Agreement for Payment Services",
            locale = "en",
            bodyHtml = FRAMEWORK_AGREEMENT_EN_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000015",
            code = "UCET_SMLOUVA_CS",
            version = "1.1.0",
            name = "Smlouva o zřízení a vedení běžného účtu",
            locale = "cs",
            bodyHtml = ACCOUNT_AGREEMENT_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000016",
            code = "UCET_SMLOUVA_EN",
            version = "1.1.0",
            name = "Current Account Opening Agreement",
            locale = "en",
            bodyHtml = ACCOUNT_AGREEMENT_EN_BODY,
        ),
        // ADR-0248: monthly statement, PAD Art. 5 annual statement of fees, and payment
        // confirmation. All three render only through the existing non-persisting
        // `POST /api/v1/documents/templates/preview` endpoint — never stored, never a
        // `Document` row, never a `document.generated` outbox event (ADR-0248 Decision). New
        // codes at version 1.0.0, not superseding any existing row.
        template(
            id = "1e575a01-0000-4000-9000-000000000017",
            code = "MESICNI_VYPIS_CS",
            version = "1.0.0",
            name = "Měsíční výpis z účtu",
            locale = "cs",
            bodyHtml = MONTHLY_STATEMENT_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000018",
            code = "MESICNI_VYPIS_EN",
            version = "1.0.0",
            name = "Monthly Account Statement",
            locale = "en",
            bodyHtml = MONTHLY_STATEMENT_EN_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000019",
            code = "ROCNI_VYPIS_POPLATKU_CS",
            version = "1.0.0",
            name = "Roční výpis poplatků",
            locale = "cs",
            bodyHtml = ANNUAL_FEE_STATEMENT_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000020",
            code = "ROCNI_VYPIS_POPLATKU_EN",
            version = "1.0.0",
            name = "Annual Statement of Fees",
            locale = "en",
            bodyHtml = ANNUAL_FEE_STATEMENT_EN_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000021",
            code = "POTVRZENI_O_PLATBE_CS",
            version = "1.0.0",
            name = "Potvrzení o provedení platby",
            locale = "cs",
            bodyHtml = PAYMENT_CONFIRMATION_CS_BODY,
        ),
        template(
            id = "1e575a01-0000-4000-9000-000000000022",
            code = "POTVRZENI_O_PLATBE_EN",
            version = "1.0.0",
            name = "Payment Confirmation",
            locale = "en",
            bodyHtml = PAYMENT_CONFIRMATION_EN_BODY,
        ),
    )

    private fun template(id: String, code: String, version: String, name: String, locale: String, bodyHtml: String) =
        DocumentTemplate(
            id = UUID.fromString(id),
            code = code,
            version = version,
            name = name,
            engine = TemplateEngine.HANDLEBARS,
            bodyHtml = bodyHtml,
            locale = locale,
            // Seeded directly as PUBLISHED (like ProductSeed's ACTIVE products) — these are meant to be
            // immediately renderable demo content, not drafts awaiting a human publish step.
            status = TemplateStatus.PUBLISHED,
            productRef = null,
            classification = DEMO_CLASSIFICATION,
            createdAt = SEEDED_AT,
            createdBy = SEEDED_BY,
        )
}

// ---------------------------------------------------------------------------------------------
// VOP — Všeobecné obchodní podmínky / General Terms and Conditions
// ---------------------------------------------------------------------------------------------

// Letterhead — inline SVG, not an <img src="..."> reference: the real render pipeline's
// PdfRenderPort (WeasyPrint/Gotenberg) never fetches external resources by design (SSRF
// mitigation), and the admin-ui preview also sandboxes the iframe without network access —
// inline SVG markup needs neither, so it renders identically in the editor preview, a
// browser "open in new window" view, and the actual generated PDF. Two locale variants
// (only the city name differs) so every document, not just the party-identification
// clause, opens with a real letterhead rather than a bare heading.
private const val LETTERHEAD_CS = """
<div style="display:flex;align-items:center;gap:12px;padding-bottom:16px;margin-bottom:24px;border-bottom:2px solid #4f46e5;">
<svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="OpenBank">
<rect width="42" height="42" rx="10" fill="#4f46e5"/>
<text x="21" y="28" font-family="system-ui,-apple-system,Segoe UI,Roboto,sans-serif" font-size="16" font-weight="700" fill="#ffffff" text-anchor="middle">OB</text>
</svg>
<div>
<div style="font-size:19px;font-weight:700;color:#0f172a;letter-spacing:-0.01em;">OpenBank</div>
<div style="font-size:10.5px;color:#64748b;text-transform:uppercase;letter-spacing:0.04em;">a.s. &middot; Na Příkopě 1, 110&nbsp;00 Praha 1</div>
</div>
</div>
"""

private const val LETTERHEAD_EN = """
<div style="display:flex;align-items:center;gap:12px;padding-bottom:16px;margin-bottom:24px;border-bottom:2px solid #4f46e5;">
<svg width="42" height="42" viewBox="0 0 42 42" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="OpenBank">
<rect width="42" height="42" rx="10" fill="#4f46e5"/>
<text x="21" y="28" font-family="system-ui,-apple-system,Segoe UI,Roboto,sans-serif" font-size="16" font-weight="700" fill="#ffffff" text-anchor="middle">OB</text>
</svg>
<div>
<div style="font-size:19px;font-weight:700;color:#0f172a;letter-spacing:-0.01em;">OpenBank</div>
<div style="font-size:10.5px;color:#64748b;text-transform:uppercase;letter-spacing:0.04em;">a.s. &middot; Na Příkopě 1, 110&nbsp;00 Prague 1</div>
</div>
</div>
"""

private const val VOP_CS_BODY = """$LETTERHEAD_CS
<h1>Všeobecné obchodní podmínky</h1>
<p style="color:#64748b;font-size:12px;">OpenBank a.s. &middot; účinné od {{document.date}}</p>

<h2>Článek 1 &ndash; Úvodní ustanovení</h2>
<p>Tyto Všeobecné obchodní podmínky (dále jen &bdquo;VOP&ldquo;) upravují vzájemná práva a povinnosti mezi
společností OpenBank a.s. (dále jen &bdquo;Banka&ldquo;) a klientem, kterým může být fyzická nebo právnická
osoba (dále jen &bdquo;Klient&ldquo;), při poskytování bankovních produktů a služeb, zejména platebních
účtů, platebních služeb a souvisejících produktů (dále jen &bdquo;Produkty&ldquo;). VOP tvoří nedílnou
součást každé smlouvy uzavřené mezi Bankou a Klientem, není-li v konkrétní smlouvě sjednáno jinak.</p>

<h2>Článek 2 &ndash; Uzavření smluvního vztahu</h2>
<p>Smluvní vztah mezi Bankou a Klientem vzniká uzavřením příslušné smlouvy o poskytování konkrétního
Produktu (např. smlouvy o zřízení a vedení běžného účtu nebo rámcové smlouvy o poskytování platebních
služeb) a je podmíněn řádným provedením identifikace a kontroly Klienta v souladu s příslušnými právními
předpisy o opatřeních proti legalizaci výnosů z trestné činnosti.</p>

<h2>Článek 3 &ndash; Práva a povinnosti stran</h2>
<p>Banka se zavazuje poskytovat sjednané Produkty s odbornou péčí a v souladu s platnými právními předpisy.
Klient se zavazuje poskytovat Bance pravdivé a úplné informace a neprodleně oznamovat veškeré změny
údajů rozhodných pro plnění smlouvy, zejména změnu kontaktních údajů, identifikačních údajů nebo
skutečného majitele.</p>

<h2>Článek 4 &ndash; Poplatky a úročení</h2>
<p>Za poskytování Produktů náleží Bance poplatky ve výši a splatnosti stanovené v aktuálním Sazebníku
poplatků, který je nedílnou součástí smluvní dokumentace. Úročení peněžních prostředků na účtu Klienta
se řídí podmínkami sjednanými pro konkrétní Produkt {{#if product.name}}(&bdquo;{{product.name}}&ldquo;{{#if product.code}}, kód {{product.code}}{{/if}}){{/if}}.</p>

<h2>Článek 5 &ndash; Ochrana osobních údajů</h2>
<p>Banka zpracovává osobní údaje Klienta v souladu s Nařízením Evropského parlamentu a Rady (EU) 2016/679
(GDPR) a příslušnými vnitrostátními právními předpisy. Podrobné informace o rozsahu, účelu a době
zpracování osobních údajů jsou uvedeny v samostatných Zásadách zpracování osobních údajů dostupných na
internetových stránkách Banky.</p>

<h2>Článek 6 &ndash; Reklamace a mimosoudní řešení sporů</h2>
<p>Klient je oprávněn podat reklamaci na kterékoli pobočce Banky nebo prostřednictvím elektronických
kanálů Banky. Nedojde-li k vyřešení reklamace ke spokojenosti Klienta, je Klient oprávněn obrátit se na
finančního arbitra nebo příslušný soud.</p>

<h2>Článek 7 &ndash; Doba trvání a ukončení</h2>
<p>Smluvní vztah se sjednává na dobu neurčitou, není-li v konkrétní smlouvě sjednáno jinak. Kterákoli ze
smluvních stran je oprávněna smluvní vztah vypovědět způsobem a ve lhůtách sjednaných v příslušné
smlouvě a těchto VOP.</p>

<h2>Článek 8 &ndash; Závěrečná ustanovení</h2>
<p>Tyto VOP a právní vztahy z nich vyplývající se řídí právním řádem České republiky. Dohled nad
činností Banky vykonává Česká národní banka. Banka je oprávněna tyto VOP jednostranně měnit; o změně
Klienta informuje způsobem sjednaným v příslušné smlouvě.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Vyhotoveno dne {{document.date}}{{#if document.caseRef}} &middot; spisová značka {{document.caseRef}}{{/if}}.<br/>
  {{#if signature.block}}{{signature.block}}{{else}}Podpis Klienta: {{party.name}}{{/if}}
</p>
"""

private const val VOP_EN_BODY = """$LETTERHEAD_EN
<h1>General Terms and Conditions</h1>
<p style="color:#64748b;font-size:12px;">OpenBank a.s. &middot; effective from {{document.date}}</p>

<h2>Article 1 &ndash; Introductory provisions</h2>
<p>These General Terms and Conditions (the &ldquo;GTC&rdquo;) govern the mutual rights and obligations
between OpenBank a.s. (the &ldquo;Bank&rdquo;) and its customer, whether a natural or legal person (the
&ldquo;Customer&rdquo;), in connection with the provision of banking products and services, in particular
payment accounts, payment services and related products (the &ldquo;Products&rdquo;). The GTC form an
integral part of every agreement concluded between the Bank and the Customer, unless the specific
agreement provides otherwise.</p>

<h2>Article 2 &ndash; Formation of the contractual relationship</h2>
<p>The contractual relationship between the Bank and the Customer arises upon conclusion of the relevant
agreement for a specific Product (e.g. a current account opening agreement or a framework agreement for
payment services) and is conditional on the Bank having duly identified and screened the Customer in
accordance with applicable anti-money-laundering legislation.</p>

<h2>Article 3 &ndash; Rights and obligations of the parties</h2>
<p>The Bank undertakes to provide the agreed Products with professional care and in accordance with
applicable law. The Customer undertakes to provide the Bank with true and complete information and to
notify the Bank without undue delay of any change to data relevant to the performance of the agreement,
in particular a change of contact details, identification data, or beneficial owner.</p>

<h2>Article 4 &ndash; Fees and interest</h2>
<p>The Bank is entitled to fees for the provision of the Products in the amount and payable as set out in
the current Schedule of Fees, which forms an integral part of the contractual documentation. Interest on
funds held in the Customer's account is governed by the terms agreed for the specific Product
{{#if product.name}}(&ldquo;{{product.name}}&rdquo;{{#if product.code}}, code {{product.code}}{{/if}}){{/if}}.</p>

<h2>Article 5 &ndash; Data protection</h2>
<p>The Bank processes the Customer's personal data in accordance with Regulation (EU) 2016/679 (GDPR) and
applicable national law. Detailed information on the scope, purpose and retention period of personal data
processing is set out in a separate Privacy Notice available on the Bank's website.</p>

<h2>Article 6 &ndash; Complaints and out-of-court dispute resolution</h2>
<p>The Customer may file a complaint at any branch of the Bank or through the Bank's electronic channels.
If a complaint is not resolved to the Customer's satisfaction, the Customer is entitled to refer the
matter to the Financial Arbitrator or the competent court.</p>

<h2>Article 7 &ndash; Term and termination</h2>
<p>The contractual relationship is concluded for an indefinite period unless the specific agreement
provides otherwise. Either party may terminate the contractual relationship in the manner and within the
time limits agreed in the relevant agreement and these GTC.</p>

<h2>Article 8 &ndash; Final provisions</h2>
<p>These GTC and the legal relationships arising from them are governed by the laws of the Czech Republic.
The Bank's activities are supervised by the Czech National Bank. The Bank may unilaterally amend these GTC
and will notify the Customer in the manner agreed in the relevant agreement.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Issued on {{document.date}}{{#if document.caseRef}} &middot; case reference {{document.caseRef}}{{/if}}.<br/>
  {{#if signature.block}}{{signature.block}}{{else}}Customer signature: {{party.name}}{{/if}}
</p>
"""

// ---------------------------------------------------------------------------------------------
// Rámcová smlouva o poskytování platebních služeb / Framework Agreement for Payment Services
// ---------------------------------------------------------------------------------------------

private const val FRAMEWORK_AGREEMENT_CS_BODY = """$LETTERHEAD_CS
<h1>Rámcová smlouva o poskytování platebních služeb</h1>

<p><strong>OpenBank a.s.</strong>, se sídlem Na Příkopě 1, 110 00 Praha 1, IČO 000 00 001, zapsaná v
obchodním rejstříku vedeném Městským soudem v Praze (dále jen &bdquo;Banka&ldquo;)</p>
<p>a</p>
<p><strong>{{party.name}}</strong>{{#if party.address}}, bytem/se sídlem {{party.address}}{{/if}}
(dále jen &bdquo;Klient&ldquo;)</p>
<p>uzavírají níže uvedeného dne tuto rámcovou smlouvu o poskytování platebních služeb (dále jen
&bdquo;Smlouva&ldquo;):</p>

<h2>Článek 1 &ndash; Předmět Smlouvy</h2>
<p>Banka se zavazuje poskytovat Klientovi platební služby spočívající zejména v provádění tuzemských
platebních transakcí, přeshraničních platebních transakcí v rámci Jednotné oblasti pro platby v eurech
(SEPA), okamžitých plateb a v případě sjednání i vydávání platebních karet, to vše prostřednictvím
platebního účtu specifikovaného v Článku 2.</p>

<h2>Článek 2 &ndash; Platební účet</h2>
<p>Pro účely této Smlouvy je Klientovi veden platební účet
{{#if account.iban}}s číslem (IBAN) <strong>{{account.iban}}</strong>{{/if}}
{{#if product.name}}v rámci produktu &bdquo;{{product.name}}&ldquo;{{#if product.code}} (kód {{product.code}}){{/if}}{{/if}}.</p>

<h2>Článek 3 &ndash; Autorizace platebních transakcí</h2>
<p>Platební transakce je autorizována Klientem prostřednictvím silného ověření klienta (SCA) v souladu s
platnými právními předpisy o platebním styku. Bez platné autorizace nebude platební transakce Bankou
provedena.</p>

<h2>Článek 4 &ndash; Poplatky</h2>
<p>Za poskytování platebních služeb dle této Smlouvy náleží Bance poplatky uvedené v aktuálním Sazebníku
poplatků, na který odkazují Všeobecné obchodní podmínky Banky (VOP).</p>

<h2>Článek 5 &ndash; Odpovědnost a reklamace</h2>
<p>Banka odpovídá za řádné a včasné provedení autorizovaných platebních transakcí. Reklamace neautorizované
nebo nesprávně provedené platební transakce uplatňuje Klient bez zbytečného odkladu, nejpozději však ve
lhůtě stanovené právními předpisy a VOP Banky.</p>

<h2>Článek 6 &ndash; Doba trvání a ukončení Smlouvy</h2>
<p>Smlouva se uzavírá na dobu neurčitou. Klient je oprávněn Smlouvu vypovědět kdykoli, Banka za podmínek
sjednaných ve VOP.</p>

<h2>Článek 7 &ndash; Rozhodné právo a závěrečná ustanovení</h2>
<p>Tato Smlouva se řídí právním řádem České republiky. Nedílnou součástí této Smlouvy jsou Všeobecné
obchodní podmínky Banky.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Uzavřeno dne {{document.date}}{{#if document.caseRef}} &middot; spisová značka {{document.caseRef}}{{/if}}.
</p>
<p>Za Banku: _________________________</p>
<p>Klient: {{#if signature.block}}{{signature.block}}{{else}}_________________________ {{party.name}}{{/if}}</p>
"""

private const val FRAMEWORK_AGREEMENT_EN_BODY = """$LETTERHEAD_EN
<h1>Framework Agreement for Payment Services</h1>

<p><strong>OpenBank a.s.</strong>, with its registered office at Na Příkopě 1, 110 00 Prague 1, Company
ID 000 00 001, registered with the Municipal Court in Prague (the &ldquo;Bank&rdquo;)</p>
<p>and</p>
<p><strong>{{party.name}}</strong>{{#if party.address}}, of {{party.address}}{{/if}}
(the &ldquo;Customer&rdquo;)</p>
<p>enter into this framework agreement for payment services (the &ldquo;Agreement&rdquo;) as of the date
set out below:</p>

<h2>Article 1 &ndash; Subject matter of the Agreement</h2>
<p>The Bank undertakes to provide the Customer with payment services consisting in particular of the
execution of domestic payment transactions, cross-border payment transactions within the Single Euro
Payments Area (SEPA), instant payments, and, where agreed, the issuance of payment cards, all via the
payment account specified in Article 2.</p>

<h2>Article 2 &ndash; Payment account</h2>
<p>For the purposes of this Agreement the Customer is provided with a payment account
{{#if account.iban}}with number (IBAN) <strong>{{account.iban}}</strong>{{/if}}
{{#if product.name}}under the &ldquo;{{product.name}}&rdquo; product{{#if product.code}} (code {{product.code}}){{/if}}{{/if}}.</p>

<h2>Article 3 &ndash; Authorisation of payment transactions</h2>
<p>A payment transaction is authorised by the Customer through Strong Customer Authentication (SCA) in
accordance with applicable payment services legislation. No payment transaction will be executed by the
Bank without valid authorisation.</p>

<h2>Article 4 &ndash; Fees</h2>
<p>The Bank is entitled to fees for the payment services provided under this Agreement as set out in the
current Schedule of Fees, referenced by the Bank's General Terms and Conditions (GTC).</p>

<h2>Article 5 &ndash; Liability and complaints</h2>
<p>The Bank is liable for the proper and timely execution of authorised payment transactions. The
Customer shall raise a complaint about an unauthorised or incorrectly executed payment transaction
without undue delay, and in any event within the time limit set out in applicable law and the Bank's GTC.</p>

<h2>Article 6 &ndash; Term and termination</h2>
<p>The Agreement is concluded for an indefinite period. The Customer may terminate the Agreement at any
time; the Bank may do so under the conditions set out in the GTC.</p>

<h2>Article 7 &ndash; Governing law and final provisions</h2>
<p>This Agreement is governed by the laws of the Czech Republic. The Bank's General Terms and Conditions
form an integral part of this Agreement.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Concluded on {{document.date}}{{#if document.caseRef}} &middot; case reference {{document.caseRef}}{{/if}}.
</p>
<p>For the Bank: _________________________</p>
<p>Customer: {{#if signature.block}}{{signature.block}}{{else}}_________________________ {{party.name}}{{/if}}</p>
"""

// ---------------------------------------------------------------------------------------------
// Smlouva o zřízení a vedení běžného účtu / Current Account Opening Agreement
// ---------------------------------------------------------------------------------------------

private const val ACCOUNT_AGREEMENT_CS_BODY = """$LETTERHEAD_CS
<h1>Smlouva o zřízení a vedení běžného účtu</h1>

<p><strong>OpenBank a.s.</strong>, se sídlem Na Příkopě 1, 110 00 Praha 1, IČO 000 00 001
(dále jen &bdquo;Banka&ldquo;)</p>
<p>a</p>
<p><strong>{{party.name}}</strong>{{#if party.address}}, bytem/se sídlem {{party.address}}{{/if}}
{{#if party.email}}, e-mail {{party.email}}{{/if}} (dále jen &bdquo;Klient&ldquo;)</p>
<p>uzavírají tuto smlouvu o zřízení a vedení běžného účtu (dále jen &bdquo;Smlouva&ldquo;):</p>

<h2>Článek 1 &ndash; Předmět Smlouvy</h2>
<p>Banka se zavazuje zřídit a vést pro Klienta běžný účet a poskytovat s ním související bankovní služby
za podmínek sjednaných touto Smlouvou, Všeobecnými obchodními podmínkami Banky (VOP) a podmínkami
sjednaného produktu.</p>

<h2>Článek 2 &ndash; Identifikace účtu</h2>
<p>Účet je veden{{#if account.iban}} pod číslem (IBAN) <strong>{{account.iban}}</strong>{{/if}}
{{#if product.name}} v rámci produktu &bdquo;{{product.name}}&ldquo;{{#if product.code}} (kód {{product.code}}){{/if}}{{/if}}.</p>

<h2>Článek 3 &ndash; Úročení a poplatky</h2>
<p>Zůstatek na účtu se úročí a poplatky za vedení účtu a související služby se účtují v souladu s
podmínkami sjednaného produktu a aktuálním Sazebníkem poplatků Banky.</p>

<h2>Článek 4 &ndash; Práva a povinnosti stran</h2>
<p>Klient je oprávněn nakládat s peněžními prostředky na účtu v souladu s touto Smlouvou a VOP. Banka je
oprávněna odmítnout provedení dispozice, která by byla v rozporu s právními předpisy nebo touto Smlouvou.</p>

<h2>Článek 5 &ndash; Všeobecné obchodní podmínky</h2>
<p>Nedílnou součástí této Smlouvy jsou aktuální Všeobecné obchodní podmínky Banky, se kterými se Klient
před podpisem této Smlouvy seznámil a s jejich obsahem souhlasí.</p>

<h2>Článek 6 &ndash; Ukončení Smlouvy</h2>
<p>Smlouva se uzavírá na dobu neurčitou a lze ji ukončit výpovědí kterékoli ze stran způsobem sjednaným
ve VOP.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Uzavřeno dne {{document.date}}{{#if document.caseRef}} &middot; spisová značka {{document.caseRef}}{{/if}}.
</p>
<p>Za Banku: _________________________</p>
<p>Klient: {{#if signature.block}}{{signature.block}}{{else}}_________________________ {{party.name}}{{/if}}</p>
"""

private const val ACCOUNT_AGREEMENT_EN_BODY = """$LETTERHEAD_EN
<h1>Current Account Opening Agreement</h1>

<p><strong>OpenBank a.s.</strong>, with its registered office at Na Příkopě 1, 110 00 Prague 1, Company
ID 000 00 001 (the &ldquo;Bank&rdquo;)</p>
<p>and</p>
<p><strong>{{party.name}}</strong>{{#if party.address}}, of {{party.address}}{{/if}}
{{#if party.email}}, email {{party.email}}{{/if}} (the &ldquo;Customer&rdquo;)</p>
<p>enter into this current account opening agreement (the &ldquo;Agreement&rdquo;):</p>

<h2>Article 1 &ndash; Subject matter of the Agreement</h2>
<p>The Bank undertakes to open and maintain a current account for the Customer and to provide related
banking services under the terms agreed in this Agreement, the Bank's General Terms and Conditions
(GTC), and the terms of the agreed product.</p>

<h2>Article 2 &ndash; Account identification</h2>
<p>The account is maintained{{#if account.iban}} under number (IBAN) <strong>{{account.iban}}</strong>{{/if}}
{{#if product.name}} under the &ldquo;{{product.name}}&rdquo; product{{#if product.code}} (code {{product.code}}){{/if}}{{/if}}.</p>

<h2>Article 3 &ndash; Interest and fees</h2>
<p>The account balance bears interest, and fees for account maintenance and related services are charged,
in accordance with the terms of the agreed product and the Bank's current Schedule of Fees.</p>

<h2>Article 4 &ndash; Rights and obligations of the parties</h2>
<p>The Customer is entitled to dispose of the funds held in the account in accordance with this Agreement
and the GTC. The Bank is entitled to refuse to execute an instruction that would be contrary to
applicable law or this Agreement.</p>

<h2>Article 5 &ndash; General Terms and Conditions</h2>
<p>The Bank's current General Terms and Conditions form an integral part of this Agreement; the Customer
confirms having reviewed and agreed to their content prior to signing this Agreement.</p>

<h2>Article 6 &ndash; Termination</h2>
<p>The Agreement is concluded for an indefinite period and may be terminated by either party in the
manner agreed in the GTC.</p>

<hr/>
<p style="font-size:12px;color:#64748b;">
  Concluded on {{document.date}}{{#if document.caseRef}} &middot; case reference {{document.caseRef}}{{/if}}.
</p>
<p>For the Bank: _________________________</p>
<p>Customer: {{#if signature.block}}{{signature.block}}{{else}}_________________________ {{party.name}}{{/if}}</p>
"""

// ---------------------------------------------------------------------------------------------
// Měsíční výpis z účtu / Monthly Account Statement (ADR-0248 §1, PSD2 Art. 58(2))
// ---------------------------------------------------------------------------------------------

private const val MONTHLY_STATEMENT_CS_BODY = """$LETTERHEAD_CS
<h1>Měsíční výpis z účtu</h1>
<p style="color:#64748b;font-size:12px;">
  OpenBank a.s. &middot; výpis za období {{document.periodFrom}} &ndash; {{document.periodTo}}
</p>

<h2>Identifikace účtu</h2>
<p>
  Majitel účtu: <strong>{{party.name}}</strong><br/>
  Číslo účtu (IBAN): <strong>{{account.iban}}</strong><br/>
  Měna kapsy: {{account.currency}}
</p>

<h2>Přehled zůstatků</h2>
<table style="width:100%;border-collapse:collapse;font-size:13px;">
<tbody>
<tr><td>Počáteční zůstatek k {{document.periodFrom}}</td><td style="text-align:right;"><strong>{{document.openingBalance}} {{account.currency}}</strong></td></tr>
<tr><td>Konečný zůstatek k {{document.periodTo}}</td><td style="text-align:right;"><strong>{{document.closingBalance}} {{account.currency}}</strong></td></tr>
</tbody>
</table>

<h2>Přehled transakcí</h2>
<table style="width:100%;border-collapse:collapse;font-size:12px;" border="1" cellpadding="4">
<thead>
<tr>
<th>Datum zaúčtování</th><th>Datum valuty</th><th>Protistrana</th><th>Reference / VS</th><th style="text-align:right;">Částka</th>
</tr>
</thead>
<tbody>
{{#each document.entries}}
<tr>
<td>{{this.bookingDate}}</td>
<td>{{this.valueDate}}</td>
<td>{{this.counterparty}}</td>
<td>{{this.reference}}</td>
<td style="text-align:right;">{{this.amount}} {{this.currency}}</td>
</tr>
{{/each}}
</tbody>
</table>

<h2>Identifikace výpisu</h2>
<p>
  Právní pořadové číslo výpisu: {{document.legalSequenceNumber}}<br/>
  Elektronické pořadové číslo výpisu: {{document.electronicSequenceNumber}}<br/>
  Výpis vygenerován dne: {{document.generatedAt}}
</p>

<hr/>
<p style="font-size:11px;color:#64748b;">
  Tento výpis je Klientovi poskytnut na trvalém nosiči dat ve smyslu čl. 58 odst. 2 směrnice
  Evropského parlamentu a Rady (EU) 2015/2366 (PSD2), a to způsobem umožňujícím jeho uložení a
  reprodukci v nezměněné podobě.
</p>
"""

private const val MONTHLY_STATEMENT_EN_BODY = """$LETTERHEAD_EN
<h1>Monthly Account Statement</h1>
<p style="color:#64748b;font-size:12px;">
  OpenBank a.s. &middot; statement for the period {{document.periodFrom}} &ndash; {{document.periodTo}}
</p>

<h2>Account identification</h2>
<p>
  Account holder: <strong>{{party.name}}</strong><br/>
  Account number (IBAN): <strong>{{account.iban}}</strong><br/>
  Pocket currency: {{account.currency}}
</p>

<h2>Balance summary</h2>
<table style="width:100%;border-collapse:collapse;font-size:13px;">
<tbody>
<tr><td>Opening balance as of {{document.periodFrom}}</td><td style="text-align:right;"><strong>{{document.openingBalance}} {{account.currency}}</strong></td></tr>
<tr><td>Closing balance as of {{document.periodTo}}</td><td style="text-align:right;"><strong>{{document.closingBalance}} {{account.currency}}</strong></td></tr>
</tbody>
</table>

<h2>Transaction list</h2>
<table style="width:100%;border-collapse:collapse;font-size:12px;" border="1" cellpadding="4">
<thead>
<tr>
<th>Booking date</th><th>Value date</th><th>Counterparty</th><th>Reference</th><th style="text-align:right;">Amount</th>
</tr>
</thead>
<tbody>
{{#each document.entries}}
<tr>
<td>{{this.bookingDate}}</td>
<td>{{this.valueDate}}</td>
<td>{{this.counterparty}}</td>
<td>{{this.reference}}</td>
<td style="text-align:right;">{{this.amount}} {{this.currency}}</td>
</tr>
{{/each}}
</tbody>
</table>

<h2>Statement identification</h2>
<p>
  Legal sequence number: {{document.legalSequenceNumber}}<br/>
  Electronic sequence number: {{document.electronicSequenceNumber}}<br/>
  Generated on: {{document.generatedAt}}
</p>

<hr/>
<p style="font-size:11px;color:#64748b;">
  This statement is provided to the Customer on a durable medium within the meaning of Article
  58(2) of Directive (EU) 2015/2366 (PSD2), in a way that allows the Customer to store and
  reproduce it unchanged.
</p>
"""

// ---------------------------------------------------------------------------------------------
// Roční výpis poplatků / Annual Statement of Fees (ADR-0248 §2, PAD (EU) 2014/92 Art. 5)
// ---------------------------------------------------------------------------------------------

private const val ANNUAL_FEE_STATEMENT_CS_BODY = """$LETTERHEAD_CS
<h1>Roční výpis poplatků</h1>
<p style="color:#64748b;font-size:12px;">OpenBank a.s. &middot; kalendářní rok {{document.year}}</p>

<h2>Identifikace účtu</h2>
<p>
  Majitel účtu: <strong>{{party.name}}</strong><br/>
  Číslo účtu (IBAN): <strong>{{account.iban}}</strong><br/>
  Měna: {{document.currency}}
</p>

<h2>Přehled poplatků</h2>
<table style="width:100%;border-collapse:collapse;font-size:12px;" border="1" cellpadding="4">
<thead>
<tr><th>Název poplatku</th><th>Kategorie (příloha II)</th><th style="text-align:right;">Částka</th></tr>
</thead>
<tbody>
{{#each document.fees}}
<tr>
<td>{{this.name}}</td>
<td>{{this.category}}</td>
<td style="text-align:right;">{{this.amount}} {{../document.currency}}</td>
</tr>
{{/each}}
</tbody>
</table>

<h2>Celková výše poplatků</h2>
<p>Celková výše poplatků účtovaných v roce {{document.year}}: <strong>{{document.totalFees}} {{document.currency}}</strong></p>

{{#if document.interestRate}}
<h2>Úročení</h2>
<p>Úroková sazba uplatněná na účet v uvedeném období: <strong>{{document.interestRate}} % p.a.</strong></p>
{{/if}}

<h2>Vydání dokumentu</h2>
<p>Datum vydání: {{document.issueDate}}</p>

<hr/>
<p style="font-size:11px;color:#64748b;">
  Tento dokument je ročním výpisem poplatků ve smyslu čl. 5 a přílohy II směrnice Evropského
  parlamentu a Rady (EU) 2014/92 o porovnatelnosti poplatků souvisejících s platebními účty,
  změně platebního účtu a přístupu k platebním účtům se základními prvky (PAD).
</p>
"""

private const val ANNUAL_FEE_STATEMENT_EN_BODY = """$LETTERHEAD_EN
<h1>Annual Statement of Fees</h1>
<p style="color:#64748b;font-size:12px;">OpenBank a.s. &middot; calendar year {{document.year}}</p>

<h2>Account identification</h2>
<p>
  Account holder: <strong>{{party.name}}</strong><br/>
  Account number (IBAN): <strong>{{account.iban}}</strong><br/>
  Currency: {{document.currency}}
</p>

<h2>Itemized list of fees</h2>
<table style="width:100%;border-collapse:collapse;font-size:12px;" border="1" cellpadding="4">
<thead>
<tr><th>Fee name</th><th>Annex II category</th><th style="text-align:right;">Amount</th></tr>
</thead>
<tbody>
{{#each document.fees}}
<tr>
<td>{{this.name}}</td>
<td>{{this.category}}</td>
<td style="text-align:right;">{{this.amount}} {{../document.currency}}</td>
</tr>
{{/each}}
</tbody>
</table>

<h2>Total fees charged</h2>
<p>Total fees charged for the year {{document.year}}: <strong>{{document.totalFees}} {{document.currency}}</strong></p>

{{#if document.interestRate}}
<h2>Interest</h2>
<p>Interest rate applied to the account during the period: <strong>{{document.interestRate}}% p.a.</strong></p>
{{/if}}

<h2>Issue</h2>
<p>Issue date: {{document.issueDate}}</p>

<hr/>
<p style="font-size:11px;color:#64748b;">
  This document is the annual statement of fees within the meaning of Article 5 and Annex II of
  Directive (EU) 2014/92 on the comparability of fees related to payment accounts, payment
  account switching and access to payment accounts with basic features (PAD).
</p>
"""

// ---------------------------------------------------------------------------------------------
// Potvrzení o provedení platby / Payment Confirmation (ADR-0248 §3, PSD2 Art. 45/48)
// ---------------------------------------------------------------------------------------------

private const val PAYMENT_CONFIRMATION_CS_BODY = """$LETTERHEAD_CS
<h1>Potvrzení o provedení platby</h1>

<h2>Identifikace platby</h2>
<p>
  Reference platby: <strong>{{payment.reference}}</strong><br/>
  Jedinečný identifikátor transakce (End-to-End ID): <strong>{{payment.endToEndId}}</strong>
</p>

<h2>Datum a čas provedení</h2>
<p>
  Datum a čas provedení: {{payment.executedAt}}<br/>
  {{#if payment.settledAt}}Datum a čas zúčtování: {{payment.settledAt}}{{/if}}
</p>

<h2>Částka platby</h2>
<p style="font-size:16px;"><strong>{{payment.amount}} {{payment.currency}}</strong></p>

<h2>Plátce a příjemce</h2>
<p>
  IBAN plátce: {{payment.payerIban}}<br/>
  IBAN příjemce: {{payment.payeeIban}}<br/>
  Název příjemce: {{payment.payeeName}}
</p>

<h2>Informace pro příjemce</h2>
<p>{{payment.remittanceInfo}}</p>

<h2>Stav platby</h2>
<p><strong>{{payment.status}}</strong></p>

{{#if payment.scaEvidenceRef}}
<h2>Ověření platby</h2>
<p>
  Reference na důkaz o silném ověření klienta (SCA), kterým byla platba autorizována:
  {{payment.scaEvidenceRef}}
</p>
{{/if}}

<hr/>
<p style="font-size:11px;color:#64748b;">
  Toto potvrzení je informací poskytovanou plátci/příjemci po provedení platební transakce ve
  smyslu čl. 45 a 48 směrnice Evropského parlamentu a Rady (EU) 2015/2366 (PSD2).
</p>
"""

private const val PAYMENT_CONFIRMATION_EN_BODY = """$LETTERHEAD_EN
<h1>Payment Confirmation</h1>

<h2>Payment identification</h2>
<p>
  Payment reference: <strong>{{payment.reference}}</strong><br/>
  Unique end-to-end identifier: <strong>{{payment.endToEndId}}</strong>
</p>

<h2>Execution date and time</h2>
<p>
  Executed on: {{payment.executedAt}}<br/>
  {{#if payment.settledAt}}Settled on: {{payment.settledAt}}{{/if}}
</p>

<h2>Payment amount</h2>
<p style="font-size:16px;"><strong>{{payment.amount}} {{payment.currency}}</strong></p>

<h2>Payer and payee</h2>
<p>
  Payer IBAN: {{payment.payerIban}}<br/>
  Payee IBAN: {{payment.payeeIban}}<br/>
  Payee name: {{payment.payeeName}}
</p>

<h2>Remittance information</h2>
<p>{{payment.remittanceInfo}}</p>

<h2>Payment status</h2>
<p><strong>{{payment.status}}</strong></p>

{{#if payment.scaEvidenceRef}}
<h2>Payment authorisation</h2>
<p>
  Reference to the Strong Customer Authentication (SCA) evidence that authorised this payment:
  {{payment.scaEvidenceRef}}
</p>
{{/if}}

<hr/>
<p style="font-size:11px;color:#64748b;">
  This confirmation is information provided to the payer/payee following execution of a payment
  transaction within the meaning of Articles 45 and 48 of Directive (EU) 2015/2366 (PSD2).
</p>
"""
