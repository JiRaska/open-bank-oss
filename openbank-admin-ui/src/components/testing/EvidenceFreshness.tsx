'use client'
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { GitCommitHorizontal, Package, TriangleAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TestEvidenceMeta } from '@/lib/types/test-intelligence'

export function formatAge(seconds: number, cs: boolean): string {
  if (seconds < 90) return cs ? 'před chvílí' : 'just now'
  const m = Math.round(seconds / 60)
  if (m < 90) return cs ? `před ${m} min` : `${m} min ago`
  const h = Math.round(seconds / 3600)
  if (h < 48) return cs ? `před ${h} h` : `${h} h ago`
  const d = Math.round(seconds / 86400)
  return cs ? `před ${d} dny` : `${d} days ago`
}

/** Provenance + staleness + running-commit match. Never renders a number it was not given. */
export function EvidenceFreshness({ evidence, components }: { evidence?: TestEvidenceMeta; components: number }) {
  const { t, language } = useLanguage()
  if (!evidence) return null
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const asOf = evidence.asOf
    ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(evidence.asOf))
    : t('neznámé', 'unknown')
  const rc = evidence.runningCommits
  const src = evidence.source === 'mixed'
    ? t('publikovaný snapshot + důkazy běžících commitů', 'published snapshot + running-commit evidence')
    : evidence.source === 'published-snapshot'
      ? t('nezávisle publikovaný snapshot', 'independently published snapshot')
      : evidence.source === 'unavailable'
        ? t('zdroj snapshotu nedostupný', 'snapshot source unavailable')
        : t('vestavěný snapshot', 'bundled snapshot')
  return <section aria-label={t('Čerstvost důkazů', 'Evidence freshness')} data-testid="evidence-freshness"
    style={{ marginBottom: 16, border: '1px solid var(--border)', borderRadius: 8, padding: 12, fontSize: 12 }}>
    <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center' }}>
      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        {evidence.source === 'mixed' ? <GitCommitHorizontal size={13} aria-hidden="true" /> : <Package size={13} aria-hidden="true" />}
        <strong>{t('Zdroj', 'Source')}:</strong> {src}
      </span>
      <span><strong>{t('Snapshot k', 'Snapshot as of')}:</strong> {asOf}{evidence.ageSeconds !== null ? ` (${formatAge(evidence.ageSeconds, language === 'cs')})` : ''}</span>
      {rc.known === null
        ? <span>{t('Běžící commity nejsou známé (mimo cluster).', 'Running commits unknown (off-cluster).')}</span>
        : <span data-testid="running-commit-match"><strong>{t('Odpovídá běžícímu kódu', 'Matches running code')}:</strong> {rc.matchingSnapshot}/{components}
          {rc.fromStore > 0 && <> · {t('ze store', 'from store')} {rc.fromStore}</>}
          {rc.fromBundledFallback > 0 && <> · {t('starší snapshot fallback', 'bundled fallback')} {rc.fromBundledFallback}</>}
          {rc.mismatchedEvidence > 0 && <> · {t('commity bez shody', 'commit mismatches')} {rc.mismatchedEvidence}</>}
          {rc.fromEquivalentBuild > 0 && <> ({t('z toho testováno na build-identickém commitu', 'of which tested on a build-identical commit')}: {rc.fromEquivalentBuild})</>}
        </span>}
      {evidence.dataset.sourceSha && <span><strong>Publisher:</strong> {evidence.dataset.sourceSha.slice(0, 8)} · run {evidence.dataset.runId}/{evidence.dataset.runAttempt}</span>}
      {evidence.dataset.snapshotAgeSeconds !== null && <span><strong>{t('Stáří dat', 'Dataset age')}:</strong> {formatAge(evidence.dataset.snapshotAgeSeconds, language === 'cs')}</span>}
    </div>
    {(evidence.stale || evidence.dataset.status === 'unavailable') && <div role="alert" style={{ marginTop: 8, color: 'var(--warning-text)', display: 'flex', gap: 6, alignItems: 'center' }}>
      <TriangleAlert size={13} aria-hidden="true" />
      {evidence.dataset.status === 'unavailable'
        ? t('Konfigurovaný důkazní snapshot není dostupný; obrazový fallback nebyl použit.', 'The configured evidence snapshot is unavailable; the image-bundled fallback was not used.')
        : evidence.asOf
        ? t('Snapshot je starší než 24 hodin — čísla nemusí odpovídat běžícímu kódu.', 'The snapshot is older than 24 hours — figures may not describe the running code.')
        : t('Stáří dat nelze určit — čísla nelze považovat za aktuální.', 'Data age cannot be determined — do not treat the figures as current.')}
    </div>}
    {evidence.dataset.error && <div role="status" style={{ marginTop: 6, color: 'var(--text-tertiary)', fontSize: 11 }}>{evidence.dataset.error}</div>}
  </section>
}
