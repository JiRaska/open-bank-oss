// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useRef, Suspense } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  Banknote, Search, RefreshCw, Plus, Zap, Globe, CheckCircle2, XCircle,
  Clock, AlertTriangle, Timer, ShieldCheck, AlertCircle, ChevronRight
} from 'lucide-react'
import { stashRow } from '@/lib/services/rowHandoff'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { hasPermission } from '@/lib/auth/roles'
import { PageHeader, StatusBadge } from '@/components/ui'
import { useSingleFlight, useIdempotencyKey, wasSkipped } from '@/lib/mutations/singleFlight'

// ADR-0080 P1 (pentest FIND-S3-03/04): all backend access goes through same-origin BFF
// routes — never NEXT_PUBLIC_ localhost URLs, which leaked the internal port map into the
// browser bundle and made the browser call backends directly.
const SEPA_API         = '/api/sepa-payments'
const DOMESTIC_API     = '/api/domestic-payments'
// The k8s workload is `sepa-instant` (no `-service` suffix) — see
// openbank-infra/gitops/components/payments/payments-services.yaml. The BFF looks
// this segment up verbatim against cluster discovery, so the old
// `sepa-instant-service` key missed and pinned this panel to `not_deployed`.
const SEPA_INSTANT_API = '/api/svc/sepa-instant'
// The BFF key is the k8s Service name: in-cluster it resolves to
// http://<key>.<namespace>.svc:<port> (see api/svc/[service]/[...path]/route.ts).
const VOP_API          = '/api/svc/vop-service'

type Tab = 'all' | 'domestic' | 'sepa' | 'sct-inst'
type CreateType = 'domestic-standard' | 'domestic-instant' | 'sepa' | 'sct-inst'
type VopStatus = 'idle' | 'loading' | 'match' | 'close_match' | 'no_match' | 'no_data'

interface Payment {
  id: string; type: 'SEPA' | 'DOMESTIC'
  status: string; amount: number; currency: string
  debtorIban?: string; creditorIban?: string
  creditorAccountNumber?: string; creditorBankCode?: string
  creditorName?: string; remittanceInfo?: string
  createdAt: string
}

interface SctInstPayment {
  paymentId: string; status: string; debtorIban: string; creditorIban: string
  amount: number; currency: string; endToEndId: string
  executionTimeoutAt?: string; settledAt?: string; createdAt: string
}

interface DomesticFormData {
  instant: boolean
  debtorAccountId: string
  debtorAccountNumber: string
  debtorBankCode: string
  debtorName: string
  creditorAccountNumber: string
  creditorBankCode: string
  creditorName: string
  amount: string
  currency: string
  variableSymbol: string
  specificSymbol: string
  constantSymbol: string
  messageForPayee: string
  priority: string
  transferScope: string
  technicalAccountCode: string
  statementLabel: string
  endToEndId: string
}

interface SepaFormData {
  instant: boolean
  debtorIban: string
  creditorIban: string
  creditorName: string
  amount: string
  bic: string
  endToEndId: string
  remittanceInfo: string
  purposeCode: string
  vopStatus: VopStatus
  vopResult: string | null
}

const TABS: { key: Tab; labelCs: string; labelEn: string; icon: React.ElementType }[] = [
  { key: 'all',       labelCs: 'Vše',               labelEn: 'All',               icon: Banknote },
  { key: 'domestic',  labelCs: 'Domácí',            labelEn: 'Domestic',          icon: Banknote },
  { key: 'sepa',      labelCs: 'SEPA',              labelEn: 'SEPA',              icon: Globe },
  { key: 'sct-inst',  labelCs: 'SCT Inst (monitor)', labelEn: 'SCT Inst (monitor)', icon: Zap },
]

const CREATE_OPTIONS: { type: CreateType; labelCs: string; labelEn: string; descCs: string; descEn: string; icon: React.ElementType; speed: string }[] = [
  { type: 'domestic-standard', labelCs: 'Domácí standard', labelEn: 'Domestic Standard',
    descCs: 'CZK převod přes CERTIS, settlement T+0, limit 2.5M Kč', descEn: 'CZK transfer via CERTIS, T+0 settlement, limit 2.5M CZK',
    icon: Banknote, speed: 'T+0' },
  { type: 'domestic-instant', labelCs: 'Domácí okamžitá', labelEn: 'Domestic Instant',
    descCs: 'Okamžitá platba přes CERTIS, settlement <10s, 24/7', descEn: 'Instant payment via CERTIS, <10s settlement, 24/7',
    icon: Zap, speed: '<10s' },
  { type: 'sepa', labelCs: 'SEPA úhrada', labelEn: 'SEPA Credit Transfer',
    descCs: 'EUR převod, settlement 1 den, max 140 zn. zpráva', descEn: 'EUR transfer, 1 day settlement, max 140 chars',
    icon: Globe, speed: '1 den' },
  { type: 'sct-inst', labelCs: 'SEPA Instant', labelEn: 'SEPA Instant',
    descCs: 'EUR SCT Inst, settlement <10s, 24/7, max €999M', descEn: 'EUR SCT Inst, <10s settlement, 24/7, max €999M',
    icon: Zap, speed: '<10s' },
]

function emptyDomestic(instant: boolean): DomesticFormData {
  return { instant, debtorAccountId: '', debtorAccountNumber: '', debtorBankCode: '', debtorName: '',
    creditorAccountNumber: '', creditorBankCode: '', creditorName: '', amount: '', currency: 'CZK',
    variableSymbol: '', specificSymbol: '', constantSymbol: '', messageForPayee: '', priority: 'STANDARD',
    transferScope: 'OWN_ACCOUNTS', technicalAccountCode: '', statementLabel: '', endToEndId: '' }
}

function emptySepa(instant: boolean): SepaFormData {
  return { instant, debtorIban: '', creditorIban: '', creditorName: '', amount: '',
    bic: '', endToEndId: '', remittanceInfo: '', purposeCode: '', vopStatus: 'idle', vopResult: null }
}

async function fetchPayments(url: string, type: 'SEPA' | 'DOMESTIC'): Promise<Payment[]> {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
    if (!res.ok) return []
    const data = await res.json()
    const items = Array.isArray(data) ? data : data.items ?? data.content ?? []
    return items.map((p: Record<string, unknown>) => ({ ...p, type })) as Payment[]
  } catch { return [] }
}

function formatAmount(n: number, currency: string, locale: string) {
  return n?.toLocaleString(locale === 'cs' ? 'cs-CZ' : 'en-GB', { minimumFractionDigits: 2 }) + ' ' + currency
}

function TabNav({ active, onChange }: { active: Tab; onChange: (t: Tab) => void }) {
  const { t } = useLanguage()
  return (
    <div role="group" aria-label={t('Rozsah plateb', 'Payment scope')} style={{ display: 'flex', gap: '2px', marginBottom: '24px', borderBottom: '1px solid var(--border)', paddingBottom: 0 }}>
      {TABS.map(tab => {
        const Icon = tab.icon
        const isActive = active === tab.key
        return (
          <button key={tab.key} type="button" aria-pressed={isActive} onClick={() => onChange(tab.key)}
            style={{
              display: 'flex', alignItems: 'center', gap: '6px', padding: '10px 18px', fontSize: '13px',
              fontWeight: isActive ? 700 : 500, color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
              border: 'none', borderBottom: isActive ? '2px solid var(--accent)' : '2px solid transparent',
              background: 'transparent', cursor: 'pointer', marginBottom: '-1px', transition: 'all 0.15s ease',
            }}>
            <Icon size={14} aria-hidden="true" />
            {tab.key === 'sct-inst' ? 'SCT Inst' : (t(tab.labelCs, tab.labelEn))}
          </button>
        )
      })}
    </div>
  )
}

