// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'
import { Can } from '@/components/auth/AuthGuard'
import { useSession } from 'next-auth/react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui/PageHeader'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  ceuFilings, GSFT_CATALOGUE, latestByCode, loadStatutoryReturns, runReturnAction,
  type ActionResult, type BreachState, type ReturnAction, type ReturnCapability, type ReturnCatalogue, type StatutoryReturn,
} from '@/components/regulatory/statutoryReturns'

type Snapshot = { capability: ReturnCapability; returns: StatutoryReturn[]; catalogues: ReturnCatalogue[] | null; breaches: BreachState }
type State = { status: 'loading' } | { status: 'error'; kind: UnavailableKind } | { status: 'ready'; data: Snapshot }

export default function StatutoryReturnsPage() {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ status: 'loading' })

  const [reloads, setReloads] = useState(0)
  const reload = useCallback(() => setReloads(n => n + 1), [])

  useEffect(() => {
    let active = true
    loadStatutoryReturns().then(result => {
      if (active) setState(result.ok ? { status: 'ready', data: result.data } : { status: 'error', kind: result.kind })
    })
    return () => { active = false }
  }, [reloads])

  return (
    <div>
      <PageHeader
        title={t('Statutární výkazy', 'Statutory returns')}
        subtitle={t('Stav sestavení a zákonných termínů podle tax-reporting-service', 'Build status and statutory deadlines from tax-reporting-service')}
        breadcrumb={<Link href="/regulatory">{t('Regulatorní výkaznictví', 'Regulatory reporting')}</Link>}
      />
      {state.status === 'loading' && <p role="status">{t('Načítání výkazů…', 'Loading returns…')}</p>}
      {state.status === 'error' && <DataUnavailable kind={state.kind} service="Tax-reporting-service" lang={language} />}
      {state.status === 'ready' && <>
        <section aria-labelledby="capability-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="capability-heading">{t('Možnosti systému', 'System capabilities')}</h2>
          <p>{t('Datový zdroj', 'Data source')}: {state.data.capability.dataSourceAvailable ? t('dostupný', 'available') : t('nedostupný', 'unavailable')}</p>
          <p>{t('Regulatorní soubor', 'Regulatory file')}: {state.data.capability.wireFormatAvailable ? t('dostupný', 'available') : t('nedostupný', 'unavailable')}</p>
          <p>{state.data.capability.note}</p>
          <p>{t('Sestavení, schválení a záznam odeslání provádí pouze operátor (ROLE_OPERATOR). Schválit nesmí ten, kdo revizi sestavil (čtyři oči) — služba to odmítne.',
            'Only an operator (ROLE_OPERATOR) assembles, approves and records submission. The person who assembled a revision may not approve it (four eyes) — the service refuses.')}</p>
        </section>
        <CatalogueSection catalogues={state.data.catalogues} returns={state.data.returns} onChanged={reload} />
        <section aria-labelledby="breaches-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
          <h2 id="breaches-heading">{t('Překročené termíny', 'Missed deadlines')}</h2>
          {!state.data.breaches.known ? <p role="status">{t('Kontrolu termínů nelze vyhodnotit: služba ji odmítla spočítat (např. není nastaven začátek reportingu nebo seznam fondů). Nejde o potvrzení, že žádný termín nebyl překročen.', 'Deadline status cannot be evaluated: the service refused to compute it (for example, no reporting start or fund roster is configured). This is not a confirmation that no deadline was missed.')}
              {state.data.breaches.reason ? ` ${t('Důvod služby', 'Service reason')}: ${state.data.breaches.reason}` : ''}</p> :
            state.data.breaches.items.length === 0 ? <p>{t('API nehlásí žádný překročený termín.', 'The API reports no missed deadline.')}</p> :
            <ul>{state.data.breaches.items.map(item =>
              <li key={`${item.catalogueId}/${item.returnCode}/${item.entityId}/${item.period}`}>
                {item.returnCode} · {item.entityId} · {item.period} · {item.kind} · {t('termín', 'due')} {item.dueDate}
              </li>)}</ul>}
        </section>
        <CeuSection returns={state.data.returns} />
        <section aria-labelledby="returns-heading" className="card" style={{ padding: 20 }}>
          <h2 id="returns-heading">{t('Revize výkazů', 'Return revisions')}</h2>
          {state.data.returns.length === 0 ? <p>{t('API zatím nevrátilo žádnou sestavenou revizi.', 'The API has not returned any built revision yet.')}</p> :
            <ul>{state.data.returns.map(item =>
              <li key={item.id} style={{ marginBottom: 8 }}>
                {item.returnCode} · {item.entityId} · {item.period} · {t('revize', 'revision')} {item.revision} · {item.status}
                {item.assembledBy ? ` · ${t('sestavil', 'assembled by')} ${item.assembledBy}` : ''}
                {item.submissionReference ? ` · ${t('reference', 'reference')} ${item.submissionReference}` : ''}
                <Can permission="statutory-returns:operate"><RevisionActions item={item} onChanged={reload} /></Can>
              </li>)}</ul>}
        </section>
      </>}
    </div>
  )
}

