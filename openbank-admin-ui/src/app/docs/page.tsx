// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import Link from 'next/link'
import { GitBranch, BookOpen, Network, FileCode, Shield, ShieldAlert, Cloud, ScrollText, ShieldCheck, LayoutGrid, Smartphone, Bluetooth, Fingerprint, FileSignature, Radar, Scale } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'

// Each section's title/desc is a [cs, en] tuple, spread into t(...) at render.
const sections: {
  href: string
  icon: React.ReactNode
  title: [string, string]
  desc: [string, string]
  badge: string
  color: string
}[] = [
  {
    href: '/docs/identity-dedup',
    icon: <Fingerprint size={22} />,
    title: ['Identita a deduplikace', 'Identity & Deduplication'],
    desc: [
      'Jak je moderně postavená jednotná identita klienta: principy, privacy-preserving blind index, tříúrovňový resolver a konkrétní ukázka deduplikace (ADR-0072, ADR-0094)',
      'How unified customer identity is built the modern way: principles, privacy-preserving blind index, a three-tier resolver and a worked deduplication example (ADR-0072, ADR-0094)',
    ],
    badge: 'ADR-0072 · 0094',
    color: '#6366f1',
  },
  {
    href: '/docs/customer-app',
    icon: <Smartphone size={22} />,
    title: ['Aplikace / Customer App', 'Customer App'],
    desc: [
      'Zákaznická aplikace (KMP/Compose): jak je tvořena, integrována a zabezpečena — plán vs realita pohledem governance, technologií a bezpečnosti (ADR-0074)',
      'Customer application (KMP/Compose): how it is built, integrated and secured — plan vs reality through the lens of governance, technology and security (ADR-0074)',
    ],
    badge: 'ADR-0074',
    color: '#7c3aed',
  },
  {
    href: '/docs/sensors',
    icon: <Radar size={22} />,
    title: ['Senzory', 'Sensors'],
    desc: [
      'Které signály zařízení zákaznická aplikace čte a k čemu: pohyb a gesta, blízkost, prostředí, soukromí a zkratky — u každého use-case, vyvolání, místo v aplikaci a nastavení (ADR-0074)',
      'Which device signals the customer app reads and what for: motion and gestures, proximity, environment, privacy and shortcuts — each with its use case, invocation, place in the app and setting (ADR-0074)',
    ],
    badge: 'ADR-0074 · 0095',
    color: '#0891b2',
  },
  {
    href: '/docs/qrlesspay',
    icon: <Bluetooth size={22} />,
    title: ['QRlessPay', 'QRlessPay'],
    desc: [
      'Otevřený BLE standard pro platbu poblíž bez QR: sekvence iOS→Android / banka A→B, bezpečnostní vrstvy a srovnání s QR (ADR-0095)',
      'Open BLE proximity-pay standard without QR: iOS→Android / Bank A→B sequence, security layers and a comparison vs QR (ADR-0095)',
    ],
    badge: 'ADR-0095',
    color: '#6366f1',
  },
  {
    href: '/docs/document-management',
    icon: <FileSignature size={22} />,
    title: ['Správa dokumentů', 'Document Management'],
    desc: [
      'Šablony, generování PDF a e-podpisová ceremonie: proč je to vlastní ohraničená doména a jak tok jede od editoru po podepsanou událost (ADR-0161, ADR-0162)',
      'Templating, PDF generation and the e-signature ceremony: why it is its own bounded context and how the flow runs from the editor to the signed event (ADR-0161, ADR-0162)',
    ],
    badge: 'ADR-0161 · 0162',
    color: '#6366f1',
  },
  {
    href: '/docs/cloud-architecture',
    icon: <Cloud size={22} />,
    title: ['Cloud Architecture', 'Cloud Architecture'],
    desc: [
      'AWS architektura dle ADR-0027 (EKS, substrát, OSS stack) se status overlay: co je live / partial / planned',
      'AWS architecture per ADR-0027 (EKS, substrate, OSS stack) with status overlay: what is live / partial / planned',
    ],
    badge: 'ADR-0027',
    color: '#0ea5e9',
  },
  {
    href: '/docs/service-map',
    icon: <Network size={22} />,
    title: ['Service Map', 'Service Map'],
    desc: [
      'Interaktivní mapa všech microservices, jejich závislostí a komunikačních kanálů',
      'Interactive map of all microservices, their dependencies and communication channels',
    ],
    badge: 'Live',
    color: '#2563eb',
  },
  {
    href: '/docs/bpmn',
    icon: <GitBranch size={22} />,
    title: ['Business Processes (BPMN)', 'Business Processes (BPMN)'],
    desc: [
      'BPMN 2.0 diagramy klíčových procesů: Account Opening, SEPA, KYC, AML Screening + 8 dalších',
      'BPMN 2.0 diagrams of key processes: Account Opening, SEPA, KYC, AML Screening + 8 more',
    ],
    badge: '12 procesů',
    color: '#7c3aed',
  },
  {
    href: '/docs/api',
    icon: <FileCode size={22} />,
    title: ['API Katalog', 'API Catalog'],
    desc: [
      'Swagger/OpenAPI dokumentace všech 33 services s live proklikem na Swagger UI',
      'Swagger/OpenAPI documentation of all 33 services with live click-through to Swagger UI',
    ],
    badge: '33 services',
    color: '#059669',
  },
  {
    href: '/docs/compliance',
    icon: <Shield size={22} />,
    title: ['Compliance Report', 'Compliance Report'],
    desc: [
      'EBA/CNB/PSD2/GDPR compliance status, audit trail, data retention přehled',
      'EBA/CNB/PSD2/GDPR compliance status, audit trail, data retention overview',
    ],
    badge: 'EBA + CNB',
    color: '#dc2626',
  },
  {
    href: '/docs/bcp',
    icon: <ShieldAlert size={22} />,
    title: ['Business Continuity Plan', 'Business Continuity Plan'],
    desc: [
      'Prioritizovaný plán obnovy, startup tiers, compliance gate, RTO/RPO — DORA Art. 11-12',
      'Prioritised recovery plan, startup tiers, compliance gate, RTO/RPO — DORA Art. 11-12',
    ],
    badge: 'DORA + CNB',
    color: '#7c3aed',
  },
  {
    href: '/docs/adr',
    icon: <ScrollText size={22} />,
    title: ['Architecture Decisions (ADR)', 'Architecture Decisions (ADR)'],
    desc: [
      'Registr všech architektonických rozhodnutí — kontext, rozhodnutí a důsledky, seskupené podle stavu',
      'Registry of all architecture decisions — context, decision and consequences, grouped by status',
    ],
    badge: 'Governance',
    color: '#0891b2',
  },
  {
    href: '/docs/threat-models',
    icon: <ShieldAlert size={22} />,
    title: ['Threat Models', 'Threat Models'],
    desc: [
      'STRIDE threat modely služeb na peněžní cestě (ADR-0030) + přehled chybějícího pokrytí money-path',
      'STRIDE threat models of money-path services (ADR-0030) + overview of missing money-path coverage',
    ],
    badge: 'ADR-0030',
    color: '#dc2626',
  },
  {
    href: '/docs/zero-trust',
    icon: <ShieldCheck size={22} />,
    title: ['Zero-Trust Security Map', 'Zero-Trust Security Map'],
    desc: [
      'Obrana do hloubky odvozená z reálných manifestů: mTLS, NetworkPolicy default-deny, JWT, L7 authz a supply-chain admission',
      'Defense in depth derived from real manifests: mTLS, NetworkPolicy default-deny, JWT, L7 authz and supply-chain admission',
    ],
    badge: 'NIS2 + DORA',
    color: '#16a34a',
  },
  {
    href: '/docs/control-tower',
    icon: <LayoutGrid size={22} />,
    title: ['Compliance Control Tower', 'Compliance Control Tower'],
    desc: [
      'Matice regulace → kontrola → důkaz (DORA/NIS2/PSD2/GDPR/AMLD/EBA). Kontroly s odznakem LIVE čtou stav z reálných manifestů',
      'Regulation → control → evidence matrix (DORA/NIS2/PSD2/GDPR/AMLD/EBA). Controls with the LIVE badge read state from real manifests',
    ],
    badge: 'Governance',
    color: '#0891b2',
  },
  {
    href: '/security/excellence',
    icon: <Scale size={22} />,
    title: ['Security Excellence', 'Security Excellence'],
    desc: [
      'Jediný souhrnný pohled na bezpečnost ekosystému: skóre excelence nad 8 doménami (posture, DORA incidenty, fraud, AML, sankce, maker-checker, audit, identita) — runbook docs/runbooks/0016',
      'A single ecosystem-wide security view: excellence score over 8 domains (posture, DORA incidents, fraud, AML, sanctions, maker-checker, audit, identity) — runbook docs/runbooks/0016',
    ],
    badge: 'LIVE',
    color: '#059669',
  },
]

