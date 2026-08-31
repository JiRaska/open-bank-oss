// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useState, useEffect, useCallback, Fragment } from 'react'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import {
  ShieldAlert, Search, CheckCircle2, Clock, RefreshCw,
  AlertTriangle, User, Play, List, ChevronDown, ChevronUp,
  ToggleLeft, ToggleRight, ExternalLink, Download, Loader2
} from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { classifyBffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'
import { statusTone } from '@/components/ui/tone'

interface SanctionCheck {
  id: string; name: string; entityType: string; status: string
  overallScore: number; checkedLists: string[]; matches: SanctionMatch[]
  checkedAt: string; reviewedBy?: string; reviewNote?: string
}
interface SanctionMatch {
  listType: string; matchType: string; matchScore: number
  matchedName: string; programs: string[]
}
interface SanctionsList {
  id: string; listType: string; displayName: string; sourceUrl: string
  enabled: boolean; lastUpdatedAt?: string; lastEntryCount?: number
  cronHour: number; cronMinute: number; cronDays: string
}

interface ApiError {
  error?: string
}

// The upstream enum (openapi.yaml ReviewCommand.newStatus). POTENTIAL_HIT is deliberately absent
// from the picker: a review that leaves the check pending is a no-op that still consumes a
// four-eyes approval.
type ReviewStatus = 'CLEAR' | 'HIT' | 'WHITELISTED' | 'ESCALATED'

// 202 + this body is the four-eyes pause, not an error (ADR-0155). `res.ok` covers 202, so the
// BFF forwards it untouched.
interface PendingApprovalResponse {
  status?: string
  approvalId?: string
}

// One row of the checker's queue (GET /api/v1/sanctions/approvals, #3472).
interface PendingApprovalItem {
  id: string
  action: string
  resourceId?: string | null
  status: string
  makerId?: string | null
  createdAt?: string | null
}

const DAYS = ['MON','TUE','WED','THU','FRI','SAT','SUN']
const DAY_LABELS_CS: Record<string,string> = { MON:'Po', TUE:'Út', WED:'St', THU:'Čt', FRI:'Pá', SAT:'So', SUN:'Ne' }
const DAY_LABELS_EN: Record<string,string> = { MON:'Mon', TUE:'Tue', WED:'Wed', THU:'Thu', FRI:'Fri', SAT:'Sat', SUN:'Sun' }

function CronEditor({ list, onSave }: { list: SanctionsList; onSave: (id: string, patch: Partial<SanctionsList>) => void }) {
  const { t } = useLanguage()
  const [hour, setHour] = useState(list.cronHour)
  const [minute, setMinute] = useState(list.cronMinute)
  const [days, setDays] = useState<string[]>(list.cronDays.split(',').filter(Boolean))
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    setHour(list.cronHour)
    setMinute(list.cronMinute)
    setDays(list.cronDays.split(',').filter(Boolean))
  }, [list.cronDays, list.cronHour, list.cronMinute])

  const toggleDay = (d: string) => setDays(prev => prev.includes(d) ? prev.filter(x => x !== d) : [...prev, d])

  const save = async () => {
    setSaving(true)
    await onSave(list.id, { cronHour: hour, cronMinute: minute, cronDays: days.join(',') })
    setSaving(false)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', padding: '12px', background: 'var(--surface-2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontWeight: 600, minWidth: '40px' }}>{t('Čas', 'Time')}</span>
        <select id={`sanctions-cron-${list.id}-hour`} aria-label={t('Hodina spouštění', 'Run hour')} value={hour} onChange={e => setHour(+e.target.value)}
          style={{ padding: '4px 8px', borderRadius: '5px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '12px' }}>
          {Array.from({length:24},(_,i)=>i).map(h => <option key={h} value={h}>{String(h).padStart(2,'0')}</option>)}
        </select>
        <span style={{ color: 'var(--text-tertiary)', fontSize: '13px' }}>:</span>
        <select id={`sanctions-cron-${list.id}-minute`} aria-label={t('Minuta spouštění', 'Run minute')} value={minute} onChange={e => setMinute(+e.target.value)}
          style={{ padding: '4px 8px', borderRadius: '5px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-primary)', fontSize: '12px' }}>
          {[0,5,10,15,20,25,30,35,40,45,50,55].map(m => <option key={m} value={m}>{String(m).padStart(2,'0')}</option>)}
        </select>
      </div>
      <div style={{ display: 'flex', gap: '4px', alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontWeight: 600, minWidth: '40px' }}>{t('Dny', 'Days')}</span>
        {DAYS.map(d => (
          <button key={d} type="button" aria-pressed={days.includes(d)} aria-label={t(`Den ${DAY_LABELS_CS[d]}`, `${DAY_LABELS_EN[d]} day`)} onClick={() => toggleDay(d)}
            style={{ padding: '3px 8px', borderRadius: '4px', fontSize: '11px', fontWeight: 600, cursor: 'pointer', border: '1px solid',
              background: days.includes(d) ? 'var(--accent)' : 'var(--surface)',
              color: days.includes(d) ? 'white' : 'var(--text-secondary)',
              borderColor: days.includes(d) ? 'var(--accent)' : 'var(--border)' }}>
            {t(DAY_LABELS_CS[d], DAY_LABELS_EN[d])}
          </button>
        ))}
      </div>
      <button type="button" onClick={save} disabled={saving}
        style={{ alignSelf: 'flex-start', padding: '5px 12px', borderRadius: '5px', fontSize: '12px', fontWeight: 600,
          background: 'var(--accent)', color: 'white', border: 'none', cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1,
          display: 'flex', alignItems: 'center', gap: '5px' }}>
        {saving ? <Loader2 size={11} style={{ animation: 'spin 0.8s linear infinite' }} /> : null}
        {t('Uložit plán', 'Save schedule')}
      </button>
    </div>
  )
}

