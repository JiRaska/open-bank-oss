// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Bot, Boxes, CheckCircle2, Eye, FileJson, Plus, RefreshCw, Send, ShieldCheck, Sparkles } from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { catalogRevisionEditorDocument, diffCatalogDocuments } from '@/lib/catalog-structural-diff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  catalogV2Operation, type CatalogSchema, type Offering, type OfferingRequest, type ProductRevision,
  type RevisionRequest, type Specification, type SpecificationRequest, type ValidateCatalogResponse,
} from '@/lib/product-catalog-v2'
import styles from './page.module.css'

function seed(schema: unknown): unknown {
  if (!schema || typeof schema !== 'object') return null
  const node = schema as Record<string, unknown>
  if (node.default !== undefined) return node.default
  if (Array.isArray(node.enum) && node.enum.length) return node.enum[0]
  if (node.type === 'object') {
    const properties = (node.properties ?? {}) as Record<string, unknown>
    const conditionalRequired = ((node.allOf ?? []) as Array<Record<string, unknown>>)
      .flatMap(rule => ((rule.then as Record<string, unknown> | undefined)?.required ?? []) as string[])
    const required = [...new Set([...((node.required ?? []) as string[]), ...conditionalRequired])]
    return Object.fromEntries(required.map(key => [key, seed(properties[key])]))
  }
  if (node.type === 'array') return []
  if (node.type === 'boolean') return false
  if (node.type === 'integer' || node.type === 'number') return node.minimum ?? 0
  if (node.type === 'string') {
    const pattern = String(node.pattern ?? '')
    if (pattern.includes('[A-Z]{3}')) return 'EUR'
    if (pattern.includes('[0-9]')) return '1'
    return 'value'
  }
  return null
}

function Badge({ state }: { state: ProductRevision['state'] }) {
  const color = state === 'PUBLISHED' ? 'var(--success-text)' : state === 'DRAFT' ? 'var(--warning-text)' : 'var(--text-tertiary)'
  return <span style={{ color, fontWeight: 700, fontSize: 11 }}>{state}</span>
}

interface CatalogReviewFinding {
  severity: 'INFO' | 'WARNING' | 'HIGH'
  category: string
  instancePath: string
  evidence: string
  recommendation: string
  requiresHumanDecision: boolean
}

interface CatalogReview {
  proposalId: string
  state: 'PROPOSED'
  contextHash: string
  summary: string
  findings: CatalogReviewFinding[]
  model: string
}

