// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Boxes, CheckCircle2, FileJson, Plus, RefreshCw, Send, ShieldCheck } from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { catalogRevisionEditorDocument, diffCatalogDocuments } from '@/lib/catalog-structural-diff'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  catalogV2Operation, type CatalogSchema, type Offering, type OfferingRequest, type ProductRevision,
  type RevisionRequest, type Specification, type SpecificationRequest, type ValidateCatalogResponse,
} from '@/lib/product-catalog-v2'

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

  useEffect(() => { void load() }, [load])
  useEffect(() => { void loadRevisions(offeringId) }, [offeringId, loadRevisions])
  useEffect(() => {
    if (!selectedRevision) { setDraftText(''); return }
    setDraftText(JSON.stringify(catalogRevisionEditorDocument(selectedRevision), null, 2))
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

  return <AuthGuard permission="catalog:read">
    <div className="page-header">
      <div><div className="breadcrumb"><span>OpenBank</span><span className="breadcrumb-sep">/</span><span>Product Studio</span></div>
        <h1 className="page-title" style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Boxes size={19} />Product Studio</h1>
        <p className="page-subtitle">{t('Odvětvově neutrální tvorba, validace a four-eyes publikace', 'Industry-neutral authoring, validation and four-eyes publication')}</p>
      </div>
      <button className="btn btn-secondary" disabled={busy} onClick={() => void load()}><RefreshCw size={13} />{t('Obnovit', 'Refresh')}</button>
    </div>

    {message && <pre className="card" style={{ padding: 12, whiteSpace: 'pre-wrap', color: message.includes('valid') || message.includes('vytvořen') || message.includes('uložen') ? 'var(--success-text)' : 'var(--text-secondary)' }}>{message}</pre>}

    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(260px, .8fr) minmax(320px, 1fr) minmax(420px, 1.5fr)', gap: 14 }}>
      <section className="card" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 14 }}><FileJson size={14} /> {t('Typy a identity', 'Types & identities')}</h2>
        <select className="input" style={{ width: '100%', marginBottom: 8 }} value={specificationId} onChange={event => { setSpecificationId(event.target.value); setOfferingId('') }}>
          <option value="">{t('Vyberte specifikaci', 'Select specification')}</option>
          {specifications.map(item => <option key={item.id} value={item.id}>{item.code} · {item.schemaRef.id}:{item.schemaRef.version}</option>)}
        </select>
        <Can permission="catalog:author">
          <select className="input" style={{ width: '100%', marginBottom: 8 }} value={newSpecSchema} onChange={event => setNewSpecSchema(event.target.value)}>
            {schemas.map(item => <option key={`${item.id}:${item.version}`} value={`${item.id}:${item.version}`}>{item.id}:{item.version}</option>)}
          </select>
          <div style={{ display: 'flex', gap: 6 }}><input className="input" value={newSpecCode} onChange={e => setNewSpecCode(e.target.value)} placeholder="TERM_LIFE" />
            <button className="btn btn-secondary" onClick={createSpecification}><Plus size={12} /></button></div>
        </Can>
        <pre style={{ maxHeight: 310, overflow: 'auto', fontSize: 10, background: 'var(--surface-2)', padding: 10, marginTop: 12 }}>
          {JSON.stringify(compatibleSchemas.at(-1)?.document ?? {}, null, 2)}
        </pre>
      </section>

      <section className="card" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 14 }}>{t('Nabídky a historie', 'Offerings & history')}</h2>
        <select className="input" style={{ width: '100%', marginBottom: 8 }} value={offeringId} onChange={e => setOfferingId(e.target.value)}>
          <option value="">{t('Vyberte nabídku', 'Select offering')}</option>
          {offerings.filter(item => !specificationId || item.specificationId === specificationId).map(item => <option key={item.id} value={item.id}>{item.code}</option>)}
        </select>
        <Can permission="catalog:author">
          <div style={{ display: 'flex', gap: 6 }}><input className="input" value={newOfferingCode} onChange={e => setNewOfferingCode(e.target.value)} placeholder="TERM_LIFE_CZ_WEB" />
            <button className="btn btn-secondary" onClick={createOffering}><Plus size={12} /></button></div>
          <button className="btn btn-primary" style={{ width: '100%', marginTop: 8 }} disabled={!selectedOffering} onClick={createDraft}><Plus size={12} />{t('Nová revize', 'New revision')}</button>
        </Can>
        <div style={{ marginTop: 12 }}>{revisions.map(item => <button key={item.id} onClick={() => setRevisionId(item.id)} style={{ display: 'flex', width: '100%', justifyContent: 'space-between', padding: 9, border: '1px solid var(--border)', background: revisionId === item.id ? 'var(--surface-3)' : 'transparent', color: 'var(--text-primary)' }}>
          <span>#{item.number} · {item.schemaRef.version}</span><Badge state={item.state} />
        </button>)}</div>
      </section>

      <section className="card" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 14 }}>{t('Schema-governed draft', 'Schema-governed draft')}</h2>
        <Can permission="catalog:author" fallback={
          <textarea className="input" style={{ width: '100%', minHeight: 390, fontFamily: 'var(--font-mono)', fontSize: 11 }} value={draftText} disabled />
        }>
          <textarea className="input" style={{ width: '100%', minHeight: 390, fontFamily: 'var(--font-mono)', fontSize: 11 }} value={draftText} onChange={e => setDraftText(e.target.value)} disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} />
          <div style={{ display: 'flex', gap: 7, marginTop: 8 }}>
            <button className="btn btn-secondary" disabled={!selectedRevision} onClick={() => void validateDraft()}><CheckCircle2 size={13} />{t('Validovat', 'Validate')}</button>
            <button className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} onClick={saveDraft}><Send size={13} />{t('Uložit', 'Save')}</button>
          </div>
        </Can>
        <Can permission="catalog:publish">
          <div style={{ borderTop: '1px solid var(--border)', marginTop: 14, paddingTop: 14 }}>
            <div style={{ fontSize: 11, color: 'var(--text-tertiary)', marginBottom: 6 }}><ShieldCheck size={12} /> {t('Publikovat musí jiný přihlášený uživatel než poslední autor.', 'A different authenticated user than the last author must publish.')}</div>
            <div style={{ display: 'flex', gap: 7 }}><input className="input" style={{ flex: 1 }} value={publishReason} onChange={e => setPublishReason(e.target.value)} placeholder={t('Důvod schválení', 'Approval reason')} />
              <button className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} onClick={publish}><ShieldCheck size={13} />{t('Publikovat', 'Publish')}</button></div>
          </div>
        </Can>
      </section>
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 14 }}>
      <section className="card" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 14 }}>{t('Draft vs. živá revize', 'Draft vs live revision')}</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <div><div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>DRAFT</div>
            <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: 10, background: 'var(--surface-2)', padding: 9 }}>{JSON.stringify(parsedDraft ?? {}, null, 2)}</pre></div>
          <div><div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>LIVE</div>
            <pre style={{ maxHeight: 300, overflow: 'auto', fontSize: 10, background: 'var(--surface-2)', padding: 9 }}>{JSON.stringify(publishedRevision?.content ?? {}, null, 2)}</pre></div>
        </div>
        <div style={{ marginTop: 10, fontSize: 11, fontWeight: 700 }}>
          {structuralDiff.length === 0
            ? t('Bez změn proti živé revizi', 'No changes from the live revision')
            : t(`${structuralDiff.length} změn`, `${structuralDiff.length} changes`)}
        </div>
        {structuralDiff.length > 0 && <ul style={{ maxHeight: 180, overflow: 'auto', paddingLeft: 18 }}>
          {structuralDiff.map(entry => <li key={`${entry.kind}:${entry.path}`} style={{ fontSize: 11 }}>
            <strong>{entry.kind}</strong> <code>{entry.path}</code>
          </li>)}
        </ul>}
      </section>
      <section className="card" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 14 }}>{t('Kontextový náhled nabídky', 'Contextual offering preview')}</h2>
        <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
          {selectedOffering?.market.countries?.join(', ') || t('Všechny země', 'All countries')} · {' '}
          {selectedOffering?.market.channels?.join(', ') || t('Všechny kanály', 'All channels')}
        </div>
        <h3 style={{ marginTop: 12 }}>{String((parsedDraft?.name as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.name as Record<string, string> | undefined)?.en ?? selectedOffering?.code ?? '—')}</h3>
        <p style={{ color: 'var(--text-secondary)' }}>{String((parsedDraft?.description as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.description as Record<string, string> | undefined)?.en ?? '')}</p>
        <pre style={{ maxHeight: 230, overflow: 'auto', fontSize: 10, background: 'var(--surface-2)', padding: 9 }}>{JSON.stringify(parsedDraft?.prices ?? [], null, 2)}</pre>
      </section>
    </div>
  </AuthGuard>
}