function ListCard({ list, onToggle, onRefresh, onSave }: {
  list: SanctionsList
  onToggle: (id: string, enabled: boolean) => void
  onRefresh: (listType: string) => void
  onSave: (id: string, patch: Partial<SanctionsList>) => void
}) {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = numberLocale
  const [expanded, setExpanded] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  const handleRefresh = async () => {
    setRefreshing(true)
    await onRefresh(list.listType)
    setRefreshing(false)
  }

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: '8px', overflow: 'hidden', opacity: list.enabled ? 1 : 0.6, transition: 'opacity 0.2s' }}>
      <div style={{ padding: '12px 16px', display: 'flex', alignItems: 'center', gap: '10px', background: 'var(--surface)' }}>
        <Can permission="sanctions:manage">
          <button type="button" onClick={() => onToggle(list.id, !list.enabled)} aria-label={list.enabled ? t('Deaktivovat sankční seznam', 'Disable sanctions list') : t('Aktivovat sankční seznam', 'Enable sanctions list')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: list.enabled ? 'var(--success)' : 'var(--text-tertiary)', padding: 0, display: 'flex' }}>
            {list.enabled ? <ToggleRight size={20} aria-hidden="true" /> : <ToggleLeft size={20} aria-hidden="true" />}
          </button>
        </Can>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{list.displayName}</div>
          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>{list.listType}</div>
        </div>
        <div style={{ textAlign: 'right', fontSize: '11px', color: 'var(--text-tertiary)' }}>
          {list.lastUpdatedAt ? (
            <>
              <div style={{ color: 'var(--success-text)', fontWeight: 600 }}>{list.lastEntryCount?.toLocaleString(numberLocale)} {t('záznamů', 'entries')}</div>
              <div>{new Date(list.lastUpdatedAt).toLocaleString(dateLocale)}</div>
            </>
          ) : <div>{t('Nikdy nestaženo', 'Never downloaded')}</div>}
        </div>
        <Can permission="sanctions:manage">
        <button type="button" onClick={handleRefresh} disabled={refreshing} aria-busy={refreshing} aria-label={t('Stáhnout sankční seznam', 'Download sanctions list')}
          style={{ padding: '5px 10px', borderRadius: '5px', fontSize: '11px', fontWeight: 600, border: '1px solid var(--border)',
            background: 'var(--surface-2)', color: 'var(--text-secondary)', cursor: refreshing ? 'not-allowed' : 'pointer',
            display: 'flex', alignItems: 'center', gap: '4px' }}>
          {refreshing ? <Loader2 size={11} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} /> : <Download size={11} aria-hidden="true" />}
          {t('Stáhnout', 'Download')}
        </button>
        </Can>
        <button type="button" onClick={() => setExpanded(e => !e)} aria-expanded={expanded} aria-label={expanded ? t('Sbalit podrobnosti seznamu', 'Collapse list details') : t('Rozbalit podrobnosti seznamu', 'Expand list details')}
          style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)', padding: '4px', display: 'flex' }}>
          {expanded ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
        </button>
      </div>
      {expanded && (
        <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', background: 'var(--surface-2)', display: 'flex', flexDirection: 'column', gap: '10px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-secondary)' }}>
            <ExternalLink size={11} />
            <a href={list.sourceUrl} target="_blank" rel="noreferrer"
              style={{ color: 'var(--accent)', textDecoration: 'none', wordBreak: 'break-all' }}>{list.sourceUrl}</a>
          </div>
          <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', marginBottom: '2px' }}>{t('Plán stahování', 'Download schedule')}</div>
          <Can permission="sanctions:manage"><CronEditor list={list} onSave={onSave} /></Can>
        </div>
      )}
    </div>
  )
}

