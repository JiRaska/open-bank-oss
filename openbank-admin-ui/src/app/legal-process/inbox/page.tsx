// SPDX-License-Identifier: Apache-2.0

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { z } from 'zod'
import { Inbox } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { EmptyState, LoadingState, PageHeader, StatusBadge } from '@/components/ui'

const base = '/api/legal-inbox'
const teams = ['ENFORCEMENT_CZ', 'LEGAL_REVIEW_CZ'] as const
const extraction = z.enum(['DRAFT', 'MANUAL_REVIEW'])
const receipt = z.object({
  receiptId: z.uuid(),
  deliveryId: z.string(),
  recordedAt: z.iso.datetime({ offset: true }),
  revision: z.number().int().nonnegative(),
  team: z.enum(teams).nullable(),
  extractionStatus: extraction.nullable(),
})
const queue = z.object({ items: z.array(receipt), hasMore: z.boolean() })
const detail = z.object({
  receipt,
  draft: z.object({
    status: extraction,
    subject: z.string().nullable(),
    senderReference: z.string().nullable(),
    attachmentCount: z.number().int().nonnegative().nullable(),
  }).nullable(),
  canReadEvidence: z.boolean(),
  canAssign: z.boolean(),
})

type Team = (typeof teams)[number]
type Receipt = z.infer<typeof receipt>

class RequestFailure extends Error {
  constructor(readonly status: number) { super('Legal inbox request failed') }
}

async function checkedFetch(path: string, options?: RequestInit) {
  const result = await fetch(base + path, { cache: 'no-store', ...options })
  if (!result.ok) throw new RequestFailure(result.status)
  return result
}

