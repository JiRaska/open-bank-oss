// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { Shield, CheckCircle2, AlertTriangle, XCircle, Info } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { DocsPageHeader } from '@/components/docs/DocsPageHeader'
import { PrintDocumentButton } from '@/components/docs/PrintDocumentButton'

// Bilingual string tuple: [Czech, English] — spread into t(cs, en) at render.
type Bilingual = [string, string]

type ComplianceItem = { req: Bilingual; status: string; note: Bilingual }
type ComplianceArea = { id: string; title: Bilingual; authority: Bilingual; status: string; items: ComplianceItem[] }

const COMPLIANCE_AREAS: ComplianceArea[] = [
  {
    id: 'psd2',
    title: ['PSD2 / RTS on SCA', 'PSD2 / RTS on SCA'],
    authority: ['EBA + ECB', 'EBA + ECB'],
    status: 'compliant',
    items: [
      { req: ['SCA pro platby nad 30 EUR', 'SCA for payments over 30 EUR'], status: 'ok', note: ['sca-service implementuje OTP + FIDO2', 'sca-service implements OTP + FIDO2'] },
      { req: ['Správa souhlasů (AIS/PIS/CBPII)', 'Consent management (AIS/PIS/CBPII)'], status: 'ok', note: ['consent-service s limitem frequency_per_day', 'consent-service with frequency_per_day limit'] },
      { req: ['Registrace a validace TPP', 'TPP registration and validation'], status: 'ok', note: ['tpp-registry-service se synchronizací EBA registru', 'tpp-registry-service with EBA register sync'] },
      { req: ['Berlin Group NextGenPSD2 API', 'Berlin Group NextGenPSD2 API'], status: 'ok', note: ['psd2-service implementuje standard', 'psd2-service implements the standard'] },
      { req: ['Redirect URI v záznamu souhlasu', 'Redirect URI in the consent record'], status: 'ok', note: ['migrace V2 přidala redirect_uri', 'V2 migration added redirect_uri'] },
      { req: ['Charge bearer SLEV pro SEPA', 'Charge bearer SLEV for SEPA'], status: 'ok', note: ['migrace V2 + constraint', 'V2 migration + constraint'] },
      { req: ['End-to-end ID v SEPA platbách', 'End-to-end ID in SEPA payments'], status: 'ok', note: ['end_to_end_id NOT NULL v DB', 'end_to_end_id NOT NULL in DB'] },
    ],
  },
  {
    id: 'aml',
    title: ['AML / 5AMLD / 6AMLD', 'AML / 5AMLD / 6AMLD'],
    authority: ['EBA + FAÚ (CZ)', 'EBA + FAÚ (CZ)'],
    status: 'compliant',
    items: [
      { req: ['PEP screening zákazníků', 'PEP screening of customers'], status: 'warn', note: ['pep_category — sloupec bez čtenáře v kódu, issue #2370', 'pep_category — column has no code reader, issue #2370'] },
      { req: ['Screening sankčních seznamů', 'Sanctions list screening'], status: 'warn', note: ['matched_list — sloupec bez čtenáře v kódu, issue #2370', 'matched_list — column has no code reader, issue #2370'] },
      { req: ['Sledování podání SAR', 'SAR filing tracking'], status: 'warn', note: ['sar_filed, sar_filed_at, sar_reference — sloupec bez čtenáře v kódu, issue #2370', 'sar_filed, sar_filed_at, sar_reference — column has no code reader, issue #2370'] },
      { req: ['Eskalace na MLRO', 'MLRO escalation'], status: 'warn', note: ['escalated_at, escalated_to_mlro — sloupec bez čtenáře v kódu, issue #2370', 'escalated_at, escalated_to_mlro — column has no code reader, issue #2370'] },
      { req: ['Dokumentace false positive', 'False positive documentation'], status: 'warn', note: ['false_positive_by, false_positive_reason — sloupec bez čtenáře v kódu, issue #2370', 'false_positive_by, false_positive_reason — column has no code reader, issue #2370'] },
      { req: ['AML screening transakcí', 'AML screening of transactions'], status: 'ok', note: ['flag aml_screened v transactions + sepa + domestic, monitoring přes fraud-service', 'aml_screened flag in transactions + sepa + domestic, monitoring via fraud-service'] },
      { req: ['Rizikové hodnocení zákazníka', 'Customer risk rating'], status: 'warn', note: ['risk_rating — sloupec bez čtenáře v kódu, issue #2370', 'risk_rating — column has no code reader, issue #2370'] },
    ],
  },
  {
    id: 'kyc',
    title: ['KYC / CDD / EDD', 'KYC / CDD / EDD'],
    authority: ['EBA AML Guidelines + FATF', 'EBA AML Guidelines + FATF'],
    status: 'compliant',
    items: [
      { req: ['Úroveň due diligence (SDD/CDD/EDD)', 'Due diligence level (SDD/CDD/EDD)'], status: 'warn', note: ['due_diligence_level — sloupec bez čtenáře v kódu, issue #2370', 'due_diligence_level — column has no code reader, issue #2370'] },
      { req: ['Prohlášení o původu prostředků', 'Source of funds declaration'], status: 'ok', note: ['source_of_funds + source_of_wealth V2', 'source_of_funds + source_of_wealth V2'] },
      { req: ['Účel obchodního vztahu', 'Business purpose'], status: 'ok', note: ['business_purpose V2', 'business_purpose V2'] },
      { req: ['Plánování periodické revize', 'Periodic review scheduling'], status: 'warn', note: ['next_review_date — sloupec bez čtenáře v kódu, issue #2370', 'next_review_date — column has no code reader, issue #2370'] },
      { req: ['Vlastní prohlášení PEP', 'PEP self-declaration'], status: 'ok', note: ['pep_declaration v kyc_cases V2', 'pep_declaration in kyc_cases V2'] },
      { req: ['Sledování skutečného majitele', 'Beneficial owner tracking'], status: 'ok', note: ['beneficial_owner_id V2', 'beneficial_owner_id V2'] },
      { req: ['Očekávaný obrat', 'Expected turnover'], status: 'ok', note: ['expected_turnover + currency V2', 'expected_turnover + currency V2'] },
    ],
  },
  {
    id: 'gdpr',
    title: ['GDPR', 'GDPR'],
    authority: ['ÚOOÚ (CZ) + EDPB', 'ÚOOÚ (CZ) + EDPB'],
    status: 'partial',
    items: [
      { req: ['Časové razítko explicitního souhlasu', 'Explicit consent timestamp'], status: 'warn', note: ['gdpr_consent_at/_version (party-service V2) — sloupec bez čtenáře v kódu, issue #2370', 'gdpr_consent_at/_version (party-service V2) — column has no code reader, issue #2370'] },
      { req: ['Marketingový souhlas', 'Marketing consent'], status: 'warn', note: ['Souhlas vlastní consent-service jako per-kanálové scopes (ADR-0198/0205, grantee party-service:marketing-comms); party-service jen forwarduje a projektuje z Kafka eventů. Send-path zatím fail-closed, ne skutečná kontrola consentu — issue #2369', 'consent-service owns the consent as per-channel scopes (ADR-0198/0205, grantee party-service:marketing-comms); party-service only forwards and projects from Kafka events. Send path is fail-closed for now, not a real consent check — issue #2369'] },
      { req: ['Právo na výmaz (anonymizace a kaskáda)', 'Right to erasure (anonymise and cascade)'], status: 'ok', note: ['ADR-0118: party-service anonymizuje in-place, kyc/notification/card-issuance kaskádují (issue #268 uzavřen)', 'ADR-0118: party-service anonymises in-place, kyc/notification/card-issuance cascade (issue #268 closed)'] },
      { req: ['Politika uchovávání dat', 'Data retention policy'], status: 'warn', note: ['data_retention_until (accounts + parties V2) — sloupec bez čtenáře v kódu, issue #2370', 'data_retention_until (accounts + parties V2) — column has no code reader, issue #2370'] },
      { req: ['Klasifikace citlivosti dat', 'Data sensitivity classification'], status: 'warn', note: ['data_sensitivity (audit_entries V2) — sloupec bez čtenáře v kódu, issue #2370', 'data_sensitivity (audit_entries V2) — column has no code reader, issue #2370'] },
      { req: ['Smlouva o zpracování osobních údajů (GDPR)', 'GDPR Data Processing Agreement'], status: 'warn', note: ['Vyžaduje právní dokumentaci (mimo scope systému)', 'Requires legal documentation (outside system scope)'] },
      { req: ['Právo na přístup (Art. 15)', 'Right of access (Art. 15)'], status: 'ok', note: ['GET /api/v1/parties/{id}/gdpr-export agreguje party+kyc+cards (issue #268 uzavřen); subjekt si ho vyžádá sám přes GET /customer/v1/privacy/gdpr-export (issue #8421)', 'GET /api/v1/parties/{id}/gdpr-export aggregates party+kyc+cards (issue #268 closed); the subject reaches it themselves via GET /customer/v1/privacy/gdpr-export (issue #8421)'] },
      { req: ['Přenositelnost dat (Art. 20)', 'Data portability (Art. 20)'], status: 'ok', note: ['ADR-0204 rozhodl rozsah i formát (filtrovaná projekce Art. 15 exportu, jen consent/contract basis) a Art. 20(2) přímý přenos zamítl; GET /api/v1/parties/{id}/gdpr-portability-export postaven, subjekt jej dosáhne přes GET /customer/v1/privacy/portability-export (issue #8421)', 'ADR-0204 decided scope and format (a filtered projection of the Art. 15 export, consent/contract basis only) and declined Art. 20(2) direct transmission; GET /api/v1/parties/{id}/gdpr-portability-export is built and the subject reaches it via GET /customer/v1/privacy/portability-export (issue #8421)'] },
    ],
  },
  {
    id: 'eba-ict',
    title: ['EBA ICT Risk Guidelines', 'EBA ICT Risk Guidelines'],
    authority: ['EBA', 'EBA'],
    status: 'compliant',
    items: [
      { req: ['Neměnný audit log', 'Immutable audit log'], status: 'ok', note: ['no_update_audit + no_delete_audit RULE v DB V2', 'no_update_audit + no_delete_audit RULE in DB V2'] },
      { req: ['10letá retence auditu', '10-year audit retention'], status: 'warn', note: ['retention_until — sloupec bez čtenáře v kódu, issue #2370', 'retention_until — column has no code reader, issue #2370'] },
      { req: ['Označování bezpečnostních událostí', 'Security event flagging'], status: 'warn', note: ['is_security_event — sloupec bez čtenáře v kódu, issue #2370', 'is_security_event — column has no code reader, issue #2370'] },
      { req: ['Actor ID v auditním záznamu', 'Actor ID in the audit record'], status: 'ok', note: ['actor_id + actor_type v transactions V2', 'actor_id + actor_type in transactions V2'] },
      { req: ['Logování IP adresy', 'IP address logging'], status: 'ok', note: ['ip_address v transactions, sepa, domestic, consent V2', 'ip_address in transactions, sepa, domestic, consent V2'] },
      { req: ['Sledování session', 'Session tracking'], status: 'ok', note: ['session_id v audit_entries V2', 'session_id in audit_entries V2'] },
      { req: ['Correlation ID', 'Correlation ID'], status: 'ok', note: ['correlation_id v transactions V2', 'correlation_id in transactions V2'] },
    ],
  },
  {
    id: 'cnb',
    title: ['Regulace ČNB (CZ)', 'CNB Regulation (CZ)'],
    authority: ['Česká národní banka', 'Czech National Bank'],
    status: 'partial',
    items: [
      { req: ['Konstantní symbol (platební styk)', 'Constant symbol (payment system)'], status: 'ok', note: ['constant_symbol s regex constraint V2', 'constant_symbol with regex constraint V2'] },
      { req: ['Specifický symbol', 'Specific symbol'], status: 'ok', note: ['specific_symbol v domestic_payments V2', 'specific_symbol in domestic_payments V2'] },
      { req: ['Výkaznický kód ČNB', 'CNB reporting code'], status: 'warn', note: ['cnb_reporting_code — sloupec bez čtenáře v kódu, issue #2370', 'cnb_reporting_code — column has no code reader, issue #2370'] },
      { req: ['Monitoring dormance', 'Dormancy monitoring'], status: 'warn', note: ['dormancy_date — sloupec bez čtenáře v kódu, issue #2370', 'dormancy_date — column has no code reader, issue #2370'] },
      { req: ['Přidělení IBAN', 'IBAN allocation'], status: 'ok', note: ['sloupec iban + unique index v accounts V2', 'iban column + unique index in accounts V2'] },
      { req: ['Regulatorní výkaznický kód', 'Regulatory reporting code'], status: 'warn', note: ['regulatory_reporting_code — sloupec bez čtenáře v kódu, issue #2370', 'regulatory_reporting_code — column has no code reader, issue #2370'] },
      { req: ['Záznamy 10 let po uzavření', 'Records for 10 years after closure'], status: 'warn', note: ['data_retention_until (accounts V2) — sloupec bez čtenáře v kódu, stejný jako řádek „Politika uchovávání dat“, issue #2370', 'data_retention_until (accounts V2) — column has no code reader, same as the "Data retention policy" row, issue #2370'] },
    ],
  },
  {
    id: 'fatca-crs',
    title: ['FATCA / CRS', 'FATCA / CRS'],
    authority: ['IRS + OECD', 'IRS + OECD'],
    status: 'compliant',
    items: [
      { req: ['FATCA status zákazníka', 'Customer FATCA status'], status: 'warn', note: ['fatca_status — sloupec bez čtenáře v kódu, issue #2370', 'fatca_status — column has no code reader, issue #2370'] },
      { req: ['CRS status zákazníka', 'Customer CRS status'], status: 'warn', note: ['crs_status — sloupec bez čtenáře v kódu, issue #2370', 'crs_status — column has no code reader, issue #2370'] },
      { req: ['Identifikace US person', 'US person identification'], status: 'warn', note: ['fatca_status — sloupec bez čtenáře v kódu, issue #2370', 'fatca_status — column has no code reader, issue #2370'] },
      { req: ['CRS reporting', 'CRS reporting'], status: 'warn', note: ['Automatický export CRS reportu chybí (ruční proces)', 'Automated CRS report export is missing (manual process)'] },
    ],
  },
]

