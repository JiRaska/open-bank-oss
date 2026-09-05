// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useSession } from 'next-auth/react'
import {
  FileSignature, Plus, Search, RefreshCw, Edit, Eye, X, Send, Archive,
  FileText, Hash, Tag, Download, FileCode2, Info, ExternalLink,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasPermission } from '@/lib/auth/roles'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { looksLikeUuid } from '@/lib/validation/iban'
import { PageHeader, StatusBadge, type Tone } from '@/components/ui'

// Go through the BFF proxy directly (svcUrl → /api/svc/document-service/...), the
// same pattern product-catalog/standing-orders/kyc now use — NOT a dedicated
// /api/document-templates/* route handler. Reason: the universal proxy
// (src/app/api/svc/[service]/[...path]/route.ts) already relays the operator's
// Keycloak bearer, detects KEDA scale-to-zero (ADR-0057) and emits the stable
// `{error}` JSON shapes classifyBffFailure() depends on — a hand-rolled proxy
// route would have to reimplement all three or silently regress the
// graceful-state rule. See product-catalog/page.tsx's own comment on this.
const SERVICE = 'document-service'
const TEMPLATES_PATH = '/api/v1/documents/templates'
const PREVIEW_PATH = `${TEMPLATES_PATH}/preview`
const DOCUMENTS_PATH = '/api/v1/documents'

const PAGE_SIZE = 25
const CONTENT_TABS = ['templates', 'documents'] as const

type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED'

interface DocumentTemplate {
  id: string
  code: string
  version: string
  name: string
  engine?: string
  bodyHtml: string
  locale?: string
  productRef?: string | null
  classification?: string
  status?: TemplateStatus
  createdAt?: string
  updatedAt?: string
}

interface DocumentMeta {
  id: string
  templateCode?: string
  templateVersion?: string
  contentType?: string
  partyRef?: string | null
  caseRef?: string | null
  productRef?: string | null
  status?: string
  retainUntil?: string | null
  createdAt?: string
}

class ApiError extends Error {
  kind: BffFailure
  constructor(kind: BffFailure, message: string) {
    super(message)
    this.kind = kind
  }
}

async function apiFetch(path: string, opts?: RequestInit) {
  const res = await fetch(svcUrl(SERVICE, path), {
    cache: 'no-store', signal: AbortSignal.timeout(8000), ...opts,
  })
  if (!res.ok) {
    const kind = await classifyBffFailure(res.clone())
    const text = await res.text().catch(() => '')
    let msg: string
    try { const parsed = JSON.parse(text); msg = parsed?.message ?? parsed?.error ?? text } catch { msg = text || res.statusText }
    throw new ApiError(kind, msg || res.statusText)
  }
  // 200/201 with an empty body (some publish/retire endpoints answer 200 with
  // no JSON payload) — don't blow up trying to parse it.
  const text = await res.text()
  if (!text) return null
  try { return JSON.parse(text) } catch { return null }
}

const TEMPLATE_STATUS_TONE: Record<TemplateStatus, Tone> = {
  DRAFT: 'warning',
  PUBLISHED: 'success',
  RETIRED: 'neutral',
}

// Generic merge-field tokens offered as clickable chips. These are a UX aid for
// authoring only — the backend's Handlebars engine (openapi CreateTemplateRequest
// .engine=HANDLEBARS) resolves whatever tokens the rendered data map actually
// supplies; this list is not validated against a live schema.
const MERGE_FIELDS = [
  'party.name', 'party.address', 'party.email',
  'product.name', 'product.code',
  'account.iban', 'document.date', 'document.caseRef',
  'signature.block',
]

