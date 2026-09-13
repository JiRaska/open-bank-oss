// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { LoadingState, PageHeader } from '@/components/ui'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { Building2, RefreshCw, ShieldAlert, AlertTriangle, Check, Search, History } from 'lucide-react'

const SVC = 'kyb-service'

/** One page of the review queue. Asked for explicitly; the service's own default is 20. */
const QUEUE_PAGE = 200

// ── Types (mirror kyb openapi Case / RepresentationDecision / Attestation) ──────

interface Representative { fullName: string; role: string | null; body: string | null }

interface Extract {
  legalName: string
  identifier: string
  scheme: string
  representatives: Representative[]
  representationRule: {
    mode: string
    requiredSigners: number | null
    sourceText: string | null
    requiredRoles?: string[]
  }
}

interface Case {
  id: string
  status: string
  scheme: string
  identifier: string
  requiredSignatures: number | null
  requiredSignerRoles: string[]
  signedCount: number
  extract: Extract | null
  reviewReason: string | null
  createdAt: string
  updatedAt: string
}

interface Attestation {
  id: string
  scheme: string
  identifier: string
  ruleTextHash: string
  ruleText: string | null
  parsedMode: string
  parsedSigners: number | null
  confirmedSigners: number
  confirmedRoles: string[]
  attestedBy: string
  attestedAt: string
  supersededAt: string | null
  note: string | null
}

type DecisionState = 'ATTESTED' | 'UNATTESTED' | 'SUPERSEDED'

interface Decision {
  state: DecisionState
  ruleText: string | null
  ruleTextHash: string
  parserSuggestsSigners: number | null
  parserSuggestsRoles: string[]
  parserMode: string
  attestation: Attestation | null
  previous: Attestation | null
}