function VopSection({ formData, setFormData }: { formData: SepaFormData; setFormData: React.Dispatch<React.SetStateAction<SepaFormData>> }) {
  const { t } = useLanguage()
  const vopColor: Record<string, string> = {
    idle: 'var(--text-tertiary)', match: '#16a34a', close_match: '#d97706',
    no_match: '#dc2626', no_data: '#6366f1', loading: 'var(--text-tertiary)',
  }
  const vopIcon = (s: VopStatus) => {
    if (s === 'loading') return <Clock size={16} />
    if (s === 'match') return <CheckCircle2 size={16} />
    if (s === 'close_match') return <AlertCircle size={16} />
    if (s === 'no_match') return <XCircle size={16} />
    return <ShieldCheck size={16} />
  }
  const vopLabel: Record<string, string> = {
    idle: '', loading: 'Ověřuji...', match: 'MATCH — jméno souhlasí',
    close_match: 'CLOSE_MATCH — jméno se mírně liší', no_match: 'NO_MATCH — jméno nesouhlasí',
    no_data: 'NO_DATA — ověření nedostupné',
  }
  return (
    <div style={{ padding: '14px', borderRadius: '8px', border: `1px solid ${vopColor[formData.vopStatus]}44`, background: `${vopColor[formData.vopStatus]}08` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
        <ShieldCheck size={14} style={{ color: formData.instant ? '#d97706' : 'var(--text-secondary)' }} />
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Ověření příjemce (VoP)', 'Verification of Payee (VoP)')}</span>
        {formData.instant && (
          <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 6px', borderRadius: '4px',
            background: '#d9770622', color: '#d97706' }}>{t('Povinné', 'Mandatory')}</span>
        )}
      </div>
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px' }}>
        <label className="sr-only" htmlFor="sepa-vop-payee-name">{t('Jméno příjemce pro ověření', 'Payee name to verify')}</label>
        <input id="sepa-vop-payee-name" className="input" style={{ flex: 1 }} placeholder={t('Jméno příjemce pro ověření', 'Payee name to verify')}
          value={formData.creditorName} onChange={e => setFormData({ ...formData, vopStatus: 'idle', vopResult: null, creditorName: e.target.value })} />
        <button type="button" className="btn btn-secondary btn-sm" disabled={!formData.creditorIban || !formData.creditorName || formData.vopStatus === 'loading'}
          onClick={async () => {
            setFormData(prev => ({ ...prev, vopStatus: 'loading', vopResult: null }))
            try {
              const res = await fetch(`${VOP_API}/api/v1/vop/verify`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ creditorIban: formData.creditorIban, creditorName: formData.creditorName }),
              })
              if (!res.ok) {
                // The service answered, but not with a verdict. That is not a payee mismatch —
                // never render it as no_match, which would tell the operator the payee is wrong
                // when we never actually checked.
                setFormData(prev => ({ ...prev, vopStatus: 'no_data', vopResult: null }))
                return
              }
              const body = await res.json() as { status: VopStatus; matchedName?: string | null }
              // matchedName is only ever populated for close_match (ADR-0171 §6) — the backend
              // will not echo a name on no_match, so there is nothing to guard here beyond
              // rendering what we are given.
              setFormData(prev => ({ ...prev, vopStatus: body.status, vopResult: body.matchedName ?? body.status }))
            } catch {
              // VoP is fail-open (ADR-0171 §3): an unreachable service must not block the payment,
              // but it must never look like a successful verification either.
              setFormData(prev => ({ ...prev, vopStatus: 'no_data', vopResult: null }))
            }
          }}>
          <ShieldCheck size={12} />{t('Ověřit', 'Verify')}
        </button>
      </div>
      {formData.vopStatus !== 'idle' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', fontWeight: 600, color: vopColor[formData.vopStatus] }}>
          {vopIcon(formData.vopStatus)}
          {vopLabel[formData.vopStatus]}
          {/* Only close_match carries a name — the backend never echoes one on no_match. */}
          {formData.vopStatus === 'close_match' && formData.vopResult && formData.vopResult !== 'close_match' && (
            <span style={{ fontWeight: 400 }}>{t('— na účtu:', '— on account:')} {formData.vopResult}</span>
          )}
        </div>
      )}
    </div>
  )
}

export default function PaymentsPage() {
  const { t } = useLanguage()
  return (
    <AuthGuard permission="payments:view">
      <div className="page-container">
        <Suspense fallback={<p>{t('Načítání...', 'Loading...')}</p>}>
          <PaymentsContent />
        </Suspense>
      </div>
    </AuthGuard>
  )
}