export default function DocsPage() {
  const { t } = useLanguage()
  return (
    <div>
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span>
            <span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Dokumentace', 'Documentation')}</span>
          </>}
        title={t('Dokumentační portál OpenBank', 'OpenBank Documentation Portal')}
        subtitle={t('Architektura, business procesy, API dokumentace a compliance přehled', 'Architecture, business processes, API documentation and compliance overview')}
        icon={<BookOpen aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: '16px' }}>
        {sections.map(s => (
          <Link key={s.href} href={s.href} style={{ textDecoration: 'none' }}>
            <div className="card" style={{
              padding: '24px',
              cursor: 'pointer',
              transition: 'transform 0.12s, box-shadow 0.12s',
              borderTop: `3px solid ${s.color}`,
            }}
              onMouseEnter={e => {
                (e.currentTarget as HTMLElement).style.transform = 'translateY(-2px)'
                ;(e.currentTarget as HTMLElement).style.boxShadow = '0 8px 24px rgba(0,0,0,0.08)'
              }}
              onMouseLeave={e => {
                (e.currentTarget as HTMLElement).style.transform = ''
                ;(e.currentTarget as HTMLElement).style.boxShadow = ''
              }}
            >
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '12px' }}>
                <div style={{ color: s.color }}>{s.icon}</div>
                <span style={{
                  fontSize: '11px', fontWeight: 600, padding: '3px 8px',
                  background: `${s.color}15`, color: s.color,
                  borderRadius: '20px', border: `1px solid ${s.color}30`,
                }}>{s.badge}</span>
              </div>
              <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '6px' }}>{t(...s.title)}</div>
              <div style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>{t(...s.desc)}</div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  )
}
