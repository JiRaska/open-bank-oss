// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Agent approval queue (ADR-0031 D4: agents propose, governance disposes). The
// ui-assistant can only RECORD proposals (draft_ticket, write_proposal tier) — they
// sit PROPOSED here until a human approves or rejects. Approval is the operator's
// recorded sign-off; the agent never executes. Segregation of duties (approver ≠
// author) is enforced by the agent.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useSession } from 'next-auth/react'
import { CheckCircle2, XCircle, Clock, ClipboardCheck, RefreshCw, ShieldCheck, AlertTriangle, Bot, UserRound, ExternalLink, Search } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader } from '@/components/ui/PageHeader'
import { AgentIdentityBadge } from '@/components/approvals/AgentIdentityBadge'
import { resolveAgentIdentity, type AgentIdentityRegistry } from '@/lib/governance/agentIdentity'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
import {
  approvalWorkbenchHref,
  filterAndSortDomainApprovals,
  type ApprovalDomain,
  type ApprovalSortOrder,
  type DomainApprovalItem,
} from '@/lib/approvals/triage'

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
  agent?: { id: string; displayName: string; icon: 'bot' | 'user'; charterKnown: boolean }
}

interface InboxItem extends Omit<DomainApprovalItem, 'domain'> {
  id: string
  domain: ApprovalDomain | 'agent'
  action: string
  resourceId: string | null
  maker: string | null
  proposedAt: string | null
}

type DecisionIntent = { proposal: Proposal; approve: boolean }

const STATE_META: Record<string, { color: string; bg: string; border: string; Icon: React.ElementType; cs: string; en: string }> = {
  PROPOSED: { color: '#d97706', bg: '#fffbeb', border: '#fcd34d', Icon: Clock, cs: 'Čeká na rozhodnutí', en: 'Pending' },
  APPROVED: { color: '#059669', bg: '#ecfdf5', border: '#6ee7b7', Icon: CheckCircle2, cs: 'Schváleno', en: 'Approved' },
  REJECTED: { color: '#dc2626', bg: '#fef2f2', border: '#fca5a5', Icon: XCircle, cs: 'Zamítnuto', en: 'Rejected' },
}

