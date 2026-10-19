// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useCallback, useEffect, useState } from 'react'
import { Shield, Info, RefreshCw } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'

// Bilingual string tuple: [Czech, English] — spread into t(cs, en) at render.
type Bilingual = [string, string]

type ComplianceItem = { req: Bilingual; status: string; note: Bilingual }
type ComplianceArea = { id: string; title: Bilingual; authority: Bilingual; status: string; items: ComplianceItem[] }
type ControlSummary = {
  id: string
  title: string
  evidence: string
  evidenceSource?: string
  status: string
  declaredStatus: string
  derivedFrom?: string
  signalState?: 'true' | 'false' | 'unknown'
  live?: boolean
}
type ControlSnapshot = {
  status: 'available' | 'unavailable'
  controls: ControlSummary[]
  posture: { source: string; collectedAt: string | null } | null
}

// Only exact control references from the authoritative catalogue are linked.
// A link documents scope; it does not establish regulatory compliance.
const REQUIREMENT_CONTROL_REFERENCES: Record<string, string[]> = {
  'Sanctions list screening': ['sanctions-screening'],
  'Immutable audit log': ['audit-trail'],
}

const COMPLIANCE_AREAS: ComplianceArea[] = [
  {
    id: 'psd2',
    title: ['PSD2 / RTS on SCA', 'PSD2 / RTS on SCA'],
    authority: ['EBA + ECB', 'EBA + ECB'],
    status: 'unknown',
    items: [
      { req: ['SCA pro platby nad 30 EUR', 'SCA for payments over 30 EUR'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Správa souhlasů (AIS/PIS/CBPII)', 'Consent management (AIS/PIS/CBPII)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Registrace a validace TPP', 'TPP registration and validation'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Berlin Group NextGenPSD2 API', 'Berlin Group NextGenPSD2 API'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Redirect URI v záznamu souhlasu', 'Redirect URI in the consent record'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Charge bearer SLEV pro SEPA', 'Charge bearer SLEV for SEPA'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['End-to-end ID v SEPA platbách', 'End-to-end ID in SEPA payments'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'aml',
    title: ['AML / 5AMLD / 6AMLD', 'AML / 5AMLD / 6AMLD'],
    authority: ['EBA + FAÚ (CZ)', 'EBA + FAÚ (CZ)'],
    status: 'unknown',
    items: [
      { req: ['PEP screening zákazníků', 'PEP screening of customers'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Screening sankčních seznamů', 'Sanctions list screening'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Sledování podání SAR', 'SAR filing tracking'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Eskalace na MLRO', 'MLRO escalation'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Dokumentace false positive', 'False positive documentation'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['AML screening transakcí', 'AML screening of transactions'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Rizikové hodnocení zákazníka', 'Customer risk rating'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'kyc',
    title: ['KYC / CDD / EDD', 'KYC / CDD / EDD'],
    authority: ['EBA AML Guidelines + FATF', 'EBA AML Guidelines + FATF'],
    status: 'unknown',
    items: [
      { req: ['Úroveň due diligence (SDD/CDD/EDD)', 'Due diligence level (SDD/CDD/EDD)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Prohlášení o původu prostředků', 'Source of funds declaration'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Účel obchodního vztahu', 'Business purpose'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Plánování periodické revize', 'Periodic review scheduling'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Vlastní prohlášení PEP', 'PEP self-declaration'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Sledování skutečného majitele', 'Beneficial owner tracking'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Očekávaný obrat', 'Expected turnover'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'gdpr',
    title: ['GDPR', 'GDPR'],
    authority: ['ÚOOÚ (CZ) + EDPB', 'ÚOOÚ (CZ) + EDPB'],
    status: 'unknown',
    items: [
      { req: ['Časové razítko explicitního souhlasu', 'Explicit consent timestamp'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Marketingový souhlas', 'Marketing consent'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Právo na výmaz (anonymizace a kaskáda)', 'Right to erasure (anonymise and cascade)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Politika uchovávání dat', 'Data retention policy'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Klasifikace citlivosti dat', 'Data sensitivity classification'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Smlouva o zpracování osobních údajů (GDPR)', 'GDPR Data Processing Agreement'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Právo na přístup (Art. 15)', 'Right of access (Art. 15)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Přenositelnost dat (Art. 20)', 'Data portability (Art. 20)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'eba-ict',
    title: ['EBA ICT Risk Guidelines', 'EBA ICT Risk Guidelines'],
    authority: ['EBA', 'EBA'],
    status: 'unknown',
    items: [
      { req: ['Neměnný audit log', 'Immutable audit log'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['10letá retence auditu', '10-year audit retention'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Označování bezpečnostních událostí', 'Security event flagging'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Actor ID v auditním záznamu', 'Actor ID in the audit record'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Logování IP adresy', 'IP address logging'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Sledování session', 'Session tracking'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Correlation ID', 'Correlation ID'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'cnb',
    title: ['Regulace ČNB (CZ)', 'CNB Regulation (CZ)'],
    authority: ['Česká národní banka', 'Czech National Bank'],
    status: 'unknown',
    items: [
      { req: ['Konstantní symbol (platební styk)', 'Constant symbol (payment system)'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Specifický symbol', 'Specific symbol'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Výkaznický kód ČNB', 'CNB reporting code'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Monitoring dormance', 'Dormancy monitoring'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Přidělení IBAN', 'IBAN allocation'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Regulatorní výkaznický kód', 'Regulatory reporting code'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Záznamy 10 let po uzavření', 'Records for 10 years after closure'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
  {
    id: 'fatca-crs',
    title: ['FATCA / CRS', 'FATCA / CRS'],
    authority: ['IRS + OECD', 'IRS + OECD'],
    status: 'unknown',
    items: [
      { req: ['FATCA status zákazníka', 'Customer FATCA status'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['CRS status zákazníka', 'Customer CRS status'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['Identifikace US person', 'US person identification'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
      { req: ['CRS reporting', 'CRS reporting'], status: 'unknown', note: ['Není přiřazen ověřený řídicí důkaz.', 'No verified control evidence is mapped.'] },
    ],
  },
]

const STATUS_CONFIG: Record<string, { label: Bilingual; color: string; bg: string; border: string; icon: React.ReactNode }> = {
  unknown: { label: ['Neověřeno', 'Unverified'], color: 'var(--text-tertiary)', bg: 'var(--surface-2)', border: 'var(--border)', icon: <Info size={14} /> },
}

const ITEM_STATUS = {
  unknown: { color: 'var(--text-tertiary)', icon: <Info size={12} /> },
}

export default function CompliancePage() {
  const { t } = useLanguage()
  const totalItems = COMPLIANCE_AREAS.flatMap(a => a.items).length
  const [snapshot, setSnapshot] = useState<ControlSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshError, setRefreshError] = useState(false)

  const refreshControls = useCallback(async () => {
    setLoading(true)
    setRefreshError(false)
    try {
      const response = await fetch('/api/compliance/controls', { cache: 'no-store' })
      if (!response.ok) throw new Error('control snapshot unavailable')
      setSnapshot(await response.json() as ControlSnapshot)
    } catch {
      setSnapshot(null)
      setRefreshError(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => { void refreshControls() }, 0)
    return () => window.clearTimeout(timer)
  }, [refreshControls])

  const controlsById = new Map((snapshot?.controls ?? []).map(control => [control.id, control]))

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Report compliance', 'Compliance Report')}</span>
          </>}
        title={t('Report compliance', 'Compliance Report')}
        subtitle={t('Oblasti přehledu: EBA · ČNB · PSD2 · GDPR · AML · FATCA/CRS', 'Report areas: EBA · CNB · PSD2 · GDPR · AML · FATCA/CRS')}
        icon={<Shield aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<PrintDocumentButton />}
      />

      {/* Scope summary: these editorial requirements are not assessed as legal compliance. */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: '12px', marginBottom: '24px' }}>
        {([
          { id: 'requirements', label: ['Požadavky v redakčním přehledu', 'Editorial requirements listed'] as Bilingual, value: totalItems, color: 'var(--info-text)', bg: 'var(--info-bg)', border: 'var(--info-border)' },
          { id: 'unverified', label: ['Bez ověřeného mapování', 'Without verified mapping'] as Bilingual, value: totalItems, color: 'var(--text-tertiary)', bg: 'var(--surface-2)', border: 'var(--border)' },
          { id: 'frameworks', label: ['Oblasti přehledu', 'Report areas'] as Bilingual, value: COMPLIANCE_AREAS.length, color: 'var(--accent-text)', bg: 'var(--accent-bg)', border: 'var(--accent-border)' },
        ]).map(stat => (
          <div key={stat.id} style={{
            padding: '16px', background: stat.bg, border: `1px solid ${stat.border}`,
            borderRadius: 'var(--r-lg)',
          }}>
            <div style={{ fontSize: '24px', fontWeight: 700, color: stat.color }}>
              {stat.value}
            </div>
            <div style={{ fontSize: '12px', color: stat.color, fontWeight: 600, marginTop: '2px' }}>{t(...stat.label)}</div>
          </div>
        ))}
      </div>

      {/* Disclaimer */}
      <div style={{
        padding: '12px 16px', marginBottom: '20px',
        background: 'var(--info-bg)', border: '1px solid var(--info-border)',
        borderRadius: 'var(--r-lg)', display: 'flex', gap: '10px',
      }}>
        <Info size={14} style={{ color: 'var(--info-text)', flexShrink: 0, marginTop: '1px' }} />
        <div style={{ fontSize: '12px', color: 'var(--text-primary)', lineHeight: 1.5 }}>
          {t('Tento přehled je redakční inventář oblastí. Neprokazuje právní shodu ani provozní účinnost kontrol. Řídicí katalog níže odděluje deklarovaný stav od dostupných odvozených signálů; signál je buildový snímek manifestů, nikoli živá kontrola clusteru.', 'This page is an editorial inventory of topics. It does not establish legal compliance or operating effectiveness. The control catalogue below separates declared status from available derived signals; the signal is a build-time manifest snapshot, not a live cluster check.')}
        </div>
      </div>

      <section className="card" style={{ padding: '18px', marginBottom: '20px' }} aria-label={t('Katalog kontrol', 'Control catalogue')}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
          <div>
            <h2 style={{ margin: 0, fontSize: '16px', color: 'var(--text-primary)' }}>{t('Řídicí katalog', 'Control catalogue')}</h2>
            <p style={{ margin: '5px 0 0', color: 'var(--text-secondary)', fontSize: '12px' }}>
              {snapshot?.posture?.collectedAt
                ? t(`Snímek manifestů pořízen ${snapshot.posture.collectedAt}.`, `Manifest snapshot collected ${snapshot.posture.collectedAt}.`)
                : t('Časové razítko snímku není dostupné.', 'Snapshot timestamp is unavailable.')}
            </p>
          </div>
          <button type="button" className="btn btn-secondary" onClick={() => void refreshControls()} disabled={loading}>
            <RefreshCw size={14} /> {t('Obnovit katalog', 'Refresh catalogue')}
          </button>
        </div>
        {loading ? <p role="status">{t('Načítám katalog…', 'Loading catalogue…')}</p> : null}
        {refreshError || snapshot?.status === 'unavailable' ? (
          <p role="status" style={{ color: 'var(--warning-text)' }}>{t('Katalog nebo snímek odvozených signálů není dostupný; stav je neznámý.', 'The catalogue or derived-signal snapshot is unavailable; status is unknown.')}</p>
        ) : null}
        {snapshot?.posture ? <p style={{ margin: '8px 0', fontSize: '11px', color: 'var(--text-tertiary)' }}>{snapshot.posture.source}</p> : null}
        {snapshot?.controls.map(control => (
          <div key={control.id} style={{ borderTop: '1px solid var(--border)', padding: '10px 0' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
              <strong style={{ color: 'var(--text-primary)', fontSize: '13px' }}>{control.title}</strong>
              <span style={{ color: control.status === 'unknown' ? 'var(--text-tertiary)' : 'var(--info-text)', fontSize: '11px', fontWeight: 700 }}>
                {t(`Deklarováno: ${control.declaredStatus}; odvozeno: ${control.status}`, `Declared: ${control.declaredStatus}; derived: ${control.status}`)}
                {control.derivedFrom ? ` · ${control.signalState ?? 'unknown'}` : ''}
              </span>
            </div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '12px', marginTop: '4px' }}>{control.evidence}</div>
            {control.evidenceSource ? <div style={{ color: 'var(--text-tertiary)', fontSize: '11px', marginTop: '3px' }}>{control.evidenceSource}</div> : null}
          </div>
        ))}
      </section>

      {/* Compliance areas */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
        {COMPLIANCE_AREAS.map(area => {
          const cfg = STATUS_CONFIG[area.status as keyof typeof STATUS_CONFIG]
          return (
            <div key={area.id} className="card" style={{ overflow: 'hidden' }}>
              {/* Header */}
              <div style={{
                padding: '16px 20px',
                borderLeft: `4px solid ${cfg.color}`,
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: '8px',
              }}>
                <div>
                  <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(...area.title)}</div>
                  <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginTop: '2px' }}>{t('Autorita:', 'Authority:')} {t(...area.authority)}</div>
                </div>
                <div style={{
                  display: 'flex', alignItems: 'center', gap: '6px',
                  padding: '5px 12px', background: cfg.bg, border: `1px solid ${cfg.border}`,
                  borderRadius: '20px', color: cfg.color, fontSize: '12px', fontWeight: 600,
                }}>
                  {cfg.icon}
                  {t(...cfg.label)}
                </div>
              </div>

              {/* Items */}
              <div style={{ padding: '0 20px 16px' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <tbody>
                    {area.items.map((item, i) => {
                      const itemCfg = ITEM_STATUS[item.status as keyof typeof ITEM_STATUS]
                      return (
                        <tr key={i} style={{ borderBottom: i < area.items.length - 1 ? '1px solid var(--border)' : 'none' }}>
                          <td style={{ padding: '8px 0', width: '20px', verticalAlign: 'top', paddingTop: '10px' }}>
                            <span style={{ color: itemCfg.color }}>{itemCfg.icon}</span>
                          </td>
                          <td style={{ padding: '8px 12px', fontSize: '13px', color: 'var(--text-primary)', fontWeight: 500 }}>
                            {t(...item.req)}
                          </td>
                          <td style={{ padding: '8px 0', fontSize: '12px', color: 'var(--text-tertiary)', textAlign: 'right' }}>
                            {REQUIREMENT_CONTROL_REFERENCES[item.req[1]]?.map(id => controlsById.get(id)?.title ?? id).join(', ') || t(...item.note)}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