export default function ProductStudioPage() {
  const { language } = useLanguage()
  const t = (cs: string, en: string) => language === 'cs' ? cs : en
  const [schemas, setSchemas] = useState<CatalogSchema[]>([])
  const [specifications, setSpecifications] = useState<Specification[]>([])
  const [offerings, setOfferings] = useState<Offering[]>([])
  const [revisions, setRevisions] = useState<ProductRevision[]>([])
  const [specificationId, setSpecificationId] = useState('')
  const [offeringId, setOfferingId] = useState('')
  const [revisionId, setRevisionId] = useState('')
  const [draftText, setDraftText] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [newSpecCode, setNewSpecCode] = useState('')
  const [newOfferingCode, setNewOfferingCode] = useState('')
  const [publishReason, setPublishReason] = useState('')
  const [newSpecSchema, setNewSpecSchema] = useState('')
  const [review, setReview] = useState<CatalogReview | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const selectedSpec = specifications.find(item => item.id === specificationId)
  const selectedOffering = offerings.find(item => item.id === offeringId)
  const selectedRevision = revisions.find(item => item.id === revisionId)
  const publishedRevision = revisions.find(item => item.state === 'PUBLISHED')
  const parsedDraft = useMemo(() => {
    try { return draftText ? JSON.parse(draftText) as Record<string, unknown> : null } catch { return null }
  }, [draftText])
  const compatibleSchemas = useMemo(
    () => schemas.filter(item => !selectedSpec || item.id === selectedSpec.schemaRef.id),
    [schemas, selectedSpec],
  )
  const liveDocument = publishedRevision ? catalogRevisionEditorDocument(publishedRevision) : null
  const structuralDiff = diffCatalogDocuments(liveDocument, parsedDraft)
  const draftCount = revisions.filter(item => item.state === 'DRAFT').length
  const publishedCount = revisions.filter(item => item.state === 'PUBLISHED').length

  const load = useCallback(async () => {
    setBusy(true); setMessage('')
    try {
      const [typeRows, specificationRows, offeringRows] = await Promise.all([
        catalogV2Operation('listProductTypesV2', {}),
        catalogV2Operation('listSpecificationsV2', {}),
        catalogV2Operation('listOfferingsV2', {}),
      ])
      setSchemas(typeRows); setSpecifications(specificationRows); setOfferings(offeringRows)
      if (!newSpecSchema && typeRows[0]) setNewSpecSchema(`${typeRows[0].id}:${typeRows[0].version}`)
      if (!specificationId && specificationRows[0]) setSpecificationId(specificationRows[0].id)
    } catch (error) { setMessage(error instanceof Error ? error.message : String(error)) } finally { setBusy(false) }
  }, [newSpecSchema, specificationId])

  const loadRevisions = useCallback(async (id: string) => {
    if (!id) { setRevisions([]); return }
    try {
      const rows = await catalogV2Operation(
        'listOfferingRevisionsV2', { pathParameters: { id } },
      )
      setRevisions(rows); setRevisionId(rows[0]?.id ?? '')
    } catch (error) { setMessage(error instanceof Error ? error.message : String(error)) }
  }, [])

  useEffect(() => {
    const task = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(task)
  }, [load])
  useEffect(() => {
    const task = window.setTimeout(() => { void loadRevisions(offeringId) }, 0)
    return () => window.clearTimeout(task)
  }, [offeringId, loadRevisions])
  useEffect(() => {
    const nextDraft = selectedRevision
      ? JSON.stringify(catalogRevisionEditorDocument(selectedRevision), null, 2)
      : ''
    const task = window.setTimeout(() => setDraftText(nextDraft), 0)
    return () => window.clearTimeout(task)
  }, [selectedRevision])

  const run = async (work: () => Promise<unknown>, success: string) => {
    setBusy(true); setMessage('')
    try { await work(); setMessage(success); await load(); if (offeringId) await loadRevisions(offeringId) }
    catch (error) { setMessage(error instanceof Error ? error.message : String(error)) }
    finally { setBusy(false) }
  }

  const createSpecification = () => {
    const schema = schemas.find(item => `${item.id}:${item.version}` === newSpecSchema)
    if (!schema || !newSpecCode.trim()) return
    const body: SpecificationRequest = {
      code: newSpecCode.trim().toUpperCase(), schemaRef: { id: schema.id, version: schema.version },
    }
    void run(() => catalogV2Operation('createSpecificationV2', {
      body,
    }), t('Specifikace vytvořena', 'Specification created'))
  }

  const createOffering = () => {
    if (!selectedSpec || !newOfferingCode.trim()) return
    const body: OfferingRequest = {
      specificationId: selectedSpec.id, code: newOfferingCode.trim().toUpperCase(),
      market: { countries: [], channels: [], brands: [], segments: [], locales: ['en'] },
    }
    void run(() => catalogV2Operation('createOfferingV2', {
      body,
    }), t('Nabídka vytvořena', 'Offering created'))
  }

  const createDraft = () => {
    const schema = compatibleSchemas.at(-1)
    if (!selectedOffering || !schema) return
    const body: RevisionRequest = {
      schemaRef: { id: schema.id, version: schema.version }, name: { en: selectedOffering.code },
      attributes: seed(schema.document) as Record<string, unknown>,
      prices: [], eligibility: [], relationships: [], documentCodes: [],
    }
    void run(() => catalogV2Operation('createOfferingRevisionV2', {
      pathParameters: { id: selectedOffering.id }, body,
    }), t('Draft vytvořen; doplňte povinná pole schématu', 'Draft created; complete the schema-required fields'))
  }

  const saveDraft = () => {
    if (!selectedRevision || selectedRevision.state !== 'DRAFT') return
    if (!parsedDraft) { setMessage(t('Draft není validní JSON', 'Draft is not valid JSON')); return }
    void run(() => catalogV2Operation('replaceOfferingRevisionV2', {
      pathParameters: { offeringId: selectedRevision.offeringId, revisionId: selectedRevision.id },
      headers: { 'If-Match': `"${selectedRevision.revision}"` }, body: parsedDraft as RevisionRequest,
    }), t('Draft uložen', 'Draft saved'))
  }

  const validateDraft = async () => {
    if (!selectedRevision) return
    try {
      const body = JSON.parse(draftText) as { attributes: Record<string, unknown>; schemaRef: { id: string; version: number } }
      const result: ValidateCatalogResponse = await catalogV2Operation('validateProductAttributesV2', {
        pathParameters: { id: body.schemaRef.id, version: body.schemaRef.version },
        body: { attributes: body.attributes },
      })
      setMessage(result.valid ? t('Schéma je validní', 'Schema validation passed') : result.violations.map(v => `${v.instancePath}: ${v.message}`).join('\n'))
    } catch (error) { setMessage(error instanceof Error ? error.message : String(error)) }
  }

  const publish = () => {
    if (!selectedRevision || !publishReason.trim()) return
    void run(() => catalogV2Operation('publishOfferingRevisionV2', {
      pathParameters: { offeringId: selectedRevision.offeringId, revisionId: selectedRevision.id },
      headers: { 'If-Match': `"${selectedRevision.revision}"` },
      body: { reason: publishReason.trim() },
    }), t('Revize publikována nezávislým schvalovatelem', 'Revision published by an independent checker'))
  }

  const reviewDraft = async () => {
    if (!selectedRevision || selectedRevision.state !== 'DRAFT') return
    setReviewing(true); setReview(null); setMessage('')
    try {
      const response = await fetch('/api/agent/catalog-reviews', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ offeringId: selectedRevision.offeringId, revisionId: selectedRevision.id }),
      })
      const body = await response.json() as CatalogReview & { error?: string }
      if (!response.ok) throw new Error(body.error ?? response.statusText)
      setReview(body)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error))
    } finally {
      setReviewing(false)
    }
  }

  return <AuthGuard permission="catalog:read">
    <section className={styles.hero}>
      <div className={styles.heroTop}>
        <div>
          <div className={styles.eyebrow}><Sparkles size={13} /> {t('Product intelligence studio', 'Product intelligence studio')}</div>
          <h1 className={styles.heroTitle}>{t('Od nápadu k důvěryhodné nabídce.', 'From product idea to a trusted offer.')}</h1>
          <p className={styles.heroCopy}>{t(
            'Řiďte život nabídky na jednom místě: typ, kontext trhu, schéma, dopad změn i nezávislé schválení. Inteligence radí, člověk rozhoduje.',
            'Run the whole offer lifecycle in one place: type, market context, schema, change impact and independent approval. Intelligence advises; people decide.',
          )}</p>
        </div>
        <button className={`btn btn-secondary ${styles.refresh}`} disabled={busy} onClick={() => void load()}><RefreshCw size={13} />{t('Obnovit data', 'Refresh data')}</button>
      </div>
      <div className={styles.metrics}>
        <div className={styles.metric}><div className={styles.metricLabel}>{t('Produktové typy', 'Product types')}</div><div className={styles.metricValue}>{schemas.length}</div><div className={styles.metricNote}>{t('důvěryhodná schémata', 'trusted schemas')}</div></div>
        <div className={styles.metric}><div className={styles.metricLabel}>{t('Specifikace', 'Specifications')}</div><div className={styles.metricValue}>{specifications.length}</div><div className={styles.metricNote}>{t('kanonické identity', 'canonical identities')}</div></div>
        <div className={styles.metric}><div className={styles.metricLabel}>{t('Aktivní práce', 'Active work')}</div><div className={styles.metricValue}>{draftCount}</div><div className={styles.metricNote}>{t('draftů k rozhodnutí', 'drafts awaiting a decision')}</div></div>
        <div className={styles.metric}><div className={styles.metricLabel}>{t('Živý stav', 'Live state')}</div><div className={styles.metricValue}>{publishedCount}</div><div className={styles.metricNote}>{t('publikovaných revizí nabídky', 'published offer revisions')}</div></div>
      </div>
    </section>

    <nav className={styles.journey} aria-label={t('Životní cyklus nabídky', 'Offer lifecycle')}>
      {[
        [t('Definice', 'Define'), t('identita a schéma', 'identity and schema')],
        [t('Návrh', 'Compose'), t('obsah a kontext trhu', 'content and market context')],
        [t('Kontrola', 'Assure'), t('validace a AI review', 'validation and AI review')],
        [t('Publikace', 'Publish'), t('four-eyes rozhodnutí', 'four-eyes decision')],
      ].map(([title, copy], index) => <div key={title} className={`${styles.journeyStep} ${index === 2 ? styles.journeyActive : ''}`}>
        <span className={styles.journeyNumber}>{index + 1}</span><span className={styles.journeyText}><strong>{title}</strong><span>{copy}</span></span>
      </div>)}
    </nav>

    {message && <div className={styles.message} role="status">{message}</div>}

    <div className={styles.workspace}>
      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Katalog', 'Catalog')}</div><h2 className={styles.panelTitle}><FileJson size={15} />{t('Typ a identita', 'Type and identity')}</h2></div><span className="badge badge-accent">v2</span></div>
        <div className={styles.panelBody}>
          <label className={styles.smallLabel}>{t('Specifikace', 'Specification')}</label>
          <select className="input" value={specificationId} onChange={event => { setSpecificationId(event.target.value); setOfferingId('') }}>
            <option value="">{t('Vyberte specifikaci', 'Select specification')}</option>
            {specifications.map(item => <option key={item.id} value={item.id}>{item.code} · {item.schemaRef.id}:{item.schemaRef.version}</option>)}
          </select>
          <Can permission="catalog:author">
            <label className={styles.smallLabel}>{t('Nová specifikace', 'New specification')}</label>
            <select className="input" value={newSpecSchema} onChange={event => setNewSpecSchema(event.target.value)}>
              {schemas.map(item => <option key={`${item.id}:${item.version}`} value={`${item.id}:${item.version}`}>{item.id}:{item.version}</option>)}
            </select>
            <div style={{ display: 'flex', gap: 7, marginTop: 7 }}><input className="input" value={newSpecCode} onChange={e => setNewSpecCode(e.target.value)} placeholder="TERM_LIFE" /><button className="btn btn-secondary" onClick={createSpecification} aria-label={t('Vytvořit specifikaci', 'Create specification')}><Plus size={13} /></button></div>
          </Can>
          <div className={styles.schemaHint}>{t('Aktivní schema:', 'Active schema:')} <strong>{compatibleSchemas.at(-1) ? `${compatibleSchemas.at(-1)!.id}:${compatibleSchemas.at(-1)!.version}` : '—'}</strong><br />{t('Formulář respektuje verzi schématu; publikovaný obsah se nemění.', 'The form respects its schema version; published content never mutates.')}</div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Nabídka', 'Offer')}</div><h2 className={styles.panelTitle}><Boxes size={15} />{t('Kontext a historie', 'Context and history')}</h2></div>{selectedOffering && <span className="badge badge-info">{selectedOffering.code}</span>}</div>
        <div className={styles.panelBody}>
          <select className="input" value={offeringId} onChange={e => setOfferingId(e.target.value)}>
            <option value="">{t('Vyberte nabídku', 'Select offering')}</option>
            {offerings.filter(item => !specificationId || item.specificationId === specificationId).map(item => <option key={item.id} value={item.id}>{item.code}</option>)}
          </select>
          <Can permission="catalog:author">
            <div style={{ display: 'flex', gap: 7, marginTop: 8 }}><input className="input" value={newOfferingCode} onChange={e => setNewOfferingCode(e.target.value)} placeholder="TERM_LIFE_CZ_WEB" /><button className="btn btn-secondary" onClick={createOffering} aria-label={t('Vytvořit nabídku', 'Create offering')}><Plus size={13} /></button></div>
            <button className="btn btn-primary" style={{ width: '100%', marginTop: 8 }} disabled={!selectedOffering} onClick={createDraft}><Plus size={13} />{t('Založit novou revizi', 'Create a new revision')}</button>
          </Can>
          <div className={styles.revisionList}>{revisions.length === 0 && <div className={styles.schemaHint}>{t('Vyberte nabídku a otevřete její rozhodovací historii.', 'Select an offer to open its decision history.')}</div>}{revisions.map(item => <button key={item.id} onClick={() => { setRevisionId(item.id); setReview(null) }} className={`${styles.revision} ${revisionId === item.id ? styles.revisionSelected : ''}`}>
            <span><strong>#{item.number}</strong> <span style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>· schema {item.schemaRef.version}</span></span><Badge state={item.state} />
          </button>)}</div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Pracovní revize', 'Working revision')}</div><h2 className={styles.panelTitle}><Send size={15} />{t('Návrh řízený schématem', 'Schema-governed draft')}</h2></div>{selectedRevision && <Badge state={selectedRevision.state} />}</div>
        <div className={styles.panelBody}>
          <div className={styles.draftBanner}><CheckCircle2 size={15} /><span>{selectedRevision?.state === 'DRAFT' ? t('Draft lze ukládat a ověřovat. Publikaci provede jiný uživatel.', 'This draft can be saved and checked. A different user performs publication.') : t('Toto je neměnný historický záznam.', 'This is an immutable historical record.')}</span></div>
          <label className={styles.smallLabel}>{t('Expert režim · úplný dokument', 'Expert mode · full document')}</label>
          <Can permission="catalog:author" fallback={<textarea className={`input ${styles.editor}`} value={draftText} disabled />}>
            <textarea className={`input ${styles.editor}`} value={draftText} onChange={e => setDraftText(e.target.value)} disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} />
            <div className={styles.actions}><button className="btn btn-secondary" disabled={!selectedRevision} onClick={() => void validateDraft()}><CheckCircle2 size={13} />{t('Ověřit schéma', 'Validate schema')}</button><button className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} onClick={saveDraft}><Send size={13} />{t('Uložit draft', 'Save draft')}</button></div>
          </Can>
          <Can permission="catalog:publish"><div style={{ borderTop: '1px solid var(--border)', marginTop: 14, paddingTop: 14 }}><label className={styles.smallLabel}>{t('Nezávislé schválení', 'Independent approval')}</label><div style={{ display: 'flex', gap: 7 }}><input className="input" value={publishReason} onChange={e => setPublishReason(e.target.value)} placeholder={t('Důvod schválení', 'Approval reason')} /><button className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} onClick={publish}><ShieldCheck size={13} />{t('Publikovat', 'Publish')}</button></div></div></Can>
        </div>
      </section>
    </div>

    <div className={styles.lowerGrid}>
      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Dopad změny', 'Change impact')}</div><h2 className={styles.panelTitle}><Eye size={15} />{t('Draft proti živé nabídce', 'Draft against live offer')}</h2></div><span className={`badge ${structuralDiff.length ? 'badge-warning' : 'badge-success'}`}>{structuralDiff.length ? t(`${structuralDiff.length} změn`, `${structuralDiff.length} changes`) : t('Bez rozdílu', 'No difference')}</span></div>
        <div className={styles.panelBody}>
          <div className={styles.insightGrid}><div className={styles.insight}><b>{structuralDiff.length}</b><span>{t('změněných cest', 'changed paths')}</span></div><div className={styles.insight}><b>{parsedDraft ? '✓' : '—'}</b><span>{t('čitelnost draftu', 'draft parseability')}</span></div><div className={styles.insight}><b>{publishedRevision ? 'LIVE' : '—'}</b><span>{t('referenční revize', 'reference revision')}</span></div></div>
          {structuralDiff.length === 0 ? <div className={styles.schemaHint}>{t('Žádná strukturální změna proti živé revizi. Před publikací vždy ověřte obchodní význam.', 'No structural change from the live revision. Always verify business meaning before publication.')}</div> : <ul className={styles.diffList}>{structuralDiff.map(entry => <li key={`${entry.kind}:${entry.path}`}><strong>{entry.kind}</strong> <code>{entry.path}</code></li>)}</ul>}

          <div className={styles.aiPanel}>
            <div className={styles.aiHead}><div><div className={styles.aiTitle}><Bot size={15} />{t('Catalog intelligence review', 'Catalog intelligence review')}</div><div className={styles.aiCopy}>{t('Připne přesný draft, vytvoří pouze návrh pro lidské posouzení a nikdy nemění ani nepublikuje nabídku.', 'Pins the exact draft, creates only a human-review proposal and never changes or publishes an offer.')}</div></div><span className={styles.aiGuard}><ShieldCheck size={11} />HITL</span></div>
            <Can permission="catalog:author"><div className={styles.actions}><button className="btn btn-secondary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT' || reviewing} onClick={() => void reviewDraft()}><Sparkles size={13} />{reviewing ? t('Kontroluji…', 'Reviewing…') : t('Spustit AI kontrolu', 'Run AI review')}</button></div></Can>
            {!selectedRevision && <div className={styles.schemaHint}>{t('Vyberte draft revizi; review nikdy nepracuje s neurčitým nebo živým obsahem.', 'Select a draft revision; review never works from an ambiguous or live document.')}</div>}
            {review && <div aria-live="polite"><div className={styles.findingText} style={{ marginTop: 11, fontWeight: 700 }}>{review.summary}</div>{review.findings.length === 0 && <div className={styles.schemaHint}>{t('Model nenašel strukturované nálezy. To nenahrazuje lidskou obchodní kontrolu.', 'The model found no structured findings. That never replaces human business review.')}</div>}{review.findings.map(finding => <div key={`${finding.category}:${finding.instancePath}`} className={`${styles.finding} ${finding.severity === 'HIGH' ? styles.findingHigh : finding.severity === 'WARNING' ? styles.findingWarning : ''}`}><div className={styles.findingTitle}><span>{finding.category}</span><span>{finding.severity}</span></div><div className={styles.findingText}>{finding.recommendation}</div><div className={styles.evidence}>{finding.instancePath} · {finding.evidence}</div></div>)}<div className={styles.provenance}><span>proposal {review.proposalId.slice(0, 8)}</span><span>model {review.model}</span><span>context {review.contextHash.slice(0, 12)}…</span></div></div>}
          </div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Pohled zákazníka', 'Customer view')}</div><h2 className={styles.panelTitle}><Boxes size={15} />{t('Kontextový náhled', 'Contextual preview')}</h2></div><span className="badge badge-neutral">{selectedOffering?.market.countries?.join(', ') || t('globální', 'global')}</span></div>
        <div className={styles.panelBody}><div className={styles.preview}><div className={styles.previewEyebrow}>{selectedOffering?.market.channels?.join(' · ') || t('Všechny kanály', 'All channels')}</div><h3 className={styles.previewName}>{String((parsedDraft?.name as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.name as Record<string, string> | undefined)?.en ?? selectedOffering?.code ?? '—')}</h3><p className={styles.previewCopy}>{String((parsedDraft?.description as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.description as Record<string, string> | undefined)?.en ?? t('Doplňte popis, aby byl dopad nabídky srozumitelný pro zákazníka i kontrolora.', 'Add a description so the offer is understandable to both customer and reviewer.'))}</p><div className={styles.previewFoot}>{t('Trh:', 'Market:')} {selectedOffering?.market.countries?.join(', ') || t('všechny země', 'all countries')} · {t('Ceny:', 'Prices:')} {Array.isArray(parsedDraft?.prices) ? parsedDraft.prices.length : 0}</div></div></div>
      </section>
    </div>
  </AuthGuard>
}
