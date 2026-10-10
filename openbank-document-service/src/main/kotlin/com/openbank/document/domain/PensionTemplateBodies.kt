// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain

// ---------------------------------------------------------------------------------------------
// Pension (ADR-0334) documents rendered by openbank-pension-service through POST /render (#12392).
// Bodies are copied VERBATIM from openbank-pension-service/src/main/resources/document-templates/,
// where a test pins their variables to what the pension adapters send. They are DRAFT wording:
// every body carries the LEGAL-REVIEW-REQUIRED marker and renders a visible DRAFT banner while the
// caller passes legalReviewRequired=true. Replacing them after legal review is a NEW seed version
// (new fixed id), never an in-place edit — published templates are immutable.
// ---------------------------------------------------------------------------------------------

internal const val PENSION_ANNUAL_STATEMENT_CS_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">NÁVRH – text vyžaduje právní kontrolu a nesmí být použit vůči klientům.</p>{{/if}}
<h1>Výpis z penzijního účtu za rok {{year}}</h1>
<p>Smlouva: {{contractReference}}</p>
<table>
<tr><td>Příspěvky účastníka</td><td>{{participantContributions}} {{currency}}</td></tr>
<tr><td>Příspěvky zaměstnavatele</td><td>{{employerContributions}} {{currency}}</td></tr>
<tr><td>Státní příspěvky</td><td>{{stateIncentives}} {{currency}}</td></tr>
<tr><td>Převody od jiného poskytovatele</td><td>{{transferIn}} {{currency}}</td></tr>
<tr><td>Hodnota prostředků k {{valueAsOf}}</td><td>{{value}} {{currency}}</td></tr>
</table>
"""

internal const val PENSION_ANNUAL_STATEMENT_EN_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">DRAFT – this wording requires legal review and must not be used with clients.</p>{{/if}}
<h1>Pension account statement for {{year}}</h1>
<p>Contract: {{contractReference}}</p>
<table>
<tr><td>Participant contributions</td><td>{{participantContributions}} {{currency}}</td></tr>
<tr><td>Employer contributions</td><td>{{employerContributions}} {{currency}}</td></tr>
<tr><td>State contributions</td><td>{{stateIncentives}} {{currency}}</td></tr>
<tr><td>Transfers in from another provider</td><td>{{transferIn}} {{currency}}</td></tr>
<tr><td>Value of the account as of {{valueAsOf}}</td><td>{{value}} {{currency}}</td></tr>
</table>
"""

internal const val PENSION_DIP_KID_CS_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">NÁVRH – text vyžaduje právní kontrolu a nesmí být použit vůči klientům.</p>{{/if}}
<h1>Sdělení klíčových informací (KID) – dlouhodobý investiční produkt</h1>
<p>Účel: tento dokument poskytuje klíčové informace o investičním produktu. Nejde o propagační materiál.</p>
<p>Číslo žádosti: {{applicationId}} · Produkt: {{productLine}} · Typ dokumentu: {{documentType}}</p>
<h2>O jaký produkt se jedná</h2>
<p>Dlouhodobý investiční produkt se strategií <strong>{{strategyCode}}</strong>.</p>
<h2>Jaká podstupuji rizika a jakého výnosu bych mohl/a dosáhnout</h2>
<p>Souhrnný ukazatel rizika, scénáře výkonnosti a náklady stanoví nástroje ve strategii podle nařízení PRIIPs.</p>
<h2>Jak dlouho bych měl/a investici držet</h2>
<p>Doporučená doba držení je do 60 let věku, nejméně 10 let; dřívější výběr ruší daňové zvýhodnění.</p>
"""

internal const val PENSION_DIP_KID_EN_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">DRAFT – this wording requires legal review and must not be used with clients.</p>{{/if}}
<h1>Key Information Document (KID) – long-term investment product</h1>
<p>Purpose: this document provides key information about an investment product. It is not marketing material.</p>
<p>Application: {{applicationId}} · Product: {{productLine}} · Document type: {{documentType}}</p>
<h2>What is this product</h2>
<p>A long-term investment product with the <strong>{{strategyCode}}</strong> strategy.</p>
<h2>What are the risks and what could I get in return</h2>
<p>The summary risk indicator, performance scenarios and costs follow the instruments in the strategy under PRIIPs.</p>
<h2>How long should I hold it</h2>
<p>The recommended holding period is until age 60 and at least 10 years; an earlier withdrawal forfeits the tax relief.</p>
"""