export default function ApprovalsPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const decidedBy = session?.user?.email || session?.user?.name || 'operator'

  const [rows, setRows] = useState<Proposal[]>([])
  const [domainItems, setDomainItems] = useState<InboxItem[]>([])
  const [domainSources, setDomainSources] = useState<Record<string, string>>({})
  const [domainLoadFailed, setDomainLoadFailed] = useState(false)
  const [loading, setLoading] = useState(true)
  const flight = useSingleFlight()
  const [decisionIntent, setDecisionIntent] = useState<DecisionIntent | null>(null)
  const decisionTriggerRef = useRef<HTMLButtonElement | null>(null)
  const [error, setError] = useState<string | null>(null)
  // The enforced charter registry (agents.yaml). `null` while it is still being fetched or
  // after the fetch failed — resolveAgentIdentity turns both into `unverifiable`, and
  // `registryLoading` keeps the two apart in the UI (#5904).
  const [registry, setRegistry] = useState<AgentIdentityRegistry | null>(null)
  const [registryLoading, setRegistryLoading] = useState(true)
  const [domainFilter, setDomainFilter] = useState<ApprovalDomain | 'all'>('all')
  const [domainQuery, setDomainQuery] = useState('')
  const [domainSort, setDomainSort] = useState<ApprovalSortOrder>('oldest')

  const load = useCallback(async () => {
    setLoading(true)
    const [proposalsResult, inboxResult] = await Promise.allSettled([
      fetch('/api/agent/proposals?state=all', { cache: 'no-store' }).then(async response => {
        if (!response.ok) throw new Error('agent proposals unavailable')
        const data = await response.json()
        return Array.isArray(data) ? data as Proposal[] : []
      }),
      fetch('/api/approvals/pending', { cache: 'no-store' }).then(async response => {
        if (!response.ok) throw new Error('domain approvals unavailable')
        const inbox = await response.json()
        return {
          items: Array.isArray(inbox.items) ? inbox.items as InboxItem[] : [],
          sources: inbox.sources && typeof inbox.sources === 'object' ? inbox.sources as Record<string, string> : {},
        }
      }),
    ])

    if (proposalsResult.status === 'fulfilled') {
      setRows(proposalsResult.value)
      setError(null)
    } else {
      setError('unreachable')
    }

    if (inboxResult.status === 'fulfilled') {
      setDomainItems(inboxResult.value.items)
      setDomainSources(inboxResult.value.sources)
      setDomainLoadFailed(false)
    } else {
      // Retain any last successful snapshot, but never let it look current or empty.
      setDomainLoadFailed(true)
    }
    setLoading(false)
  }, [])

  useEffect(() => { void load() }, [load])

  // Charter lookup is deliberately a separate read from the queue: a registry that cannot be
  // read must degrade the IDENTITY column only, never blank the queue itself.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const res = await fetch('/api/governance/agent-identities', { cache: 'no-store' })
        if (!res.ok) throw new Error('registry')
        const body = (await res.json()) as AgentIdentityRegistry
        if (!cancelled) setRegistry(body)
      } catch {
        if (!cancelled) setRegistry(null)
      } finally {
        if (!cancelled) setRegistryLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [])

  // Keyed per proposal so approve and reject of the SAME proposal contend, while
  // two different proposals stay independent (#7104). Segregation of duties is
  // enforced server-side and is untouched by this lock.
  const decide = async (p: Proposal, approve: boolean, reason: string): Promise<boolean> => {
    let succeeded = false
    const outcome = await flight.run(`proposal:${p.id}`, async () => {
    try {
      const res = await fetch('/api/agent/proposals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ proposalId: p.id, approve, decidedBy, reason }),
      })
      if (!res.ok) {
        const e = await res.json().catch(() => ({}))
        setError(e.error || t('Chyba při rozhodování', 'Decision failed'))
      } else {
        setError(null)
        await load()
        succeeded = true
      }
    } catch {
      setError('unreachable')
    }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }

  const requestDecision = (proposal: Proposal, approve: boolean, trigger: HTMLButtonElement) => {
    decisionTriggerRef.current = trigger
    setError(null)
    setDecisionIntent({ proposal, approve })
  }

  const closeDecision = () => {
    const trigger = decisionTriggerRef.current
    setDecisionIntent(null)
    setError(null)
    requestAnimationFrame(() => { if (trigger?.isConnected) trigger.focus() })
  }

  const pending = useMemo(() => rows.filter(r => r.state === 'PROPOSED'), [rows])
  const decided = useMemo(() => rows.filter(r => r.state !== 'PROPOSED'), [rows])
  // Sources that answered anything other than 200 — a 403 here is ordinary (lending's list is
  // desk-role gated while this page is not), and it must never look like an empty queue.
  const unavailableSources = useMemo(
    () => Object.entries(domainSources).filter(([, v]) => v === 'unavailable' || v === 'forbidden').map(([k]) => k),
    [domainSources],
  )
  const notConfiguredSources = useMemo(
    () => Object.entries(domainSources).filter(([, v]) => v === 'not-configured').map(([k]) => k),
    [domainSources],
  )
  const domainApprovalItems = useMemo(
    () => domainItems.filter((item): item is DomainApprovalItem => item.domain !== 'agent'),
    [domainItems],
  )
  const domainOptions = useMemo(
    () => [...new Set(domainApprovalItems.map(item => item.domain))].sort(),
    [domainApprovalItems],
  )
  const visibleDomainItems = useMemo(
    () => filterAndSortDomainApprovals(domainApprovalItems, domainFilter, domainSort, domainQuery),
    [domainApprovalItems, domainFilter, domainQuery, domainSort],
  )

  return (
    <AuthGuard permission="approvals:view">
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Schvalování', 'Approvals')}</span></div>}
        icon={<ClipboardCheck size={18} aria-hidden="true" />}
        title={t('Fronta schvalování (AI agent)', 'Approval queue (AI agent)')}
        subtitle={t('Agent navrhuje, governance rozhoduje (ADR-0031 D4). Návrhy nemají žádný efekt, dokud je člověk neschválí. Schválení musí udělat někdo jiný než autor.', 'Agents propose, governance disposes (ADR-0031 D4). Proposals have no effect until a human approves them. The approver must differ from the author.')}
        actions={<button type="button" onClick={load} disabled={loading} aria-busy={loading}
          aria-label={t('Obnovit schvalovací frontu', 'Refresh approval queue')} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw aria-hidden="true" size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {error && (
        <div role="alert" className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', display: 'flex', gap: 8, alignItems: 'center', color: 'var(--danger-text)', background: 'var(--danger-bg)', fontSize: 13 }}>
          <AlertTriangle aria-hidden="true" size={15} /> {error === 'unreachable' ? t('Agent je nedostupný.', 'Agent unreachable.') : error}
        </div>
      )}

      {/* ADR-0227 D2/D4: domain maker-checker queues, federated. Read-only here — disposal
          belongs to the governed per-domain flows (money-path adds SCA). */}
      <div style={{ fontSize: 12, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-secondary)', margin: '4px 0 10px' }}>
        {t('Doménová schvalování (money-path)', 'Domain approvals (money-path)')} ({domainApprovalItems.length})
      </div>
      {domainLoadFailed && (
        <div className="card" role="alert" style={{
          padding: 14, marginBottom: 16, fontSize: 13,
          color: '#92400e', background: '#fffbeb', border: '1px solid #fcd34d',
        }}>
          {t(
            'Doménovou schvalovací frontu se nepodařilo načíst. Případná zobrazená data jsou z posledního úspěšného načtení; prázdný seznam neznamená, že nic nečeká.',
            'Domain approval inbox could not be loaded. Any displayed data is from the last successful read; an empty list does not mean nothing is pending.',
          )}
        </div>
      )}
      {unavailableSources.length > 0 && (
        <div className="card" style={{
          padding: 14, marginBottom: 16, fontSize: 13,
          color: '#92400e', background: '#fffbeb', border: '1px solid #fcd34d',
        }}>
          {t(
            `Fronta není úplná — nepodařilo se načíst: ${unavailableSources.join(', ')}. Prázdný seznam neznamená, že nic nečeká.`,
            `This queue is incomplete — could not read: ${unavailableSources.join(', ')}. An empty list does not mean nothing is pending.`,
          )}
        </div>
      )}
      {notConfiguredSources.length > 0 && (
        <div className="card" style={{
          padding: 14, marginBottom: 16, fontSize: 13,
          color: '#475569', background: '#f8fafc', border: '1px solid #cbd5e1',
        }}>
          {t(
            `Část fronty zatím není napojená: ${notConfiguredSources.join(', ')}. Rozhodnutí z těchto domén se zde nezobrazí, dokud jejich read endpoint nebude dostupný.`,
            `Some queues are not connected yet: ${notConfiguredSources.join(', ')}. Decisions from these domains will not appear here until their read endpoint is available.`,
          )}
        </div>
      )}
      {!loading && !domainLoadFailed && unavailableSources.length === 0 && notConfiguredSources.length === 0 && domainApprovalItems.length === 0 && (
        <div className="card" style={{ padding: 16, marginBottom: 16, color: 'var(--text-secondary)', fontSize: 13 }}>
          {t('Žádná doménová schvalování nečekají.', 'No domain approvals pending.')}
        </div>
      )}
      {domainApprovalItems.length > 0 && (
        <div className="card" role="group" aria-label={t('Filtry doménové fronty', 'Domain queue filters')} style={{ padding: 14, marginBottom: 12, display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
          <div style={{ flex: '2 1 240px' }}>
            <label htmlFor="approval-domain-search" style={{ display: 'block', marginBottom: 5, fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' }}>{t('Hledat ve frontě', 'Search queue')}</label>
            <div style={{ position: 'relative' }}>
              <Search aria-hidden="true" size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input id="approval-domain-search" className="input" type="search" value={domainQuery} onChange={event => setDomainQuery(event.target.value)} placeholder={t('ID, akce, zdroj nebo maker', 'ID, action, resource or maker')} style={{ width: '100%', paddingLeft: 32 }} />
            </div>
          </div>
          <div style={{ flex: '1 1 180px' }}>
            <label htmlFor="approval-domain-filter" style={{ display: 'block', marginBottom: 5, fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' }}>{t('Doména', 'Domain')}</label>
            <select id="approval-domain-filter" className="input" value={domainFilter} onChange={event => setDomainFilter(event.target.value as ApprovalDomain | 'all')} style={{ width: '100%' }}>
              <option value="all">{t('Všechny domény', 'All domains')}</option>
              {domainOptions.map(domain => <option key={domain} value={domain}>{domain}</option>)}
            </select>
          </div>
          <div style={{ flex: '1 1 180px' }}>
            <label htmlFor="approval-domain-sort" style={{ display: 'block', marginBottom: 5, fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)' }}>{t('Pořadí', 'Order')}</label>
            <select id="approval-domain-sort" className="input" value={domainSort} onChange={event => setDomainSort(event.target.value as ApprovalSortOrder)} style={{ width: '100%' }}>
              <option value="oldest">{t('Nejstarší první', 'Oldest first')}</option>
              <option value="newest">{t('Nejnovější první', 'Newest first')}</option>
            </select>
          </div>
          <span role="status" aria-live="polite" style={{ flex: '0 0 auto', paddingBottom: 9, fontSize: 11, color: 'var(--text-tertiary)' }}>
            {t(`Zobrazeno ${visibleDomainItems.length} z ${domainApprovalItems.length}`, `Showing ${visibleDomainItems.length} of ${domainApprovalItems.length}`)}
          </span>
        </div>
      )}
      {!loading && domainApprovalItems.length > 0 && visibleDomainItems.length === 0 && (
        <div className="card" style={{ padding: 16, marginBottom: 12, color: 'var(--text-secondary)', fontSize: 13 }}>
          {t('Žádné čekající žádosti neodpovídají vybraným filtrům.', 'No pending approvals match the selected filters.')}
        </div>
      )}
      <div style={{ display: 'grid', gap: 10, marginBottom: 24 }}>
        {visibleDomainItems.map(item => {
          const workbenchHref = approvalWorkbenchHref(item)
          return <div key={`${item.domain}:${item.id}`} data-testid={`domain-approval-${item.domain}:${item.id}`} className="card" style={{ padding: 14, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
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
                {item.proposedAt && <span> · {new Date(item.proposedAt).toLocaleString(dateLocale)}</span>}
              </div>
            </div>
            <span style={{ fontSize: 10, fontWeight: 700, color: '#d97706', background: '#fffbeb', border: '1px solid #fcd34d', padding: '2px 7px', borderRadius: 20, textTransform: 'uppercase', flexShrink: 0 }}>
              {t('Čeká', 'Pending')}
            </span>
            {workbenchHref && <Link href={workbenchHref} className="btn btn-secondary" aria-label={t(`Otevřít řízenou kontrolu žádosti ${item.id}`, `Open governed review for approval ${item.id}`)} style={{ fontSize: 11, textDecoration: 'none' }}>
              {t('Otevřít řízenou kontrolu', 'Open governed review')} <ExternalLink aria-hidden="true" size={12} />
            </Link>}
          </div>
        })}
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
          // Provenance comes from the enforced charter registry, never from the shape of the
          // proposedBy string. `unresolved` means agents.yaml has no such actor; `unverifiable`
          // means we could not read agents.yaml and must not claim either way (#5904).
          const identity = resolveAgentIdentity(p.proposedBy, registry)
          const chartered = identity.status === 'chartered'
          // The ADR-0080 caution stands whenever AI authorship is established OR cannot be
          // ruled out. Only a positively unchartered actor drops it.
          const cautionAi = identity.status !== 'unresolved'
          const aiGenerated = p.agent ? p.agent.icon === 'bot' : /assistant|agent|\bai\b/i.test(p.proposedBy)
          const ProposerIcon = aiGenerated ? Bot : UserRound
          return (
            <div key={p.id} className="card" style={{ padding: 18, borderLeft: `3px solid ${chartered ? '#d97706' : m.color}` }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10, marginBottom: 8 }}>
                <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span aria-hidden="true" style={{ width: 28, height: 28, borderRadius: 8, background: aiGenerated ? '#fffbeb' : 'var(--surface-2)', color: aiGenerated ? '#b45309' : 'var(--text-secondary)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}><ProposerIcon size={15} /></span>
                  {p.title}
                  <AgentIdentityBadge identity={identity} loading={registryLoading} lang={language} />
                </div>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0, fontSize: 11, fontWeight: 700, color: m.color, background: m.bg, border: `1px solid ${m.border}`, padding: '2px 8px', borderRadius: 20 }}>
                  <m.Icon size={11} /> {t(m.cs, m.en)}
                </span>
              </div>
              {cautionAi && !registryLoading && (
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
                {t('Navrhl', 'Proposed by')} <b>{p.agent?.displayName ?? p.proposedBy}</b>
                <span className="mono"> · {p.agent?.id ?? p.proposedBy}</span>
                {p.modelId ? <span className="mono"> · {p.modelId}</span> : null} · {new Date(p.proposedAt).toLocaleString(dateLocale)}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <Can permission="agent:decide" fallback={<span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Rozhodování vyžaduje oprávnění agenta.', 'Decision access requires agent authorization.')}</span>}>
                  <button type="button" aria-label={t(`Zkontrolovat a schválit návrh ${p.title}`, `Review and approve proposal ${p.title}`)} aria-busy={flight.isRunning(`proposal:${p.id}`)} onClick={event => requestDecision(p, true, event.currentTarget)} disabled={flight.isRunning(`proposal:${p.id}`)}
                    style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, padding: '6px 14px', borderRadius: 6, border: '1px solid #6ee7b7', background: '#ecfdf5', color: '#059669', cursor: 'pointer' }}>
                    <CheckCircle2 aria-hidden="true" size={14} /> {t('Schválit', 'Approve')}
                  </button>
                  <button type="button" aria-label={t(`Zkontrolovat a zamítnout návrh ${p.title}`, `Review and reject proposal ${p.title}`)} aria-busy={flight.isRunning(`proposal:${p.id}`)} onClick={event => requestDecision(p, false, event.currentTarget)} disabled={flight.isRunning(`proposal:${p.id}`)}
                    style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, padding: '6px 14px', borderRadius: 6, border: '1px solid #fca5a5', background: '#fef2f2', color: '#dc2626', cursor: 'pointer' }}>
                    <XCircle aria-hidden="true" size={14} /> {t('Zamítnout', 'Reject')}
                  </button>
                </Can>
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
      {decisionIntent && <ApprovalDecisionDialog
        intent={decisionIntent}
        busy={flight.isRunning(`proposal:${decisionIntent.proposal.id}`)}
        failed={error !== null}
        onCancel={closeDecision}
        onConfirm={async reason => {
          const succeeded = await decide(decisionIntent.proposal, decisionIntent.approve, reason)
          if (succeeded) setDecisionIntent(null)
        }}
      />}
    </div>
    </AuthGuard>
  )
}

function ApprovalDecisionDialog({ intent, busy, failed, onCancel, onConfirm }: {
  intent: DecisionIntent
  busy: boolean
  failed: boolean
  onCancel: () => void
  onConfirm: (reason: string) => Promise<void>
}) {
  const { t } = useLanguage()
  const [reason, setReason] = useState('')
  const dialogRef = useRef<HTMLDivElement>(null)
  const action = intent.approve ? t('Schválit návrh', 'Approve proposal') : t('Zamítnout návrh', 'Reject proposal')
  const titleId = `approval-decision-${intent.proposal.id}-title`
  const impactId = `approval-decision-${intent.proposal.id}-impact`

  return <div
    ref={dialogRef}
    role="alertdialog"
    aria-modal="true"
    aria-labelledby={titleId}
    aria-describedby={impactId}
    aria-busy={busy}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onCancel()
      trapDialogFocus(event, dialogRef.current)
    }}
    style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'grid', placeItems: 'center', padding: 20 }}
  ><div className="card" style={{ width: 'min(560px, 100%)', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}>
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
      <AlertTriangle aria-hidden="true" size={19} style={{ color: intent.approve ? 'var(--warning)' : 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
      <div>
        <h2 id={titleId} style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>{action}: {intent.proposal.title}</h2>
        <p id={impactId} style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
          {intent.approve
            ? t('Tímto zaznamenáte lidské schválení. Návrh smí pokračovat jen podle svého řízeného následného procesu; AI tím nezískává oprávnění jednat sama.', 'This records human approval. The proposal may proceed only through its governed follow-up process; this does not authorize the AI to act on its own.')
            : t('Tímto zaznamenáte lidské zamítnutí. Návrh se neprovede a důvod zůstane v auditní stopě.', 'This records human rejection. The proposal will not be executed and the reason remains in the audit trail.')}
        </p>
      </div>
    </div>
    <div style={{ marginTop: 14, padding: '11px 12px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)', fontSize: 12.5 }}>
      <div><strong>{t('Navrhl', 'Proposed by')}:</strong> {intent.proposal.agent?.displayName ?? intent.proposal.proposedBy}</div>
      <div style={{ marginTop: 5 }}><strong>{t('Navrhovaná akce', 'Suggested action')}:</strong> {intent.proposal.suggestedAction}</div>
    </div>
    <label htmlFor="approval-decision-reason" style={{ display: 'block', marginTop: 14, fontSize: 12.5, fontWeight: 700 }}>
      {t('Důvod rozhodnutí (povinný, součást auditní stopy)', 'Decision reason (required, recorded in the audit trail)')}
    </label>
    <textarea
      id="approval-decision-reason"
      aria-label={t('Důvod rozhodnutí návrhu', 'Decision reason for proposal')}
      autoFocus
      rows={4}
      maxLength={1000}
      value={reason}
      onChange={event => setReason(event.target.value)}
      disabled={busy}
      style={{ width: '100%', marginTop: 6, padding: '9px 10px', borderRadius: 7, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)', resize: 'vertical', font: 'inherit' }}
    />
    {failed && <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>
      {t('Rozhodnutí se nepodařilo uložit. Nic se nezměnilo; zkontrolujte připojení a zkuste to znovu.', 'The decision could not be recorded. Nothing changed; check the connection and try again.')}
    </p>}
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
      <button type="button" className="btn btn-secondary" disabled={busy} onClick={onCancel}>{t('Zpět ke kontrole', 'Back to review')}</button>
      <button type="button" className={intent.approve ? 'btn btn-primary' : 'btn btn-danger'} disabled={busy || !reason.trim()} aria-busy={busy} onClick={() => void onConfirm(reason.trim())}>
        {busy ? t('Ukládám rozhodnutí…', 'Recording decision…') : intent.approve ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit zamítnutí', 'Confirm rejection')}
      </button>
    </div>
  </div></div>
}
