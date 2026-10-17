'use client'
// SPDX-License-Identifier: Apache-2.0
import { TriangleAlert, Radio, Package } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TestEvidenceMeta } from '@/lib/types/test-results'

export function formatAge(seconds: number, cs: boolean): string {
  if (seconds < 90) return cs ? 'před chvílí' : 'just now'
  const m = Math.round(seconds / 60)
  if (m < 90) return cs ? `před ${m} min` : `${m} min ago`
  const h = Math.round(seconds / 3600)
  if (h < 48) return cs ? `před ${h} h` : `${h} h ago`
  const d = Math.round(seconds / 86400)
  return cs ? `před ${d} dny` : `${d} days ago`
}

/** Provenance + staleness + "exists vs reported" gap. Never renders a number it was not given. */
export function EvidenceFreshness({ evidence, reportedCases }: { evidence?: TestEvidenceMeta; reportedCases?: number }) {
  const { t, language } = useLanguage()
  if (!evidence) return null
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const inv = evidence.inventory
  const asOf = evidence.asOf
    ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(evidence.asOf))
    : t('neznámé', 'unknown')
  const src = evidence.source === 'live' ? t('živě z CI', 'live from CI') : t('vestavěný snapshot', 'bundled snapshot')
  return <section aria-label={t('Čerstvost důkazů', 'Evidence freshness')} data-testid="evidence-freshness"
    style={{ marginBottom: 16, border: '1px solid var(--border)', borderRadius: 8, padding: 12, fontSize: 12 }}>
    <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center' }}>
      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        {evidence.source === 'live' ? <Radio size={13} aria-hidden="true" /> : <Package size={13} aria-hidden="true" />}
        <strong>{t('Zdroj', 'Source')}:</strong> {src}
      </span>
      <span><strong>{t('Stav k', 'As of')}:</strong> {asOf}{evidence.ageSeconds !== null ? ` (${formatAge(evidence.ageSeconds, language === 'cs')})` : ''}</span>
      {inv && <span><strong>{t('Testovací soubory v repu', 'Test files in repo')}:</strong> {inv.totalFiles} <span style={{ color: 'var(--text-tertiary)' }}>(Kotlin {inv.kotlin.unit + inv.kotlin.integration} · admin-ui {inv.adminUi} · e2e {inv.e2e})</span></span>}
      {reportedCases !== undefined && <span><strong>{t('Testy s výsledkem', 'Tests that reported')}:</strong> {reportedCases}</span>}
    </div>
    {evidence.stale && <div role="alert" style={{ marginTop: 8, color: 'var(--warning-text)', display: 'flex', gap: 6, alignItems: 'center' }}>
      <TriangleAlert size={13} aria-hidden="true" />
      {evidence.asOf
        ? t('Data jsou starší než 24 hodin — čísla nemusí odpovídat aktuálnímu stavu repozitáře.', 'Data is older than 24 hours — figures may not reflect the repository today.')
        : t('Stáří dat nelze určit — čísla nelze považovat za aktuální.', 'Data age cannot be determined — do not treat the figures as current.')}
    </div>}
    {evidence.source === 'bundled' && evidence.liveError && <div style={{ marginTop: 6, color: 'var(--text-secondary)' }}>
      {t('Živý zdroj není dostupný, zobrazen vestavěný snapshot.', 'Live source unavailable; showing the bundled snapshot.')}</div>}
    {!inv && <div style={{ marginTop: 6, color: 'var(--text-secondary)' }}>{t('Inventář testovacích souborů není k dispozici.', 'Test-file inventory is not available.')}</div>}
  </section>
}
