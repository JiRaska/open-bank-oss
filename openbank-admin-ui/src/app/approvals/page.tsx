// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Agent approval queue (ADR-0031 D4: agents propose, governance disposes). The
// ui-assistant can only RECORD proposals (draft_ticket, write_proposal tier) — they
// sit PROPOSED here until a human approves or rejects. Approval is the operator's
// recorded sign-off; the agent never executes. Segregation of duties (approver ≠
// author) is enforced by the agent.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { CheckCircle2, XCircle, Clock, ClipboardCheck, RefreshCw, ShieldCheck, AlertTriangle, Bot } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

interface Proposal {
  id: string
  title: string
  rationale: string
  suggestedAction: string
  proposedBy: string
  proposedAt: string
  state: 'PROPOSED' | 'APPROVED' | 'REJECTED'
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
  modelId: string | null
}

interface InboxItem {
  id: string
  domain: 'lending' | 'agent'
  action: string
  resourceId: string | null
  maker: string | null
  proposedAt: string | null
}

const STATE_META: Record<string, { color: string; bg: string; border: string; Icon: React.ElementType; cs: string; en: string }> = {
  PROPOSED: { color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: Clock, cs: 'Čeká na rozhodnutí', en: 'Pending' },
  APPROVED: { color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2, cs: 'Schváleno', en: 'Approved' },
  REJECTED: { color: '#dc2626', bg: '#fef2f2', border: '#fca5a5', Icon: XCircle, cs: 'Zamítnuto', en: 'Rejected' },
}