function wrapPreviewHtml(innerHtml: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    body{font-family:system-ui,-apple-system,sans-serif;padding:20px;color:#0f172a;background:#fff;font-size:13px;line-height:1.6;margin:0}
    img{max-width:100%}
  </style></head><body>${innerHtml}</body></html>`
}

// Fallback-only preview: highlights {{token}} placeholders without merging any
// real data. Used before the first successful dynamic-preview call and if a
// POST to PREVIEW_PATH fails (see runPreview()) — never the primary path once
// a merged preview has rendered at least once.
function buildHighlightedPreviewHtml(bodyHtml: string): string {
  const highlighted = (bodyHtml || '').replace(
    /\{\{\s*([\w.]+)\s*\}\}/g,
    (_m, token) => `<mark style="background:#fde68a;color:#78350f;padding:0 3px;border-radius:2px;font-family:monospace;font-size:0.9em;">{{${token}}}</mark>`,
  )
  return wrapPreviewHtml(highlighted)
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// Small hand-rolled syntax highlighter for the body editor — not a full HTML
// parser, just enough to make tag names / attributes / {{merge tokens}}
// visually distinct for a non-technical author. Every literal text fragment
// goes through escapeHtml(); only the <span> wrappers this function adds are
// real markup, so the result is safe to render via dangerouslySetInnerHTML
// (this is purely a *read-only, aria-hidden* display layer behind the actual
// textarea — see the overlay technique below).
const TAG_OR_TOKEN = /(\{\{[^}]*\}\})|(<\/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[a-zA-Z_-][a-zA-Z0-9_-]*(?:=(?:"[^"]*"|'[^']*'))?)*)(\s*\/?>)/g
const ATTR = /([a-zA-Z_-][a-zA-Z0-9_-]*)(=)("[^"]*"|'[^']*')?/g

function highlightHtmlSource(source: string): string {
  let out = ''
  let lastIndex = 0
  TAG_OR_TOKEN.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = TAG_OR_TOKEN.exec(source)) !== null) {
    out += escapeHtml(source.slice(lastIndex, m.index))
    if (m[1]) {
      out += `<span style="color:var(--warning-text);font-weight:600;">${escapeHtml(m[1])}</span>`
    } else {
      const [, , open, name, attrsRaw, close] = m
      out += escapeHtml(open ?? '')
      out += `<span style="color:var(--accent);font-weight:600;">${escapeHtml(name ?? '')}</span>`
      let attrOut = ''
      let attrLast = 0
      ATTR.lastIndex = 0
      let am: RegExpExecArray | null
      const attrs = attrsRaw ?? ''
      while ((am = ATTR.exec(attrs)) !== null) {
        attrOut += escapeHtml(attrs.slice(attrLast, am.index))
        attrOut += `<span style="color:var(--text-secondary);">${escapeHtml(am[1])}</span>`
        if (am[2]) attrOut += escapeHtml(am[2])
        if (am[3]) attrOut += `<span style="color:var(--success-text);">${escapeHtml(am[3])}</span>`
        attrLast = ATTR.lastIndex
      }
      attrOut += escapeHtml(attrs.slice(attrLast))
      out += attrOut
      out += escapeHtml(close ?? '')
    }
    lastIndex = TAG_OR_TOKEN.lastIndex
  }
  out += escapeHtml(source.slice(lastIndex))
  return out
}

// Default sample data for the dynamic live preview, matching MERGE_FIELDS'
// token shape. Pure UX convenience so a new template shows a real merged
// preview immediately — the author can freely edit it in the "Sample data
// (JSON)" panel; it is never persisted with the template.
const DEFAULT_SAMPLE_DATA = {
  party: { name: 'Jana Nováková', address: 'Václavské náměstí 1, 110 00 Praha 1', email: 'jana.novakova@example.com' },
  product: { name: 'Standard Savings Account', code: 'SAVINGS_STANDARD' },
  account: { iban: 'CZ6508000000192000145399' },
  document: { date: '2026-07-14', caseRef: 'CASE-2026-000123' },
  signature: { block: 'Podepsáno elektronicky / Signed electronically' },
}
const DEFAULT_SAMPLE_DATA_TEXT = JSON.stringify(DEFAULT_SAMPLE_DATA, null, 2)

export default function DocumentTemplatesPage() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles: string[] = session?.user?.roles ?? []
  const canEdit = hasPermission(roles, 'templates:edit')

  const [tab, setTab] = useState<'templates' | 'documents'>('templates')
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  const moveTabFocus = (event: React.KeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextIndex = (index + 1) % CONTENT_TABS.length
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextIndex = (index - 1 + CONTENT_TABS.length) % CONTENT_TABS.length
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = CONTENT_TABS.length - 1
    if (nextIndex === null) return
    event.preventDefault()
    setTab(CONTENT_TABS[nextIndex])
    requestAnimationFrame(() => tabRefs.current[nextIndex]?.focus())
  }

  // ── Templates list ──────────────────────────────────────────────────────────
  const [templates, setTemplates] = useState<DocumentTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  const [modalOpen, setModalOpen] = useState(false)
  const [editingTemplate, setEditingTemplate] = useState<DocumentTemplate | null>(null)
  const [formData, setFormData] = useState<Partial<DocumentTemplate>>({})
  const [saving, setSaving] = useState(false)
  const [previewHtml, setPreviewHtml] = useState('')
  const [sampleDataText, setSampleDataText] = useState(DEFAULT_SAMPLE_DATA_TEXT)
  const [sampleDataInvalid, setSampleDataInvalid] = useState(false)
  const [previewNote, setPreviewNote] = useState<string | null>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  const highlightRef = useRef<HTMLPreElement>(null)
  // Request-generation counter (not just an AbortController) so a stale
  // response — even one that resolves before its abort takes effect — can
  // never clobber a newer keystroke's result.
  const previewRequestIdRef = useRef(0)
  const previewAbortRef = useRef<AbortController | null>(null)
  const lastMergedPreviewRef = useRef<string | null>(null)

  // Inline two-step confirm for publish/retire — never a raw `window.confirm`
  // or `alert`; the only lightweight confirm precedent found elsewhere in
  // admin-ui (standing-orders/cards) is read-only with no destructive action,
  // so this bar is modelled on the app's own modal-overlay convention instead.
  const [pendingAction, setPendingAction] = useState<{ id: string; kind: 'publish' | 'retire' } | null>(null)
  const [actioning, setActioning] = useState(false)
  const [lifecycleError, setLifecycleError] = useState<string | null>(null)
  const actionCancelRef = useRef<HTMLButtonElement>(null)
  const actionReturnFocusRef = useRef<HTMLElement | null>(null)
  const actionReturnIdRef = useRef<string | null>(null)

  const openLifecycleDialog = (
    event: React.MouseEvent<HTMLButtonElement>,
    action: { id: string; kind: 'publish' | 'retire' },
  ) => {
    actionReturnFocusRef.current = event.currentTarget
    actionReturnIdRef.current = `template-row-primary-${action.id}`
    setLifecycleError(null)
    setPendingAction(action)
  }

  const load = useCallback(async () => {
    setLoading(true)
    setUnavailable(null)
    setActionError(null)
    try {
      // Bounded request — even though the openapi.yaml doesn't document a
      // `limit` param for listTemplates, we pass one defensively (rule #2:
      // never fetch an unbounded list) and additionally cap the rendered rows
      // client-side via `visibleCount` + "Load more".
      const data = await apiFetch(`${TEMPLATES_PATH}?limit=200`)
      let items: DocumentTemplate[] = Array.isArray(data) ? data : (data?.items ?? data?.content ?? data?.templates ?? [])
      items = items.sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? '') || a.code.localeCompare(b.code))
      setTemplates(items)
      setVisibleCount(PAGE_SIZE)
    } catch (e) {
      setUnavailable({ kind: e instanceof ApiError ? e.kind : 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // Real dynamic preview: on a 250ms debounce after either the body or the
  // sample-data JSON changes, merge the sample data into the template body
  // through the SAME Handlebars engine the actual render pipeline uses
  // (document-service's POST /templates/preview) — not a token highlighter.
  // The old regex highlighter (buildHighlightedPreviewHtml) is now only a
  // fallback: before the first successful call, while the sample JSON is
  // unparseable, or if the request fails.
  async function runPreview() {
    let parsedData: Record<string, unknown>
    try {
      parsedData = JSON.parse(sampleDataText || '{}')
    } catch {
      // Don't call the backend with unparseable JSON — flag it inline and
      // keep showing whatever preview is already on screen.
      setSampleDataInvalid(true)
      return
    }
    setSampleDataInvalid(false)

    // Cancel any in-flight call before starting a new one, and bump the
    // generation counter so even a response that lands after this point is
    // recognized as stale and ignored.
    previewAbortRef.current?.abort()
    const controller = new AbortController()
    previewAbortRef.current = controller
    const requestId = ++previewRequestIdRef.current
    const timeoutId = setTimeout(() => controller.abort(), 8000)

    try {
      const data = await apiFetch(PREVIEW_PATH, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bodyHtml: formData.bodyHtml ?? '', data: parsedData }),
        signal: controller.signal,
      }) as { renderedHtml?: string } | null
      clearTimeout(timeoutId)
      if (requestId !== previewRequestIdRef.current) return // superseded by a newer keystroke
      const wrapped = wrapPreviewHtml(data?.renderedHtml ?? '')
      lastMergedPreviewRef.current = wrapped
      setPreviewHtml(wrapped)
      setPreviewNote(null)
    } catch {
      clearTimeout(timeoutId)
      if (requestId !== previewRequestIdRef.current) return // superseded, not a real failure
      setPreviewNote(t(
        'Náhled se sloučenými daty se teď nepodařilo obnovit — zobrazují se zvýrazněné zástupné symboly.',
        'Could not refresh the merged preview right now — showing highlighted placeholders instead.',
      ))
      setPreviewHtml(lastMergedPreviewRef.current ?? buildHighlightedPreviewHtml(formData.bodyHtml ?? ''))
    }
  }

  useEffect(() => {
    const id = setTimeout(() => { void runPreview() }, 250)
    return () => clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- runPreview closes over formData.bodyHtml/sampleDataText, both already deps below
  }, [formData.bodyHtml, sampleDataText])

  // "Open in a new window" — a Blob URL so the merged preview opens as its own
  // real document/tab (what a client would actually see), not squeezed into a
  // small iframe. Revoked after a delay so the new tab has time to load it.
  function openPreviewInNewWindow() {
    if (!previewHtml) return
    const blobUrl = URL.createObjectURL(new Blob([previewHtml], { type: 'text/html' }))
    window.open(blobUrl, '_blank', 'noopener,noreferrer')
    setTimeout(() => URL.revokeObjectURL(blobUrl), 30_000)
  }

  const filtered = useMemo(() => templates.filter(tpl => {
    if (statusFilter !== 'ALL' && (tpl.status ?? 'DRAFT') !== statusFilter) return false
    if (search) {
      const q = search.toLowerCase()
      return tpl.code?.toLowerCase().includes(q) || tpl.name?.toLowerCase().includes(q)
    }
    return true
  }), [templates, statusFilter, search])

  const visible = filtered.slice(0, visibleCount)

  // Reset the preview state for a fresh modal session — otherwise a stale
  // note/merged-preview from the previously edited template would flash
  // before the debounced effect catches up.
  const resetPreviewState = () => {
    setSampleDataText(DEFAULT_SAMPLE_DATA_TEXT)
    setSampleDataInvalid(false)
    setPreviewNote(null)
    setPreviewHtml('')
    lastMergedPreviewRef.current = null
    previewAbortRef.current?.abort()
    previewRequestIdRef.current++
  }

  const openCreateModal = () => {
    setEditingTemplate(null)
    setFormData({ code: '', version: '1.0.0', name: '', engine: 'HANDLEBARS', bodyHtml: '', locale: language === 'cs' ? 'cs' : 'en', classification: 'internal' })
    setActionError(null)
    resetPreviewState()
    setModalOpen(true)
  }

  const openEditModal = (tpl: DocumentTemplate) => {
    setEditingTemplate(tpl)
    setFormData({ ...tpl })
    setActionError(null)
    resetPreviewState()
    setModalOpen(true)
  }

  function insertToken(token: string) {
    const placeholder = `{{${token}}}`
    const el = bodyRef.current
    const current = formData.bodyHtml ?? ''
    if (!el) {
      setFormData(p => ({ ...p, bodyHtml: current + placeholder }))
      return
    }
    const start = el.selectionStart ?? current.length
    const end = el.selectionEnd ?? current.length
    const next = current.slice(0, start) + placeholder + current.slice(end)
    setFormData(p => ({ ...p, bodyHtml: next }))
    requestAnimationFrame(() => {
      el.focus()
      const pos = start + placeholder.length
      el.setSelectionRange(pos, pos)
    })
  }

  // ONE lock across save, publish and retire (#7091): they were separate React flags,
  // so a save could overlap a publish on the same template. React state disables the
  // control a render too late, so the claim is synchronous.
  const flight = useSingleFlight()

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    const outcome = await flight.run('template:write', async () => {
    setSaving(true)
    setActionError(null)
    try {
      if (editingTemplate?.id) {
        // Published template versions are immutable (ADR-0162 D2) — this admin
        // console only ever edits a DRAFT; publishing creates the immutable
        // record. We still route through the same endpoint the backend exposes.
        await apiFetch(`${TEMPLATES_PATH}/${editingTemplate.id}`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formData),
        })
      } else {
        await apiFetch(TEMPLATES_PATH, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formData),
        })
      }
      await load()
      setModalOpen(false)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('Uložení šablony selhalo', 'Failed to save the template'))
    } finally {
      setSaving(false)
    }
    })
    if (wasSkipped(outcome)) return
  }

  const runAction = async (id: string, kind: 'publish' | 'retire') => {
    const outcome = await flight.run('template:write', async () => {
    setActioning(true)
    setLifecycleError(null)
    try {
      await apiFetch(`${TEMPLATES_PATH}/${id}/${kind}`, { method: 'POST' })
      await load()
      setPendingAction(null)
    } catch (err) {
      setLifecycleError(err instanceof Error ? err.message : t('Akci se nepodařilo provést', 'The action could not be completed'))
    } finally {
      setActioning(false)
    }
    })
    if (wasSkipped(outcome)) return
  }

  return (
    <AuthGuard permission="templates:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px' }}>
        <PageHeader breadcrumb={<div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span className="breadcrumb-current">{t('Šablony dokumentů', 'Document Templates')}</span></div>} icon={<FileSignature size={20} aria-hidden="true" />} title={t('Šablony dokumentů', 'Document Templates')} subtitle={t('Správa šablon smluv, formulářů a vygenerovaných dokumentů (openbank-document-service)', 'Manage contract/form templates and generated documents (openbank-document-service)')} actions={<div style={{ display: 'flex', gap: '8px' }}>
            {canEdit && tab === 'templates' && (
              <button className="btn btn-primary" type="button" onClick={openCreateModal} disabled={loading}>
                <Plus size={14} /> {t('Nová šablona', 'New Template')}
              </button>
            )}
            <button className="btn btn-secondary" type="button" onClick={load} disabled={loading}
              aria-busy={loading} aria-label={t('Obnovit šablony dokumentů', 'Refresh document templates')}>
              <RefreshCw size={13} aria-hidden="true" style={{ animation: loading ? 'spin 1s linear infinite' : 'none' }} />
              {t('Obnovit', 'Refresh')}
            </button>
          </div>} />

        <div role="tablist" aria-label={t('Obsah šablon dokumentů', 'Document template content')} style={{ display: 'flex', gap: '4px', marginBottom: '18px', borderBottom: '1px solid var(--border)' }}>
          {([
            { id: 'templates' as const, label: t('Šablony', 'Templates'), icon: <FileSignature size={13} /> },
            { id: 'documents' as const, label: t('Dokumenty', 'Documents'), icon: <FileText size={13} /> },
          ]).map((tb, index) => (
            <button key={tb.id} ref={element => { tabRefs.current[index] = element }} id={`document-${tb.id}-tab`} role="tab" tabIndex={tab === tb.id ? 0 : -1} aria-selected={tab === tb.id} aria-controls={`document-${tb.id}-panel`} onKeyDown={event => moveTabFocus(event, index)} onClick={() => setTab(tb.id)}
              style={{
                display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 14px', fontSize: '13px', fontWeight: 600,
                border: 'none', borderBottom: tab === tb.id ? '2px solid var(--accent)' : '2px solid transparent',
                background: 'none', cursor: 'pointer', color: tab === tb.id ? 'var(--accent)' : 'var(--text-secondary)',
              }}>
              <span aria-hidden="true">{tb.icon}</span>{tb.label}
            </button>
          ))}
        </div>

        <section id="document-templates-panel" role="tabpanel" aria-labelledby="document-templates-tab" hidden={tab !== 'templates'}>
            {unavailable && (
              <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
                <DataUnavailable kind={unavailable.kind} service={t('Document-service', 'Document-service')} feature={t('Šablony dokumentů', 'Document templates')} lang={language} dense />
                {templates.length > 0 && (
                  <div role="status" aria-live="polite" style={{ padding: '0 16px 14px', color: 'var(--warning-text)', fontSize: '12px', fontWeight: 600 }}>
                    {t('Zobrazené šablony a jejich publikační stavy jsou poslední dostupná data; obnovení se nezdařilo.', 'Displayed templates and publication states are the last available data; refresh failed.')}
                  </div>
                )}
              </div>
            )}

            {actionError && (
              <div className="card" style={{ padding: '12px 16px', color: 'var(--danger-text)', marginBottom: '16px', border: '1px solid var(--danger-border)', background: 'var(--danger-bg)', fontSize: '13px' }}>
                <strong>{t('Chyba', 'Error')}:</strong> {actionError}
              </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '20px' }}>
              {[
                { label: t('Celkem', 'Total'), value: templates.length, color: 'var(--text-primary)' },
                { label: t('Návrh', 'Draft'), value: templates.filter(x => (x.status ?? 'DRAFT') === 'DRAFT').length, color: 'var(--warning-text)' },
                { label: t('Publikováno', 'Published'), value: templates.filter(x => x.status === 'PUBLISHED').length, color: 'var(--success-text)' },
                { label: t('Vyřazeno', 'Retired'), value: templates.filter(x => x.status === 'RETIRED').length, color: 'var(--text-tertiary)' },
              ].map(k => (
                <div key={k.label} className="stat-card">
                  <div className="stat-value" style={{ color: k.color }}>{loading ? '—' : k.value}</div>
                  <div className="stat-label">{k.label}</div>
                </div>
              ))}
            </div>

            <div style={{ display: 'flex', gap: '8px', marginBottom: '16px', flexWrap: 'wrap', alignItems: 'center' }}>
              <div style={{ position: 'relative', flex: 1, minWidth: '220px', maxWidth: '300px' }}>
                <Search size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
                <input id="template-search" aria-label={t('Vyhledat šablonu podle kódu nebo názvu', 'Search templates by code or name')} className="input" style={{ paddingLeft: '30px', width: '100%' }} placeholder={t('Kód nebo název…', 'Code or name…')} value={search} onChange={e => setSearch(e.target.value)} />
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                <label htmlFor="template-status-filter" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{t('Status', 'Status')}:</label>
                <select id="template-status-filter" className="input" style={{ width: 'auto', padding: '5px 10px', fontSize: '12px' }} value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
                  <option value="ALL">{t('Všechny', 'All')}</option>
                  <option value="DRAFT">{t('Návrh', 'Draft')}</option>
                  <option value="PUBLISHED">{t('Publikováno', 'Published')}</option>
                  <option value="RETIRED">{t('Vyřazeno', 'Retired')}</option>
                </select>
              </div>
            </div>

            <div className="card" style={{ overflow: 'hidden' }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t('Kód', 'Code')}</th>
                    <th>{t('Název', 'Name')}</th>
                    <th style={{ width: '90px' }}>{t('Verze', 'Version')}</th>
                    <th style={{ width: '100px' }}>{t('Status', 'Status')}</th>
                    <th style={{ width: '80px' }}>{t('Jazyk', 'Locale')}</th>
                    <th style={{ width: '140px' }}>{t('Produkt', 'Product ref')}</th>
                    <th style={{ width: canEdit ? '160px' : '70px', textAlign: 'right' }}>{t('Akce', 'Actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {loading && Array.from({ length: 5 }).map((_, i) => (
                    <tr key={i}>{Array.from({ length: 7 }).map((_, j) => (
                      <td key={j}><div className="skeleton" style={{ height: '13px', width: j === 1 ? '140px' : '60px' }} /></td>
                    ))}</tr>
                  ))}
                  {!loading && !unavailable && visible.length === 0 && (
                    <tr><td colSpan={7} style={{ padding: 0 }}>
                      <DataUnavailable kind="no_data" feature={t('Šablony', 'Templates')} lang={language} dense />
                    </td></tr>
                  )}
                  {!loading && visible.map(tpl => (
                    <tr key={tpl.id}>
                      <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 700, fontSize: '11px' }}>{tpl.code}</td>
                      <td style={{ fontSize: '13px', fontWeight: 600 }}>{tpl.name}</td>
                      <td style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>v{tpl.version}</td>
                      <td><StatusBadge status={tpl.status ?? 'DRAFT'} tone={TEMPLATE_STATUS_TONE[tpl.status ?? 'DRAFT']} /></td>
                      <td style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{tpl.locale ?? '—'}</td>
                      <td style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-tertiary)' }}>{tpl.productRef ?? '—'}</td>
                      <td style={{ textAlign: 'right' }}>
                        <div style={{ display: 'flex', gap: '3px', justifyContent: 'flex-end' }}>
                          <button id={`template-row-primary-${tpl.id}`} type="button" className="btn btn-secondary btn-sm" style={{ padding: '4px' }} title={canEdit ? t('Upravit', 'Edit') : t('Zobrazit', 'View')} aria-label={canEdit ? t('Upravit šablonu', 'Edit template') : t('Zobrazit šablonu', 'View template')} onClick={() => openEditModal(tpl)}>
                            {canEdit ? <Edit size={13} /> : <Eye size={13} />}
                          </button>
                          {canEdit && (tpl.status ?? 'DRAFT') === 'DRAFT' && (
                            <button type="button" className="btn btn-secondary btn-sm" style={{ padding: '4px', color: 'var(--success-text)' }} title={t('Publikovat', 'Publish')} aria-label={t('Publikovat šablonu', 'Publish template')} onClick={event => openLifecycleDialog(event, { id: tpl.id, kind: 'publish' })}>
                              <Send size={13} />
                            </button>
                          )}
                          {canEdit && tpl.status === 'PUBLISHED' && (
                            <button type="button" className="btn btn-secondary btn-sm" style={{ padding: '4px', color: 'var(--warning-text)' }} title={t('Vyřadit', 'Retire')} aria-label={t('Vyřadit šablonu', 'Retire template')} onClick={event => openLifecycleDialog(event, { id: tpl.id, kind: 'retire' })}>
                              <Archive size={13} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!loading && filtered.length > visibleCount && (
                <div style={{ padding: '12px', textAlign: 'center', borderTop: '1px solid var(--border)' }}>
                  <button className="btn btn-secondary btn-sm" type="button" onClick={() => setVisibleCount(c => c + PAGE_SIZE)}>
                    {t(`Načíst dalších ${Math.min(PAGE_SIZE, filtered.length - visibleCount)}`, `Load ${Math.min(PAGE_SIZE, filtered.length - visibleCount)} more`)}
                  </button>
                </div>
              )}
            </div>
          </section>

        <section id="document-documents-panel" role="tabpanel" aria-labelledby="document-documents-tab" hidden={tab !== 'documents'}><DocumentsLookup t={t} language={language} /></section>
      </div>

      {/* Create / edit modal — split-pane HTML editor + live sandboxed preview */}
      {modalOpen && (
        <div style={{ position: 'fixed', top: 0, left: 0, width: '100%', height: '100%', background: 'rgba(15,23,42,0.65)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '20px' }}>
          <div className="card" role="dialog" aria-modal="true" aria-labelledby="template-editor-title" style={{ width: '920px', maxWidth: '100%', maxHeight: '92vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', borderBottom: '1px solid var(--border)' }}>
              <h2 id="template-editor-title" style={{ fontSize: '15px', fontWeight: 700 }}>
                {!canEdit
                  ? t('Zobrazit šablonu', 'View Template')
                  : editingTemplate ? t('Upravit šablonu', 'Edit Template') : t('Nová šablona', 'New Template')}
              </h2>
              <button type="button" aria-label={t('Zavřít editor šablony', 'Close template editor')} onClick={() => setModalOpen(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)' }}><X size={18} aria-hidden="true" /></button>
            </div>
            <form onSubmit={handleSave} style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <fieldset disabled={!canEdit} style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '14px' }}>
                <div className="grid-3">
                  <div>
                    <label htmlFor="template-code" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Kód *', 'Code *')}</label>
                    <input id="template-code" className="input" required disabled={!canEdit || !!editingTemplate} value={formData.code ?? ''} onChange={e => setFormData(p => ({ ...p, code: e.target.value }))} placeholder="LOAN_AGREEMENT" />
                  </div>
                  <div>
                    <label htmlFor="template-version" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Verze *', 'Version *')}</label>
                    <input id="template-version" className="input" required value={formData.version ?? ''} onChange={e => setFormData(p => ({ ...p, version: e.target.value }))} placeholder="1.0.0" />
                  </div>
                  <div>
                    <label htmlFor="template-locale" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Jazyk', 'Locale')}</label>
                    <input id="template-locale" className="input" value={formData.locale ?? ''} onChange={e => setFormData(p => ({ ...p, locale: e.target.value }))} placeholder="cs" />
                  </div>
                </div>
                <div>
                  <label htmlFor="template-name" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Název *', 'Name *')}</label>
                  <input id="template-name" className="input" required value={formData.name ?? ''} onChange={e => setFormData(p => ({ ...p, name: e.target.value }))} placeholder={t('Smlouva o úvěru', 'Loan agreement')} />
                </div>
                <div className="grid-2">
                  <div>
                    <label htmlFor="template-product-ref" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Odkaz na produkt', 'Product ref')}</label>
                    <input id="template-product-ref" className="input" value={formData.productRef ?? ''} onChange={e => setFormData(p => ({ ...p, productRef: e.target.value }))} placeholder={t('volitelné', 'optional')} />
                  </div>
                  <div>
                    <label htmlFor="template-classification" style={{ display: 'block', marginBottom: '5px', fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Klasifikace', 'Classification')}</label>
                    <input id="template-classification" className="input" value={formData.classification ?? ''} onChange={e => setFormData(p => ({ ...p, classification: e.target.value }))} placeholder="restricted" />
                  </div>
                </div>

                <div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                    <label htmlFor="template-body-html" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{t('Tělo šablony (HTML)', 'Template body (HTML)')}</label>
                    <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                      <FileCode2 size={11} /> {t('Handlebars zástupné symboly', 'Handlebars placeholders')}
                    </span>
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px', marginBottom: '8px' }}>
                    {MERGE_FIELDS.map(f => (
                      <button key={f} type="button" disabled={!canEdit} onClick={() => insertToken(f)}
                        style={{ fontSize: '10.5px', fontFamily: 'var(--font-mono)', padding: '3px 8px', borderRadius: '10px', border: '1px solid var(--accent-border)', background: 'var(--accent-bg)', color: 'var(--accent)', cursor: canEdit ? 'pointer' : 'default' }}>
                        <Tag size={9} style={{ marginRight: '3px', verticalAlign: '-1px' }} />{`{{${f}}}`}
                      </button>
                    ))}
                  </div>

                  <div style={{ marginBottom: '10px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                      <label htmlFor="template-sample-data" style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>
                        {t('Ukázková data pro náhled (JSON)', 'Sample data for preview (JSON)')}
                      </label>
                      {sampleDataInvalid && (
                        <span style={{ fontSize: '10.5px', color: 'var(--warning-text)' }}>
                          {t('Neplatný JSON — zobrazuje se poslední platný náhled', 'Invalid JSON — showing the last valid preview')}
                        </span>
                      )}
                    </div>
                    <textarea
                      id="template-sample-data"
                      value={sampleDataText}
                      onChange={e => setSampleDataText(e.target.value)}
                      spellCheck={false}
                      aria-label={t('Ukázková data pro náhled', 'Sample data for preview')}
                      style={{
                        width: '100%', height: '90px', resize: 'vertical', fontFamily: 'var(--font-mono)', fontSize: '11px',
                        padding: '8px 10px', borderRadius: '8px', background: 'var(--surface-2)', color: 'var(--text-primary)',
                        border: `1px solid ${sampleDataInvalid ? 'var(--warning-border)' : 'var(--border)'}`,
                      }}
                    />
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', marginBottom: '6px' }}>
                    <span style={{ fontSize: '10px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>
                      {t('Zdrojový kód', 'Source')}
                    </span>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <span style={{ fontSize: '10px', fontWeight: 600, color: 'var(--text-tertiary)', textTransform: 'uppercase' }}>
                        {t('Náhled', 'Preview')}
                      </span>
                      <button
                        type="button"
                        onClick={openPreviewInNewWindow}
                        disabled={!previewHtml}
                        title={t('Otevřít náhled v novém okně (jak jej uvidí klient)', 'Open the preview in a new window (as the client would see it)')}
                        style={{
                          display: 'flex', alignItems: 'center', gap: '4px', fontSize: '10.5px', fontWeight: 600,
                          padding: '2px 7px', borderRadius: '10px', border: '1px solid var(--accent-border)',
                          background: 'var(--accent-bg)', color: 'var(--accent)',
                          cursor: previewHtml ? 'pointer' : 'default', opacity: previewHtml ? 1 : 0.5,
                        }}
                      >
                        <ExternalLink size={10} /> {t('Nové okno', 'New window')}
                      </button>
                    </div>
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', height: '320px' }}>
                    {/* Syntax-highlight overlay: a read-only, aria-hidden <pre> renders the
                        colored markup behind a textarea whose own text/background are
                        transparent (only the caret and native text-selection show), so
                        typing/scrolling/selecting all still work exactly like a plain
                        textarea while the author sees tags/attributes/{{tokens}} colored. */}
                    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
                      <pre
                        ref={highlightRef}
                        aria-hidden="true"
                        style={{
                          position: 'absolute', inset: 0, margin: 0, overflow: 'auto', pointerEvents: 'none',
                          fontFamily: 'var(--font-mono)', fontSize: '12px', lineHeight: 1.5, padding: '10px',
                          borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--surface-2)',
                          whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                        }}
                        dangerouslySetInnerHTML={{
                          __html: formData.bodyHtml
                            ? highlightHtmlSource(formData.bodyHtml)
                            : `<span style="color:var(--text-tertiary);">${escapeHtml(t('<p>Vážený/á {{party.name}}, …</p>', '<p>Dear {{party.name}}, …</p>'))}</span>`,
                        }}
                      />
                      <textarea
                        ref={bodyRef}
                        required
                        value={formData.bodyHtml ?? ''}
                        onChange={e => setFormData(p => ({ ...p, bodyHtml: e.target.value }))}
                        onScroll={e => {
                          if (highlightRef.current) {
                            highlightRef.current.scrollTop = e.currentTarget.scrollTop
                            highlightRef.current.scrollLeft = e.currentTarget.scrollLeft
                          }
                        }}
                        spellCheck={false}
                        id="template-body-html"
                        style={{
                          position: 'relative', width: '100%', height: '100%', resize: 'none', margin: 0,
                          fontFamily: 'var(--font-mono)', fontSize: '12px', lineHeight: 1.5, padding: '10px',
                          borderRadius: '8px', border: '1px solid var(--border)',
                          background: 'transparent', color: 'transparent', caretColor: 'var(--text-primary)',
                          whiteSpace: 'pre-wrap', wordBreak: 'break-word',
                        }}
                      />
                    </div>
                    <iframe
                      title={t('Náhled šablony', 'Template preview')}
                      // Sandboxed WITHOUT allow-scripts: the merged HTML still
                      // embeds attacker-influenceable content (the *data* merged
                      // in — e.g. a party name — can originate from any caller in
                      // production) and this is an admin console, not a public
                      // site — the preview must never execute script.
                      sandbox="allow-same-origin"
                      srcDoc={previewHtml}
                      style={{ width: '100%', height: '100%', border: '1px solid var(--border)', borderRadius: '8px', background: '#fff' }}
                    />
                  </div>
                  {previewNote && (
                    <div style={{ display: 'flex', gap: '6px', alignItems: 'flex-start', marginTop: '8px', fontSize: '11px', color: 'var(--warning-text)' }}>
                      <Info size={12} style={{ flexShrink: 0, marginTop: '1px' }} />
                      {previewNote}
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: '6px', alignItems: 'flex-start', marginTop: '8px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                    <Info size={12} style={{ flexShrink: 0, marginTop: '1px' }} />
                    {/* Honest scope note (ADR-0162 D6): a drag-and-drop visual
                        builder (GrapesJS/TipTap) is a follow-up enhancement, not
                        delivered in this pass — no new npm dependency was added. */}
                    {t(
                      'Toto je textový editor s živým náhledem se sloučenými ukázkovými daty, ne vizuální drag-and-drop builder. Grafický editor (GrapesJS/TipTap) je plánované rozšíření (ADR-0162 D6), zatím nedodáno.',
                      'This is a text editor with a live preview merging real sample data, not a drag-and-drop visual builder. A graphical editor (GrapesJS/TipTap) is a planned follow-up (ADR-0162 D6), not delivered in this pass.',
                    )}
                  </div>
                </div>

                {actionError && (
                  <div role="alert" style={{ padding: '10px 12px', background: 'var(--danger-bg)', color: 'var(--danger-text)', borderRadius: '6px', fontSize: '12px', border: '1px solid var(--danger-border)' }}>
                    {actionError}
                  </div>
                )}
              </fieldset>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', paddingTop: '4px' }}>
                <button type="button" className="btn btn-secondary" onClick={() => setModalOpen(false)} disabled={saving}>{t('Zavřít', 'Close')}</button>
                {canEdit && (
                  <button type="submit" className="btn btn-primary" disabled={saving}>
                    {saving ? t('Ukládám…', 'Saving…') : t('Uložit šablonu', 'Save Template')}
                  </button>
                )}
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Inline confirm for publish/retire — never a raw browser confirm()/alert() */}
      {pendingAction && (
        <Dialog.Root open onOpenChange={open => {
          if (!open && !actioning) {
            setLifecycleError(null)
            setPendingAction(null)
          }
        }}>
          <Dialog.Portal>
            <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 1100, background: 'rgba(15,23,42,0.65)' }} />
            <Dialog.Content
              className="card"
              aria-modal="true"
              aria-busy={actioning}
              onOpenAutoFocus={event => {
                event.preventDefault()
                actionCancelRef.current?.focus()
              }}
              onCloseAutoFocus={event => {
                event.preventDefault()
                const original = actionReturnFocusRef.current
                const rowFallback = actionReturnIdRef.current ? document.getElementById(actionReturnIdRef.current) : null
                const pageFallback = document.getElementById('template-status-filter')
                const target = original?.isConnected ? original : rowFallback ?? pageFallback
                target?.focus()
              }}
              onEscapeKeyDown={event => { if (actioning) event.preventDefault() }}
              onInteractOutside={event => { if (actioning) event.preventDefault() }}
              style={{ position: 'fixed', zIndex: 1101, top: '50%', left: '50%', transform: 'translate(-50%, -50%)', width: 'calc(100% - 40px)', maxWidth: '400px', padding: '20px' }}
            >
            <Dialog.Title style={{ fontSize: '14px', fontWeight: 700, marginBottom: '8px', color: 'var(--text-primary)' }}>
              {pendingAction.kind === 'publish' ? t('Publikovat šablonu?', 'Publish this template?') : t('Vyřadit šablonu?', 'Retire this template?')}
            </Dialog.Title>
            <Dialog.Description style={{ fontSize: '12.5px', color: 'var(--text-secondary)', marginBottom: '16px', lineHeight: 1.5 }}>
              {pendingAction.kind === 'publish'
                ? t('Publikovaná verze je neměnná — další úprava vytvoří novou verzi.', 'A published version is immutable — a further edit creates a new version.')
                : t('Vyřazená šablona se přestane nabízet pro generování nových dokumentů.', 'A retired template stops being offered for new document generation.')}
            </Dialog.Description>
            {lifecycleError && (
              <div role="alert" style={{ padding: '10px 12px', marginBottom: '16px', background: 'var(--danger-bg)', color: 'var(--danger-text)', borderRadius: '6px', fontSize: '12px', border: '1px solid var(--danger-border)' }}>
                {lifecycleError}
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <Dialog.Close asChild>
                <button ref={actionCancelRef} className="btn btn-secondary" type="button" disabled={actioning}>{t('Zrušit', 'Cancel')}</button>
              </Dialog.Close>
              <button className="btn btn-primary" type="button" onClick={() => runAction(pendingAction.id, pendingAction.kind)} disabled={actioning} aria-busy={actioning}>
                {actioning ? t('Provádím…', 'Working…') : t('Potvrdit', 'Confirm')}
              </button>
            </div>
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>
      )}
    </AuthGuard>
  )
}

// ── Documents tab — read-only lookup-by-ID ──────────────────────────────────────
// NOTE (honest scope): openbank-document-service's openapi.yaml exposes
// GET /api/v1/documents/{id} and GET /api/v1/documents/{id}/content, but no
// bulk "list all documents" endpoint. A full sortable/filterable Document
// list therefore cannot be built against the real contract without inventing
// an endpoint that doesn't exist. This view instead mirrors the KYC page's
// per-ID lookup pattern (src/app/kyc/page.tsx) — validate the ID client-side
// (rule #2), fetch metadata, offer a content download. Signature-ceremony
// status is NOT included in this pass (deferred as a nice-to-have per scope).
function DocumentsLookup({ t, language }: { t: (cs: string, en: string) => string; language: 'cs' | 'en' }) {
  const [idInput, setIdInput] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [loading, setLoading] = useState(false)
  const [doc, setDoc] = useState<DocumentMeta | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const lookup = useCallback(async () => {
    const id = idInput.trim()
    if (!looksLikeUuid(id)) {
      setInvalid(true)
      setDoc(null)
      setUnavailable(null)
      return
    }
    setInvalid(false)
    setLoading(true)
    setUnavailable(null)
    setDoc(null)
    try {
      const res = await fetch(svcUrl(SERVICE, `${DOCUMENTS_PATH}/${id}`), { cache: 'no-store', signal: AbortSignal.timeout(8000) })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = (await res.json()) as DocumentMeta
      setDoc(data)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [idInput])

  return (
    <div>
      <div className="card" style={{ padding: '16px 20px', marginBottom: '16px', display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
        <div style={{ position: 'relative', flex: 1, minWidth: '260px' }}>
          <Hash size={13} aria-hidden="true" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
          <input
            id="document-id"
            aria-label={t('ID dokumentu', 'Document ID')}
            className="input"
            style={{ paddingLeft: '30px', width: '100%', fontFamily: 'var(--font-mono)' }}
            placeholder={t('ID dokumentu (UUID)…', 'Document ID (UUID)…')}
            value={idInput}
            onChange={e => { setIdInput(e.target.value); setInvalid(false) }}
            onKeyDown={e => { if (e.key === 'Enter') lookup() }}
          />
        </div>
        <button className="btn btn-primary" type="button" onClick={lookup} disabled={loading || idInput.trim().length === 0} aria-busy={loading} aria-label={t('Vyhledat dokument podle ID', 'Look up document by ID')}>
          <Search size={13} /> {loading ? t('Hledám…', 'Looking up…') : t('Vyhledat', 'Look up')}
        </button>
      </div>

      {invalid && (
        <div className="card" style={{ padding: '10px 14px', marginBottom: '16px', fontSize: '12px', color: 'var(--warning-text)', border: '1px solid var(--warning-border)', background: 'var(--warning-bg)' }}>
          {t('Zadejte platné UUID dokumentu (např. z e-mailu s potvrzením podpisu).', 'Enter a valid document UUID (e.g. from a signature-confirmation email).')}
        </div>
      )}

      {unavailable && (
        <div className="card" style={{ padding: 0, marginBottom: '16px' }}>
          <DataUnavailable kind={unavailable.kind} service={t('Document-service', 'Document-service')} feature={t('Dokument', 'Document')} lang={language} dense />
        </div>
      )}

      {!doc && !unavailable && !invalid && (
        <div className="card" style={{ padding: 0 }}>
          <DataUnavailable kind="no_data" feature={t('Vyhledání dokumentu', 'Document lookup')} lang={language} dense
            detail={t(
              'Zadejte ID vygenerovaného dokumentu — seznam všech dokumentů zatím backend nevystavuje (jen vyhledání podle ID).',
              'Enter a generated document ID — the backend does not yet expose a bulk list, only lookup by ID.',
            )} />
        </div>
      )}

      {doc && (
        <div className="card" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <FileText size={16} style={{ color: 'var(--accent)' }} />
              <span style={{ fontSize: '14px', fontWeight: 700 }}>{doc.templateCode ?? t('Dokument', 'Document')}</span>
              {doc.templateVersion && <span style={{ fontSize: '11px', color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>v{doc.templateVersion}</span>}
            </div>
            <a
              href={svcUrl(SERVICE, `${DOCUMENTS_PATH}/${doc.id}/content`)}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-secondary btn-sm"
              style={{ textDecoration: 'none' }}
            >
              <Download size={13} /> {t('Stáhnout / zobrazit', 'Download / view')}
            </a>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '10px', fontSize: '12px' }}>
            <Row label={t('ID dokumentu', 'Document ID')} value={doc.id} mono />
            <Row label={t('Status', 'Status')} value={doc.status ?? '—'} />
            <Row label={t('Party ref', 'Party ref')} value={doc.partyRef ?? '—'} mono />
            <Row label={t('Case ref', 'Case ref')} value={doc.caseRef ?? '—'} mono />
            <Row label={t('Produkt', 'Product ref')} value={doc.productRef ?? '—'} mono />
            <Row label={t('Typ obsahu', 'Content type')} value={doc.contentType ?? '—'} />
            <Row label={t('Vytvořeno', 'Created')} value={doc.createdAt ?? '—'} />
            <Row label={t('Uchovávat do', 'Retain until')} value={doc.retainUntil ?? '—'} />
          </div>
        </div>
      )}
    </div>
  )
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '5px 0', borderBottom: '1px solid var(--border)' }}>
      <span style={{ color: 'var(--text-tertiary)' }}>{label}</span>
      <span style={{ fontWeight: 600, fontFamily: mono ? 'var(--font-mono)' : undefined, textAlign: 'right' }}>{value}</span>
    </div>
  )
}
