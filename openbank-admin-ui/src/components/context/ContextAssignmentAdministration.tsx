// SPDX-License-Identifier: Apache-2.0

'use client'

import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useSession } from 'next-auth/react'
import { KeyRound } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Proposal = { id: string; principalId: string; caseId: string; purpose: string; validTo: string; makerId: string }
type Assignment = { id: string; principalId: string; caseId: string; purpose: string; validTo: string }

export function ContextAssignmentAdministration() {
  const { data: session } = useSession()
  const { t } = useLanguage()
  const [pending, setPending] = useState<Proposal[]>([])
  const [active, setActive] = useState<Assignment[]>([])
  const [principalId, setPrincipalId] = useState('')
  const [caseId, setCaseId] = useState('')
  const [purpose, setPurpose] = useState('PAYMENT_COMPLAINT')
  const [hours, setHours] = useState(8)
  const [message, setMessage] = useState<string | null>(null)
  const isAdmin = session?.user?.roles?.includes('ROLE_ADMIN') ?? false

  const load = useCallback(async () => {
    const [pendingResponse, activeResponse] = await Promise.all([
      fetch('/api/context/assignments/pending', { cache: 'no-store' }),
      fetch('/api/context/assignments', { cache: 'no-store' }),
    ])
    if (!pendingResponse.ok || !activeResponse.ok) throw new Error('assignment service unavailable')
    const [pendingBody, activeBody] = await Promise.all([pendingResponse.json(), activeResponse.json()])
    if (!Array.isArray(pendingBody) || !pendingBody.every(isProposal) ||
        !Array.isArray(activeBody) || !activeBody.every(isAssignment)) throw new Error('invalid assignment response')
    setPending(pendingBody); setActive(activeBody)
  }, [])

  useEffect(() => {
    if (!isAdmin) return
    const timer = window.setTimeout(() => {
      void load().catch(() => setMessage(t('Správa přístupů není dostupná.', 'Access administration is unavailable.')))
    }, 0)
    return () => window.clearTimeout(timer)
  }, [isAdmin, load, t])
  if (!isAdmin) return null

  async function propose(event: FormEvent) {
    event.preventDefault(); setMessage(null)
    try {
      const validTo = new Date(Date.now() + Math.min(Math.max(hours, 1), 24 * 31) * 3_600_000).toISOString()
      const response = await fetch('/api/context/assignments', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ principalId: principalId.trim(), caseId: caseId.trim(), purpose, validTo }),
      })
      if (!response.ok) throw new Error('proposal rejected')
      setPrincipalId(''); setCaseId(''); setMessage(t('Návrh čeká na nezávislé schválení.', 'The proposal awaits independent approval.')); await load()
    } catch { setMessage(t('Návrh přístupu se nepodařilo uložit.', 'The access proposal could not be saved.')) }
  }

  async function decide(id: string, approve: boolean) {
    try {
      const response = await fetch(`/api/context/assignments/${id}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ approve }),
      })
      if (!response.ok) throw new Error('decision rejected')
      setMessage(t('Rozhodnutí bylo auditně zaznamenáno.', 'The decision was recorded in the audit trail.')); await load()
    } catch { setMessage(t('Rozhodnutí nebylo přijato; maker nesmí schválit vlastní návrh.', 'The decision was rejected; a maker cannot approve their own proposal.')) }
  }

  async function revoke(id: string) {
    try {
      const response = await fetch(`/api/context/assignments/${id}`, { method: 'DELETE' })
      if (!response.ok) throw new Error('revocation rejected')
      setMessage(t('Přístup byl okamžitě odvolán.', 'Access was revoked immediately.')); await load()
    } catch { setMessage(t('Odvolání přístupu selhalo.', 'Access revocation failed.')) }
  }

  return <details className="card" style={{ padding: 18, marginBottom: 24 }}>
    <summary style={{ cursor: 'pointer', fontWeight: 700 }}><KeyRound size={16} aria-hidden="true" /> {t('Správa přístupů ke grafu', 'Graph access administration')}</summary>
    <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Časově omezené přidělení vyžaduje jiného schvalovatele. Odvolání platí okamžitě.', 'Time-bound assignment requires a different approver. Revocation takes effect immediately.')}</p>
    <form onSubmit={propose} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 160px), 1fr))', gap: 8 }}>
      <input required className="input" value={principalId} onChange={e => setPrincipalId(e.target.value)} placeholder={t('Uživatel', 'Principal')} aria-label={t('Uživatel', 'Principal')} />
      <input required className="input" value={caseId} onChange={e => setCaseId(e.target.value)} placeholder={t('ID případu', 'Case ID')} aria-label={t('ID případu', 'Case ID')} />
      <select className="input" value={purpose} onChange={e => setPurpose(e.target.value)} aria-label={t('Účel', 'Purpose')}><option>PAYMENT_COMPLAINT</option><option>INCIDENT_IMPACT</option></select>
      <input className="input" type="number" min={1} max={744} value={hours} onChange={e => setHours(Number(e.target.value))} aria-label={t('Platnost v hodinách', 'Validity in hours')} />
      <button className="btn btn-primary">{t('Navrhnout', 'Propose')}</button>
    </form>
    {message && <p role="status">{message}</p>}
    {pending.length > 0 && <div><h3>{t('Čeká na checker', 'Awaiting checker')}</h3>{pending.map(item => <div key={item.id} style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}><code>{item.caseId}</code><span>{item.principalId} · {item.purpose}</span><button className="btn btn-primary btn-sm" onClick={() => void decide(item.id, true)}>{t('Schválit', 'Approve')}</button><button className="btn btn-secondary btn-sm" onClick={() => void decide(item.id, false)}>{t('Zamítnout', 'Reject')}</button></div>)}</div>}
    {active.length > 0 && <div><h3>{t('Aktivní přístupy', 'Active access')}</h3>{active.map(item => <div key={item.id} style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}><code>{item.caseId}</code><span>{item.principalId} · {item.purpose} · {new Date(item.validTo).toLocaleString()}</span><button className="btn btn-secondary btn-sm" onClick={() => void revoke(item.id)}>{t('Odvolat', 'Revoke')}</button></div>)}</div>}
  </details>
}

function record(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' }
function text(value: unknown): value is string { return typeof value === 'string' && value.length > 0 }
function isProposal(value: unknown): value is Proposal {
  return record(value) && text(value.id) && text(value.principalId) && text(value.caseId) &&
    text(value.purpose) && text(value.validTo) && text(value.makerId)
}
function isAssignment(value: unknown): value is Assignment {
  return record(value) && text(value.id) && text(value.principalId) && text(value.caseId) &&
    text(value.purpose) && text(value.validTo)
}
