// SPDX-License-Identifier: Apache-2.0
'use client'

import type { ReactNode } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { useTestEvidenceView } from './useTestEvidenceView'

/** The complete archive is fetched only after an expert view is explicitly opened. */
export function FullTestReportPanel({ collectedAt, refreshKey, onRefresh, children }: {
  collectedAt: string; refreshKey: string; onRefresh: () => void
  children: (report: TestIntelligenceReport) => ReactNode
}) {
  const { t } = useLanguage()
  const result = useTestEvidenceView({ view: 'full' }, refreshKey)
  const report = result.value && !('view' in result.value) ? result.value : null
  const changed = report !== null && report.collectedAt !== collectedAt
  if (changed || !report) return <div role="status">
    <p>{changed ? t('Report se mezitím změnil. Obnovte přehled, aby podklady patřily ke stejnému snímku.', 'The report has changed. Refresh the overview to keep the evidence in one snapshot.')
      : result.loading ? t('Načítám podrobné důkazy…', 'Loading detailed evidence…') : t('Podrobné důkazy nyní nejsou dostupné. Přehled zůstává k dispozici.', 'Detailed evidence is unavailable. The overview remains available.')}</p>
    {!result.loading && <button type="button" className="btn btn-secondary btn-sm" onClick={() => { onRefresh(); result.retry() }}>{t('Obnovit důkazy', 'Refresh evidence')}</button>}
  </div>
  return <>{result.error ? <p role="status">{t('Zobrazené podklady pocházejí z posledního úspěšného načtení.', 'Showing the last successfully loaded evidence.')}</p> : null}{children(report)}</>
}