const STATE_COLOR: Record<DecisionState, string> = {
  ATTESTED: '#16a34a',
  UNATTESTED: '#d97706',
  SUPERSEDED: '#dc2626',
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

// ── Confirmation form for one entity ────────────────────────────────────────────

function AttestationForm({
  scheme,
  identifier,
  decision,
  onAttested,
}: {
  scheme: string
  identifier: string
  decision: Decision
  onAttested: () => void
}) {
  const { t } = useLanguage()
  // Seeded from the STANDING confirmation when the entity has one, and only otherwise from the
  // parser's suggestion.
  //
  // This distinction is the whole point. For an ATTESTED decision the server sends the PARSER's
  // numbers in `parserSuggests*` (`parsedSigners`, and `parserSuggestsRoles` is always empty
  // there) — so seeding from those on an entity a human confirmed as "2 signatures, předseda +
  // člen" would open the re-confirm form pre-filled with the parser's "1" and no offices. One
  // click on a button labelled "Re-confirm" would then supersede a role-constrained joint rule
  // with a bare count of one, silently, which is precisely the mistake human attestation exists
  // to prevent.
  const standing = decision.attestation
  const [signers, setSigners] = useState<string>(
    String(standing?.confirmedSigners ?? decision.parserSuggestsSigners ?? 1),
  )
  const [roles, setRoles] = useState<string>(
    (standing?.confirmedRoles ?? decision.parserSuggestsRoles).join(', '),
  )
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const flight = useSingleFlight()

  const submit = useCallback(async () => {
    let succeeded = false
    const outcome = await flight.run(`kyb-attest:${scheme}:${identifier}`, async () => {
      setBusy(true)
      setError(null)
      try {
        const parsed = Number(signers)
        if (!Number.isInteger(parsed) || parsed < 1) {
          setError(t('Počet podpisů musí být celé číslo alespoň 1.', 'The signature count must be a whole number of at least 1.'))
          return
        }
        const res = await fetch(svcUrl(SVC, `/api/v1/kyb/representation/${scheme}/${identifier}`), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            confirmedSigners: parsed,
            confirmedRoles: roles.split(',').map(r => r.trim()).filter(Boolean),
            // The hash of the text THIS form rendered. If the register moved since, the service
            // answers 409 rather than recording a confirmation of a rule nobody read.
            ruleTextHash: decision.ruleTextHash,
            note: note || null,
          }),
          signal: AbortSignal.timeout(8000),
        })
        if (!res.ok) {
          setError(
            res.status === 409
              ? t(
                  'Text pravidla se v rejstříku změnil od otevření formuláře. Načítám nové znění — přečtěte si ho a potvrďte znovu.',
                  'The register text changed since this form was opened. Loading the new wording — read it and confirm again.',
                )
              : t('Potvrzení se nezdařilo.', 'Confirmation failed.'),
          )
          // Without this the panel keeps the stale decision: `load` is keyed on scheme+identifier,
          // so nothing re-runs on its own and every further click re-posts a hash that can never
          // match again. Telling the operator to "reload" while offering no way to is a dead end.
          if (res.status === 409) onAttested()
          return
        }
        succeeded = true
        onAttested()
      } catch {
        setError(t('Služba je nedostupná.', 'Service unavailable.'))
      } finally {
        setBusy(false)
      }
    })
    if (wasSkipped(outcome)) return false
    return succeeded
  }, [flight, scheme, identifier, signers, roles, note, decision.ruleTextHash, onAttested, t])

  return (
    <div style={{ marginTop: 12, display: 'grid', gap: 10 }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 10 }}>
        <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
          {t('Počet podpisů', 'Signature count')}
          <input
            className="input"
            type="number"
            min={1}
            value={signers}
            onChange={e => setSigners(e.target.value)}
            aria-label={t('Počet vyžadovaných podpisů', 'Required signature count')}
          />
        </label>
        <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
          {t('Funkce (oddělené čárkou, prázdné = jakýkoli zástupce)', 'Offices (comma-separated, empty = any representative)')}
          <input
            className="input"
            value={roles}
            onChange={e => setRoles(e.target.value)}
            placeholder="predseda, clen"
            autoComplete="off"
            spellCheck={false}
            aria-label={t('Funkce, které musí podepsat', 'Offices that must sign')}
          />
        </label>
      </div>
      <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)' }}>
        {t('Poznámka (na co jste se dívali)', 'Note (what you looked at)')}
        <input
          className="input"
          value={note}
          onChange={e => setNote(e.target.value)}
          placeholder={t('např. zápis z valné hromady', 'e.g. board minutes')}
          aria-label={t('Poznámka k potvrzení', 'Confirmation note')}
        />
      </label>
      {roles.trim() !== '' && (
        <div style={{ fontSize: 12, color: 'var(--text-secondary)', display: 'flex', gap: 6, alignItems: 'flex-start' }}>
          <AlertTriangle size={14} aria-hidden="true" style={{ color: '#d97706', flexShrink: 0, marginTop: 1 }} />
          <span>
            {t(
              'Pravidlo vázané na funkce jde vždy na ruční kontrolu podepisujících — počet sám nestačí, každou funkci musí pokrýt jiná osoba.',
              'A role-constrained rule always routes the signatories to manual review — the count alone is not enough, and each office must be filled by a different person.',
            )}
          </span>
        </div>
      )}
      {error && <div role="alert" style={{ fontSize: 12, color: 'var(--danger)' }}>{error}</div>}
      <div>
        <button
          type="button"
          className="btn btn-primary"
          onClick={submit}
          disabled={busy}
          aria-busy={busy}
          aria-label={t('Potvrdit způsob zastoupení', 'Confirm the representation rule')}
          style={{ fontSize: 13 }}
        >
          <Check size={14} aria-hidden="true" style={{ marginRight: 4 }} />
          {decision.state === 'ATTESTED'
            ? t('Přepotvrdit', 'Re-confirm')
            : t('Potvrdit zastoupení', 'Confirm representation')}
        </button>
      </div>
    </div>
  )
}

// ── One entity's representation panel ───────────────────────────────────────────