export default function ApprovalsPage() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const decidedBy = session?.user?.email || session?.user?.name || 'operator'

  const [rows, setRows] = useState<Proposal[]>([])
  const [domainItems, setDomainItems] = useState<InboxItem[]>([])
  const [domainSources, setDomainSources] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [proposalsRes, inboxRes] = await Promise.all([
        fetch('/api/agent/proposals?state=all', { cache: 'no-store' }),
        fetch('/api/approvals/pending', { cache: 'no-store' }),
      ])
      const data = await proposalsRes.json()
      setRows(Array.isArray(data) ? data : [])
      if (inboxRes.ok) {
        const inbox = await inboxRes.json()
        setDomainItems(Array.isArray(inbox.items) ? inbox.items : [])
        setDomainSources(inbox.sources ?? {})
      }
      setError(null)
    } catch {
      setError('unreachable')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const decide = async (p: Proposal, approve: boolean) => {
    setBusyId(p.id)
    try {
      const res = await fetch('/api/agent/proposals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ proposalId: p.id, approve, decidedBy, reason: reasons[p.id] || null }),
      })
      if (!res.ok) {
        const e = await res.json().catch(() => ({}))
        setError(e.error || t('Chyba při rozhodování', 'Decision failed'))
      } else {
        setError(null)
        await load()
      }
    } catch {
      setError('unreachable')
    } finally {
      setBusyId(null)
    }
  }

  const pending = useMemo(() => rows.filter(r => r.state === 'PROPOSED'), [rows])
  const decided = useMemo(() => rows.filter(r => r.state !== 'PROPOSED'), [rows])
  // Sources that answered anything other than 200 — a 403 here is ordinary (lending's list is
  // desk-role gated while this page is not), and it must never look like an empty queue.
  const degradedSources = useMemo(
    () => Object.entries(domainSources).filter(([, v]) => v !== 'ok').map(([k]) => k),
    [domainSources],
  )

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Schvalování', 'Approvals')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <ClipboardCheck size={18} style={{ color: 'var(--accent)' }} />
            {t('Fronta schvalování (AI agent)', 'Approval queue (AI agent)')}
          </h1>
          <p className="page-subtitle">
            {t(
              'Agent navrhuje, governance rozhoduje (ADR-0031 D4). Návrhy nemají žádný efekt, dokud je člověk neschválí. Schválení musí udělat někdo jiný než autor.',
              'Agents propose, governance disposes (ADR-0031 D4). Proposals have no effect until a human approves them. The approver must differ from the author.',
            )}
          </p>
        </div>
        <button onClick={load} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {error && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid #dc2626', display: 'flex', gap: 8, alignItems: 'center', color: '#dc2626', fontSize: 13 }}>
          <AlertTriangle size={15} /> {error === 'unreachable' ? t('Agent je nedostupný.', 'Agent unreachable.') : error}
        </div>
      )}

      {/* ADR-0227 D2/D4: domain maker-checker queues, federated. Read-only here — disposal
          belongs to the governed per-domain flows (money-path adds SCA). */}
      <div style={{ fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', margin: '4px 0 10px' }}>
        {t('Doménová schvalování (money-path)', 'Domain approvals (money-path)')} ({domainItems.filter(i => i.domain !== 'agent').length})
      </div>
      {degradedSources.length > 0 && (
        <div className="card" style={{
          padding: 14, marginBottom: 16, fontSize: 13,
          color: '#92400e', background: '#fffbeb', border: '1px solid #fcd34d',
        }}>
          {t(
            `Fronta není úplná — nepodařilo se načíst: ${degradedSources.join(', ')}. Prázdný seznam neznamená, že nic nečeká.`,
            `This queue is incomplete — could not read: ${degradedSources.join(', ')}. An empty list does not mean nothing is pending.`,
          )}
        </div>
      )}
      {!loading && degradedSources.length === 0 && domainItems.filter(i => i.domain !== 'agent').length === 0 && (
        <div className="card" style={{ padding: 16, marginBottom: 16, color: 'var(--text-secondary)', fontSize: 13 }}>
          {t('Žádná doménová schvalování nečekají.', 'No domain approvals pending.')}
        </div>
      )}
      <div style={{ display: 'grid', gap: 10, marginBottom: 24 }}>
        {domainItems.filter(i => i.domain !== 'agent').map(item => (
          <div key={`${item.domain}:${item.id}`} className="card" style={{ padding: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{
              fontSize: 10, fontWeight: 800, padding: '2px 8px', borderRadius: 10, textTransform: 'uppercase',
              color: '#1d4ed8', background: '#eff6ff', border: '1px solid #bfdbfe', flexShrink: 0,
            }}>
              {item.domain}
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>{item.action}</div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 2 }}>
                {item.resourceId && <span style={{ fontFamily: 'var(--font-mono)' }}>{item.resourceId} · </span>}
                {item.maker && <span>{t('navrhl', 'by')} {item.maker}</span>}
                {item.proposedAt && <span> · {new Date(item.proposedAt).toLocaleString()}</span>}
              </div>
            </div>
            <span style={{ fontSize: 10, fontWeight: 700, color: '#d97706', background: '#fffbeb', border: '1px solid #fcd34d', padding: '2px 7px', borderRadius: 20, textTransform: 'uppercase', flexShrink: 0 }}>
              {t('Čeká', 'Pending')}
            </span>
          </div>
        ))}
      </div>

      <div style={{ fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', margin: '4px 0 10px' }}>
        {t('Čeká na rozhodnutí (AI agent)', 'Pending (AI agent)')} ({pending.length})
      </div>
      {!loading && pending.length === 0 && (
        <div className="card" style={{ padding: 20, color: 'var(--text-secondary)', fontSize: 13 }}>
          {t('Žádné návrhy nečekají na schválení. Agent může návrh vytvořit nástrojem draft_ticket.', 'No proposals awaiting approval. The agent can create one via the draft_ticket tool.')}
        </div>
      )}
      <div style={{ display: 'grid', gap: 12 }}>
        {pending.map(p => {
          const m = STATE_META[p.state]
          const aiGenerated = /assistant|agent|\bai\b/i.test(p.proposedBy)
          return (
            <div key={p.id} className="card" style={{ padding: 18, borderLeft: `3px solid ${aiGenerated ? '#d97706' : m.color}` }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10, marginBottom: 8 }}>
                <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: 8 }}>
                  {p.title}
                  {aiGenerated && (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 10, fontWeight: 700, color: '#b45309', background: '#fffbeb', border: '1px solid #fcd34d', padding: '2px 7px', borderRadius: 20, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <Bot size={11} /> {t('AI návrh', 'AI-generated')}
                    </span>
                  )}
                </div>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0, fontSize: 11, fontWeight: 700, color: m.color, background: m.bg, border: `1px solid ${m.border}`, padding: '2px 8px', borderRadius: 20 }}>
                  <m.Icon size={11} /> {t(m.cs, m.en)}
                </span>
              </div>
              {aiGenerated && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: '#b45309', background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: 6, padding: '8px 10px', marginBottom: 8 }}>
                  <AlertTriangle size={14} style={{ flexShrink: 0 }} />
                  {t(
                    'Tento návrh vytvořila AI. Nezakládá žádnou autoritu — než schválíš, nezávisle ověř, že je legitimní a žádaný (ADR-0080).',
                    'This proposal was generated by AI. It carries no authority on its own — independently verify it is legitimate and intended before approving (ADR-0080).',
                  )}
                </div>
              )}
              <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.5, marginBottom: 8 }}>{p.rationale}</div>
              <div style={{ fontSize: 12, color: 'var(--text-primary)', background: 'var(--surface-2)', borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
                <span style={{ fontWeight: 700 }}>{t('Navrhovaná akce: ', 'Suggested action: ')}</span>{p.suggestedAction}
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginBottom: 10 }}>
                {t('Navrhl', 'Proposed by')} <b>{p.proposedBy}</b> · {new Date(p.proposedAt).toLocaleString('cs-CZ')}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <input
                  placeholder={t('Důvod rozhodnutí (volitelné)', 'Decision reason (optional)')}
                  value={reasons[p.id] || ''}
                  onChange={e => setReasons(r => ({ ...r, [p.id]: e.target.value }))}
                  style={{ flex: 1, minWidth: 180, fontSize: 12, padding: '6px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)' }}
                />
                <button onClick={() => decide(p, true)} disabled={busyId === p.id}
                  style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, padding: '6px 14px', borderRadius: 6, border: '1px solid #6ee7b7', background: '#ecfdf5', color: '#059669', cursor: 'pointer' }}>
                  <CheckCircle2 size={14} /> {t('Schválit', 'Approve')}
                </button>
                <button onClick={() => decide(p, false)} disabled={busyId === p.id}
                  style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, padding: '6px 14px', borderRadius: 6, border: '1px solid #fca5a5', background: '#fef2f2', color: '#dc2626', cursor: 'pointer' }}>
                  <XCircle size={14} /> {t('Zamítnout', 'Reject')}
                </button>
              </div>
            </div>
          )
        })}
      </div>

      {decided.length > 0 && (
        <>
          <div style={{ fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', margin: '24px 0 10px', display: 'flex', alignItems: 'center', gap: 6 }}>
            <ShieldCheck size={13} /> {t('Rozhodnuté', 'Decided')} ({decided.length})
          </div>
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ textAlign: 'left', color: 'var(--text-secondary)', background: 'var(--surface-2)' }}>
                  <th style={{ padding: '8px 14px' }}>{t('Návrh', 'Proposal')}</th>
                  <th style={{ padding: '8px 14px' }}>{t('Stav', 'State')}</th>
                  <th style={{ padding: '8px 14px' }}>{t('Rozhodl', 'Decided by')}</th>
                  <th style={{ padding: '8px 14px' }}>{t('Důvod', 'Reason')}</th>
                </tr>
              </thead>
              <tbody>
                {decided.map(p => {
                  const m = STATE_META[p.state]
                  return (
                    <tr key={p.id} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={{ padding: '8px 14px', color: 'var(--text-primary)', fontWeight: 600 }}>{p.title}</td>
                      <td style={{ padding: '8px 14px' }}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: m.color, fontWeight: 700 }}><m.Icon size={12} /> {t(m.cs, m.en)}</span>
                      </td>
                      <td style={{ padding: '8px 14px', color: 'var(--text-secondary)' }}>{p.decidedBy || '—'}</td>
                      <td style={{ padding: '8px 14px', color: 'var(--text-tertiary)' }}>{p.decisionReason || '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