internal const val PENSION_DPS_KEY_INFORMATION_CS_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">NÁVRH – text vyžaduje právní kontrolu a nesmí být použit vůči klientům.</p>{{/if}}
<h1>Sdělení klíčových informací před uzavřením smlouvy o doplňkovém penzijním spoření</h1>
<p>Číslo žádosti: {{applicationId}} · Produkt: {{productLine}} · Typ dokumentu: {{documentType}}</p>
<h2>Zvolená investiční strategie</h2>
<p>Strategie: <strong>{{strategyCode}}</strong></p>
<h2>Co je doplňkové penzijní spoření</h2>
<p>Spoření na stáří ve fondech penzijní společnosti se státním příspěvkem a daňovým zvýhodněním.
Hodnota investice může klesat i stoupat; návratnost původně investované částky není zaručena.</p>
<h2>Náklady, rizika a odstoupení</h2>
<p>Úplné informace o poplatcích, rizicích a lhůtě pro odstoupení obsahuje statut fondu a smlouva.</p>
<p>Tento dokument podepisujete silným ověřením; podpis je vázán na otisk tohoto dokumentu.</p>
"""

internal const val PENSION_DPS_KEY_INFORMATION_EN_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">DRAFT – this wording requires legal review and must not be used with clients.</p>{{/if}}
<h1>Pre-contractual key information for a supplementary pension savings contract</h1>
<p>Application: {{applicationId}} · Product: {{productLine}} · Document type: {{documentType}}</p>
<h2>Chosen investment strategy</h2>
<p>Strategy: <strong>{{strategyCode}}</strong></p>
<h2>What supplementary pension savings are</h2>
<p>Retirement savings in the pension company's funds, with a state contribution and tax relief.
The value of the investment can fall as well as rise; the amount invested is not guaranteed.</p>
<h2>Costs, risks and withdrawal</h2>
<p>Full information on fees, risks and the withdrawal period is in the fund statute and the contract.</p>
<p>You sign this document with strong customer authentication; the signature is bound to this document's hash.</p>
"""

internal const val PENSION_TAX_CERTIFICATE_CS_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">NÁVRH – text vyžaduje právní kontrolu a nesmí být použit vůči klientům.</p>{{/if}}
<h1>Potvrzení o zaplacených příspěvcích pro účely daně z příjmů za rok {{taxYear}}</h1>
<p>Smlouva: {{contractReference}}</p>
<table>
<tr><td>Příspěvky účastníka</td><td>{{participantContributions}} {{currency}}</td></tr>
<tr><td>Z toho odečitatelná částka</td><td>{{deductibleAmount}} {{currency}}</td></tr>
<tr><td>Příspěvky zaměstnavatele</td><td>{{employerContributions}} {{currency}}</td></tr>
<tr><td>– osvobozené</td><td>{{employerExempt}} {{currency}}</td></tr>
<tr><td>– zdanitelné</td><td>{{employerTaxable}} {{currency}}</td></tr>
<tr><td>Státní příspěvky</td><td>{{stateIncentives}} {{currency}}</td></tr>
</table>
"""

internal const val PENSION_TAX_CERTIFICATE_EN_BODY = """{{!-- SPDX-License-Identifier: Apache-2.0 --}}
{{!-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0. --}}
<!-- LEGAL-REVIEW-REQUIRED: draft wording, not approved by legal (ADR-0334, #12379) -->
{{#if legalReviewRequired}}<p class="draft">DRAFT – this wording requires legal review and must not be used with clients.</p>{{/if}}
<h1>Certificate of contributions paid, for income tax purposes, year {{taxYear}}</h1>
<p>Contract: {{contractReference}}</p>
<table>
<tr><td>Participant contributions</td><td>{{participantContributions}} {{currency}}</td></tr>
<tr><td>Of which deductible</td><td>{{deductibleAmount}} {{currency}}</td></tr>
<tr><td>Employer contributions</td><td>{{employerContributions}} {{currency}}</td></tr>
<tr><td>– exempt</td><td>{{employerExempt}} {{currency}}</td></tr>
<tr><td>– taxable</td><td>{{employerTaxable}} {{currency}}</td></tr>
<tr><td>State contributions</td><td>{{stateIncentives}} {{currency}}</td></tr>
</table>
"""