export default function LegalInboxPage() {
  const { t, language } = useLanguage()
  const [offset, setOffset] = useState(0)
  const [records, setRecords] = useState<z.infer<typeof queue> | null>(null)
  const [listError, setListError] = useState<'blocked' | 'load' | null>(null)
  const [selected, setSelected] = useState<Receipt | null>(null)
  const [opened, setOpened] = useState<z.infer<typeof detail> | null>(null)
  const [team, setTeam] = useState<Team | ''>('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const actionInFlight = useRef(false)

  const readQueue = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await checkedFetch('?offset=' + offset, { signal })
      const next = queue.parse(await response.json())
      if (!signal?.aborted) {
        setRecords(next)
        setListError(null)
      }
    } catch (error) {
      if (!signal?.aborted) {
        setRecords(null)
        setListError(error instanceof RequestFailure && [401, 403].includes(error.status) ? 'blocked' : 'load')
      }
    }
  }, [offset])

  useEffect(() => {
    const abort = new AbortController()
    void readQueue(abort.signal)
    return () => abort.abort()
  }, [readQueue])

  const readDetail = useCallback(async (id: string, signal?: AbortSignal) => {
    const response = await checkedFetch('/' + id, { signal })
    const next = detail.parse(await response.json())
    if (next.receipt.receiptId !== id) throw new Error('Unexpected receipt')
    if (!signal?.aborted) {
      setOpened(next)
      setTeam(next.receipt.team ?? '')
    }
  }, [])

  const id = selected?.receiptId
  useEffect(() => {
    if (!id) return
    const abort = new AbortController()
    void readDetail(id, abort.signal).catch(() => {
      if (!abort.signal.aborted) setMessage('detail')
    })
    return () => abort.abort()
  }, [id, readDetail])

  function select(row: Receipt | null) {
    setSelected(row)
    setOpened(null)
    setTeam('')
    setMessage('')
  }

  async function action(operation: 'extract' | 'assignment', body?: object) {
    if (!id || actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    try {
      await checkedFetch('/' + id + '/' + operation, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      try {
        await readDetail(id)
        setMessage(operation === 'assignment' ? 'assigned' : 'extracted')
      } catch {
        setOpened(null)
        setMessage('refresh')
      }
      await readQueue()
    } catch (error) {
      if (error instanceof RequestFailure && error.status === 409) {
        await readDetail(id).catch(() => setOpened(null))
        await readQueue()
        setMessage('conflict')
      } else if (error instanceof RequestFailure && [401, 403, 404].includes(error.status)) {
        setOpened(null)
        setMessage('detail')
      } else {
        setMessage('error')
      }
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  async function download(file: 'message' | 'delivery') {
    if (!id || actionInFlight.current) return
    actionInFlight.current = true
    setBusy(true)
    setMessage('')
    try {
      const response = await checkedFetch('/' + id + '/originals/' + file)
      if (response.headers.get('Content-Type')?.split(';')[0] !== 'application/octet-stream') throw new Error('Unexpected media')
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a')
      link.href = url
      link.download = `isds-${file}.zfo`
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch {
      setMessage('download')
    } finally {
      actionInFlight.current = false
      setBusy(false)
    }
  }

  const labels: Record<string, string> = {
    detail: t('Zprávu nelze načíst.', 'The message could not be loaded.'),
    error: t('Výsledek operace se nepodařilo potvrdit. Obnovte zprávu.', 'The action could not be confirmed. Refresh the message.'),
    refresh: t('Operace proběhla, detail se nepodařilo obnovit.', 'The action succeeded, but the details could not be refreshed.'),
    conflict: t('Přidělení mezitím změnil jiný operátor. Zkontrolujte aktuální stav.', 'Another operator changed the assignment. Review the current state.'),
    assigned: t('Přidělení uloženo.', 'Assignment saved.'),
    extracted: t('Vytěžení dokončeno.', 'Extraction finished.'),
    download: t('Originál se nepodařilo stáhnout.', 'The original could not be downloaded.'),
  }
  const teamName = (value: Team | null) => value === null
    ? t('Nepřiděleno', 'Unassigned')
    : value === 'ENFORCEMENT_CZ' ? t('Exekuce CZ', 'Enforcement CZ') : t('Právní kontrola CZ', 'Legal review CZ')
  const status = (value: Receipt['extractionStatus']) => value === 'DRAFT'
    ? t('Neověřený návrh', 'Unverified draft')
    : value === 'MANUAL_REVIEW' ? t('Ruční kontrola', 'Manual review') : t('Čeká na vytěžení', 'Awaiting extraction')

  return <>
    <PageHeader title={t('Doručené právní zprávy', 'Legal inbox')} icon={<Inbox aria-hidden="true" size={23} />} />
    <section className="card" aria-label={t('Fronta ISDS', 'ISDS queue')}>
      {listError === 'blocked' && <p role="alert">{t('Nemáte přístup k datové schránce.', 'You do not have access to a data mailbox.')}</p>}
      {listError === 'load' && <p role="alert">{t('Frontu nelze načíst.', 'The queue could not be loaded.')} <button className="btn btn-secondary" onClick={() => void readQueue()}>{t('Zkusit znovu', 'Retry')}</button></p>}
      {!listError && records === null && <LoadingState label={t('Načítání…', 'Loading…')} />}
      {records?.items.length === 0 && <EmptyState title={t('V této části fronty nejsou zprávy', 'There are no messages on this page')} />}
      {!!records?.items.length && <div className="overflow-x-auto">
        <table className="data-table">
          <thead><tr><th scope="col">{t('Zpráva ISDS', 'ISDS message')}</th><th scope="col">{t('Importováno', 'Imported')}</th><th scope="col">{t('Tým', 'Team')}</th><th scope="col">{t('Vytěžení', 'Extraction')}</th></tr></thead>
          <tbody>{records.items.map(row => <tr key={row.receiptId}>
            <td><button className="btn btn-secondary" disabled={busy} aria-pressed={id === row.receiptId} onClick={() => { if (id !== row.receiptId) select(row) }}>{row.deliveryId}</button></td>
            <td>{new Date(row.recordedAt).toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')}</td>
            <td>{teamName(row.team)}</td><td>{status(row.extractionStatus)}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      {(offset > 0 || records?.hasMore) && <div className="flex flex-wrap gap-2 p-3">
        <button className="btn btn-secondary" disabled={busy || offset === 0} onClick={() => { select(null); setRecords(null); setOffset(offset - 50) }}>{t('Předchozí', 'Previous')}</button>
        <button className="btn btn-secondary" disabled={busy || !records?.hasMore} onClick={() => { select(null); setRecords(null); setOffset(offset + 50) }}>{t('Další', 'Next')}</button>
      </div>}
    </section>
    {selected && <section className="card p-4 mt-4" aria-label={t('Detail zprávy', 'Message details')}>
      <div className="flex flex-wrap justify-between gap-2 mb-3">
        <h2>{t('Zpráva ISDS', 'ISDS message')} {selected.deliveryId}</h2>
        <button className="btn btn-secondary" disabled={busy} onClick={() => select(null)}>{t('Zavřít detail', 'Close details')}</button>
      </div>
      {message && <p role={['assigned', 'extracted'].includes(message) ? 'status' : 'alert'}>{labels[message]}</p>}
      {!opened && !message && <LoadingState label={t('Načítání…', 'Loading…')} />}
      {opened && <>
        <p className="mb-3"><StatusBadge status={opened.receipt.extractionStatus ?? 'PENDING'} label={status(opened.receipt.extractionStatus)} tone={opened.receipt.extractionStatus ? 'warning' : 'neutral'} /></p>
        {opened.canReadEvidence && <div className="flex flex-wrap gap-2 mb-3">
          <button className="btn btn-secondary" disabled={busy} onClick={() => void download('message')}>{t('Stáhnout originál ZFO', 'Download original ZFO')}</button>
          <button className="btn btn-secondary" disabled={busy} onClick={() => void download('delivery')}>{t('Stáhnout doručenku ZFO', 'Download receipt ZFO')}</button>
          <button className="btn btn-secondary" disabled={busy || opened.receipt.extractionStatus !== null} onClick={() => void action('extract')}>{t('Vytěžit obálku', 'Extract envelope')}</button>
        </div>}
        {opened.draft?.status === 'DRAFT' && <dl className="grid gap-2 mb-3">
          <div><dt>{t('Věc · dmAnnotation', 'Subject · dmAnnotation')}</dt><dd className="break-words">{opened.draft.subject || '–'}</dd></div>
          <div><dt>{t('Reference odesílatele · dmSenderRefNumber', 'Sender reference · dmSenderRefNumber')}</dt><dd className="break-words">{opened.draft.senderReference || '–'}</dd></div>
          <div><dt>{t('Počet příloh', 'Attachments')}</dt><dd>{opened.draft.attachmentCount}</dd></div>
        </dl>}
        {opened.draft?.status === 'MANUAL_REVIEW' && <p>{t('Obálku musí posoudit operátor.', 'An operator must review the envelope.')}</p>}
        {opened.canAssign && <form className="flex flex-wrap gap-2 items-end" onSubmit={event => {
          event.preventDefault()
          if (team && team !== opened.receipt.team) void action('assignment', { team, expectedRevision: opened.receipt.revision })
        }}>
          <label className="grid gap-1">{t('Přidělit týmu', 'Assign to team')}
            <select className="form-input" disabled={busy} value={team} onChange={event => setTeam(event.target.value as Team | '')} required>
              <option value="">{t('Vyberte tým', 'Select a team')}</option>
              {teams.map(value => <option key={value} value={value}>{teamName(value)}</option>)}
            </select>
          </label>
          <button type="submit" className="btn btn-primary" disabled={busy || !team || team === opened.receipt.team}>{t('Uložit přidělení', 'Save assignment')}</button>
        </form>}
      </>}
    </section>}
  </>
}
