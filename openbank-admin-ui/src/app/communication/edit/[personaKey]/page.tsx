// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// Communication Studio — style editor (ADR-0285 D7, phase 2).
//
// Two independent sections, gated by two different permissions, because they are two different
// roles' work on the SAME draft (D6):
//  - Maker (ROLE_COMMS_EDITOR): draft -> submit for review.
//  - Checker (ROLE_COMMS_APPROVER): initiates the publish call once a version is IN_REVIEW.
//    That call is four-eyes-paused (commstyle.publish) — a DIFFERENT approver must then decide
//    the resulting PendingApproval on the queue (/approvals/communication), after which THIS same
//    caller retries with the returned approval id (handled inline below).
// The server independently refuses a draft's own maker as its publisher
// (CommunicationStyleService.publish) — this page does not attempt to replicate that check, only
// to surface whatever the server decides.

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, ListChecks, Save, Send, Trash2, UploadCloud } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { classifyBffFailure } from '@/lib/services/bff'
import { UiMessageEditor } from '@/components/communication/UiMessageEditor'
import { PageHeader } from '@/components/ui/PageHeader'

interface StyleVersionDraft {
  id: string
  version: number
  status: string
}

// ADR-0285 D4: a golden-set entry is a TEST INPUT (what a hypothetical customer might ask), not
// content the bot serves — so unlike the style draft above, nothing here goes through D3's lint
// and there is no four-eyes step. It is pure CRUD; no replay is wired yet (see the service's own
// KDoc), so creating an entry here is inert until a later, separately-reviewed change adds one.
interface GoldenSetEntry {
  id: string
  question: string
  expectedLanguage: string
  expectNoFigureFromMemory: boolean
  expectedToneMarkers: string[]
  requiredComplianceSentence: string | null
  createdBy: string
  createdAt: string
}

const PROXY_BASE = '/api/svc/communication-service/api/v1/personas'
const STYLE_WRITE_BASE = '/api/svc/communication-service/api/v2/personas'