const STATUS_CONFIG: Record<string, { label: Bilingual; color: string; bg: string; border: string; icon: React.ReactNode }> = {
  compliant: { label: ['V souladu', 'Compliant'], color: '#16a34a', bg: '#f0fdf4', border: '#86efac', icon: <CheckCircle2 size={14} /> },
  partial:   { label: ['Částečně', 'Partial'],    color: '#d97706', bg: '#fffbeb', border: '#fde68a', icon: <AlertTriangle size={14} /> },
  gap:       { label: ['Mezera', 'Gap'],          color: '#dc2626', bg: '#fef2f2', border: '#fecaca', icon: <XCircle size={14} /> },
}

const ITEM_STATUS = {
  ok:   { color: '#16a34a', icon: <CheckCircle2 size={12} /> },
  warn: { color: '#d97706', icon: <AlertTriangle size={12} /> },
  fail: { color: '#dc2626', icon: <XCircle size={12} /> },
}

export default function CompliancePage() {
  const { t } = useLanguage()
  const totalItems = COMPLIANCE_AREAS.flatMap(a => a.items).length
  const okItems = COMPLIANCE_AREAS.flatMap(a => a.items).filter(i => i.status === 'ok').length
  const warnItems = COMPLIANCE_AREAS.flatMap(a => a.items).filter(i => i.status === 'warn').length

  return (
    <div className="docs-printable">
      <DocsPageHeader
        crumbs={<>
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Dokumentace', 'Docs')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Report compliance', 'Compliance Report')}</span>
          </>}
        title={t('Report compliance', 'Compliance Report')}
        subtitle={t('EBA · ČNB · PSD2 · GDPR · AML 5AMLD/6AMLD · FATCA/CRS · připravenost na audit', 'EBA · CNB · PSD2 · GDPR · AML 5AMLD/6AMLD · FATCA/CRS · audit readiness')}
        icon={<Shield aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        actions={<PrintDocumentButton />}
      />

      {/* Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: '12px', marginBottom: '24px' }}>
        {([
          { id: 'compliant', label: ['V souladu', 'Compliant'] as Bilingual, value: okItems, total: totalItems, color: '#16a34a', bg: '#f0fdf4' },
          { id: 'warnings', label: ['Upozornění', 'Warnings'] as Bilingual, value: warnItems, total: totalItems, color: '#d97706', bg: '#fffbeb' },
          { id: 'coverage', label: ['Pokrytí', 'Coverage'] as Bilingual, value: `${Math.round(okItems/totalItems*100)}%`, total: null, color: '#2563eb', bg: '#eff6ff' },
          { id: 'frameworks', label: ['Rámce', 'Frameworks'] as Bilingual, value: COMPLIANCE_AREAS.length, total: null, color: '#7c3aed', bg: '#faf5ff' },
        ]).map(stat => (
          <div key={stat.id} style={{
            padding: '16px', background: stat.bg, border: `1px solid ${stat.color}30`,
            borderRadius: 'var(--r-lg)',
          }}>
            <div style={{ fontSize: '24px', fontWeight: 700, color: stat.color }}>
              {stat.value}{stat.total ? <span style={{ fontSize: '14px', opacity: 0.6 }}>/{stat.total}</span> : ''}
            </div>
            <div style={{ fontSize: '12px', color: stat.color, opacity: 0.8, marginTop: '2px' }}>{t(...stat.label)}</div>
          </div>
        ))}
      </div>

      {/* Disclaimer */}
      <div style={{
        padding: '12px 16px', marginBottom: '20px',
        background: '#eff6ff', border: '1px solid #bfdbfe',
        borderRadius: 'var(--r-lg)', display: 'flex', gap: '10px',
      }}>
        <Info size={14} style={{ color: '#2563eb', flexShrink: 0, marginTop: '1px' }} />
        <div style={{ fontSize: '12px', color: '#1e40af', lineHeight: 1.5 }}>
          {t('Tento report reflektuje technickou implementaci databázových schémat a API. Plná regulatorní compliance vyžaduje také právní dokumentaci, interní politiky, školení zaměstnanců a pravidelné audity. Doporučujeme konzultaci s regulatorním právníkem před podáním žádosti o bankovní licenci u ČNB.', 'This report reflects the technical implementation of database schemas and APIs. Full regulatory compliance also requires legal documentation, internal policies, staff training and regular audits. We recommend consulting a regulatory lawyer before applying for a banking licence with the CNB.')}
        </div>
      </div>

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
                            {t(...item.note)}
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