export default function SanctionsPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = numberLocale
  const [tab, setTab] = useState<'checks'|'search'|'lists'>('checks')
  const [checks, setChecks] = useState<SanctionCheck[]>([])
  const [lists, setLists] = useState<SanctionsList[]>([])
  const [loading, setLoading] = useState(true)
  const [listsLoading, setListsLoading] = useState(true)
  const [checksUnavail, setChecksUnavail] = useState<{ kind: UnavailableKind } | null>(null)
  const [search, setSearch] = useState('')
  const [refreshingAll, setRefreshingAll] = useState(false)

  const [searchName, setSearchName] = useState('')
  const [searchType, setSearchType] = useState<'INDIVIDUAL'|'ORGANIZATION'>('INDIVIDUAL')
  const [searchDob, setSearchDob] = useState('')
  const [searchNationality, setSearchNationality] = useState('')
  const [screening, setScreening] = useState(false)
  const [screenResult, setScreenResult] = useState<SanctionCheck | null>(null)
  const [screenError, setScreenError] = useState('')
  const [listsError, setListsError] = useState('')
  // Selected list types for manual screening — initialised to all enabled lists once loaded
  const [selectedListTypes, setSelectedListTypes] = useState<string[]>([])
  const [listScopeInitialised, setListScopeInitialised] = useState(false)

  // Manual disposition of a hit (issue #3334). POST /api/v1/sanctions/review existed, was
  // publicly routed and had no caller anywhere in the product — so this queue could only grow.
  const [reviewFor, setReviewFor] = useState<string | null>(null)
  const [reviewStatus, setReviewStatus] = useState<ReviewStatus>('CLEAR')
  const [reviewNote, setReviewNote] = useState('')
  const [reviewBusy, setReviewBusy] = useState(false)
  const [reviewError, setReviewError] = useState('')
  // Four-eyes (ADR-0155): a 202 parks the maker's decision until a DIFFERENT operator decides it.
  // The id has to survive in the UI — it is the only way back to this exact decision, and the
  // retry must carry it as X-Approval-Id or the interceptor mints a fresh one and 202s forever.
  const [pendingApproval, setPendingApproval] = useState<{ id: string; checkId: string } | null>(null)
  const [decideId, setDecideId] = useState('')
  // The checker's queue (#3472). Before sanctions-service served this list, a decision parked at
  // 202 was reachable only by whoever had been handed its id out of band.
  const [pendingQueue, setPendingQueue] = useState<PendingApprovalItem[]>([])
  const [queueUnavail, setQueueUnavail] = useState(false)
  const [decideBusy, setDecideBusy] = useState(false)
  const [decideMsg, setDecideMsg] = useState('')

  const loadChecks = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/sanctions/checks', { cache: 'no-store' })
      if (!res.ok) {
        // Classify honestly (idle / not deployed / unreachable) instead of leaking
        // a raw "HTTP <status>" string — admin-ui graceful-state rule.
        setChecks([])
        setChecksUnavail({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json().catch(() => ([]))
      setChecks(Array.isArray(data) ? data : [])
      setChecksUnavail(null)
    } catch {
      setChecks([])
      setChecksUnavail({ kind: 'unreachable' })
    }
    finally { setLoading(false) }
  }, [])

  const loadLists = useCallback(async () => {
    setListsLoading(true)
    setListsError('')
    try {
      const res = await fetch('/api/sanctions/lists', { cache: 'no-store' })
      const data = await res.json().catch(() => ([]))
      if (!res.ok) {
        const errorPayload = data as ApiError
        setLists([])
        setListsError(errorPayload.error ?? t(`Načtení listů selhalo (HTTP ${res.status})`, `Failed to load lists (HTTP ${res.status})`))
        return
      }
      setLists(Array.isArray(data) ? data : [])
    } catch (error) {
      setLists([])
      setListsError(error instanceof Error ? error.message : 'Spojení se službou selhalo')
    }
    finally { setListsLoading(false) }
  }, [])

  const loadPendingQueue = useCallback(async () => {
    try {
      const res = await fetch('/api/sanctions/approvals', { cache: 'no-store' })
      if (!res.ok) {
        // A refused or unreachable read must never render as an empty queue — "nothing is
        // waiting" is the most dangerous thing an approvals surface can say wrongly.
        setPendingQueue([])
        setQueueUnavail(true)
        return
      }
      const data = await res.json().catch(() => ([]))
      setPendingQueue(Array.isArray(data) ? data : [])
      setQueueUnavail(false)
    } catch {
      setPendingQueue([])
      setQueueUnavail(true)
    }
  }, [])

  useEffect(() => { loadChecks(); loadLists(); loadPendingQueue() }, [loadChecks, loadLists, loadPendingQueue])

  // Once lists load for the first time, initialise scope to all enabled lists
  useEffect(() => {
    if (!listScopeInitialised && lists.length > 0) {
      setSelectedListTypes(lists.filter(lst => lst.enabled).map(lst => lst.listType))
      setListScopeInitialised(true)
    }
  }, [lists, listScopeInitialised])

  const handleScreen = async () => {
    if (!searchName.trim()) return
    setScreening(true); setScreenResult(null); setScreenError('')
    try {
      const res = await fetch('/api/sanctions/screen', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          // eslint-disable-next-line react-hooks/purity -- time-relative display; timestamps are stable server data.
          idempotencyKey: `manual-${Date.now()}`,
          entityType: searchType,
          name: searchName.trim(),
          aliases: [],
          dateOfBirth: searchDob || null,
          nationality: searchNationality || null,
          identifiers: {},
          listTypes: selectedListTypes.length > 0 ? selectedListTypes : null,
        })
      })
      if (res.ok) { setScreenResult(await res.json()); loadChecks() }
      else {
        const errorPayload = await res.json().catch(() => ({ error: t('Screening selhal', 'Screening failed') })) as ApiError
        setScreenError(`${t('Chyba', 'Error')} ${res.status}: ${errorPayload.error ?? t('Screening selhal', 'Screening failed')}`)
      }
    } catch (error) {
      setScreenError(error instanceof Error ? error.message : t('Spojení se službou selhalo', 'Connection to the service failed'))
    }
    setScreening(false)
  }

  const handleToggleList = async (id: string, enabled: boolean) => {
    setListsError('')
    const res = await fetch(`/api/sanctions/lists/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }) })
    if (!res.ok) {
      const errorPayload = await res.json().catch(() => ({ error: 'Update failed' })) as ApiError
      setListsError(errorPayload.error ?? `Aktualizace listu selhala (HTTP ${res.status})`)
      return
    }
    loadLists()
  }

  const handleRefreshList = async (listType: string) => {
    setListsError('')
    const res = await fetch(`/api/sanctions/lists/${listType}/refresh`, { method: 'POST' })
    if (!res.ok) {
      const errorPayload = await res.json().catch(() => ({ error: 'Refresh failed' })) as ApiError
      setListsError(errorPayload.error ?? t(`Obnovení listu selhalo (HTTP ${res.status})`, `Failed to refresh list (HTTP ${res.status})`))
      return
    }
    loadLists()
  }

  const handleSaveCron = async (id: string, patch: Partial<SanctionsList>) => {
    setListsError('')
    const res = await fetch(`/api/sanctions/lists/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) })
    if (!res.ok) {
      const errorPayload = await res.json().catch(() => ({ error: 'Update failed' })) as ApiError
      setListsError(errorPayload.error ?? t(`Uložení plánu selhalo (HTTP ${res.status})`, `Failed to save schedule (HTTP ${res.status})`))
      return
    }
    loadLists()
  }

  const handleRefreshAll = async () => {
    setRefreshingAll(true)
    setListsError('')
    const res = await fetch('/api/sanctions/lists', { method: 'POST' })
    if (!res.ok) {
      const errorPayload = await res.json().catch(() => ({ error: 'Refresh failed' })) as ApiError
      setListsError(errorPayload.error ?? t(`Obnovení všech listů selhalo (HTTP ${res.status})`, `Failed to refresh all lists (HTTP ${res.status})`))
      setRefreshingAll(false)
      return
    }
    await loadLists()
    setRefreshingAll(false)
  }

  const openReview = (c: SanctionCheck) => {
    setReviewFor(c.id)
    // Pre-select the safer direction: ESCALATED for a confirmed HIT, CLEAR for a potential one.
    // Never pre-select CLEAR on a HIT — a wrongly-cleared true positive is a real sanctions
    // violation (rules.yaml), and a default is a decision most people accept.
    setReviewStatus(c.status === 'HIT' ? 'ESCALATED' : 'CLEAR')
    setReviewNote('')
    setReviewError('')
    setPendingApproval(null)
  }

  // SEPARATE locks for the maker and checker halves (#7098): a maker disposition and a
  // checker decision are different operations and must not block each other. Neither
  // lock addresses the cross-operator four-eyes race — that is arbitrated upstream
  // (403 on self-approval, below) and no client-side lock could ever see it.
  const reviewFlight = useSingleFlight()
  const decideFlight = useSingleFlight()

  /** Submit a disposition. `approvalId` is set only on the post-approval retry. */
  const submitReview = async (checkId: string, approvalId?: string) => {
    if (!reviewNote.trim()) {
      setReviewError(t('Poznámka je povinná — je to auditní stopa rozhodnutí.', 'A note is required — it is the audit trail for this decision.'))
      return
    }
    const outcome = await reviewFlight.run(`sanctions:review:${checkId}`, async () => {
    setReviewBusy(true)
    setReviewError('')
    try {
      const res = await fetch('/api/sanctions/review', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(approvalId ? { 'X-Approval-Id': approvalId } : {}),
        },
        body: JSON.stringify({ checkId, note: reviewNote.trim(), newStatus: reviewStatus }),
      })

      if (res.status === 202) {
        const parked = await res.json().catch(() => ({})) as PendingApprovalResponse
        if (parked.approvalId) {
          setPendingApproval({ id: parked.approvalId, checkId })
          setReviewError('')
        } else {
          // 202 without an id would leave the decision unreachable — say so rather than
          // rendering a success that never completes.
          setReviewError(t('Služba vrátila 202 bez approvalId — rozhodnutí nelze dokončit.', 'The service returned 202 with no approvalId — this decision cannot be completed.'))
        }
        return
      }

      if (!res.ok) {
        const payload = await res.json().catch(() => ({})) as ApiError
        setReviewError(payload.error === 'unauthorized'
          ? t('Nemáte oprávnění k tomuto rozhodnutí.', 'You are not authorised to make this decision.')
          : t(`Rozhodnutí se nepodařilo uložit (HTTP ${res.status}).`, `Could not record the decision (HTTP ${res.status}).`))
        return
      }

      setReviewFor(null)
      setPendingApproval(null)
      setReviewNote('')
      await loadChecks()
    } catch {
      setReviewError(t('Služba je nedostupná.', 'The service is unreachable.'))
    } finally {
      setReviewBusy(false)
    }
    })
    if (wasSkipped(outcome)) return
  }


  /** Checker half of the four-eyes gate. A maker deciding their own request gets 403 upstream. */
  const decideApproval = async (approve: boolean) => {
    const id = decideId.trim()
    if (!id) return
    const outcome = await decideFlight.run(`sanctions:decide:${id}`, async () => {
    setDecideBusy(true)
    setDecideMsg('')
    try {
      const res = await fetch(`/api/sanctions/approvals/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approve }),
      })
      if (res.status === 403) {
        setDecideMsg(t('Zamítnuto: vlastní žádost nelze schválit (oddělení pravomocí).', 'Refused: you cannot decide your own request (segregation of duties).'))
        return
      }
      if (!res.ok) {
        setDecideMsg(t(`Rozhodnutí selhalo (HTTP ${res.status}).`, `Decision failed (HTTP ${res.status}).`))
        return
      }
      setDecideMsg(approve
        ? t('Schváleno. Maker nyní může akci zopakovat.', 'Approved. The maker can now retry the action.')
        : t('Zamítnuto.', 'Rejected.'))
      setDecideId('')
      await loadPendingQueue()
    } catch {
      setDecideMsg(t('Služba je nedostupná.', 'The service is unreachable.'))
    } finally {
      setDecideBusy(false)
    }
    })
    if (wasSkipped(outcome)) return
  }

  const filtered = checks.filter(c =>
    c.name?.toLowerCase().includes(search.toLowerCase()) ||
    c.status?.toLowerCase().includes(search.toLowerCase()) ||
    c.checkedLists?.some(l => l.toLowerCase().includes(search.toLowerCase()))
  )
  const hits = checks.filter(c => c.status === 'HIT')
  const clear = checks.filter(c => c.status === 'CLEAR')
  const pending = checks.filter(c => c.status === 'POTENTIAL_HIT')

  const TABS = [
    { id: 'checks' as const, label: t('Záznamy kontrol', 'Check Records'), icon: <ShieldAlert size={13} aria-hidden="true" /> },
    { id: 'search' as const, label: t('Manuální vyhledávání', 'Manual Search'), icon: <Search size={13} aria-hidden="true" /> },
    { id: 'lists' as const, label: t('Správa listů', 'List Management'), icon: <List size={13} aria-hidden="true" /> },
  ]

  return (
    <AuthGuard permission="sanctions:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <PageHeader
          title={t('Prověření sankcí', 'Sanctions Screening')}
          subtitle={t('OFAC SDN · EU Consolidated · UN · HM Treasury · PEP · ČNB', 'OFAC SDN · EU Consolidated · UN · HM Treasury · PEP · ČNB')}
          icon={<ShieldAlert size={18} aria-hidden="true" />}
          actions={<ServiceStatusBadge
            label="sanctions-service :8123"
            loading={loading}
            unavailable={checksUnavail}
            copy={{
              up: t('sanctions-service běží', 'sanctions-service is up'),
              idle: t('sanctions-service spí (scale-to-zero), probouzí se…', 'sanctions-service idle (scaled to zero), waking…'),
              down: t('sanctions-service neodpovídá', 'sanctions-service is not responding'),
              checking: t('Zjišťuji stav služby…', 'Checking service…'),
            }}
          />}
        />

        {hits.length > 0 && (
          <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
            background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
            display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
              {t(`${hits.length} sankční shoda${hits.length > 1 ? 'y' : ''} vyžaduje okamžitou pozornost`, `${hits.length} sanctions match${hits.length > 1 ? 'es' : ''} require immediate attention`)}
            </span>
          </div>
        )}

        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Kontrol celkem', 'Total Checks'), value: checks.length, icon: <ShieldAlert size={16} />, tone: undefined },
            { label: t('Shody (HIT)', 'Matches (HIT)'), value: hits.length, icon: <AlertTriangle size={16} />, tone: 'danger' },
            { label: t('Čisté', 'Clear'), value: clear.length, icon: <CheckCircle2 size={16} />, tone: 'success' },
            { label: t('Čeká na review', 'Pending Review'), value: pending.length, icon: <Clock size={16} />, tone: 'warning' },
          ].map(k => (
            <StatCard key={k.label} label={k.label} value={k.value} icon={k.icon} tone={k.tone as Tone | undefined} />
          ))}
        </div>

        <div className="card">
          <div role="group" aria-label={t('Sekce sankčního workflow', 'Sanctions workflow sections')} style={{ display: 'flex', borderBottom: '1px solid var(--border)', padding: '0 4px' }}>
            {TABS.map(t => (
              <button key={t.id} type="button" aria-pressed={tab === t.id} aria-label={t.label} onClick={() => setTab(t.id)}
                style={{ padding: '12px 16px', fontSize: '13px', fontWeight: tab === t.id ? 700 : 500,
                  color: tab === t.id ? 'var(--accent)' : 'var(--text-secondary)',
                  background: 'none', border: 'none', borderBottom: tab === t.id ? '2px solid var(--accent)' : '2px solid transparent',
                  cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '-1px' }}>
                {t.icon}{t.label}
              </button>
            ))}
          </div>

          {tab === 'checks' && (
            <>
              <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '8px', alignItems: 'center' }}>
                <div style={{ position: 'relative', flex: 1 }}>
                  <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
                  <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat jméno, status, seznam…', 'Search name, status, list…')} aria-label={t('Hledat sankční kontroly', 'Search sanctions checks')}
                    style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                      border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
                </div>
                <button type="button" aria-busy={loading} aria-label={t('Obnovit sankční kontroly', 'Refresh sanctions checks')} onClick={loadChecks} style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface-2)', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                  <RefreshCw size={12} aria-hidden="true" />{t('Obnovit', 'Refresh')}
                </button>
              </div>
              {loading ? (
                <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                  <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
                </div>
              ) : checksUnavail ? (
                <DataUnavailable kind={checksUnavail.kind} service={t('Sanctions-service', 'Sanctions-service')} feature={t('Sankční kontroly', 'Sanctions checks')} lang={language} />
              ) : filtered.length === 0 ? (
                <DataUnavailable kind="no_data" feature={t('Sankční kontroly', 'Sanctions checks')} lang={language}
                  detail={t('Použijte záložku Manuální vyhledávání pro první kontrolu.', 'Use the Manual Search tab to run a first check.')} />
              ) : (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                    {[t('Entita', 'Entity'), t('Typ', 'Type'), t('Seznamy', 'Lists'), t('Skóre', 'Score'), t('Výsledek', 'Result'), t('Zkontrolováno', 'Checked At'), t('Rozhodnutí', 'Disposition')].map(h => (
                      <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                    ))}
                  </tr></thead>
                  <tbody>{filtered.map(c => {
                    const isHit = c.status === 'HIT'
                    const isPending = c.status === 'POTENTIAL_HIT'
                    return (
                      <Fragment key={c.id}>
                      <tr style={{ borderBottom: '1px solid var(--border)', background: isHit ? 'rgba(239,68,68,0.03)' : '' }}
                        onMouseEnter={e => (e.currentTarget.style.background = isHit ? 'rgba(239,68,68,0.06)' : 'var(--surface-2)')}
                        onMouseLeave={e => (e.currentTarget.style.background = isHit ? 'rgba(239,68,68,0.03)' : '')}>
                        <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <User size={12} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />{c.name}
                          </div>
                        </td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.entityType}</td>
                        <td style={{ padding: '12px 16px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                          {(c.checkedLists ?? []).slice(0,3).join(', ')}{(c.checkedLists?.length ?? 0) > 3 ? ` +${(c.checkedLists?.length ?? 0)-3}` : ''}
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <div style={{ flex: 1, height: '4px', borderRadius: '2px', background: 'var(--surface-4)', overflow: 'hidden' }}>
                              <div style={{ height: '100%', width: `${(c.overallScore ?? 0) * 100}%`, borderRadius: '2px',
                                background: (c.overallScore ?? 0) > 0.8 ? 'var(--danger)' : (c.overallScore ?? 0) > 0.5 ? 'var(--warning)' : 'var(--success)' }} />
                            </div>
                            <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', minWidth: '32px' }}>
                              {Math.round((c.overallScore ?? 0) * 100)}%
                            </span>
                          </div>
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          {/* Delegated to StatusBadge/tone.ts on purpose. The hand-rolled ternary
                              this replaces read `isHit ? danger : isPending ? warning : success`,
                              so ESCALATED — a real SanctionsCheck value that isHighRisk() treats as
                              high risk — rendered GREEN, as did any status the UI had not been
                              taught. statusTone() resolves an unrecognised value to `neutral`,
                              never `success`, which is the property that makes this safe by
                              default rather than by enumeration. */}
                          <StatusBadge status={c.status} />
                        </td>
                        <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>
                          {c.checkedAt ? new Date(c.checkedAt).toLocaleString(dateLocale) : '—'}
                        </td>
                        <td style={{ padding: '12px 16px', fontSize: '12px' }}>
                          {c.reviewedBy ? (
                            <span title={c.reviewNote ?? ''} style={{ color: 'var(--text-tertiary)' }}>
                              {t('Rozhodl', 'By')} {c.reviewedBy}
                            </span>
                          ) : (isHit || isPending) ? (
                            <Can permission="sanctions:review">
                              <button type="button" onClick={() => openReview(c)} disabled={reviewFor === c.id}
                                style={{ padding: '4px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface-2)',
                                  color: 'var(--text-primary)', fontSize: '11px', fontWeight: 600, cursor: reviewFor === c.id ? 'default' : 'pointer' }}>
                                {t('Posoudit', 'Review')}
                              </button>
                            </Can>
                          ) : <span style={{ color: 'var(--text-tertiary)' }}>—</span>}
                        </td>
                      </tr>
                      {reviewFor === c.id && <Can permission="sanctions:review">
                        <tr style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                          <td colSpan={7} style={{ padding: '16px' }}>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', maxWidth: '640px' }}>
                              <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
                                {t('Manuální rozhodnutí', 'Manual disposition')} — {c.name}
                              </div>

                              {pendingApproval?.checkId === c.id ? (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', padding: '12px', borderRadius: '8px',
                                  background: 'var(--warning-bg)', border: '1px solid var(--warning-border)' }}>
                                  <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--warning-text)' }}>
                                    {t('Čeká na druhého schvalovatele (čtyři oči, ADR-0155)', 'Awaiting a second approver (four-eyes, ADR-0155)')}
                                  </div>
                                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                                    {t('Předejte toto ID jinému operátorovi. Rozhodnutí schválí níže v poli „Schválit žádost“ — vlastní žádost schválit nelze.',
                                       'Hand this id to another operator. They decide it in the "Decide an approval" box below — nobody can approve their own request.')}
                                  </div>
                                  <code style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-primary)', wordBreak: 'break-all' }}>
                                    {pendingApproval.id}
                                  </code>
                                  <div style={{ display: 'flex', gap: '8px' }}>
                                    <button onClick={() => submitReview(c.id, pendingApproval.id)} disabled={reviewBusy}
                                      style={{ padding: '6px 12px', borderRadius: '6px', border: 'none', background: 'var(--accent)', color: '#fff', fontSize: '12px', fontWeight: 600, cursor: 'pointer' }}>
                                      {reviewBusy ? <Loader2 size={12} className="spin" /> : t('Zopakovat po schválení', 'Retry once approved')}
                                    </button>
                                    <button onClick={() => { setReviewFor(null); setPendingApproval(null) }}
                                      style={{ padding: '6px 12px', borderRadius: '6px', border: '1px solid var(--border)', background: 'transparent', color: 'var(--text-secondary)', fontSize: '12px', cursor: 'pointer' }}>
                                      {t('Zavřít', 'Close')}
                                    </button>
                                  </div>
                                </div>
                              ) : (
                                <>
                                  <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
                                    <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                                      <label htmlFor="sanctions-review-status" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                                        {t('Nový stav', 'New status')}
                                      </label>
                                      <select id="sanctions-review-status" value={reviewStatus} onChange={e => setReviewStatus(e.target.value as ReviewStatus)}
                                        style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)' }}>
                                        <option value="CLEAR">{t('CLEAR — falešná shoda', 'CLEAR — false positive')}</option>
                                        <option value="WHITELISTED">{t('WHITELISTED — trvale povoleno', 'WHITELISTED — permanently allowed')}</option>
                                        <option value="ESCALATED">{t('ESCALATED — předat compliance', 'ESCALATED — hand to compliance')}</option>
                                        <option value="HIT">{t('HIT — potvrzená shoda', 'HIT — confirmed match')}</option>
                                      </select>
                                    </div>
                                  </div>
                                  <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                                    <label htmlFor="sanctions-review-note" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                                      {t('Odůvodnění *', 'Rationale *')}
                                    </label>
                                    <textarea id="sanctions-review-note" value={reviewNote} onChange={e => setReviewNote(e.target.value)} rows={2}
                                      placeholder={t('Proč je toto rozhodnutí správné — jde o auditní stopu.', 'Why this decision is correct — this is the audit trail.')}
                                      style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', resize: 'vertical' }} />
                                  </div>
                                  {reviewError && (
                                    <div style={{ fontSize: '12px', color: 'var(--danger-text)' }}>{reviewError}</div>
                                  )}
                                  <div style={{ display: 'flex', gap: '8px' }}>
                                    <button onClick={() => submitReview(c.id)} disabled={reviewBusy}
                                      style={{ padding: '6px 14px', borderRadius: '6px', border: 'none', background: 'var(--accent)', color: '#fff', fontSize: '12px', fontWeight: 600, cursor: reviewBusy ? 'default' : 'pointer' }}>
                                      {reviewBusy ? <Loader2 size={12} className="spin" /> : t('Odeslat rozhodnutí', 'Submit decision')}
                                    </button>
                                    <button onClick={() => setReviewFor(null)}
                                      style={{ padding: '6px 14px', borderRadius: '6px', border: '1px solid var(--border)', background: 'transparent', color: 'var(--text-secondary)', fontSize: '12px', cursor: 'pointer' }}>
                                      {t('Zrušit', 'Cancel')}
                                    </button>
                                  </div>
                                </>
                              )}
                            </div>
                          </td>
                        </tr>
                      </Can>}
                      </Fragment>
                    )
                  })}</tbody>
                </table>
              )}

              {/* Checker half of the four-eyes gate. It is an id field rather than a queue because
                  sanctions-service exposes no pending-approvals list endpoint — ApprovalResource
                  serves only PATCH /{id}, so the id has to be handed over out of band. The
                  ADR-0227 inbox federates lending and agent only, and is read-only by design. */}
              <Can permission="sanctions:review" fallback={<div style={{ padding: '16px', borderTop: '1px solid var(--border)', color: 'var(--text-tertiary)', fontSize: '12px' }}>{t('Rozhodování sankčních žádostí je dostupné pouze operátorům a administrátorům.', 'Sanctions decisions are available to operators and administrators only.')}</div>}>
              <div style={{ padding: '16px', borderTop: '1px solid var(--border)' }}>
                <div style={{ maxWidth: '560px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-primary)' }}>
                    {t('Schválit žádost (druhý pár očí)', 'Decide an approval (second pair of eyes)')}
                  </div>
                  <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                    {t('Vlastní žádost schválit nelze — službu to odmítne.',
                       'You cannot decide your own request — the service refuses it.')}
                  </div>

                  {queueUnavail ? (
                    // Never render "nothing pending" for a read that failed — a supervisor would
                    // read an empty queue as "nothing needs me".
                    <div style={{ fontSize: '12px', color: 'var(--warning-text)' }}>
                      {t('Frontu žádostí se nepodařilo načíst — nezaměňujte s prázdnou frontou.',
                         'Could not load the approval queue — do not read this as an empty queue.')}
                    </div>
                  ) : pendingQueue.length === 0 ? (
                    <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>
                      {t('Žádné čekající žádosti.', 'No approvals waiting.')}
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                      {pendingQueue.map(a => (
                        <div key={a.id} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px',
                          borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontSize: '12px', color: 'var(--text-primary)' }}>
                              {a.action}{a.makerId ? ` — ${t('žádá', 'asked by')} ${a.makerId}` : ''}
                            </div>
                            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)', wordBreak: 'break-all' }}>
                              {a.id}{a.createdAt ? ` · ${new Date(a.createdAt).toLocaleString(dateLocale)}` : ''}
                            </div>
                          </div>
                          <button onClick={() => setDecideId(a.id)}
                            style={{ padding: '4px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'transparent',
                              color: 'var(--text-primary)', fontSize: '11px', fontWeight: 600, cursor: 'pointer', flexShrink: 0 }}>
                            {t('Vybrat', 'Select')}
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <input id="sanctions-approval-id" aria-label={t('ID žádosti', 'Approval id')} value={decideId} onChange={e => setDecideId(e.target.value)} placeholder={t('ID žádosti', 'Approval id')}
                      style={{ flex: 1, padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '12px',
                        fontFamily: 'var(--font-mono)', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
                    <button type="button" onClick={() => decideApproval(true)} disabled={decideBusy || !decideId.trim()} aria-busy={decideBusy}
                      style={{ padding: '8px 14px', borderRadius: '6px', border: 'none', background: 'var(--success)', color: '#fff', fontSize: '12px', fontWeight: 600,
                        cursor: decideBusy || !decideId.trim() ? 'default' : 'pointer', opacity: decideBusy || !decideId.trim() ? 0.6 : 1 }}>
                      {t('Schválit', 'Approve')}
                    </button>
                    <button type="button" onClick={() => decideApproval(false)} disabled={decideBusy || !decideId.trim()} aria-busy={decideBusy}
                      style={{ padding: '8px 14px', borderRadius: '6px', border: '1px solid var(--border)', background: 'transparent', color: 'var(--text-secondary)', fontSize: '12px',
                        cursor: decideBusy || !decideId.trim() ? 'default' : 'pointer', opacity: decideBusy || !decideId.trim() ? 0.6 : 1 }}>
                      {t('Zamítnout', 'Reject')}
                    </button>
                  </div>
                  {decideMsg && <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{decideMsg}</div>}
                </div>
              </div>
              </Can>
            </>
          )}

          {tab === 'search' && (
            <Can permission="sanctions:screen" fallback={<DataUnavailable kind="unauthorized" feature={t('Manuální sankční prověření', 'Manual sanctions screening')} lang={language} />}>
            <div style={{ padding: '24px' }}>
              <div style={{ maxWidth: '560px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
                <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '4px' }}>{t('Manuální prověření entity', 'Manual entity screening')}</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                  <label htmlFor="sanctions-search-name" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('Jméno / Název *', 'Name / Entity *')}</label>
                  <input id="sanctions-search-name" value={searchName} onChange={e => setSearchName(e.target.value)} placeholder={t('Celé jméno nebo název organizace', 'Full name or organisation name')}
                    onKeyDown={e => e.key === 'Enter' && handleScreen()}
                    style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
                </div>
                <div style={{ display: 'flex', gap: '12px' }}>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '4px' }}>
                    <label htmlFor="sanctions-search-type" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('Typ entity', 'Entity type')}</label>
                    <select id="sanctions-search-type" value={searchType} onChange={e => setSearchType(e.target.value as 'INDIVIDUAL'|'ORGANIZATION')}
                      style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)' }}>
                      <option value="INDIVIDUAL">{t('Fyzická osoba', 'Individual')}</option>
                      <option value="ORGANIZATION">{t('Organizace', 'Organisation')}</option>
                    </select>
                  </div>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '4px' }}>
                    <label htmlFor="sanctions-search-dob" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('Datum narození', 'Date of birth')}</label>
                    <input id="sanctions-search-dob" value={searchDob} onChange={e => setSearchDob(e.target.value)} type="date"
                      style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)' }} />
                  </div>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '4px' }}>
                    <label htmlFor="sanctions-search-nationality" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{t('Státní příslušnost', 'Nationality')}</label>
                    <input id="sanctions-search-nationality" value={searchNationality} onChange={e => setSearchNationality(e.target.value.toUpperCase().slice(0,2))}
                      placeholder="CZ" maxLength={2}
                      style={{ padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }} />
                  </div>
                </div>
                {/* List scope selector */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <label style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                      {t('Rozsah prověření', 'Search scope')}
                    </label>
                    <div role="group" aria-label={t('Výběr všech sankčních listů', 'Select sanctions lists')} style={{ display: 'flex', gap: '8px' }}>
                      <button type="button" onClick={() => setSelectedListTypes(lists.map(lst => lst.listType))}
                        style={{ fontSize: '11px', fontWeight: 600, color: 'var(--accent)', background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}>
                        {t('Vše', 'All')}
                      </button>
                      <span style={{ color: 'var(--border)', fontSize: '11px' }}>·</span>
                      <button type="button" onClick={() => setSelectedListTypes([])}
                        style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-tertiary)', background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}>
                        {t('Nic', 'None')}
                      </button>
                    </div>
                  </div>
                  {lists.length === 0 ? (
                    <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', fontStyle: 'italic' }}>
                      {t('Načítám listy…', 'Loading lists…')}
                    </div>
                  ) : (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px', padding: '12px', background: 'var(--surface-2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                      {lists.map(lst => {
                        const checked = selectedListTypes.includes(lst.listType)
                        const isPep = lst.displayName.toLowerCase().includes('pep') || lst.listType.toLowerCase().includes('pep')
                        return (
                          <label key={lst.listType} style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer', padding: '6px 8px', borderRadius: '5px',
                            background: checked ? (isPep ? 'rgba(168,85,247,0.07)' : 'rgba(99,102,241,0.07)') : 'transparent',
                            border: `1px solid ${checked ? (isPep ? 'rgba(168,85,247,0.25)' : 'rgba(99,102,241,0.25)') : 'transparent'}`,
                            transition: 'all 0.15s', opacity: lst.enabled ? 1 : 0.5 }}>
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={e => setSelectedListTypes(prev =>
                                e.target.checked ? [...prev, lst.listType] : prev.filter(x => x !== lst.listType)
                              )}
                              style={{ width: '13px', height: '13px', marginTop: '1px', accentColor: isPep ? 'rgb(168,85,247)' : 'var(--accent)', cursor: 'pointer', flexShrink: 0 }}
                            />
                            <div style={{ minWidth: 0 }}>
                              <div style={{ fontSize: '12px', fontWeight: 600, color: checked ? 'var(--text-primary)' : 'var(--text-secondary)',
                                display: 'flex', alignItems: 'center', gap: '4px', flexWrap: 'wrap', lineHeight: 1.3 }}>
                                {lst.displayName}
                                {isPep && <span style={{ fontSize: '9px', fontWeight: 700, color: 'rgb(168,85,247)', background: 'rgba(168,85,247,0.1)', padding: '1px 4px', borderRadius: '3px' }}>PEP</span>}
                                {!lst.enabled && <span style={{ fontSize: '9px', fontWeight: 700, color: 'var(--text-tertiary)', background: 'var(--surface-4)', padding: '1px 4px', borderRadius: '3px' }}>{t('vyp.', 'off')}</span>}
                              </div>
                              <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)', marginTop: '2px' }}>
                                {lst.lastEntryCount ? `${lst.lastEntryCount.toLocaleString(numberLocale)} ${t('zázn.', 'entries')}` : t('nestaženo', 'not synced')}
                              </div>
                            </div>
                          </label>
                        )
                      })}
                    </div>
                  )}
                  {selectedListTypes.length === 0 && lists.length > 0 && (
                    <div style={{ fontSize: '12px', color: 'var(--warning-text)', background: 'var(--warning-bg)', border: '1px solid var(--warning-border)', borderRadius: '6px', padding: '8px 10px' }}>
                      {t('Nejsou vybrány žádné listy — prověření neproběhne.', 'No lists selected — screening will not run.')}
                    </div>
                  )}
                </div>

                <button type="button" aria-busy={screening} aria-label={screening ? t('Prověřování probíhá', 'Screening in progress') : t('Spustit prověření sankcí', 'Run sanctions screening')} onClick={handleScreen} disabled={screening || !searchName.trim() || selectedListTypes.length === 0}
                  style={{ padding: '10px 20px', borderRadius: '7px', fontSize: '13px', fontWeight: 700,
                    background: 'var(--accent)', color: 'white', border: 'none',
                    cursor: screening || !searchName.trim() || selectedListTypes.length === 0 ? 'not-allowed' : 'pointer',
                    opacity: screening || !searchName.trim() || selectedListTypes.length === 0 ? 0.6 : 1,
                    display: 'flex', alignItems: 'center', gap: '8px', alignSelf: 'flex-start' }}>
                  {screening ? <Loader2 size={14} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} /> : <Play size={14} aria-hidden="true" />}
                  {screening ? t('Prověřuji…', 'Screening…') : t('Spustit prověření', 'Run screening')}
                  {!screening && selectedListTypes.length > 0 && selectedListTypes.length < lists.length && (
                    <span style={{ fontSize: '11px', fontWeight: 600, opacity: 0.8 }}>
                      ({selectedListTypes.length}/{lists.length})
                    </span>
                  )}
                </button>
                {screenError && (
                  <div style={{ padding: '12px', borderRadius: '7px', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: '13px', color: 'var(--danger-text)' }}>
                    {screenError}
                  </div>
                )}
                {screenResult && (() => {
                  /* Only CLEAR and WHITELISTED may say "clear record". The previous code gated on
                     `status === 'HIT'` alone, so POTENTIAL_HIT, ESCALATED and any unrecognised
                     value rendered a green box, a tick, and the literal text CLEAR RECORD --
                     a false textual assertion that a screened name is clean, which is worse than
                     the wrong colour. POTENTIAL_HIT is directly producible by the screening
                     endpoint this panel renders.

                     The default is deliberately the cautious one: anything this UI has not been
                     taught reads as "review required", never as clear. Same property as
                     statusTone(), which resolves an unknown value to `neutral` and never to
                     `success`. */
                  const isClear = screenResult.status === 'CLEAR' || screenResult.status === 'WHITELISTED'
                  const tone = statusTone(screenResult.status)
                  const headline = screenResult.status === 'HIT'
                    ? t('SHODA NALEZENA', 'MATCH FOUND')
                    : isClear
                      ? t('ČISTÝ ZÁZNAM', 'CLEAR RECORD')
                      : t('NUTNÁ KONTROLA', 'REVIEW REQUIRED')
                  return (
                  <div style={{ padding: '16px', borderRadius: '8px', border: `2px solid var(--${tone}-border)`,
                    background: `var(--${tone}-bg)` }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                      {isClear
                        ? <CheckCircle2 size={18} style={{ color: 'var(--success)' }} />
                        : <AlertTriangle size={18} style={{ color: `var(--${tone})` }} />}
                      <span style={{ fontSize: '15px', fontWeight: 800, color: `var(--${tone}-text)` }}>
                        {headline}
                      </span>
                      <span style={{ marginLeft: 'auto', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                        {t('Skóre', 'Score')}: {Math.round((screenResult.overallScore ?? 0) * 100)}%
                      </span>
                    </div>
                    <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                      <strong>{screenResult.name}</strong> · {screenResult.entityType}
                    </div>
                    <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                      {t('Prověřeno v:', 'Checked against:')} {(screenResult.checkedLists ?? []).join(', ')}
                    </div>
                    {(screenResult.matches ?? []).length > 0 && (
                      <div style={{ marginTop: '10px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                        {screenResult.matches.map((m, i) => (
                          <div key={i} style={{ padding: '8px 10px', borderRadius: '5px', background: 'rgba(239,68,68,0.08)', fontSize: '12px' }}>
                            <strong>{m.listType}</strong> · {m.matchType} · {Math.round(m.matchScore * 100)}% · {m.matchedName}
                            {m.programs?.length > 0 && <span style={{ color: 'var(--text-tertiary)' }}> [{m.programs.join(', ')}]</span>}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                  )})()}
              </div>
            </div>
            </Can>
          )}

          {tab === 'lists' && (
            <div style={{ padding: '16px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
                <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                  {lists.filter(l => l.enabled).length} {t('z', 'of')} {lists.length} {t('listů aktivních', 'lists active')}
                </div>
                <Can permission="sanctions:manage">
                <button type="button" aria-busy={refreshingAll} aria-label={t('Stáhnout všechny sankční listy', 'Download all sanctions lists')} onClick={handleRefreshAll} disabled={refreshingAll}
                  style={{ padding: '7px 14px', borderRadius: '6px', fontSize: '12px', fontWeight: 600,
                    background: 'var(--accent)', color: 'white', border: 'none',
                    cursor: refreshingAll ? 'not-allowed' : 'pointer', opacity: refreshingAll ? 0.7 : 1,
                    display: 'flex', alignItems: 'center', gap: '6px' }}>
                  {refreshingAll ? <Loader2 size={12} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} /> : <RefreshCw size={12} aria-hidden="true" />}
                  {t('Stáhnout vše', 'Download all')}
                </button>
                </Can>
              </div>
              {listsLoading ? (
                <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                  <Loader2 size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
                </div>
              ) : lists.length === 0 ? (
                <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                  {listsError || t('Žádné sankční listy nenalezeny. Zkontrolujte připojení ke službě.', 'No sanctions lists found. Check service connection.')}
                </div>
              ) : (
                <>
                  {listsError && (
                    <div style={{ marginBottom: '12px', padding: '12px', borderRadius: '7px', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: '13px', color: 'var(--danger-text)' }}>
                      {listsError}
                    </div>
                  )}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {lists.map(list => (
                      <ListCard key={list.id} list={list}
                        onToggle={handleToggleList}
                        onRefresh={handleRefreshList}
                        onSave={handleSaveCron} />
                    ))}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      </div>
    </AuthGuard>
  )
}
