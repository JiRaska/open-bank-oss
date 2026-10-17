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
  const src = evidence.source === 'running-commit'
    ? t('evidence pro běžící commit', 'evidence for the running commit')
    : t('vestavěný snapshot', 'bundled snapshot')
  return <section aria-label={t('Čerstvost důkazů', 'Evidence freshness')} data-testid="evidence-freshness"
    style={{ marginBottom: 16, border: '1px solid var(--border)', borderRadius: 8, padding: 12, fontSize: 12 }}>
    <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center' }}>
      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        {evidence.source === 'running-commit' ? <GitCommitHorizontal size={13} aria-hidden="true" /> : <Package size={13} aria-hidden="true" />}
        <strong>{t('Zdroj', 'Source')}:</strong> {src}
      </span>
      <span><strong>{t('Snapshot k', 'Snapshot as of')}:</strong> {asOf}{evidence.ageSeconds !== null ? ` (${formatAge(evidence.ageSeconds, language === 'cs')})` : ''}</span>
      {rc.known === null
        ? <span>{t('Běžící commity nejsou známé (mimo cluster).', 'Running commits unknown (off-cluster).')}</span>
        : <span data-testid="running-commit-match"><strong>{t('Odpovídá běžícímu kódu', 'Matches running code')}:</strong> {rc.matchingSnapshot}/{components}
          {rc.fromStore > 0 && <> · {t('ze store', 'from store')} {rc.fromStore}</>}
        </span>}
    </div>
    {evidence.stale && <div role="alert" style={{ marginTop: 8, color: 'var(--warning-text)', display: 'flex', gap: 6, alignItems: 'center' }}>
      <TriangleAlert size={13} aria-hidden="true" />
      {evidence.asOf
        ? t('Snapshot je starší než 24 hodin — čísla nemusí odpovídat běžícímu kódu.', 'The snapshot is older than 24 hours — figures may not describe the running code.')
        : t('Stáří dat nelze určit — čísla nelze považovat za aktuální.', 'Data age cannot be determined — do not treat the figures as current.')}
    </div>}
  </section>
}
