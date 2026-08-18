// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'use client'

import Link from 'next/link'
import { ShieldCheck, ScrollText, Scale, Lock, Landmark, ListOrdered, Info } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

const ACCENT = '#6366f1'
const INK = 'var(--text-primary)'
const SUB = 'var(--text-secondary)'

type Bilingual = [string, string]
type Verdict = 'pass' | 'conditional' | 'risk' | 'missing'

const VERDICT_STYLE: Record<Verdict, { color: string; bg: string; border: string; cs: string; en: string }> = {
  pass: { color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', cs: 'projde', en: 'pass' },
  conditional: { color: '#d97706', bg: '#fffbeb', border: '#fcd34d', cs: 'projde s podmínkami', en: 'pass with conditions' },
  risk: { color: '#dc2626', bg: '#fef2f2', border: '#fecaca', cs: 'může změnit návrh', en: 'may change the design' },
  missing: { color: '#64748b', bg: '#f8fafc', border: '#cbd5e1', cs: 'nezačato', en: 'not started' },
}

type Area = { id: string; Icon: React.ElementType; title: Bilingual; verdict: Verdict; summary: Bilingual; items: { label: Bilingual; note: Bilingual }[] }

const AREAS: Area[] = [
  {
    id: 'security',
    Icon: ShieldCheck,
    title: ['Bezpečnostní review banky', 'Bank security review'],
    verdict: 'conditional',
    summary: [
      'Nic strukturálního nechybí. Podmínkou jsou čtyři brány, které si threat model §8 ukládá sám — a security oddělení by si je vyžádalo tak jako tak.',
      'Nothing structural is missing. The conditions are the four gates the threat model §8 sets for itself — which a security function would demand anyway.',
    ],
    items: [
      { label: ['Nezávislé krypto review', 'Independent cryptographic review'], note: ['Nikdo mimo tým neprověřil práci se seedem, životnost klíče ani porovnávání.', 'Nobody outside the team has examined seed handling, key lifetime or comparison behaviour.'] },
      { label: ['Fuzzing CBOR dekodéru', 'CBOR decoder fuzzing'], note: ['Nový money-path kód. Striktnost je pokrytá unit testy, fuzzer nikdy neběžel.', 'New money-path code. Strictness is unit-tested; a fuzzer has never run against it.'] },
      { label: ['Dvě souběžné CBCentralManager instance', 'Two concurrent CBCentralManager instances'], note: ['Aplikace je nikdy neběžela současně. Očekáváme, že to jde — a „očekáváme" odstraní jen test na dvou zařízeních.', 'The app has never run two at once. It is expected to work, and only a two-device lab run removes the word “expected”.'] },
      { label: ['Účinnost varování', 'Warning efficacy'], note: ['Sami jsme napsali, že varování, které nikdo nečte, není kontrola. Netestováno s uživateli.', 'We wrote that a warning nobody reads is not a control. It has not been tested with users.'] },
    ],
  },
  {
    id: 'gdpr',
    Icon: Lock,
    title: ['Ochrana osobních údajů', 'Data protection'],
    verdict: 'risk',
    summary: [
      'Nejrizikovější oblast a jediná, která pravděpodobně změní samotný návrh. Advert vysílá křestní jméno komukoli v dosahu, včetně lidí bez jakéhokoli vztahu k platbě.',
      'The highest-risk area, and the one most likely to change the design itself. The advert broadcasts a first name to everyone in range, including people with no relationship to the payment.',
    ],
    items: [
      { label: ['Právní základ nepokrývá kolemjdoucí', 'Lawful basis does not cover bystanders'], note: ['Plnění smlouvy pokrývá plátce a příjemce. Kolemjdoucí nejsou stranou ničeho.', 'Performance of a contract covers payer and payee. Bystanders are party to nothing.'] },
      { label: ['DPIA vyžadována', 'DPIA required'], note: ['Pozice je obhajitelná, ale nejspíš dopadne na „jen iniciály" jako výchozí stav, ne jako opt-out. Počítat s tím.', 'The position is defensible but will plausibly land on initials-only as the default rather than an opt-out. Plan for it.'] },
      { label: ['European Accessibility Act', 'European Accessibility Act'], note: ['Od června 2025 na bankovní služby. Varování a SAS musí fungovat pod VoiceOver/TalkBack. Neřešeno.', 'Applies to banking services since June 2025. Warnings and SAS must work under VoiceOver/TalkBack. Unaddressed.'] },
    ],
  },
  {
    id: 'payments',
    Icon: Scale,
    title: ['Platební compliance (PSD2, AML, ČNB)', 'Payments compliance (PSD2, AML, ČNB)'],
    verdict: 'pass',
    summary: [
      'Nejsilnější místo návrhu: QRlessPay nikdy nehýbe penězi. Přenáší návrh platby, samotná platba jde existujícím railem s nezměněnou SCA.',
      'The strongest part of the design: QRlessPay never moves money. It transfers a proposal; the payment itself runs on the existing rail with unchanged SCA.',
    ],
    items: [
      { label: ['Nová platební služba dle PSD2?', 'A new payment service under PSD2?'], note: ['Nejspíš ne. Závěr ale musí potvrdit právník — není inženýrský.', 'Most likely not. That conclusion is counsel’s to make, not engineering’s.'] },
      { label: ['SCA a dynamic linking', 'SCA and dynamic linking'], note: ['Beze změny. Částka i příjemce pocházejí z podepsaného bundle, který si plátce ověřil.', 'Unchanged. Amount and payee come from the signed bundle the payer verified.'] },
      { label: ['Odpovědnost za chybnou platbu', 'Misdirected-payment liability'], note: ['Rozhodnutí „varovat, ne blokovat" je vědomé přijetí rizika. Dnes žije jen v pull requestu, potřebuje byznys sign-off.', 'The decision to warn rather than block is a deliberate risk acceptance. It lives only in a pull request and needs business sign-off.'] },
    ],
  },
  {
    id: 'legal',
    Icon: Landmark,
    title: ['Právo, IP a standardizace', 'Legal, IP and standardisation'],
    verdict: 'missing',
    summary: [
      'Technicky je architektura na standard stavěná dobře. Celá právně-institucionální vrstva ale chybí — nic z toho není těžké, všechno má dlouhé lhůty a nic nezačalo.',
      'Technically the architecture suits a standard well. The entire legal and institutional layer is absent — none of it is hard, all of it has long lead times, and none has started.',
    ],
    items: [
      { label: ['Bluetooth SIG identifikátory', 'Bluetooth SIG identifiers'], note: ['UUID jsou placeholdery a 16bitový alias je fakticky obsazený bez přidělení. Pro pilot jedno, pro cizí implementátory povinné.', 'The UUIDs are placeholders and the 16-bit alias is squatted. Immaterial for a pilot, mandatory before third parties implement.'] },
      { label: ['Patentová rešerše a IPR politika', 'Patent search and IPR policy'], note: ['Proximity payments je hustě patentované pole. Bez rešerše a závazku (royalty-free nebo FRAND) to žádná banka nepřijme.', 'Proximity payments is densely patented. No bank adopts it without a search and a royalty-free or FRAND commitment.'] },
      { label: ['Ochranná známka', 'Trademark'], note: ['„QRlessPay" není registrovaná. Podat před publikací, jinak ji zaregistruje někdo jiný.', '“QRlessPay” is unregistered. File before publication, or someone else will.'] },
      { label: ['Neutrální governance', 'Neutral governance'], note: ['EPC ani ČBA nepřijmou repozitář jedné banky. Otevřená otázka: self-certifikace, nebo certifikační autorita?', 'Neither EPC nor ČBA adopts one bank’s repository. Open question: self-declared conformance, or a certifying body?'] },
    ],
  },
]

const SEQUENCE: Bilingual[] = [
  ['DPIA a nezávislé krypto review — obojí může změnit návrh, všechno ostatní je pak levnější.', 'DPIA and independent cryptographic review — either can change the design; everything else is cheaper afterwards.'],
  ['Test na dvou zařízeních a fuzzing v CI — prerekvizity §8, nikoho externího nepotřebují.', 'Two-device lab run and fuzzing in CI — §8 prerequisites, neither needs anyone external.'],
  ['Rozhodnutí: SAS default, hodnotový práh, jen iniciály, přístupnost.', 'Decisions: SAS default, high-value threshold, initials-only, accessibility.'],
  ['Bluetooth SIG a ochranná známka — levné, dlouhé lhůty, začít brzy.', 'Bluetooth SIG and trademark — cheap, long lead times, start early.'],
  ['ADR-0030 druhé schválení, pak pilot.', 'ADR-0030 second approval, then pilot.'],
  ['Patenty a governance — až s prvním vážným zájemcem; dělat to spekulativně znamená platit za opci, kterou nikdo nevyužil.', 'Patents and governance — only with a genuinely interested second institution; doing it speculatively buys an option nobody has taken up.'],
]

export default function QrlessPayReadinessPage() {
  const { t } = useLanguage()

  return (
    <div>
      <DocsPageHeader
        crumbs={<>
          <span>OpenBank</span><span className="breadcrumb-sep">/</span>
          <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
          <Link href="/docs/qrlesspay" style={{ color: 'inherit' }}>QRlessPay</Link>
          <span className="breadcrumb-sep">/</span>
          <span className="breadcrumb-current">{t('Připravenost', 'Readiness')}</span>
        </>}
        title={t('QRlessPay — posouzení připravenosti', 'QRlessPay — readiness assessment')}
        subtitle={t(
            'Co se zeptá bezpečnost, compliance a právníci — včetně otázek, které bychom raději neslyšeli. Sebehodnocení implementačního týmu, ne schválení.',
            'What security, compliance and counsel will each ask — including the questions we would rather they did not. A self-assessment by the implementing team, not an approval.',
          )}
        icon={<ScrollText aria-hidden="true" size={18} style={{ color: ACCENT }} />}
      />

      <div className="card" style={{ padding: 14, marginBottom: 16, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', display: 'flex', gap: 10 }}>
        <Info size={16} style={{ color: ACCENT, flexShrink: 0, marginTop: 1 }} />
        <div style={{ fontSize: 13, color: INK, lineHeight: 1.55 }}>
          {t(
            'Tento dokument není schválení a nesmí se jako schválení citovat. Schválením jsou ta review, která doporučuje — tady se jen tvrdí, že jsou to ta správná a že se za nimi nic neschovává.',
            'This document is not an approval and must not be cited as one. The approval is the set of reviews it recommends; this only argues that they are the right ones and that nothing else is hiding behind them.',
          )}
        </div>
      </div>

      {AREAS.map((area) => {
        const v = VERDICT_STYLE[area.verdict]
        return (
          <div key={area.id} className="card" style={{ padding: 20, marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
              <h2 style={{ fontSize: 15, fontWeight: 700, color: INK, margin: 0, display: 'flex', alignItems: 'center', gap: 7 }}>
                <area.Icon size={15} style={{ color: ACCENT }} /> {t(area.title[0], area.title[1])}
              </h2>
              <span style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em', color: v.color, background: v.bg, border: `1px solid ${v.border}`, padding: '2px 9px', borderRadius: 20 }}>
                {t(v.cs, v.en)}
              </span>
            </div>
            <p style={{ fontSize: 12.5, color: SUB, margin: '6px 0 14px', lineHeight: 1.6 }}>
              {t(area.summary[0], area.summary[1])}
            </p>
            <div style={{ display: 'grid', gap: 8 }}>
              {area.items.map((item, i) => (
                <div key={i} style={{ borderLeft: `3px solid ${v.border}`, paddingLeft: 10 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 700, color: INK }}>{t(item.label[0], item.label[1])}</div>
                  <div style={{ fontSize: 12.5, color: SUB, lineHeight: 1.5 }}>{t(item.note[0], item.note[1])}</div>
                </div>
              ))}
            </div>
          </div>
        )
      })}

      <div className="card" style={{ padding: 20, marginBottom: 16 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, color: INK, margin: 0, display: 'flex', alignItems: 'center', gap: 7 }}>
          <ListOrdered size={15} style={{ color: ACCENT }} /> {t('Doporučené pořadí', 'Recommended sequence')}
        </h2>
        <p style={{ fontSize: 12.5, color: SUB, margin: '4px 0 14px' }}>
          {t('Seřazeno tak, aby práce, která může změnit návrh, proběhla dřív než ta, která na něm staví.', 'Ordered so that work which can change the design happens before work that builds on it.')}
        </p>
        <ol style={{ margin: 0, paddingLeft: 18, color: SUB, fontSize: 13, lineHeight: 1.75 }}>
          {SEQUENCE.map((step, i) => <li key={i}>{t(step[0], step[1])}</li>)}
        </ol>
      </div>

      <div className="card" style={{ padding: 20 }}>
        <h2 style={{ fontSize: 15, fontWeight: 700, color: INK, margin: '0 0 12px' }}>{t('Dokumenty', 'Documents')}</h2>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/compliance/qrlesspay-readiness.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Plné posouzení (Markdown)', 'Full assessment (Markdown)')}</a>
          <Link href="/docs/qrlesspay" style={linkBtn}>{t('QRlessPay — přehled', 'QRlessPay — overview')}</Link>
          <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/threat-models/qrlesspay.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Threat model', 'Threat model')}</a>
          <a href="https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-sdk.md" target="_blank" rel="noopener noreferrer" style={linkBtn}>{t('Návrh SDK', 'SDK proposal')}</a>
        </div>
      </div>
    </div>
  )
}

const linkBtn: React.CSSProperties = { fontSize: 12.5, fontWeight: 600, color: ACCENT, background: 'var(--accent-bg)', border: '1px solid var(--accent-border)', padding: '6px 12px', borderRadius: 8, textDecoration: 'none' }