function PaymentsContent() {
  const searchParams = useSearchParams()
  const router = useRouter()
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const canCreate = hasPermission(session?.user?.roles ?? [], 'payments:create')

  const [activeTab, setActiveTab] = useState<Tab>((searchParams.get('tab') as Tab) || 'all')
  const [payments, setPayments] = useState<Payment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState<'ALL' | 'SEPA' | 'DOMESTIC'>('ALL')

  const [showCreate, setShowCreate] = useState<'payment-type' | 'domestic-form' | 'sepa-form' | null>(null)
  const [creating, setCreating] = useState(false)
  // Two distinct defects, two mechanisms (see src/lib/mutations/singleFlight.ts):
  //  - `flight` rejects a second submit in the SAME tick, before `disabled={creating}`
  //    has rendered. Without it two clicks booked two payments.
  //  - `idem` holds ONE Idempotency-Key per payload, so a retry after a lost or failed
  //    response REPLAYS the original attempt. The payment services genuinely honour the
  //    key (Redis IdempotencyStore + UNIQUE idempotency_key + a pre-insert lookup) — a
  //    freshly minted UUID per submit, which is what this page used to send, threw that
  //    protection away at the only layer that could use it.
  const flight = useSingleFlight()
  const domesticIdem = useIdempotencyKey()
  const sepaIdem = useIdempotencyKey()
  const [createError, setCreateError] = useState<string | null>(null)
  const [createSuccess, setCreateSuccess] = useState<string | null>(null)
  const [domesticForm, setDomesticForm] = useState<DomesticFormData>(emptyDomestic(false))
  const [sepaForm, setSepaForm] = useState<SepaFormData>(emptySepa(false))
  const [paymentReview, setPaymentReview] = useState<'domestic' | 'sepa' | null>(null)
  const reviewBackRef = useRef<HTMLButtonElement>(null)
  const reviewConfirmRef = useRef<HTMLButtonElement>(null)
  const domesticSubmitRef = useRef<HTMLButtonElement>(null)
  const sepaSubmitRef = useRef<HTMLButtonElement>(null)

  const returnToPaymentForm = () => {
    const submit = paymentReview === 'domestic' ? domesticSubmitRef.current : sepaSubmitRef.current
    setPaymentReview(null)
    setCreateError(null)
    window.requestAnimationFrame(() => submit?.focus())
  }

  // SCT Inst monitoring state
  const [sctPayments, setSctPayments] = useState<SctInstPayment[]>([])
  const [sctLoading, setSctLoading] = useState(true)
  const [sctSearch, setSctSearch] = useState('')
  const [sctServiceUp, setSctServiceUp] = useState<boolean | null>(null)
  const [sctError, setSctError] = useState<string | null>(null)

  const handleTabChange = useCallback((t: Tab) => {
    setActiveTab(t)
    if (t === 'all' || t === 'domestic' || t === 'sepa') {
      router.replace('/payments', { scroll: false })
    } else {
      router.replace('/payments?tab=sct-inst', { scroll: false })
    }
  }, [router])

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const [sepa, domestic] = await Promise.all([
        fetchPayments(SEPA_API, 'SEPA'),
        fetchPayments(DOMESTIC_API, 'DOMESTIC'),
      ])
      setPayments([...sepa, ...domestic].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()))
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('Nepodařilo se načíst platby', 'Failed to load payments'))
    } finally { setLoading(false) }
  }, [])

  const loadSct = useCallback(async () => {
    setSctLoading(true)
    setSctError(null)
    const [healthResult, paymentsResult] = await Promise.allSettled([
      fetch(`${SEPA_INSTANT_API}/q/health/ready`).then(r => r.ok),
      fetch(`${SEPA_INSTANT_API}/api/v1/sepa-instant`).then(async r => {
        if (!r.ok) throw new Error(`SCT Inst request failed (${r.status})`)
        const data = await r.json()
        return Array.isArray(data) ? data : data.payments ?? []
      }),
    ])

    setSctServiceUp(healthResult.status === 'fulfilled' ? healthResult.value : false)
    if (paymentsResult.status === 'fulfilled') {
      setSctPayments(paymentsResult.value)
    } else {
      setSctError(t(
        'Aktuální SCT Inst platby nejsou dostupné. Zobrazené údaje mohou být zastaralé.',
        'Current SCT Inst payments are unavailable. Displayed data may be stale.',
      ))
    }
    setSctLoading(false)
  }, [t])

  useEffect(() => { load() }, [load])
  useEffect(() => { if (activeTab === 'sct-inst') loadSct() }, [activeTab, loadSct])

  const selectPaymentType = (t: CreateType) => {
    if (!canCreate) return
    setCreateError(null)
    setCreateSuccess(null)
    if (t === 'domestic-standard' || t === 'domestic-instant') {
      setDomesticForm(emptyDomestic(t === 'domestic-instant'))
      setShowCreate('domestic-form')
    } else {
      setSepaForm(emptySepa(t === 'sct-inst'))
      setShowCreate('sepa-form')
    }
  }

  const handleDomesticCreate = async (e?: React.FormEvent, confirmed = false) => {
    e?.preventDefault()
    setCreateError(null); setCreateSuccess(null)
    if (!canCreate) {
      setCreateError(t('Nemáte oprávnění vytvářet platby', 'You do not have permission to create payments'))
      return
    }
    const f = domesticForm
    if (!f.debtorAccountId || !f.debtorAccountNumber || !f.debtorBankCode || !f.debtorName ||
        !f.creditorAccountNumber || !f.creditorBankCode || !f.creditorName || !f.amount) {
      setCreateError(t('Vyplňte všechna povinná pole', 'Please fill all required fields'))
      return
    }
    if (!confirmed) {
      setPaymentReview('domestic')
      return
    }
    const outcome = await flight.run('payment:create:domestic', async () => {
    setCreating(true)
    try {
      const payload = {
        debtorAccountId: f.debtorAccountId, debtorAccountNumber: f.debtorAccountNumber,
        debtorBankCode: f.debtorBankCode, debtorName: f.debtorName,
        creditorAccountNumber: f.creditorAccountNumber, creditorBankCode: f.creditorBankCode,
        creditorName: f.creditorName, amount: Number(f.amount), currency: f.currency,
        priority: f.priority, transferScope: f.transferScope, instant: f.instant,
        ...(f.variableSymbol && { variableSymbol: f.variableSymbol }),
        ...(f.specificSymbol && { specificSymbol: f.specificSymbol }),
        ...(f.constantSymbol && { constantSymbol: f.constantSymbol }),
        ...(f.messageForPayee && { messageForPayee: f.messageForPayee }),
        ...(f.transferScope === 'TECHNICAL_ACCOUNT' && f.technicalAccountCode && { technicalAccountCode: f.technicalAccountCode }),
        ...(f.statementLabel && { statementLabel: f.statementLabel }),
        ...(f.endToEndId && { endToEndId: f.endToEndId }),
      }
      const res = await fetch(`/api/domestic-payments`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': domesticIdem.forPayload(payload) },
        body: JSON.stringify(payload),
      })
      if (!res.ok) throw new Error(await res.text() || t('Vytvoření platby selhalo', 'Failed to create payment'))
      // Cleared only once the attempt has definitively succeeded: the next deliberate
      // submission of an identical payload is then a NEW payment, not a replay.
      domesticIdem.clear()
      setCreateSuccess(t(f.instant ? 'Okamžitá platba vytvořena' : 'Platba vytvořena', f.instant ? 'Instant payment created' : 'Payment created'))
      setPaymentReview(null)
      setShowCreate(null)
      load()
    } catch (err: unknown) {
      setCreateError(err instanceof Error ? err.message : t('Neznámá chyba', 'Unknown error'))
    } finally { setCreating(false) }
    })
    if (wasSkipped(outcome)) return
  }

  const handleSepaCreate = async (e?: React.FormEvent, confirmed = false) => {
    e?.preventDefault()
    setCreateError(null); setCreateSuccess(null)
    if (!canCreate) {
      setCreateError(t('Nemáte oprávnění vytvářet platby', 'You do not have permission to create payments'))
      return
    }
    const f = sepaForm
    if (!f.debtorIban || !f.creditorIban || !f.creditorName || !f.amount) {
      setCreateError(t('Vyplňte všechna povinná pole', 'Please fill all required fields'))
      return
    }
    // VoP fails open (ADR-0171 §3): a lookup that produced no name — a non-domestic IBAN, a
    // scheme outage — must not refuse the payment, or the control breaches the execution-time
    // obligation it exists to serve. Only an actual name mismatch blocks; no_data proceeds on
    // the warning the panel above already shows. This gate predates the real backend, when the
    // mock could only ever return match/close_match/no_match and no_data was unreachable.
    if (f.instant && (f.vopStatus === 'idle' || f.vopStatus === 'loading')) {
      setCreateError(t('SCT Inst vyžaduje ověření příjemce (VoP) — spusťte ověření', 'SCT Inst requires Verification of Payee — run the check first'))
      return
    }
    if (f.instant && f.vopStatus === 'no_match') {
      setCreateError(t('Jméno příjemce nesouhlasí (VoP NO_MATCH) — platbu nelze odeslat', 'Payee name does not match (VoP NO_MATCH) — payment cannot be sent'))
      return
    }
    if (!confirmed) {
      setPaymentReview('sepa')
      return
    }
    const outcome = await flight.run('payment:create:sepa', async () => {
    setCreating(true)
    try {
      const payload = {
        debtorIban: f.debtorIban, creditorIban: f.creditorIban,
        creditorName: f.creditorName, amount: Number(f.amount),
        currency: 'EUR', instant: f.instant,
        ...(f.bic && { bic: f.bic }),
        ...(f.endToEndId && { endToEndId: f.endToEndId }),
        ...(f.remittanceInfo && { remittanceInfo: f.remittanceInfo }),
        ...(f.purposeCode && { purposeCode: f.purposeCode }),
      }
      const res = await fetch(`/api/sepa-payments`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': sepaIdem.forPayload(payload) },
        body: JSON.stringify(payload),
      })
      if (!res.ok) throw new Error(await res.text() || t('Vytvoření platby selhalo', 'Failed to create payment'))
      sepaIdem.clear()
      setCreateSuccess(t(f.instant ? 'SCT Inst platba vytvořena' : 'SEPA platba vytvořena', f.instant ? 'SCT Inst payment created' : 'SEPA payment created'))
      setPaymentReview(null)
      setShowCreate(null)
      load()
    } catch (err: unknown) {
      setCreateError(err instanceof Error ? err.message : t('Neznámá chyba', 'Unknown error'))
    } finally { setCreating(false) }
    })
    if (wasSkipped(outcome)) return
  }

  // ── Filtered data ──────────────────────────────────────────────
  const filtered = payments.filter(p => {
    if (activeTab === 'domestic' && p.type !== 'DOMESTIC') return false
    if (activeTab === 'sepa' && p.type !== 'SEPA') return false
    if (typeFilter !== 'ALL' && p.type !== typeFilter && activeTab === 'all') return false
    if (search) {
      const s = search.toLowerCase()
      return p.id.toLowerCase().includes(s) ||
        p.creditorName?.toLowerCase().includes(s) ||
        p.creditorIban?.toLowerCase().includes(s) ||
        p.creditorAccountNumber?.toLowerCase().includes(s) ||
        p.creditorBankCode?.toLowerCase().includes(s) ||
        (p.creditorAccountNumber && p.creditorBankCode && `${p.creditorAccountNumber}/${p.creditorBankCode}`.toLowerCase().includes(s))
    }
    return true
  })

  const sepaCount     = payments.filter(p => p.type === 'SEPA').length
  const domesticCount = payments.filter(p => p.type === 'DOMESTIC').length
  const pendingCount  = payments.filter(p => p.status === 'PENDING' || p.status === 'PROCESSING').length

  // ── SCT Inst monitoring ─────────────────────────────────────────
  const sctSettled = sctPayments.filter(p => p.status === 'SETTLED').length
  const sctProcessing = sctPayments.filter(p => p.status === 'PROCESSING').length
  const sctTimedOut = sctPayments.filter(p => p.status === 'TIMEOUT').length
  const sctVolume = sctPayments.reduce((s, p) => s + (p.amount ?? 0), 0)
  const sctFiltered = sctPayments.filter(p =>
    p.debtorIban?.toLowerCase().includes(sctSearch.toLowerCase()) ||
    p.creditorIban?.toLowerCase().includes(sctSearch.toLowerCase()) ||
    p.status?.toLowerCase().includes(sctSearch.toLowerCase()) ||
    p.endToEndId?.toLowerCase().includes(sctSearch.toLowerCase())
  )
  const sctStatusColor = (s: string) => {
    if (s === 'SETTLED') return { bg: 'var(--success-bg)', text: 'var(--success-text)', border: 'var(--success-border)' }
    if (s === 'TIMEOUT' || s === 'REJECTED') return { bg: 'var(--danger-bg)', text: 'var(--danger-text)', border: 'var(--danger-border)' }
    if (s === 'RECALLED') return { bg: 'var(--warning-bg)', text: 'var(--warning-text)', border: 'var(--warning-border)' }
    return { bg: 'rgba(99,102,241,0.1)', text: '#6366f1', border: 'rgba(99,102,241,0.2)' }
  }
  const timeoutCountdown = (timeoutAt?: string, status?: string) => {
    if (status !== 'PROCESSING' || !timeoutAt) return null
    // eslint-disable-next-line react-hooks/purity -- time-relative display; timestamps are stable server data.
    const ms = new Date(timeoutAt).getTime() - Date.now()
    if (ms <= 0) return <span style={{ fontSize: '11px', color: 'var(--danger)', fontWeight: 700 }}>TIMEOUT</span>
    return <span style={{ fontSize: '11px', color: ms < 3000 ? 'var(--danger)' : 'var(--warning)', fontWeight: 600 }}>{(ms / 1000).toFixed(1)}s</span>
  }

  return (
    <div>
      <PageHeader
        title={t('Platby', 'Payments')}
        subtitle={t('Tuzemské a SEPA platební příkazy', 'Domestic and SEPA payment orders')}
        icon={<Banknote size={18} aria-hidden="true" />}
        breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Platby', 'Payments')}</span></div>}
        actions={activeTab !== 'sct-inst' ? (
          <button
            className="btn btn-secondary"
            type="button"
            onClick={load}
            disabled={loading}
            aria-busy={loading}
            aria-label={t('Obnovit platby', 'Refresh payments')}
          >
            <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
            {t('Obnovit', 'Refresh')}
          </button>
        ) : undefined}
      />

      <TabNav active={activeTab} onChange={handleTabChange} />

      {/* ── TAB: SCT Inst monitoring ─────────────────────────────── */}
      {activeTab === 'sct-inst' && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                {t('Real-time platby — vypořádání do 10 sekund · EPC SCT Inst · 24/7/365', 'Real-time payments — settlement <10s · EPC SCT Inst · 24/7/365')}
              </span>
            </div>
            <span style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '11px', fontWeight: 600,
              padding: '4px 10px', borderRadius: '20px',
              background: sctServiceUp === true ? 'var(--success-bg)' : sctServiceUp === false ? 'var(--danger-bg)' : 'var(--surface-3)',
              color: sctServiceUp === true ? 'var(--success-text)' : sctServiceUp === false ? 'var(--danger-text)' : 'var(--text-tertiary)',
              border: `1px solid ${sctServiceUp === true ? 'var(--success-border)' : sctServiceUp === false ? 'var(--danger-border)' : 'var(--border)'}` }}>
              {sctServiceUp === true ? <CheckCircle2 size={10} /> : sctServiceUp === false ? <XCircle size={10} /> : <Clock size={10} />}
              sepa-instant :8127
            </span>
          </div>

          {sctTimedOut > 0 && (
            <div style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
              background: 'var(--danger-bg)', border: '1px solid var(--danger-border)',
              display: 'flex', alignItems: 'center', gap: '10px' }}>
              <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
              <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--danger-text)' }}>
                {sctTimedOut} {t('platby překročily 10s limit', 'payments exceeded 10s limit')} — {t('vyžaduje prošetření', 'requires investigation')}
              </span>
            </div>
          )}

          {sctError && (
            <div role="status" style={{ marginBottom: '20px', padding: '12px 16px', borderRadius: '8px',
              background: 'var(--warning-bg)', border: '1px solid var(--warning-border)',
              display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px' }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13px', fontWeight: 600, color: 'var(--warning-text)' }}>
                <AlertTriangle size={16} aria-hidden="true" style={{ flexShrink: 0 }} />
                {sctError}
              </span>
              <button className="btn btn-secondary btn-sm" type="button" onClick={loadSct} disabled={sctLoading} aria-busy={sctLoading}>
                <RefreshCw size={12} aria-hidden="true" />
                {t('Zkusit znovu', 'Retry')}
              </button>
            </div>
          )}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '20px' }}>
            {[
              { label: t('Platby celkem', 'Total payments'), value: sctPayments.length, icon: <Zap size={16} />, color: 'var(--accent)' },
              { label: t('Vypořádáno', 'Settled'), value: sctSettled, icon: <CheckCircle2 size={16} />, color: '#16a34a' },
              { label: t('Zpracovává se', 'Processing'), value: sctProcessing, icon: <Timer size={16} />, color: '#d97706' },
              { label: t('Objem (EUR)', 'Volume (EUR)'), value: sctVolume.toLocaleString(numberLocale, { maximumFractionDigits: 0 }), icon: <Zap size={16} />, color: 'var(--accent)' },
            ].map(k => (
              <div key={k.label} className="stat-card">
                <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color, marginBottom: '10px' }}>{k.icon}</div>
                <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
              </div>
            ))}
          </div>

          <div className="card" style={{ overflow: 'hidden' }}>
            <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
              <div style={{ position: 'relative', flex: 1, maxWidth: '320px' }}>
                <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
                <label className="sr-only" htmlFor="payments-sct-search">{t('Hledat SCT platby', 'Search SCT payments')}</label>
                <input id="payments-sct-search" value={sctSearch} onChange={e => setSctSearch(e.target.value)}
                  placeholder={t('Hledat IBAN, end-to-end ID, status…', 'Search IBAN, end-to-end ID, status…')}
                  style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                    border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
              </div>
              <button
                aria-label={t('Obnovit SCT platby', 'Refresh SCT payments')}
                className="btn btn-secondary btn-sm"
                type="button"
                onClick={loadSct}
                disabled={sctLoading}
                aria-busy={sctLoading}
              >
                <RefreshCw size={12} aria-hidden="true" style={{ animation: sctLoading ? 'spin 0.8s linear infinite' : 'none' }} />
              </button>
            </div>
            {sctLoading && !sctPayments.length ? (
              <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
                <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} /><div>{t('Načítám…', 'Loading…')}</div>
              </div>
            ) : !sctError && sctFiltered.length === 0 ? (
              <div style={{ padding: '48px', textAlign: 'center' }}>
                <Zap size={32} style={{ color: 'var(--text-tertiary)', marginBottom: '12px' }} />
                <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '4px' }}>{t('Žádné SCT Inst platby', 'No SCT Inst payments')}</div>
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Mikroservisa běží na portu 8127.', 'Microservice runs on port 8127.')}</div>
              </div>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {[t('Debtor IBAN', 'Debtor IBAN'), t('Creditor IBAN', 'Creditor IBAN'), t('Částka', 'Amount'),
                    t('End-to-End ID', 'End-to-End ID'), t('Status', 'Status'), t('Timeout', 'Timeout'), t('Vytvořeno', 'Created')].map(h => (
                    <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700, color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                  ))}
                </tr></thead>
                <tbody>{sctFiltered.map(p => {
                  const sc = sctStatusColor(p.status)
                  return (
                    <tr key={p.paymentId} style={{ borderBottom: '1px solid var(--border)' }}
                      onMouseEnter={e => (e.currentTarget.style.background = 'var(--surface-2)')}
                      onMouseLeave={e => (e.currentTarget.style.background = '')}>
                      <td style={{ padding: '12px 16px', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-primary)' }}>{p.debtorIban}</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>{p.creditorIban}</td>
                      <td style={{ padding: '12px 16px', fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{formatAmount(p.amount, p.currency, language)}</td>
                      <td style={{ padding: '12px 16px', fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>{p.endToEndId}</td>
                      <td style={{ padding: '12px 16px' }}>
                        <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600, background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{p.status}</span>
                      </td>
                      <td style={{ padding: '12px 16px' }}>{timeoutCountdown(p.executionTimeoutAt, p.status)}</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{p.createdAt ? new Date(p.createdAt).toLocaleString(numberLocale) : '—'}</td>
                    </tr>
                  )
                })}</tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {/* ── TABS: All / Domestic / SEPA ──────────────────────────── */}
      {activeTab !== 'sct-inst' && (
        <>
          {/* Stats */}
          {activeTab === 'all' && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px', marginBottom: '20px' }}>
              {[
                { label: t('SEPA Platby', 'SEPA Payments'), value: sepaCount, color: 'var(--accent)' },
                { label: t('Tuzemské Platby', 'Domestic Payments'), value: domesticCount, color: 'var(--green)' },
                { label: t('Čekající / Zpracovává se', 'Pending / Processing'), value: pendingCount, color: 'var(--yellow)' },
              ].map(s => (
                <div key={s.label} className="stat-card">
                  <div className="stat-value" style={{ color: s.color }}>{loading ? '—' : s.value}</div>
                  <div className="stat-label">{s.label}</div>
                </div>
              ))}
            </div>
          )}

          {canCreate && (
            <div style={{ display: 'flex', gap: '10px', marginBottom: '16px', justifyContent: 'flex-end' }}>
              <button className="btn btn-primary" type="button" aria-expanded={showCreate === 'payment-type'} aria-controls="payment-create-type-panel" aria-label={t('Nová platba', 'New Payment')} onClick={() => setShowCreate(showCreate ? null : 'payment-type')}>
                <Plus size={14} aria-hidden="true" />
                {t('Nová platba', 'New Payment')}
              </button>
            </div>
          )}

          {/* Payment type selector */}
          {showCreate === 'payment-type' && (
            <div id="payment-create-type-panel" className="card" style={{ padding: '20px', marginBottom: '20px' }}>
              <h2 style={{ fontSize: '16px', fontWeight: 600, marginBottom: '16px' }}>{t('Vyberte typ platby', 'Select payment type')}</h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '12px' }}>
                {CREATE_OPTIONS.map(opt => {
                  const Icon = opt.icon
                  return (
                    <button key={opt.type} type="button" onClick={() => selectPaymentType(opt.type)}
                      style={{ display: 'flex', flexDirection: 'column', gap: '8px', padding: '16px', borderRadius: '10px',
                        border: `1px solid var(--border)`, background: 'var(--surface-1)', cursor: 'pointer',
                        textAlign: 'left', transition: 'all 0.15s ease' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                        <div style={{ width: '36px', height: '36px', borderRadius: '8px', background: 'var(--accent)18',
                          display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                          <Icon size={18} style={{ color: 'var(--accent)' }} />
                        </div>
                        <div>
                          <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{t(opt.labelCs, opt.labelEn)}</div>
                          <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>{t(opt.descCs, opt.descEn)}</div>
                        </div>
                      </div>
                      <div style={{ fontSize: '11px', fontWeight: 600, color: opt.type === 'domestic-instant' || opt.type === 'sct-inst' ? '#d97706' : 'var(--text-tertiary)' }}>
                        {t('Vypořádání:', 'Settlement:')} {opt.speed}
                      </div>
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {/* Domestic form */}
          {showCreate === 'domestic-form' && (
            <div className="card" style={{ padding: '20px', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
                <h2 style={{ fontSize: '16px', fontWeight: 600 }}>
                  {domesticForm.instant
                    ? t('Domácí okamžitá platba (CERTIS Okamžitá)', 'Domestic Instant Payment (CERTIS Okamžitá)')
                    : t('Domácí platba (CERTIS)', 'Domestic Payment (CERTIS)')}
                </h2>
                {domesticForm.instant && (
                  <span style={{ fontSize: '10px', fontWeight: 700, padding: '3px 8px', borderRadius: '4px',
                    background: '#d9770622', color: '#d97706' }}>
                    {t('OKAMŽITÁ PLATBA — settlement <10 sec, 24/7/365', 'INSTANT — settlement <10 sec, 24/7/365')}
                  </span>
                )}
              </div>
              <form onSubmit={handleDomesticCreate} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-transfer-scope" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Typ převodu', 'Transfer Scope')}</label>
                    <select id="domestic-transfer-scope" className="input" style={{ width: '100%' }} value={domesticForm.transferScope}
                      onChange={e => setDomesticForm({ ...domesticForm, transferScope: e.target.value })}>
                      <option value="OWN_ACCOUNTS">{t('Vlastní účty', 'Own Accounts')}</option>
                      <option value="INTERNAL_CLIENT">{t('Interní klient', 'Internal Client')}</option>
                      <option value="TECHNICAL_ACCOUNT">{t('Technický účet', 'Technical Account')}</option>
                    </select>
                  </div>
                  {domesticForm.transferScope === 'TECHNICAL_ACCOUNT' && (
                    <div>
                      <label htmlFor="domestic-technical-account-code" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Kód technického účtu', 'Technical Account Code')}</label>
                      <input id="domestic-technical-account-code" className="input" style={{ width: '100%' }} value={domesticForm.technicalAccountCode}
                        onChange={e => setDomesticForm({ ...domesticForm, technicalAccountCode: e.target.value })} required />
                    </div>
                  )}
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-debtor-account-id" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Účet plátce (ID)', 'Debtor Account ID')}</label>
                    <input id="domestic-debtor-account-id" className="input" style={{ width: '100%' }} value={domesticForm.debtorAccountId}
                      onChange={e => setDomesticForm({ ...domesticForm, debtorAccountId: e.target.value })} required />
                  </div>
                  <div>
                    <label htmlFor="domestic-debtor-account-number" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Číslo účtu plátce', 'Debtor Account No.')}</label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <input id="domestic-debtor-account-number" className="input" style={{ flex: 2 }} placeholder="1234567890" value={domesticForm.debtorAccountNumber}
                        onChange={e => setDomesticForm({ ...domesticForm, debtorAccountNumber: e.target.value })} required />
                      <span style={{ display: 'flex', alignItems: 'center' }}>/</span>
                      <input id="domestic-debtor-bank-code" aria-label={t('Kód banky plátce', 'Debtor bank code')} className="input" style={{ flex: 1 }} placeholder="0100" value={domesticForm.debtorBankCode}
                        onChange={e => setDomesticForm({ ...domesticForm, debtorBankCode: e.target.value })} required />
                    </div>
                  </div>
                  <div>
                    <label htmlFor="domestic-debtor-name" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Jméno plátce', 'Debtor Name')}</label>
                    <input id="domestic-debtor-name" className="input" style={{ width: '100%' }} value={domesticForm.debtorName}
                      onChange={e => setDomesticForm({ ...domesticForm, debtorName: e.target.value })} required />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-creditor-account-number" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Číslo účtu příjemce', 'Creditor Account No.')}</label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <input id="domestic-creditor-account-number" className="input" style={{ flex: 2 }} placeholder="0987654321" value={domesticForm.creditorAccountNumber}
                        onChange={e => setDomesticForm({ ...domesticForm, creditorAccountNumber: e.target.value })} required />
                      <span style={{ display: 'flex', alignItems: 'center' }}>/</span>
                      <input id="domestic-creditor-bank-code" aria-label={t('Kód banky příjemce', 'Creditor bank code')} className="input" style={{ flex: 1 }} placeholder="0100" value={domesticForm.creditorBankCode}
                        onChange={e => setDomesticForm({ ...domesticForm, creditorBankCode: e.target.value })} required />
                    </div>
                  </div>
                  <div>
                    <label htmlFor="domestic-creditor-name" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Jméno příjemce', 'Creditor Name')}</label>
                    <input id="domestic-creditor-name" className="input" style={{ width: '100%' }} value={domesticForm.creditorName}
                      onChange={e => setDomesticForm({ ...domesticForm, creditorName: e.target.value })} required />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-amount" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Částka', 'Amount')}</label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <input id="domestic-amount" type="number" step="0.01" min="0.01" max="2500000" className="input" style={{ flex: 1 }} value={domesticForm.amount}
                        onChange={e => setDomesticForm({ ...domesticForm, amount: e.target.value })} required />
                      <span className="input" style={{ width: '80px', border: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--surface-2)' }}>CZK</span>
                    </div>
                  </div>
                  <div>
                    <label htmlFor="domestic-message" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Zpráva pro příjemce', 'Message for Payee')}</label>
                    <input id="domestic-message" className="input" style={{ width: '100%' }} value={domesticForm.messageForPayee}
                      onChange={e => setDomesticForm({ ...domesticForm, messageForPayee: e.target.value })} />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-variable-symbol" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Variabilní symbol', 'Variable Symbol')}</label>
                    <input id="domestic-variable-symbol" className="input" style={{ width: '100%' }} value={domesticForm.variableSymbol}
                      onChange={e => setDomesticForm({ ...domesticForm, variableSymbol: e.target.value })} />
                  </div>
                  <div>
                    <label htmlFor="domestic-specific-symbol" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Specifický symbol', 'Specific Symbol')}</label>
                    <input id="domestic-specific-symbol" className="input" style={{ width: '100%' }} value={domesticForm.specificSymbol}
                      onChange={e => setDomesticForm({ ...domesticForm, specificSymbol: e.target.value })} />
                  </div>
                  <div>
                    <label htmlFor="domestic-constant-symbol" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Konstantní symbol', 'Constant Symbol')}</label>
                    <input id="domestic-constant-symbol" className="input" style={{ width: '100%' }} value={domesticForm.constantSymbol}
                      onChange={e => setDomesticForm({ ...domesticForm, constantSymbol: e.target.value })} />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="domestic-priority" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Priorita', 'Priority')}</label>
                    <select id="domestic-priority" className="input" style={{ width: '100%' }} value={domesticForm.priority}
                      onChange={e => setDomesticForm({ ...domesticForm, priority: e.target.value })}>
                      <option value="STANDARD">{t('Standardní', 'Standard')}</option>
                      <option value="URGENT">{t('Urgentní', 'Urgent')}</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor="domestic-end-to-end" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('End-To-End ID', 'End-To-End ID')}</label>
                    <input id="domestic-end-to-end" className="input" style={{ width: '100%' }} value={domesticForm.endToEndId}
                      onChange={e => setDomesticForm({ ...domesticForm, endToEndId: e.target.value })} />
                  </div>
                  <div>
                    <label htmlFor="domestic-statement-label" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Označení výpisu', 'Statement Label')}</label>
                    <input id="domestic-statement-label" className="input" style={{ width: '100%' }} value={domesticForm.statementLabel}
                      onChange={e => setDomesticForm({ ...domesticForm, statementLabel: e.target.value })} />
                  </div>
                </div>
                {createError && <div style={{ color: 'var(--red)', fontSize: '13px', marginTop: '4px' }}>{createError}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '8px' }}>
                  <button type="button" className="btn btn-secondary" onClick={() => setShowCreate(null)}>{t('Zrušit', 'Cancel')}</button>
                  <button ref={domesticSubmitRef} type="submit" className="btn btn-primary" disabled={creating}>
                    {creating ? t('Odesílám...', 'Sending...') : t(domesticForm.instant ? 'Odeslat okamžitě' : 'Vytvořit', domesticForm.instant ? 'Send instant' : 'Create')}
                  </button>
                </div>
              </form>
            </div>
          )}

          {/* SEPA form */}
          {showCreate === 'sepa-form' && (
            <div className="card" style={{ padding: '20px', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
                <h2 style={{ fontSize: '16px', fontWeight: 600 }}>
                  {sepaForm.instant
                    ? t('SEPA Instant Payment (SCT Inst)', 'SEPA Instant Payment (SCT Inst)')
                    : t('SEPA Credit Transfer (SCT)', 'SEPA Credit Transfer (SCT)')}
                </h2>
                {sepaForm.instant && (
                  <span style={{ fontSize: '10px', fontWeight: 700, padding: '3px 8px', borderRadius: '4px',
                    background: '#0ea5e922', color: '#0ea5e9' }}>
                    {t('SCT INST — settlement <10 sec, 24/7/365', 'SCT INST — settlement <10 sec, 24/7/365')}
                  </span>
                )}
              </div>
              <form onSubmit={handleSepaCreate} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="sepa-debtor-iban" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('IBAN plátce', 'Debtor IBAN')}</label>
                    <input id="sepa-debtor-iban" className="input" style={{ width: '100%', fontFamily: 'var(--font-mono)' }}
                      placeholder="CZ65 0800 0000 1920 0014 5399" value={sepaForm.debtorIban}
                      onChange={e => setSepaForm({ ...sepaForm, debtorIban: e.target.value })} required />
                  </div>
                  <div>
                    <label htmlFor="sepa-creditor-iban" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('IBAN příjemce', 'Creditor IBAN')}</label>
                    <input id="sepa-creditor-iban" className="input" style={{ width: '100%', fontFamily: 'var(--font-mono)' }}
                      placeholder="CZ65 0800 0000 1920 0014 5399" value={sepaForm.creditorIban}
                      onChange={e => setSepaForm({ ...sepaForm, creditorIban: e.target.value })} required />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="sepa-creditor-name" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Jméno příjemce', 'Creditor Name')}</label>
                    <input id="sepa-creditor-name" className="input" style={{ width: '100%' }} placeholder="John Doe" value={sepaForm.creditorName}
                      onChange={e => setSepaForm({ ...sepaForm, creditorName: e.target.value })} required />
                    <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '3px' }}>
                      {t('Max 70 znaků. SWIFT charset: A-Z, 0-9, / - ? : ( ) . , \' + (bez diakritiky)', 'Max 70 chars. SWIFT charset: A-Z, 0-9, / - ? : ( ) . , \' + (no diacritics)')}
                    </div>
                  </div>
                  <div>
                    <label htmlFor="sepa-amount" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Částka (EUR)', 'Amount (EUR)')}</label>
                    <input id="sepa-amount" type="number" step="0.01" min="0.01" className="input" style={{ width: '100%' }} placeholder="100.00"
                      value={sepaForm.amount} onChange={e => setSepaForm({ ...sepaForm, amount: e.target.value })} required />
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div>
                    <label htmlFor="sepa-bic" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('BIC (nepovinný)', 'BIC (optional)')}</label>
                    <input id="sepa-bic" className="input" style={{ width: '100%', fontFamily: 'var(--font-mono)' }} placeholder="KOMBCZPP"
                      value={sepaForm.bic} onChange={e => setSepaForm({ ...sepaForm, bic: e.target.value })} />
                    <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '3px' }}>
                      {t('Odvodit z IBAN — nepovinné', 'Derivable from IBAN — optional')}
                    </div>
                  </div>
                  <div>
                    <label htmlFor="sepa-end-to-end" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('End-to-End ID (nepovinný)', 'End-to-End ID (optional)')}</label>
                    <input id="sepa-end-to-end" className="input" style={{ width: '100%', fontFamily: 'var(--font-mono)' }} placeholder="Max 35 znaků"
                      value={sepaForm.endToEndId} onChange={e => setSepaForm({ ...sepaForm, endToEndId: e.target.value })} />
                  </div>
                </div>
                <div>
                  <label htmlFor="sepa-remittance" className="stat-label" style={{ display: 'block', marginBottom: '4px' }}>{t('Zpráva pro příjemce (nepovinná)', 'Remittance Info (optional)')}</label>
                  <input id="sepa-remittance" className="input" style={{ width: '100%' }} placeholder={t('Max 140 znaků, free-form', 'Max 140 chars, free-form')}
                    maxLength={140} value={sepaForm.remittanceInfo}
                    onChange={e => setSepaForm({ ...sepaForm, remittanceInfo: e.target.value })} />
                  <div style={{ fontSize: '10px', color: 'var(--text-tertiary)', marginTop: '3px' }}>
                    {sepaForm.remittanceInfo.length}/140
                  </div>
                </div>
                <VopSection formData={sepaForm} setFormData={setSepaForm} />
                {createError && <div style={{ color: 'var(--red)', fontSize: '13px', marginTop: '4px' }}>{createError}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '8px' }}>
                  <button type="button" className="btn btn-secondary" onClick={() => setShowCreate(null)}>{t('Zrušit', 'Cancel')}</button>
                  <button ref={sepaSubmitRef} type="submit" className="btn btn-primary" disabled={creating}>
                    {creating ? t('Odesílám...', 'Sending...') : t(sepaForm.instant ? 'Odeslat okamžitě' : 'Vytvořit', sepaForm.instant ? 'Send instant' : 'Create')}
                  </button>
                </div>
              </form>
            </div>
          )}

          {paymentReview && (
            <div
              role="alertdialog"
              aria-modal="true"
              aria-labelledby="payment-create-review-title"
              aria-describedby="payment-create-review-impact"
              onKeyDown={event => {
                if (event.key === 'Escape' && !creating) {
                  returnToPaymentForm()
                }
                if (event.key === 'Tab') {
                  const first = reviewBackRef.current
                  const last = reviewConfirmRef.current
                  if (event.shiftKey && document.activeElement === first) {
                    event.preventDefault()
                    last?.focus()
                  } else if (!event.shiftKey && document.activeElement === last) {
                    event.preventDefault()
                    first?.focus()
                  }
                }
              }}
              style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.72)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}
            >
              <div className="card" style={{ width: 'min(620px, 100%)', maxHeight: '90vh', overflowY: 'auto', padding: 22 }}>
                <h2 id="payment-create-review-title" style={{ margin: 0, fontSize: 18 }}>
                  {t('Zkontrolovat platební příkaz', 'Review payment order')}
                </h2>
                <p id="payment-create-review-impact" style={{ color: 'var(--text-secondary)', fontSize: 13, lineHeight: 1.55 }}>
                  {t('Potvrzením odešlete přesně tento příkaz platební službě. Přijetí příkazu ještě neznamená vypořádání; další stav a případné schválení řídí platební workflow.', 'Confirmation submits this exact order to the payment service. Acceptance is not settlement; subsequent status and any required approval remain controlled by the payment workflow.')}
                </p>
                {paymentReview === 'domestic' ? (
                  <dl style={{ display: 'grid', gridTemplateColumns: '155px minmax(0, 1fr)', gap: '9px 12px', padding: 14, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}>
                    <dt>{t('Typ', 'Type')}</dt><dd>{domesticForm.instant ? t('Domácí okamžitá', 'Domestic instant') : t('Domácí standardní', 'Domestic standard')}</dd>
                    <dt>{t('Částka', 'Amount')}</dt><dd style={{ fontSize: 16, fontWeight: 750 }}>{formatAmount(Number(domesticForm.amount), domesticForm.currency, language)}</dd>
                    <dt>{t('Plátce', 'Debtor')}</dt><dd>{domesticForm.debtorName}<br/><span className="mono">{domesticForm.debtorAccountNumber}/{domesticForm.debtorBankCode}</span></dd>
                    <dt>{t('Příjemce', 'Beneficiary')}</dt><dd>{domesticForm.creditorName}<br/><span className="mono">{domesticForm.creditorAccountNumber}/{domesticForm.creditorBankCode}</span></dd>
                    <dt>{t('Rozsah převodu', 'Transfer scope')}</dt><dd>{domesticForm.transferScope}</dd>
                    <dt>{t('Priorita', 'Priority')}</dt><dd>{domesticForm.priority}</dd>
                    <dt>{t('Variabilní symbol', 'Variable symbol')}</dt><dd>{domesticForm.variableSymbol || '—'}</dd>
                    <dt>{t('End-to-End ID', 'End-to-End ID')}</dt><dd className="mono">{domesticForm.endToEndId || '—'}</dd>
                    <dt>{t('Zpráva', 'Message')}</dt><dd>{domesticForm.messageForPayee || '—'}</dd>
                  </dl>
                ) : (
                  <dl style={{ display: 'grid', gridTemplateColumns: '155px minmax(0, 1fr)', gap: '9px 12px', padding: 14, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}>
                    <dt>{t('Typ', 'Type')}</dt><dd>{sepaForm.instant ? 'SEPA Instant (SCT Inst)' : 'SEPA Credit Transfer (SCT)'}</dd>
                    <dt>{t('Částka', 'Amount')}</dt><dd style={{ fontSize: 16, fontWeight: 750 }}>{formatAmount(Number(sepaForm.amount), 'EUR', language)}</dd>
                    <dt>{t('IBAN plátce', 'Debtor IBAN')}</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{sepaForm.debtorIban}</dd>
                    <dt>{t('Příjemce', 'Beneficiary')}</dt><dd>{sepaForm.creditorName}<br/><span className="mono" style={{ overflowWrap: 'anywhere' }}>{sepaForm.creditorIban}</span></dd>
                    <dt>BIC</dt><dd className="mono">{sepaForm.bic || t('Odvozen z IBAN', 'Derived from IBAN')}</dd>
                    <dt>{t('VoP výsledek', 'VoP result')}</dt><dd>{sepaForm.vopStatus === 'idle' ? t('Nebylo provedeno', 'Not performed') : sepaForm.vopStatus.toUpperCase()}{sepaForm.vopResult && sepaForm.vopResult !== sepaForm.vopStatus ? ` — ${sepaForm.vopResult}` : ''}</dd>
                    <dt>{t('End-to-End ID', 'End-to-End ID')}</dt><dd className="mono">{sepaForm.endToEndId || '—'}</dd>
                    <dt>{t('Zpráva', 'Remittance')}</dt><dd>{sepaForm.remittanceInfo || '—'}</dd>
                  </dl>
                )}
                {createError && <div role="alert" data-testid="payment-create-review-error" style={{ marginTop: 14, padding: 10, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 12 }}>{createError}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
                  <button ref={reviewBackRef} autoFocus type="button" className="btn btn-secondary" disabled={creating} onClick={returnToPaymentForm}>{t('Zpět k úpravám', 'Back to editing')}</button>
                  <button ref={reviewConfirmRef} type="button" className="btn btn-primary" aria-busy={creating} disabled={creating} onClick={() => void (paymentReview === 'domestic' ? handleDomesticCreate(undefined, true) : handleSepaCreate(undefined, true))}>
                    {creating ? t('Odesílám…', 'Submitting…') : t('Potvrdit a odeslat', 'Confirm and submit')}
                  </button>
                </div>
              </div>
            </div>
          )}

          {createSuccess && (
            <div className="card" style={{ padding: '16px', color: 'var(--green)', marginBottom: '16px', fontSize: '14px', fontWeight: 600 }}>
              {createSuccess}
            </div>
          )}

          {/* Toolbar */}
          <div style={{ display: 'flex', gap: '10px', marginBottom: '16px' }}>
            <div style={{ position: 'relative', flex: 1, maxWidth: '320px' }}>
              <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
              <label className="sr-only" htmlFor="payments-search">{t('Hledat platby', 'Search payments')}</label>
              <input id="payments-search" className="input" style={{ paddingLeft: '32px', width: '100%' }}
                placeholder={t('Hledat podle ID, IBAN, příjemce…', 'Search by ID, IBAN, creditor…')}
                value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <div style={{ display: 'flex', gap: '4px' }}>
              {(['ALL', 'SEPA', 'DOMESTIC'] as const).map(t => {
                const show = activeTab === 'all' || (activeTab === 'domestic' && t === 'DOMESTIC') || (activeTab === 'sepa' && t === 'SEPA')
                if (!show) return null
                return (
                  <button key={t} className={`btn ${typeFilter === t ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => setTypeFilter(t)} style={{ fontSize: '12px', padding: '6px 12px' }}>
                    {t}
                  </button>
                )
              })}
            </div>
          </div>

          {error && <div className="card" style={{ padding: '16px', color: 'var(--red)', marginBottom: '16px' }}>{error}</div>}

          {/* Payments table */}
          <div className="card" style={{ overflow: 'hidden' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t('ID', 'ID')}</th>
                  <th>{t('Typ', 'Type')}</th>
                  <th>{t('Stav', 'Status')}</th>
                  <th>{t('Částka', 'Amount')}</th>
                  <th>{t('Příjemce', 'Creditor')}</th>
                  <th>{t('IBAN / Účet příjemce', 'Creditor IBAN / Account')}</th>
                  <th>{t('Vytvořeno', 'Created')}</th>
                  <th aria-label={t('Detail', 'Detail')} style={{ width: '36px' }} />
                </tr>
              </thead>
              <tbody>
                {loading && Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i}>{Array.from({ length: 8 }).map((_, j) => <td key={j}><div className="skeleton" style={{ height: '14px', width: j === 0 ? '120px' : '80px' }} /></td>)}</tr>
                ))}
                {!loading && filtered.length === 0 && (
                  <tr><td colSpan={8} style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>
                    {t('Nebyly nalezeny žádné platby', 'No payments found')}
                  </td></tr>
                )}
                {!loading && filtered.map(p => (
                  <tr key={`${p.type}-${p.id}`} style={{ cursor: 'pointer' }}
                    title={t('Zobrazit detail platby', 'View payment detail')}
                    tabIndex={0}
                    aria-label={t(`Otevřít detail platby ${p.id.slice(0, 8)}`, `Open payment ${p.id.slice(0, 8)} detail`)}
                    onClick={() => { stashRow('payments', p.id, p); router.push(`/payments/${p.id}?type=${p.type}`) }}
                    onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); stashRow('payments', p.id, p); router.push(`/payments/${p.id}?type=${p.type}`) } }}>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{p.id.slice(0, 8)}…</td>
                    <td><span className="tag" style={{ color: p.type === 'SEPA' ? 'var(--accent)' : 'var(--green)' }}>{p.type}</span></td>
                    <td>
                      <StatusBadge status={p.status} />
                    </td>
                    <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 600 }}>{formatAmount(p.amount, p.currency, language)}</td>
                    <td style={{ fontSize: '13px' }}>{p.creditorName ?? '—'}</td>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>
                      {p.creditorIban ? p.creditorIban : (p.creditorAccountNumber && p.creditorBankCode ? `${p.creditorAccountNumber}/${p.creditorBankCode}` : '—')}
                    </td>
                    <td style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{new Date(p.createdAt).toLocaleDateString(numberLocale)}</td>
                    <td style={{ textAlign: 'right', paddingRight: '8px' }}><ChevronRight size={14} style={{ color: 'var(--text-muted)' }} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