function RepresentationPanel({ scheme, identifier }: { scheme: string; identifier: string }) {
  const { t, language } = useLanguage()
  const [decision, setDecision] = useState<Decision | null>(null)
  const [history, setHistory] = useState<Attestation[] | null>(null)
  const [historyFailed, setHistoryFailed] = useState(false)
  const [unavail, setUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const generation = useRef(0)

  const load = useCallback(async () => {
    const mine = ++generation.current
    setLoading(true)
    setUnavail(null)
    try {
      const res = await fetch(svcUrl(SVC, `/api/v1/kyb/representation/${scheme}/${identifier}`), {
        signal: AbortSignal.timeout(6000),
      })
      if (mine !== generation.current) return
      if (!res.ok) {
        setUnavail(await classifyBffFailure(res))
        setDecision(null)
        return
      }
      const next = await res.json()
      if (mine !== generation.current) return
      setDecision(next)
    } catch {
      if (mine !== generation.current) return
      setUnavail('unreachable')
      setDecision(null)
    } finally {
      if (mine === generation.current) setLoading(false)
    }
  }, [scheme, identifier])

  useEffect(() => {
    load()
    return () => { generation.current += 1 }
  }, [load])

  const loadHistory = useCallback(async () => {
    try {
      const res = await fetch(svcUrl(SVC, `/api/v1/kyb/representation/${scheme}/${identifier}/history`), {
        signal: AbortSignal.timeout(6000),
      })
      // An empty list is a real answer ("never confirmed"); a failed fetch is not. Without this
      // branch a 403 or a 500 leaves `history` null and the button simply appears inert.
      setHistory(res.ok ? await res.json() : [])
      setHistoryFailed(!res.ok)
    } catch {
      setHistory([])
      setHistoryFailed(true)
    }
  }, [scheme, identifier])

  if (loading && !decision) {
    return <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Načítám zastoupení…', 'Loading representation…')}</div>
  }
  if (unavail) {
    return <DataUnavailable kind={unavail} service={SVC} feature={t('způsob zastoupení', 'representation rule')} lang={language} />
  }
  if (!decision) return null

  const stateLabel: Record<DecisionState, string> = {
    ATTESTED: t('Potvrzeno člověkem', 'Confirmed by a human'),
    UNATTESTED: t('Čeká na potvrzení', 'Awaiting confirmation'),
    SUPERSEDED: t('Pravidlo se ZMĚNILO', 'The rule has CHANGED'),
  }

  return (
    <section aria-label={t('Způsob zastoupení', 'Representation rule')} style={{ marginTop: 14, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        <span
          style={{
            fontSize: 11,
            fontWeight: 700,
            padding: '2px 8px',
            borderRadius: 20,
            background: `${STATE_COLOR[decision.state]}15`,
            color: STATE_COLOR[decision.state],
            border: `1px solid ${STATE_COLOR[decision.state]}30`,
          }}
        >
          {stateLabel[decision.state]}
        </span>
        {decision.state === 'ATTESTED' && decision.attestation && (
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            {decision.attestation.confirmedSigners}× {t('podpis', 'signature')}
            {decision.attestation.confirmedRoles.length > 0 && ` · ${decision.attestation.confirmedRoles.join(', ')}`}
            {' · '}{decision.attestation.attestedBy}
          </span>
        )}
      </div>

      {decision.state === 'SUPERSEDED' && decision.previous && (
        <div role="alert" style={{ fontSize: 12, padding: 10, borderRadius: 6, background: 'var(--danger-bg, #fee2e2)', color: 'var(--danger, #991b1b)', marginBottom: 8 }}>
          {t(
            'Dřívější potvrzení platilo pro JINÉ znění a nesmí se použít.',
            'The earlier confirmation was about a DIFFERENT wording and must not be reused.',
          )}{' '}
          {decision.previous.attestedBy} · {decision.previous.confirmedSigners}× {t('podpis', 'signature')}
          {decision.previous.confirmedRoles.length > 0 && ` (${decision.previous.confirmedRoles.join(', ')})`}
          {decision.previous.ruleText && (
            <div style={{ marginTop: 6, fontStyle: 'italic' }}>
              {t('Tehdy:', 'Then:')} “{decision.previous.ruleText}”
            </div>
          )}
        </div>
      )}

      <div style={{ fontSize: 13, marginBottom: 6 }}>
        <div style={{ color: 'var(--text-secondary)', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {t('Znění v rejstříku', 'Register wording')}
        </div>
        <div className="mono" style={{ fontSize: 12, marginTop: 3, whiteSpace: 'pre-wrap' }}>
          {decision.ruleText ?? t('(rejstřík žádné znění nezveřejňuje)', '(the register publishes no wording)')}
        </div>
      </div>

      <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 6 }}>
        {t('Návrh parseru', 'Parser suggestion')}: {decision.parserMode}
        {decision.parserSuggestsSigners !== null && ` / ${decision.parserSuggestsSigners}×`}
        {decision.parserSuggestsRoles.length > 0 && ` / ${decision.parserSuggestsRoles.join(', ')}`}
        {' — '}
        <em>{t('pouze návrh; závazné je vaše potvrzení.', 'a suggestion only; your confirmation is what binds.')}</em>
      </div>

      <Can
        permission="business-onboarding:attest"
        fallback={<div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Potvrdit zastoupení může pouze oprávněný operátor.', 'Only an authorized operator can confirm the representation.')}</div>}
      >
        {/*
          Keyed on the rule text, deliberately. The form seeds its fields ONCE (useState
          initialisers) while `ruleTextHash` is read from the prop at submit time — so without a
          key, a refresh that brings back a different wording leaves the operator's typed numbers
          beside a hash of text they have not read, and the 409 guard cannot fire because that
          hash is fresh. Re-keying remounts the form whenever the text changes, which is the only
          thing that keeps the two coupled.
        */}
        <AttestationForm
          key={decision.ruleTextHash}
          scheme={scheme}
          identifier={identifier}
          decision={decision}
          onAttested={load}
        />
      </Can>

      <div style={{ marginTop: 10 }}>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={loadHistory}
          aria-label={t('Zobrazit historii potvrzení', 'Show the confirmation history')}
          style={{ fontSize: 12 }}
        >
          <History size={13} aria-hidden="true" style={{ marginRight: 4 }} />
          {t('Historie potvrzení', 'Confirmation history')}
        </button>
        {history && (
          <ul style={{ marginTop: 8, fontSize: 12, listStyle: 'none', padding: 0, display: 'grid', gap: 5 }}>
            {history.length === 0 && (
              <li role={historyFailed ? 'alert' : undefined} style={{ color: 'var(--text-secondary)' }}>
                {historyFailed
                  ? t('Historii se nepodařilo načíst.', 'The history could not be loaded.')
                  : t('Zatím žádné potvrzení.', 'No confirmation yet.')}
              </li>
            )}
            {history.map(a => (
              <li key={a.id} style={{ color: a.supersededAt ? 'var(--text-tertiary)' : 'var(--text-primary)' }}>
                <span className="mono">{a.attestedAt.slice(0, 16).replace('T', ' ')}</span>
                {' · '}{a.attestedBy}{' · '}{a.confirmedSigners}×
                {a.confirmedRoles.length > 0 && ` (${a.confirmedRoles.join(', ')})`}
                {a.supersededAt && ` · ${t('nahrazeno', 'superseded')}`}
                {a.note && ` · ${a.note}`}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

// ── Page ────────────────────────────────────────────────────────────────────────

export default function BusinessOnboardingPage() {
  const { t, language } = useLanguage()
  const [cases, setCases] = useState<Case[]>([])
  const [unavail, setUnavail] = useState<UnavailableKind | null>(null)
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const loadGeneration = useRef(0)

  const load = useCallback(async () => {
    const generation = ++loadGeneration.current
    setLoading(true)
    setUnavail(null)
    try {
      // No status parameter means the operator review queue (MANUAL_REVIEW) — kyb-service's own
      // default for this route. `size` is explicit because the service defaults to 20, and a
      // silently truncated array made the count label below assert "20 cases awaiting review" for
      // a queue of any size, while the search box filtered only that first page.
      const res = await fetch(
        svcUrl(SVC, '/api/v1/kyb/cases', { page: '0', size: String(QUEUE_PAGE) }),
        { signal: AbortSignal.timeout(6000) },
      )
      if (generation !== loadGeneration.current) return
      if (!res.ok) {
        const failure = await classifyBffFailure(res)
        if (generation !== loadGeneration.current) return
        setUnavail(failure)
        setCases([])
        return
      }
      const next = await res.json()
      if (generation !== loadGeneration.current) return
      setCases(next)
    } catch {
      if (generation !== loadGeneration.current) return
      setUnavail('unreachable')
      setCases([])
    } finally {
      if (generation === loadGeneration.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
    return () => { loadGeneration.current += 1 }
  }, [load])

  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase(language === 'cs' ? 'cs-CZ' : 'en-US')
    return cases
      .filter(c => {
        if (!needle) return true
        return [c.identifier, c.id, c.extract?.legalName ?? '']
          .some(v => v.toLocaleLowerCase(language === 'cs' ? 'cs-CZ' : 'en-US').includes(needle))
      })
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
  }, [cases, language, query])

  const queueLabel = (count: number) => language === 'cs'
    ? `${count} ${count === 1 ? 'případ' : count >= 2 && count <= 4 ? 'případy' : 'případů'} ke kontrole`
    : `${count} ${count === 1 ? 'case' : 'cases'} awaiting review`

  return <AuthGuard permission="business-onboarding:view">
    <div>
      <PageHeader
        icon={<Building2 size={20} aria-hidden="true" />}
        title={t('Firemní onboarding — způsob zastoupení', 'Business Onboarding — Representation')}
        subtitle={t(
          'Kolik podpisů a od koho firemní smlouva vyžaduje. Text z rejstříku čte heuristika, ale závazné je vždy potvrzení člověka, jednou pro každé IČO a znění pravidla (ADR-0284).',
          'How many signatures a business agreement needs, and from whom. A heuristic reads the register text, but a human confirmation is what binds — once per identifier and rule wording (ADR-0284).',
        )}
        actions={<button type="button" aria-busy={loading} aria-label={t('Obnovit frontu firemních případů', 'Refresh the business case queue')} className="btn btn-secondary" onClick={load} disabled={loading} style={{ fontSize: '13px' }}>
          <RefreshCw size={14} aria-hidden="true" style={{ marginRight: '4px' }} />{t('Obnovit', 'Refresh')}
        </button>}
      />
      {loading && cases.length === 0 ? (
        <LoadingState
          label={t('Načítám frontu…', 'Loading the queue…')}
          description={t('Hledám případy, které čekají na posouzení zastoupení.', 'Looking for cases awaiting a representation review.')}
        />
      ) : unavail ? (
        <DataUnavailable kind={unavail} service={SVC} feature={t('firemní onboarding', 'business onboarding')} lang={language} />
      ) : cases.length === 0 ? (
        <div className="card" style={{ padding: '40px', textAlign: 'center' }}>
          <ShieldAlert size={28} style={{ color: 'var(--success)', marginBottom: '10px' }} />
          <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>
            {t('Žádný případ ke kontrole', 'No case awaiting review')}
          </div>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            {t('Každá firma ve frontě už má potvrzené zastoupení.', 'Every company in the queue has a confirmed representation.')}
          </div>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <section className="card" aria-label={t('Filtr fronty', 'Queue filter')} style={{ padding: 14 }}>
            <div aria-live="polite" style={{ fontSize: 12, marginBottom: 10 }}>
              <strong>{queueLabel(cases.length)}</strong>
              {cases.length >= QUEUE_PAGE && (
                <span style={{ color: 'var(--text-secondary)' }}>
                  {' · '}
                  {t(
                    'zobrazena první stránka — fronta může být delší',
                    'first page shown — the queue may be longer',
                  )}
                </span>
              )}
            </div>
            <label style={{ display: 'grid', gap: 5, fontSize: 12, color: 'var(--text-secondary)', maxWidth: 340 }}>
              {t('Hledat firmu nebo případ', 'Find a company or case')}
              <span style={{ position: 'relative' }}>
                <Search size={14} aria-hidden="true" style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-tertiary)' }} />
                <input className="input" value={query} onChange={e => setQuery(e.target.value)} placeholder={t('Název, IČO nebo ID případu', 'Name, identifier, or case ID')} autoComplete="off" spellCheck={false} style={{ width: '100%', paddingLeft: 30 }} />
              </span>
            </label>
          </section>
          {visible.length === 0 && (
            <div className="card" role="status" style={{ padding: 28, textAlign: 'center' }}>
              <strong>{t('Filtru neodpovídá žádný případ', 'No case matches this filter')}</strong>
            </div>
          )}
          {visible.map(c => (
            <div key={c.id} className="card" style={{ padding: '18px' }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
                <strong style={{ fontSize: 15 }}>{c.extract?.legalName ?? t('(neznámý název)', '(unknown name)')}</strong>
                <span className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                  {c.scheme} {c.identifier}
                </span>
                <span className="mono" style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
                  {t('případ', 'case')} {shortId(c.id)}
                </span>
              </div>
              {c.reviewReason && (
                <div style={{ marginTop: 6, fontSize: 12, color: 'var(--text-secondary)' }}>{c.reviewReason}</div>
              )}
              {c.extract && c.extract.representatives.length > 0 && (
                <div style={{ marginTop: 8, fontSize: 12 }}>
                  <span style={{ color: 'var(--text-secondary)' }}>{t('Zapsaní zástupci', 'Listed representatives')}: </span>
                  {c.extract.representatives.map(r => `${r.fullName}${r.role ? ` (${r.role})` : ''}`).join(' · ')}
                </div>
              )}
              <RepresentationPanel scheme={c.scheme} identifier={c.identifier} />
            </div>
          ))}
        </div>
      )}
    </div>
  </AuthGuard>
}
