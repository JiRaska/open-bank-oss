// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useState } from 'react'
import { z } from 'zod'
import { Inbox } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { EmptyState, LoadingState, PageHeader } from '@/components/ui'

const timestamp = z.iso.datetime({ offset: true })
const runs = z.array(z.object({
  mailbox: z.string(),
  windowFrom: timestamp,
  windowThrough: timestamp,
  startedAt: timestamp,
  discoveredAt: timestamp.nullable(),
  status: z.enum(['RUNNING', 'COMPLETE', 'INCOMPLETE', 'DISCOVERY_FAILED', 'INTERRUPTED']),
  discoveredCount: z.number().int().nonnegative(),
  importedCount: z.number().int().nonnegative(),
  pendingCount: z.number().int().nonnegative().nullable(),
}))

export default function DataMailboxes() {
  const { t, language } = useLanguage()
  const [data, setData] = useState<z.infer<typeof runs> | null>(null)
  const [failed, setFailed] = useState(false)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    void fetch('/api/legal-inbox/isds-runs', { cache: 'no-store', signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('ISDS history unavailable')
        const result = runs.parse(await response.json())
        if (!controller.signal.aborted) setData(result)
      })
      .catch(() => { if (!controller.signal.aborted) setFailed(true) })
    return () => controller.abort()
  }, [refresh])

  const date = (value: string) => new Date(value).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')
  const statuses = {
    RUNNING: t('Probíhá', 'Running'),
    COMPLETE: t('Dokončeno', 'Complete'),
    INCOMPLETE: t('Zprávy čekají na import', 'Messages pending import'),
    DISCOVERY_FAILED: t('Seznam se nepodařilo načíst', 'Could not list messages'),
    INTERRUPTED: t('Přerušeno', 'Interrupted'),
  }
  return <>
    <PageHeader
      title={t('Datové schránky', 'Data mailboxes')}
      icon={<Inbox aria-hidden="true" size={23} />}
      actions={<button className="btn btn-secondary" onClick={() => {
        setData(null)
        setFailed(false)
        setRefresh(value => value + 1)
      }}>{t('Obnovit historii', 'Refresh history')}</button>}
    />
    <section className="card" aria-label={t('Historie vyčítání ISDS', 'ISDS polling history')}>
      {failed && <p role="alert">{t('Historii se nepodařilo načíst.', 'Could not load polling history.')}</p>}
      {!failed && data === null && <LoadingState label={t('Načítání…', 'Loading…')} />}
      {data?.length === 0 && <EmptyState title={t('Žádné zaznamenané běhy', 'No recorded runs')} />}
      {!!data?.length && <div className="overflow-x-auto">
        <table className="data-table">
          <thead><tr>
            <th scope="col">{t('Schránka', 'Mailbox')}</th><th scope="col">{t('Spuštěno', 'Started')}</th>
            <th scope="col">{t('Okno vyčítání', 'Scan window')}</th><th scope="col">{t('Stav', 'Status')}</th>
            <th scope="col">{t('Nalezeno', 'Discovered')}</th><th scope="col">{t('Importováno', 'Imported')}</th>
            <th scope="col">{t('Čekalo po běhu', 'Pending after run')}</th>
          </tr></thead>
          <tbody>{data.map(run => <tr key={run.mailbox + run.startedAt}>
            <td>{run.mailbox}</td><td>{date(run.startedAt)}</td>
            <td>{date(run.windowFrom)} – {date(run.windowThrough)}<br />
              {run.discoveredAt ? t('Seznam uložen', 'Discovery saved') : t('Bez potvrzení seznamu', 'Discovery not confirmed')}
            </td>
            <td>{statuses[run.status]}</td><td>{run.discoveredAt ? run.discoveredCount : '–'}</td>
            <td>{run.importedCount}</td><td>{run.pendingCount ?? '–'}</td>
          </tr>)}</tbody>
        </table>
      </div>}
    </section>
  </>
}