/** ADR-0367 pilot: the CEÚ daily change overview, read-only. Approval, SDAT delivery and ČNB's answer are recorded in the backend. */
function CeuSection({ returns }: { returns: StatutoryReturn[] }) {
  const { t } = useLanguage()
  const filings = ceuFilings(returns)
  const show = (value: number | null) => (value === null ? t('neuvedeno', 'not reported') : String(value))
  return (
    <section aria-labelledby="ceu-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
      <h2 id="ceu-heading">{t('CEÚ – denní přehled změn (ČNB)', 'CEÚ – daily change overview (ČNB)')}</h2>
      <p>{t('Každý pracovní den do 12:00 přehled změn účtů za období od předchozího pracovního dne. Syntetičtí zákazníci jsou vždy vyloučeni a jejich počet je uveden.',
        'Every working day by 12:00, the account changes since the previous working day. Synthetic customers are always excluded and their count is shown.')}</p>
      {filings.length === 0 ? <p>{t('API zatím nevrátilo žádný přehled CEÚ. Vykazování CEÚ začíná od nastaveného data začátku vykazování (OPENBANK_CEU_REPORTING_START); prázdný seznam před tímto dnem je očekávaný.',
        'The API has not returned any CEÚ overview yet. CEÚ reporting starts at the configured reporting start (OPENBANK_CEU_REPORTING_START); an empty list before that date is expected.')}</p> :
        <table className="table">
          <thead>
            <tr>
              <th>{t('Subjekt', 'Entity')}</th>
              <th>{t('Období do', 'Window end')}</th>
              <th>{t('Stav', 'Status')}</th>
              <th>{t('Změny', 'Changes')}</th>
              <th>{t('Vyloučené syntetické účty / vazby', 'Excluded synthetic accounts / ties')}</th>
              <th>{t('Reference SDAT', 'SDAT reference')}</th>
              <th>{t('Odpověď ČNB', 'ČNB answer')}</th>
            </tr>
          </thead>
          <tbody>
            {filings.map(item => (
              <tr key={item.id}>
                <td>{item.entityId}</td>
                <td>{item.window}{item.initialLoad ? ` · ${t('úvodní plné načtení', 'initial full load')}` : ''}</td>
                <td>{item.status} · {t('revize', 'revision')} {item.revision}</td>
                <td>{show(item.changes)}</td>
                <td>{show(item.syntheticAccountsDropped)} / {show(item.syntheticRelationsDropped)}</td>
                <td>{item.submissionReference ?? '—'}</td>
                <td>{item.acknowledgement ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>}
    </section>
  )
}

/** Every catalogue and return code the deployment reports under, with the latest revision per code. */
function CatalogueSection({ catalogues, returns, onChanged }: { catalogues: ReturnCatalogue[] | null; returns: StatutoryReturn[]; onChanged: () => void }) {
  const { t } = useLanguage()
  const latest = latestByCode(returns)
  return (
    <section aria-labelledby="catalogues-heading" className="card" style={{ padding: 20, marginBottom: 16 }}>
      <h2 id="catalogues-heading">{t('Katalogy výkazů', 'Return catalogues')}</h2>
      {catalogues === null ? <p role="status">{t('Katalogy výkazů se nepodařilo načíst. Nejde o potvrzení, že žádný výkaz neexistuje.', 'The return catalogues could not be loaded. This is not a confirmation that no return exists.')}</p> :
        catalogues.length === 0 ? <p>{t('Služba nehlásí žádný katalog výkazů.', 'The service reports no return catalogue.')}</p> :
        catalogues.map(catalogue => (
          <div key={catalogue.id} style={{ marginBottom: 16 }}>
            <h3>{catalogue.id} · v{catalogue.version} · {catalogue.jurisdiction}
              {catalogue.wireFormatVerified ? '' : ` · ${t('formát souboru neověřen', 'wire format not verified')}`}</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>{t('Kód', 'Code')}</th>
                  <th>{t('Název', 'Name')}</th>
                  <th>{t('Periodicita', 'Periodicity')}</th>
                  <th>{t('Právní základ', 'Legal basis')}</th>
                  <th>{t('Na vyžádání', 'On demand')}</th>
                  <th>{t('Poslední revize', 'Latest revision')}</th>
                </tr>
              </thead>
              <tbody>
                {catalogue.returns.map(definition => {
                  const last = latest.get(`${catalogue.id}/${definition.code}`)
                  return (
                    <tr key={definition.code} data-testid={`return-${catalogue.id}-${definition.code}`}>
                      <td>{definition.code}</td>
                      <td>{definition.name}</td>
                      <td>{definition.periodicity}</td>
                      <td>{definition.legalBasis}</td>
                      <td>{definition.onDemand ? t('ano', 'yes') : t('ne', 'no')}</td>
                      <td>{last ? last.map(item => <div key={item.id}>{`${item.entityId} · ${item.period} · ${t('revize', 'revision')} ${item.revision} · ${item.status}`}</div>) : t('zatím nesestaveno', 'not assembled yet')}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        ))}
      {catalogues && catalogues.length > 0 && <Can permission="statutory-returns:operate"><AssembleForm catalogues={catalogues} onChanged={onChanged} /></Can>}
    </section>
  )
}

function useAction(onChanged: () => void) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ActionResult | null>(null)

  const run = async (action: ReturnAction) => {
    setBusy(true)
    const outcome = await runReturnAction(action)
    setBusy(false)
    setResult(outcome)
    if (outcome.ok) onChanged()
  }
  return { busy, result, run }
}

/** The backend's refusal, shown verbatim with its status: 409 (state, four eyes), 422 (validation), 503 (not configured). */
function ActionOutcome({ result }: { result: ActionResult | null }) {
  const { t } = useLanguage()
  if (!result) return null
  if (result.ok) return <p role="status">{t('Hotovo', 'Done')}: {result.data.returnCode} · {result.data.period} · {result.data.status}
    {result.package && <> · <a href={`data:application/pgp-encrypted;base64,${result.package.encryptedPackage}`} download={result.package.fileName}>
      {t('Stáhnout zašifrovaný balík', 'Download encrypted package')} ({result.package.fileName})</a></>}</p>
  const label = result.status === 503 ? t('Služba to nyní nemůže provést (není nakonfigurováno)', 'The service cannot do this now (not configured)')
    : result.status === 409 ? t('Odmítnuto stavem nebo pravidlem čtyř očí', 'Refused by state or the four-eyes rule')
    : result.status === 422 || result.status === 400 ? t('Neplatný požadavek', 'Invalid request')
    : result.status === 401 || result.status === 403 ? t('Nemáte oprávnění', 'Not permitted')
    : result.status === 0 ? t('Služba je nedostupná', 'Service unreachable')
    : t('Akce selhala', 'Action failed')
  return <p role="alert">{label}{result.message ? `: ${result.message}` : ''}</p>
}

function AssembleForm({ catalogues, onChanged }: { catalogues: ReturnCatalogue[]; onChanged: () => void }) {
  const { t } = useLanguage()
  const options = catalogues.flatMap(c => c.returns.map(r => ({ key: `${c.id}/${r.code}`, catalogueId: c.id, definition: r })))
  const [selected, setSelected] = useState(options[0]?.key ?? '')
  const [entityId, setEntityId] = useState('company')
  const [period, setPeriod] = useState('')
  const { busy, result, run } = useAction(onChanged)
  const choice = options.find(o => o.key === selected)
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!choice) return
    void run({ kind: 'assemble', body: { catalogueId: choice.catalogueId, returnCode: choice.definition.code, entityId: entityId.trim(), period: period.trim() } })
  }
  return (
    <form onSubmit={submit} aria-label={t('Sestavit výkaz', 'Assemble a return')} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'end' }}>
      <label>{t('Výkaz', 'Return')}
        <select value={selected} onChange={e => setSelected(e.target.value)}>
          {options.map(o => <option key={o.key} value={o.key}>{o.key}</option>)}
        </select>
      </label>
      <label>{t('Subjekt', 'Entity')}
        <input value={entityId} onChange={e => setEntityId(e.target.value)} required />
      </label>
      <label>{t('Období', 'Period')}
        <input value={period} onChange={e => setPeriod(e.target.value)} required
          placeholder={choice ? periodHint(choice.definition.periodicity) : ''} />
      </label>
      <button type="submit" className="btn btn-primary" disabled={busy || !choice}>{t('Sestavit', 'Assemble')}</button>
      <div style={{ flexBasis: '100%' }}><ActionOutcome result={result} /></div>
    </form>
  )
}

function periodHint(periodicity: string) {
  switch (periodicity) {
    case 'MONTH': return 'YYYY-MM'
    case 'QUARTER': return 'YYYY-Qn'
    case 'YEAR': return 'YYYY'
    default: return 'YYYY-MM-DD'
  }
}

/** The next lifecycle step for one revision. GSFT is delivered through its own route, never /submitted. */
function RevisionActions({ item, onChanged }: { item: StatutoryReturn; onChanged: () => void }) {
  const { t } = useLanguage()
  const [text, setText] = useState('')
  const [ack, setAck] = useState('')
  const { busy, result, run } = useAction(onChanged)
  const { data: session } = useSession()
  const gsft = item.catalogueId === GSFT_CATALOGUE
  // Four eyes (StatutoryReturn.approve): the backend records the JWT `sub` as assembledBy, and
  // the session's user.id is that same `sub`, so the assembler is never offered Approve.
  const isAssembler = Boolean(item.assembledBy && session?.user?.id && item.assembledBy === session.user.id)
  const ackControls = <>
    <input aria-label={t('Reference potvrzení nebo důvod zamítnutí', 'Acknowledgement reference or rejection reason')} value={ack} onChange={e => setAck(e.target.value)} />
    <button type="button" className="btn btn-secondary" disabled={busy || !ack.trim()}
      onClick={() => void run({ kind: 'acknowledgement', id: item.id, accepted: true, text: ack.trim() })}>{t('Potvrzeno', 'Acknowledged')}</button>
    <button type="button" className="btn btn-secondary" disabled={busy || !ack.trim()}
      onClick={() => void run({ kind: 'acknowledgement', id: item.id, accepted: false, text: ack.trim() })}>{t('Zamítnuto', 'Rejected')}</button>
  </>
  let controls: React.ReactNode = null
  if (item.status === 'ASSEMBLED') {
    controls = isAssembler
      ? <p>{t('Tuto revizi jste sestavili, schválit ji musí jiný operátor (čtyři oči).', 'You assembled this revision; another operator must approve it (four eyes).')}</p>
      : <button type="button" className="btn btn-secondary" disabled={busy}
        onClick={() => void run({ kind: 'approve', id: item.id })}>{t('Schválit', 'Approve')}</button>
  } else if (item.status === 'APPROVED' || (gsft && item.status === 'SUBMITTED' && item.submissionReference)) {
    const again = item.status === 'SUBMITTED'
    controls = <>
      {!again && <input aria-label={t('Reference odeslání', 'Submission reference')} value={text} onChange={e => setText(e.target.value)} />}
      <button type="button" className="btn btn-secondary" disabled={busy || (!again && !text.trim())}
        onClick={() => void run({ kind: gsft ? 'gsft-delivery' : 'submitted', id: item.id, reference: again ? item.submissionReference! : text.trim() })}>
        {again ? t('Znovu získat zašifrovaný balík GSFT', 'Fetch the encrypted GSFT package again')
          : gsft ? t('Zašifrovat a odeslat GSFT', 'Encrypt and deliver to GSFT') : t('Zaznamenat odeslání', 'Record submission')}
      </button>
    </>
  }
  if (item.status === 'SUBMITTED') {
    controls = <>{controls}{ackControls}</>
  }
  if (!controls) return null
  return <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 4 }}>{controls}<ActionOutcome result={result} /></div>
}