export default function CommunicationStyleEditorPage() {
  const { t } = useLanguage()
  const params = useParams<{ personaKey: string }>()
  const personaKey = params?.personaKey ?? ''

  const [tone, setTone] = useState('')
  const [formality, setFormality] = useState('')
  const [formOfAddress, setFormOfAddress] = useState('')
  const [signature, setSignature] = useState('')
  const [maxLength, setMaxLength] = useState('')
  const [uiMessages, setUiMessages] = useState<Record<string, string>>({})
  const [preferredTerms, setPreferredTerms] = useState<Record<string, string>>({})
  const [forbiddenTerms, setForbiddenTerms] = useState<string[]>([])
  const [loadingPublished, setLoadingPublished] = useState(true)
  const [publishedLoaded, setPublishedLoaded] = useState(false)
  const [basePublishedVersion, setBasePublishedVersion] = useState<number | null>(null)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoadingPublished(true)
    setPublishedLoaded(false)
    setBasePublishedVersion(null)
    setTone(''); setFormality(''); setFormOfAddress(''); setSignature(''); setMaxLength('')
    setUiMessages({}); setPreferredTerms({}); setForbiddenTerms([])
    fetch(`${STYLE_WRITE_BASE}/${encodeURIComponent(personaKey)}/editor-state`, { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8_000)]) })
      .then(async res => {
        if (!res.ok) throw new Error('Editor state unavailable')
        const state = await res.json()
        if (controller.signal.aborted) return
        if (!Number.isInteger(state.basePublishedVersion) || state.basePublishedVersion < 0) throw new Error('Invalid published style generation')
        const published = state.published
        if (published && (!Number.isInteger(published.styleVersion) || published.styleVersion !== state.basePublishedVersion)) throw new Error('Inconsistent editor state')
        setBasePublishedVersion(state.basePublishedVersion)
        if (!published) { setPublishedLoaded(true); return }
        setTone(published.tone ?? '')
        setFormality(published.formality ?? '')
        setFormOfAddress(published.formOfAddress ?? '')
        setSignature(published.signature ?? '')
        setMaxLength(published.maxLength == null ? '' : String(published.maxLength))
        setUiMessages(published.uiMessages ?? {})
        setPreferredTerms(published.preferredTerms ?? {})
        setForbiddenTerms(published.forbiddenTerms ?? [])
        setPublishedLoaded(true)
      })
      .catch(() => { if (!controller.signal.aborted) setUnavailable({ kind: 'unreachable' }) })
      .finally(() => { if (!controller.signal.aborted) setLoadingPublished(false) })
    return () => controller.abort()
  }, [personaKey, loadAttempt])

  const [saving, setSaving] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lintViolations, setLintViolations] = useState<string[] | null>(null)
  const [draft, setDraft] = useState<StyleVersionDraft | null>(null)
  const [draftId, setDraftId] = useState('')

  // Checker's initiate-publish state — deliberately separate from the maker's `draft` above:
  // an approver deciding to publish typically was not the one who just drafted it in this
  // browser tab (that's the whole point), so they look it up by id.
  const [publishing, setPublishing] = useState(false)
  const [publishResult, setPublishResult] = useState<{ ok: boolean; text: string } | null>(null)
  const [pendingApprovalId, setPendingApprovalId] = useState<string | null>(null)

  useEffect(() => { setUnavailable(null) }, [personaKey])

  const saveDraft = useCallback(async () => {
    setSaving(true); setError(null); setLintViolations(null)
    try {
      const res = await fetch(`${STYLE_WRITE_BASE}/${encodeURIComponent(personaKey)}/style-versions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          tone,
          formality,
          formOfAddress,
          signature: signature || null,
          maxLength: maxLength ? Number(maxLength) : null,
          preferredTerms,
          forbiddenTerms,
          uiMessages,
          basePublishedVersion,
        }),
      })
      if (res.status === 400) {
        const body = await res.json().catch(() => null)
        if (Array.isArray(body?.violations) && body.violations.length > 0) {
          // D3's lint rejection: every violated rule, so the editor sees the whole
          // problem in one round trip instead of one rejection per save.
          setLintViolations(body.violations)
          return
        }
        setError(t('Formulář obsahuje neplatné hodnoty.', 'The form contains invalid values.'))
        return
      }
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const created: StyleVersionDraft = await res.json()
      setDraft(created)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally { setSaving(false) }
  }, [personaKey, tone, formality, formOfAddress, signature, maxLength, preferredTerms, forbiddenTerms, uiMessages, basePublishedVersion, t])

  const submitForReview = useCallback(async () => {
    if (!draft) return
    setSubmitting(true); setError(null)
    try {
      const res = await fetch(`${STYLE_WRITE_BASE}/style-versions/${encodeURIComponent(draft.id)}/submit`, {
        method: 'POST',
      })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const updated: StyleVersionDraft = await res.json()
      setDraft(updated)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally { setSubmitting(false) }
  }, [draft, t])

  const initiatePublish = useCallback(async (approvalId?: string) => {
    const id = (draftId || draft?.id || '').trim()
    if (!id) return
    setPublishing(true); setPublishResult(null)
    try {
      const headers: Record<string, string> = {}
      if (approvalId) headers['x-approval-id'] = approvalId
      const res = await fetch(`${STYLE_WRITE_BASE}/style-versions/${encodeURIComponent(id)}/publish`, {
        method: 'POST',
        headers,
      })
      if (res.status === 202) {
        const body = await res.json().catch(() => null)
        const newApprovalId: string | undefined = body?.id
        setPendingApprovalId(newApprovalId ?? null)
        setPublishResult({
          ok: true,
          text: t(
            'Publikace pozastavena — čeká na jiného schvalovatele na nástěnce.',
            'Publish paused — waiting for a different approver on the workbench.',
          ),
        })
        return
      }
      if (res.status === 409) {
        const body = await res.json().catch(() => null)
        const reason: string = typeof body?.error === 'string' ? body.error : ''
        const stale = reason.includes('published style changed') || reason.includes('no known published base')
        setPublishResult({
          ok: false,
          text: stale
            ? t('Koncept vychází ze starší verze. Načtěte aktuální text a připravte nový koncept.', 'This draft is based on an older version. Reload the current copy and prepare a new draft.')
            : t('Publikace byla odmítnuta. Ověřte stav verze a schvalovatele.', 'Publish was refused. Check the version status and approver.'),
        })
        return
      }
      if (!res.ok) {
        setPublishResult({
          ok: false,
          text: t(
            'Publikace se nezdařila. Ověřte stav verze a ID konceptu.',
            'Publish failed. Check the version status and draft id.',
          ),
        })
        return
      }
      setPendingApprovalId(null)
      setPublishResult({ ok: true, text: t('Publikováno.', 'Published.') })
    } catch {
      setPublishResult({ ok: false, text: t('Publikace se nezdařila.', 'Publish failed.') })
    } finally { setPublishing(false) }
  }, [draftId, draft, t])

  // Golden set (D4) — independent of the draft/publish state above: entries belong to the
  // persona, not to any one style/playbook version.
  const [goldenSet, setGoldenSet] = useState<GoldenSetEntry[] | null>(null)
  const [gsQuestion, setGsQuestion] = useState('')
  const [gsLanguage, setGsLanguage] = useState('cs')
  const [gsNoFigure, setGsNoFigure] = useState(true)
  const [gsToneMarkers, setGsToneMarkers] = useState('')
  const [gsCompliance, setGsCompliance] = useState('')
  const [gsSaving, setGsSaving] = useState(false)
  const [gsError, setGsError] = useState<string | null>(null)

  const loadGoldenSet = useCallback(async () => {
    try {
      const res = await fetch(`${PROXY_BASE}/${encodeURIComponent(personaKey)}/golden-set`, { cache: 'no-store' })
      if (!res.ok) { setGoldenSet([]); return }
      setGoldenSet(await res.json())
    } catch {
      setGoldenSet([])
    }
  }, [personaKey])

  useEffect(() => { void loadGoldenSet() }, [loadGoldenSet])

  const createGoldenSetEntry = useCallback(async () => {
    setGsSaving(true); setGsError(null)
    try {
      const res = await fetch(`${PROXY_BASE}/${encodeURIComponent(personaKey)}/golden-set`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          question: gsQuestion,
          expectedLanguage: gsLanguage,
          expectNoFigureFromMemory: gsNoFigure,
          expectedToneMarkers: gsToneMarkers.split(',').map(m => m.trim()).filter(Boolean),
          requiredComplianceSentence: gsCompliance || null,
        }),
      })
      if (!res.ok) {
        setGsError(t('Vytvoření se nezdařilo.', 'Create failed.'))
        return
      }
      setGsQuestion(''); setGsToneMarkers(''); setGsCompliance('')
      await loadGoldenSet()
    } catch {
      setGsError(t('Vytvoření se nezdařilo.', 'Create failed.'))
    } finally { setGsSaving(false) }
  }, [personaKey, gsQuestion, gsLanguage, gsNoFigure, gsToneMarkers, gsCompliance, loadGoldenSet, t])

  const deleteGoldenSetEntry = useCallback(async (id: string) => {
    try {
      await fetch(`/api/svc/communication-service/api/v1/personas/golden-set/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      })
      await loadGoldenSet()
    } catch {
      setGsError(t('Odstranění se nezdařilo.', 'Delete failed.'))
    }
  }, [loadGoldenSet, t])

  if (unavailable) {
    return (
      <div className="page">
        <PageHeader title={t('Editor stylu', 'Style editor')} />
        <DataUnavailable kind={unavailable.kind} service="Communication" feature={t('editor stylu', 'style editor')}>
          <button type="button" className="btn btn-secondary" onClick={() => { setUnavailable(null); if (!publishedLoaded) setLoadAttempt(v => v + 1) }}>
            {t('Zkusit znovu', 'Try again')}
          </button>
        </DataUnavailable>
      </div>
    )
  }

  return (
    <AuthGuard>
      <div className="page">
        <PageHeader
          title={t('Editor stylu', 'Style editor')}
          breadcrumb={(
            <div className="breadcrumb">
              <Link href={`/communication/${encodeURIComponent(personaKey)}`}>
                <ArrowLeft size={14} /> {personaKey}
              </Link>
            </div>
          )}
        />

        <Can permission="communication:style:propose">
          <div className="card" style={{ marginBottom: '16px', maxWidth: '640px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', margin: 0 }}>
              {t('Návrh (maker)', 'Draft (maker)')}
            </h2>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Tón', 'Tone')}
              <input className="input" value={tone} onChange={e => setTone(e.target.value)}
                placeholder={t('např. vřelý a uklidňující', 'e.g. warm and reassuring')} disabled={!!draft || loadingPublished} />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Formálnost', 'Formality')}
              <input className="input" value={formality} onChange={e => setFormality(e.target.value)}
                placeholder={t('např. neformální, tykání', 'e.g. informal, first-name basis')} disabled={!!draft || loadingPublished} />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Oslovení', 'Form of address')}
              <input className="input" value={formOfAddress} onChange={e => setFormOfAddress(e.target.value)}
                placeholder={t('např. tykání', 'e.g. informal "you"')} disabled={!!draft || loadingPublished} />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Podpis', 'Signature')}
              <input className="input" value={signature} onChange={e => setSignature(e.target.value)}
                placeholder={t('nepovinné', 'optional')} disabled={!!draft || loadingPublished} />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Maximální délka (znaky)', 'Max length (characters)')}
              <input className="input" type="number" min={1} value={maxLength}
                onChange={e => setMaxLength(e.target.value)} placeholder={t('nepovinné', 'optional')} disabled={!!draft || loadingPublished} />
            </label>

            {personaKey === 'customer-copilot' && (
              <UiMessageEditor value={uiMessages} onChange={setUiMessages} disabled={!!draft || loadingPublished} />
            )}

            {lintViolations && lintViolations.length > 0 && (
              <div className="badge badge-danger" style={{ display: 'block', whiteSpace: 'pre-wrap' }}>
                {t('Text neprošel kontrolou stylu:', 'Text rejected by the style lint:')}
                <ul style={{ margin: '6px 0 0 18px' }}>
                  {lintViolations.map(v => <li key={v}>{v}</li>)}
                </ul>
              </div>
            )}
            {error && <div className="badge badge-danger" style={{ display: 'block' }}>{error}</div>}

            <div style={{ display: 'flex', gap: '8px' }}>
              {!draft && (
                <button type="button" className="btn btn-primary" onClick={saveDraft} disabled={saving || !publishedLoaded || basePublishedVersion === null || !tone || !formality || !formOfAddress}>
                  <Save size={14} /> {saving ? t('Ukládám…', 'Saving…') : t('Uložit koncept', 'Save draft')}
                </button>
              )}
              {draft && draft.status === 'DRAFT' && (
                <button type="button" className="btn btn-primary" onClick={submitForReview} disabled={submitting}>
                  <Send size={14} /> {submitting ? t('Odesílám…', 'Submitting…') : t('Odeslat ke schválení', 'Submit for review')}
                </button>
              )}
            </div>

            {draft && (
              <p style={{ margin: 0, fontSize: '13px', color: 'var(--text-secondary)' }}>
                {t('Verze', 'Version')} {draft.version} — <strong>{draft.status}</strong>
                {draft.status === 'IN_REVIEW' && (
                  <> — {t('ID konceptu pro schvalovatele:', 'draft id for an approver:')} <span className="mono">{draft.id}</span></>
                )}
              </p>
            )}
          </div>
        </Can>

        <Can permission="communication:style:decide">
          <div className="card" style={{ maxWidth: '640px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', margin: 0 }}>
              <UploadCloud size={14} /> {t('Publikace (checker)', 'Publish (checker)')}
            </h2>
            <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)' }}>
              {t(
                'Zahájí publikaci verze IN_REVIEW. Zablokuje se čtyřma očima, dokud ji na nástěnce neschválí jiný schvalovatel — pak zopakujte publikaci.',
                'Initiates publish for an IN_REVIEW version. Pauses on four-eyes until a different approver decides it on the workbench — then retry publish here.',
              )}
            </p>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('ID konceptu', 'Draft id')}
              <input
                className="input"
                style={{ fontFamily: 'var(--font-mono)' }}
                value={draftId || draft?.id || ''}
                onChange={e => setDraftId(e.target.value)}
                placeholder={t('vloženo z návrhu výše, nebo vlastní', 'filled from the draft above, or paste one')}
              />
            </label>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button type="button" className="btn btn-primary" onClick={() => initiatePublish()} disabled={publishing || !(draftId || draft?.id)}>
                <UploadCloud size={14} /> {publishing ? t('Publikuji…', 'Publishing…') : t('Publikovat', 'Publish')}
              </button>
              {pendingApprovalId && (
                <button type="button" className="btn btn-secondary" onClick={() => initiatePublish(pendingApprovalId)} disabled={publishing}>
                  {t('Zopakovat s ID schválení', 'Retry with approval id')}
                </button>
              )}
            </div>
            {publishResult && (
              <p style={{ margin: 0, fontSize: '13px', color: publishResult.ok ? 'var(--green)' : 'var(--red)' }}>{publishResult.text}</p>
            )}
          </div>
        </Can>

        <Can permission="communication:golden-set:manage">
          <div className="card" style={{ marginTop: '16px', maxWidth: '640px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <h2 style={{ fontSize: '13px', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-secondary)', margin: 0 }}>
              <ListChecks size={14} /> {t('Zlatá sada (D4)', 'Golden set (D4)')}
            </h2>
            <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)' }}>
              {t(
                'Testovací vstupy pro budoucí přehrávání při publikaci — zatím se nikde nepoužívají.',
                'Test inputs for a future publish-time replay — not consumed anywhere yet.',
              )}
            </p>

            {goldenSet && goldenSet.length > 0 && (
              <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {goldenSet.map(entry => (
                  <li key={entry.id} style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
                    gap: '8px', padding: '8px', border: '1px solid var(--border)', borderRadius: '8px',
                  }}
                  >
                    <div>
                      <p style={{ margin: 0, fontSize: '13px' }}>{entry.question}</p>
                      <p style={{ margin: '2px 0 0', fontSize: '11px', color: 'var(--text-muted)' }}>
                        {entry.expectedLanguage}
                        {entry.expectNoFigureFromMemory && ` · ${t('bez čísel z paměti', 'no figure from memory')}`}
                        {entry.expectedToneMarkers.length > 0 && ` · ${entry.expectedToneMarkers.join(', ')}`}
                      </p>
                    </div>
                    <button type="button" className="btn btn-secondary" onClick={() => void deleteGoldenSetEntry(entry.id)} aria-label={t('Smazat', 'Delete')}>
                      <Trash2 size={14} />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Otázka', 'Question')}
              <input className="input" value={gsQuestion} onChange={e => setGsQuestion(e.target.value)}
                placeholder={t('co by se mohl zákazník zeptat', 'what a customer might ask')} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Očekávaný jazyk', 'Expected language')}
              <input className="input" value={gsLanguage} onChange={e => setGsLanguage(e.target.value)} placeholder="cs" />
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-muted)' }}>
              <input type="checkbox" checked={gsNoFigure} onChange={e => setGsNoFigure(e.target.checked)} />
              {t('Bez čísla z paměti', 'No figure from memory')}
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Tónové značky (oddělené čárkou)', 'Tone markers (comma-separated)')}
              <input className="input" value={gsToneMarkers} onChange={e => setGsToneMarkers(e.target.value)}
                placeholder={t('stručný, formální oslovení', 'brief, formal address')} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '12px', color: 'var(--text-muted)' }}>
              {t('Povinná věta (nepovinné)', 'Required compliance sentence (optional)')}
              <input className="input" value={gsCompliance} onChange={e => setGsCompliance(e.target.value)} />
            </label>
            {gsError && <div className="badge badge-danger" style={{ display: 'block' }}>{gsError}</div>}
            <div>
              <button type="button" className="btn btn-primary" onClick={() => void createGoldenSetEntry()} disabled={gsSaving || !gsQuestion.trim()}>
                <Save size={14} /> {gsSaving ? t('Ukládám…', 'Saving…') : t('Přidat', 'Add')}
              </button>
            </div>
          </div>
        </Can>
      </div>
    </AuthGuard>
  )
}
