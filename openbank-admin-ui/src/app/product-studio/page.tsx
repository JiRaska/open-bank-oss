// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Bot, Boxes, CheckCircle2, CircleAlert, Eye, FileJson, Link2, ListChecks, LockKeyhole, Plus, RefreshCw, Send, ShieldCheck, Sparkles, X } from 'lucide-react'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'
import { canReviewPrivateCatalogDraft, type AgentModelDescriptor } from '@/lib/catalog-review-capability'
import { catalogFieldValue, catalogSchemaFields, type CatalogSchemaField, withCatalogFieldValue } from '@/lib/catalog-schema-form'
import { catalogRevisionEditorDocument, diffCatalogDocuments } from '@/lib/catalog-structural-diff'
import {
  addOfferingRelationship,
  defaultMarketContextInput,
  marketContextFromInput,
  removeOfferingRelationship,
  type MarketContextInput,
} from '@/lib/catalog-offer-composition'
import { proposeBundleComponents } from '@/lib/catalog-bundle-proposals'
import { explainOfferSelection, simulateBundleImpact } from '@/lib/catalog-offer-intelligence'
import { selectOffersForMarket } from '@/lib/catalog-offer-selection'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { claimSingleFlight, releaseSingleFlight } from '@/lib/single-flight'
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

const relationshipKinds = ['BUNDLE', 'ADD_ON', 'REPLACEMENT', 'DEPENDENCY', 'COMPATIBLE_WITH'] as const
type RelationshipKind = typeof relationshipKinds[number]

interface DraftRelationship {
  kind: RelationshipKind
  targetOfferingId: string
}

type CatalogMutation = 'create-specification' | 'create-offering' | 'create-revision' | 'save-draft' | 'publish'

function isDraftRelationship(value: unknown): value is DraftRelationship {
  return Boolean(value) && typeof value === 'object' &&
    relationshipKinds.includes((value as DraftRelationship).kind) &&
    typeof (value as DraftRelationship).targetOfferingId === 'string'
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
  const [activeMutation, setActiveMutation] = useState<CatalogMutation | null>(null)
  const mutationInFlight = useRef(false)
  const [newSpecCode, setNewSpecCode] = useState('')
  const [newOfferingCode, setNewOfferingCode] = useState('')
  const [marketContextInput, setMarketContextInput] = useState<MarketContextInput>(defaultMarketContextInput)
  const [previewContextInput, setPreviewContextInput] = useState<MarketContextInput>(defaultMarketContextInput)
  const [relationshipTargetId, setRelationshipTargetId] = useState('')
  const [relationshipKind, setRelationshipKind] = useState<RelationshipKind>('BUNDLE')
  const [publishReason, setPublishReason] = useState('')
  const [newSpecSchema, setNewSpecSchema] = useState('')
  const [review, setReview] = useState<CatalogReview | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [validationState, setValidationState] = useState<'idle' | 'valid' | 'invalid'>('idle')
  const [reviewCapability, setReviewCapability] = useState<'checking' | 'available' | 'unavailable'>('checking')

  const selectedSpec = specifications.find(item => item.id === specificationId)
  const selectedOffering = offerings.find(item => item.id === offeringId)
  const selectedRevision = revisions.find(item => item.id === revisionId)
  const publishedRevision = revisions.find(item => item.state === 'PUBLISHED')
  const parsedDraft = useMemo(() => {
    try { return draftText ? JSON.parse(draftText) as Record<string, unknown> : null } catch { return null }
  }, [draftText])
  const draftRelationships = useMemo(
    () => Array.isArray(parsedDraft?.relationships) ? parsedDraft.relationships.filter(isDraftRelationship) : [],
    [parsedDraft],
  )
  const relationshipCandidates = useMemo(
    () => offerings.filter(item => item.id !== selectedOffering?.id),
    [offerings, selectedOffering?.id],
  )
  const bundleProposals = useMemo(
    () => selectedOffering
      ? proposeBundleComponents(selectedOffering, offerings, draftRelationships.map(item => item.targetOfferingId))
      : [],
    [draftRelationships, offerings, selectedOffering],
  )
  const compatibleSchemas = useMemo(
    () => schemas.filter(item => !selectedSpec || item.id === selectedSpec.schemaRef.id),
    [schemas, selectedSpec],
  )
  const activeSchema = selectedRevision
    ? schemas.find(item => item.id === selectedRevision.schemaRef.id && item.version === selectedRevision.schemaRef.version)
    : compatibleSchemas.at(-1)
  const guidedFields = useMemo(
    () => catalogSchemaFields(activeSchema?.document),
    [activeSchema],
  )
  const liveDocument = publishedRevision ? catalogRevisionEditorDocument(publishedRevision) : null
  const structuralDiff = diffCatalogDocuments(liveDocument, parsedDraft)
  const offerSelections = useMemo(
    () => selectOffersForMarket(
      offerings.filter(item => !specificationId || item.specificationId === specificationId),
      marketContextFromInput(previewContextInput),
    ),
    [offerings, specificationId, previewContextInput],
  )
  const selectedOfferSelection = offerSelections.find(selection => selection.offering.id === offeringId)
  const selectedOfferExplanation = selectedOfferSelection
    ? explainOfferSelection(selectedOfferSelection, language)
    : null
  const bundleImpacts = useMemo(
    () => selectedOffering
      ? bundleProposals.slice(0, 3).map(proposal => ({
          id: proposal.offering.id,
          impact: simulateBundleImpact(
            selectedOffering,
            proposal.offering,
            marketContextFromInput(previewContextInput),
            language,
          ),
        }))
      : [],
    [bundleProposals, language, previewContextInput, selectedOffering],
  )
  const draftCount = revisions.filter(item => item.state === 'DRAFT').length
  const publishedCount = revisions.filter(item => item.state === 'PUBLISHED').length
  const readiness = [
    { label: t('Čitelný návrh', 'Readable draft'), ready: Boolean(parsedDraft) },
    { label: t('Schéma ověřeno', 'Schema verified'), ready: validationState === 'valid' },
    { label: t('Živá reference k porovnání', 'Live baseline available'), ready: Boolean(publishedRevision) },
    { label: t('Rozdíly návrhu jsou viditelné', 'Draft differences are visible'), ready: Boolean(parsedDraft && structuralDiff.length > 0) },
  ]

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
    const controller = new AbortController()
    void fetch('/api/agent/chat', { signal: controller.signal, cache: 'no-store' })
      .then(async response => response.ok ? response.json() as Promise<{ models?: AgentModelDescriptor[] }> : null)
      .then(result => setReviewCapability(canReviewPrivateCatalogDraft(result?.models ?? []) ? 'available' : 'unavailable'))
      .catch(() => setReviewCapability('unavailable'))
    return () => controller.abort()
  }, [])
  useEffect(() => {
    const nextDraft = selectedRevision
      ? JSON.stringify(catalogRevisionEditorDocument(selectedRevision), null, 2)
      : ''
    const task = window.setTimeout(() => setDraftText(nextDraft), 0)
    return () => window.clearTimeout(task)
  }, [selectedRevision])

  const updateGuidedField = (field: CatalogSchemaField, raw: string | boolean) => {
    if (!parsedDraft) return
    const value = field.type === 'boolean' ? raw === true : field.type === 'integer' || field.type === 'number'
      ? (raw === '' ? '' : Number(raw)) : raw
    setDraftText(JSON.stringify(withCatalogFieldValue(parsedDraft, field.path, value), null, 2))
    setValidationState('idle')
    setReview(null)
  }

  const run = async (operation: CatalogMutation, work: () => Promise<unknown>, success: string) => {
    if (!claimSingleFlight(mutationInFlight)) return
    setActiveMutation(operation); setMessage('')
    try { await work(); setMessage(success); await load(); if (offeringId) await loadRevisions(offeringId) }
    catch (error) { setMessage(error instanceof Error ? error.message : String(error)) }
    finally { releaseSingleFlight(mutationInFlight); setActiveMutation(null) }
  }

  const createSpecification = () => {
    const schema = schemas.find(item => `${item.id}:${item.version}` === newSpecSchema)
    if (!schema || !newSpecCode.trim()) return
    const body: SpecificationRequest = {
      code: newSpecCode.trim().toUpperCase(), schemaRef: { id: schema.id, version: schema.version },
    }
    void run('create-specification', () => catalogV2Operation('createSpecificationV2', {
      body,
    }), t('Specifikace vytvořena', 'Specification created'))
  }

  const createOffering = () => {
    if (!selectedSpec || !newOfferingCode.trim()) return
    const body: OfferingRequest = {
      specificationId: selectedSpec.id, code: newOfferingCode.trim().toUpperCase(),
      market: marketContextFromInput(marketContextInput),
    }
    void run('create-offering', () => catalogV2Operation('createOfferingV2', {
      body,
    }), t('Nabídka vytvořena', 'Offering created'))
  }

  const updateMarketContext = (field: keyof MarketContextInput, value: string) => {
    setMarketContextInput(current => ({ ...current, [field]: value }))
  }

  const updatePreviewContext = (field: keyof MarketContextInput, value: string) => {
    setPreviewContextInput(current => ({ ...current, [field]: value }))
  }

  const addRelationship = () => {
    if (!parsedDraft || !selectedOffering || !relationshipTargetId) return
    try {
      setDraftText(JSON.stringify(addOfferingRelationship(parsedDraft, selectedOffering.id, {
        kind: relationshipKind, targetOfferingId: relationshipTargetId,
      }), null, 2))
      setValidationState('idle')
      setReview(null)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error))
    }
  }

  const removeRelationship = (relationship: DraftRelationship) => {
    if (!parsedDraft) return
    setDraftText(JSON.stringify(removeOfferingRelationship(parsedDraft, relationship), null, 2))
    setValidationState('idle')
    setReview(null)
  }

  const applyBundleProposal = (targetOfferingId: string) => {
    if (!parsedDraft || !selectedOffering) return
    try {
      setDraftText(JSON.stringify(addOfferingRelationship(parsedDraft, selectedOffering.id, {
        kind: 'BUNDLE', targetOfferingId,
      }), null, 2))
      setValidationState('idle')
      setReview(null)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error))
    }
  }

  const createDraft = () => {
    const schema = compatibleSchemas.at(-1)
    if (!selectedOffering || !schema) return
    const body: RevisionRequest = {
      schemaRef: { id: schema.id, version: schema.version }, name: { en: selectedOffering.code },
      attributes: seed(schema.document) as Record<string, unknown>,
      prices: [], eligibility: [], relationships: [], documentCodes: [],
    }
    void run('create-revision', () => catalogV2Operation('createOfferingRevisionV2', {
      pathParameters: { id: selectedOffering.id }, body,
    }), t('Draft vytvořen; doplňte povinná pole schématu', 'Draft created; complete the schema-required fields'))
  }

  const saveDraft = () => {
    if (!selectedRevision || selectedRevision.state !== 'DRAFT') return
    if (!parsedDraft) { setMessage(t('Draft není validní JSON', 'Draft is not valid JSON')); return }
    void run('save-draft', () => catalogV2Operation('replaceOfferingRevisionV2', {
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
      setValidationState(result.valid ? 'valid' : 'invalid')
      setMessage(result.valid ? t('Schéma je validní', 'Schema validation passed') : result.violations.map(v => `${v.instancePath}: ${v.message}`).join('\n'))
    } catch (error) { setValidationState('invalid'); setMessage(error instanceof Error ? error.message : String(error)) }
  }

  const publish = () => {
    if (!selectedRevision || !publishReason.trim()) return
    void run('publish', () => catalogV2Operation('publishOfferingRevisionV2', {
      pathParameters: { offeringId: selectedRevision.offeringId, revisionId: selectedRevision.id },
      headers: { 'If-Match': `"${selectedRevision.revision}"` },
      body: { reason: publishReason.trim() },
    }), t('Revize publikována nezávislým schvalovatelem', 'Revision published by an independent checker'))
  }

  const reviewDraft = async () => {
    if (!selectedRevision || selectedRevision.state !== 'DRAFT' || reviewCapability !== 'available') return
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
      const detail = error instanceof Error ? error.message : String(error)
      setReviewCapability(detail === 'model unavailable' ? 'unavailable' : reviewCapability)
      setMessage(detail === 'model unavailable'
        ? t('Privátní AI kontrola není v tomto prostředí dostupná. Návrh zůstává uvnitř platformy; použijte kontrolu schématu a dopadu změny.', 'Private AI review is unavailable in this environment. The draft stays inside the platform; use schema validation and change impact instead.')
        : detail)
    } finally {
      setReviewing(false)
    }
  }

  return <AuthGuard permission="catalog:read">
    <section className={styles.hero}>
      <div className={styles.heroTop}>
        <div>
          <div className={styles.eyebrow}><Sparkles size={13} aria-hidden="true" /> {t('Product intelligence studio', 'Product intelligence studio')}</div>
          <h1 className={styles.heroTitle}>{t('Od nápadu k důvěryhodné nabídce.', 'From product idea to a trusted offer.')}</h1>
          <p className={styles.heroCopy}>{t(
            'Řiďte život nabídky na jednom místě: typ, kontext trhu, schéma, dopad změn i nezávislé schválení. Inteligence radí, člověk rozhoduje.',
            'Run the whole offer lifecycle in one place: type, market context, schema, change impact and independent approval. Intelligence advises; people decide.',
          )}</p>
        </div>
        <button type="button" className={`btn btn-secondary ${styles.refresh}`} disabled={busy || activeMutation !== null} aria-busy={busy} onClick={() => void load()}><RefreshCw size={13} aria-hidden="true" />{busy ? t('Obnovuji…', 'Refreshing…') : t('Obnovit data', 'Refresh data')}</button>
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
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Katalog', 'Catalog')}</div><h2 className={styles.panelTitle}><FileJson size={15} aria-hidden="true" />{t('Typ a identita', 'Type and identity')}</h2></div><span className="badge badge-accent">v2</span></div>
        <div className={styles.panelBody}>
          <label className={styles.smallLabel} htmlFor="studio-specification">{t('Specifikace', 'Specification')}</label>
          <select id="studio-specification" className="input" value={specificationId} onChange={event => { setSpecificationId(event.target.value); setOfferingId('') }}>
            <option value="">{t('Vyberte specifikaci', 'Select specification')}</option>
            {specifications.map(item => <option key={item.id} value={item.id}>{item.code} · {item.schemaRef.id}:{item.schemaRef.version}</option>)}
          </select>
          <Can permission="catalog:author">
            <label className={styles.smallLabel} htmlFor="studio-new-spec-schema">{t('Nová specifikace', 'New specification')}</label>
            <select id="studio-new-spec-schema" className="input" value={newSpecSchema} onChange={event => setNewSpecSchema(event.target.value)}>
              {schemas.map(item => <option key={`${item.id}:${item.version}`} value={`${item.id}:${item.version}`}>{item.id}:{item.version}</option>)}
            </select>
            <div style={{ display: 'flex', gap: 7, marginTop: 7 }}><input id="studio-new-spec-code" className="input" aria-label={t('Kód nové specifikace', 'New specification code')} value={newSpecCode} onChange={e => setNewSpecCode(e.target.value)} placeholder="TERM_LIFE" /><button type="button" className="btn btn-secondary" disabled={activeMutation !== null || !newSpecCode.trim()} aria-busy={activeMutation === 'create-specification'} onClick={createSpecification} aria-label={activeMutation === 'create-specification' ? t('Vytvářím specifikaci', 'Creating specification') : t('Vytvořit specifikaci', 'Create specification')}><Plus size={13} aria-hidden="true" /></button></div>
          </Can>
          <div className={styles.schemaHint}>{t('Aktivní schema:', 'Active schema:')} <strong>{activeSchema ? `${activeSchema.id}:${activeSchema.version}` : '—'}</strong><br />{t('Formulář respektuje verzi schématu; publikovaný obsah se nemění.', 'The form respects its schema version; published content never mutates.')}</div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Nabídka', 'Offer')}</div><h2 className={styles.panelTitle}><Boxes size={15} aria-hidden="true" />{t('Kontext a historie', 'Context and history')}</h2></div>{selectedOffering && <span className="badge badge-info">{selectedOffering.code}</span>}</div>
        <div className={styles.panelBody}>
          <label className={styles.smallLabel} htmlFor="studio-offering">{t('Nabídka', 'Offer')}</label>
          <select id="studio-offering" className="input" value={offeringId} onChange={e => setOfferingId(e.target.value)}>
            <option value="">{t('Vyberte nabídku', 'Select offering')}</option>
            {offerings.filter(item => !specificationId || item.specificationId === specificationId).map(item => <option key={item.id} value={item.id}>{item.code}</option>)}
          </select>
          <Can permission="catalog:author">
            <div style={{ display: 'flex', gap: 7, marginTop: 8 }}><input id="studio-new-offering-code" className="input" aria-label={t('Kód nové nabídky', 'New offer code')} value={newOfferingCode} onChange={e => setNewOfferingCode(e.target.value)} placeholder="TERM_LIFE_CZ_WEB" /><button type="button" className="btn btn-secondary" disabled={activeMutation !== null || !selectedSpec || !newOfferingCode.trim()} aria-busy={activeMutation === 'create-offering'} onClick={createOffering} aria-label={activeMutation === 'create-offering' ? t('Vytvářím nabídku', 'Creating offer') : t('Vytvořit nabídku', 'Create offer')}><Plus size={13} aria-hidden="true" /></button></div>
            <div className={styles.marketContext}>
              <div className={styles.marketTitle}><LockKeyhole size={13} aria-hidden="true" /><span>{t('Dostupnost nabídky', 'Offer availability')}</span></div>
              <p>{t('Neveřejná nabídka používá obchodní segment, nikoli identitu zákazníka. Katalog neobsahuje osobní údaje.', 'A private offer uses a commercial segment, never a customer identity. The catalog contains no personal data.')}</p>
              <div className={styles.marketGrid}>
                <label><span>{t('Značky', 'Brands')}</span><input className="input" value={marketContextInput.brands} onChange={event => updateMarketContext('brands', event.target.value)} placeholder="retail" /></label>
                <label><span>{t('Země', 'Countries')}</span><input className="input" value={marketContextInput.countries} onChange={event => updateMarketContext('countries', event.target.value)} placeholder="CZ, DE" /></label>
                <label><span>{t('Kanály', 'Channels')}</span><input className="input" value={marketContextInput.channels} onChange={event => updateMarketContext('channels', event.target.value)} placeholder="WEB, BRANCH" /></label>
                <label><span>{t('Segmenty', 'Segments')}</span><input className="input" value={marketContextInput.segments} onChange={event => updateMarketContext('segments', event.target.value)} placeholder="employee, premium" /></label>
                <label><span>{t('Lokality', 'Locales')}</span><input className="input" value={marketContextInput.locales} onChange={event => updateMarketContext('locales', event.target.value)} placeholder="cs-CZ, en" /></label>
              </div>
            </div>
            <button type="button" className="btn btn-primary" style={{ width: '100%', marginTop: 8 }} disabled={!selectedOffering || activeMutation !== null} aria-busy={activeMutation === 'create-revision'} onClick={createDraft}><Plus size={13} aria-hidden="true" />{activeMutation === 'create-revision' ? t('Zakládám revizi…', 'Creating revision…') : t('Založit novou revizi', 'Create a new revision')}</button>
          </Can>
          <div className={styles.revisionList}>{revisions.length === 0 && <div className={styles.schemaHint}>{t('Vyberte nabídku a otevřete její rozhodovací historii.', 'Select an offer to open its decision history.')}</div>}{revisions.map(item => <button type="button" key={item.id} aria-pressed={revisionId === item.id} onClick={() => { setRevisionId(item.id); setReview(null) }} className={`${styles.revision} ${revisionId === item.id ? styles.revisionSelected : ''}`}>
            <span><strong>#{item.number}</strong> <span style={{ color: 'var(--text-tertiary)', fontSize: 11 }}>· schema {item.schemaRef.version}</span></span><Badge state={item.state} />
          </button>)}</div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Pracovní revize', 'Working revision')}</div><h2 className={styles.panelTitle}><Send size={15} aria-hidden="true" />{t('Návrh řízený schématem', 'Schema-governed draft')}</h2></div>{selectedRevision && <Badge state={selectedRevision.state} />}</div>
        <div className={styles.panelBody}>
          <div className={styles.draftBanner}><CheckCircle2 size={15} aria-hidden="true" /><span>{selectedRevision?.state === 'DRAFT' ? t('Draft lze ukládat a ověřovat. Publikaci provede jiný uživatel.', 'This draft can be saved and checked. A different user performs publication.') : t('Toto je neměnný historický záznam.', 'This is an immutable historical record.')}</span></div>
          <Can permission="catalog:author" fallback={<textarea className={`input ${styles.editor}`} value={draftText} disabled />}>
            {parsedDraft && <div className={styles.composition}>
              <div className={styles.compositionHead}><div><span><Link2 size={13} aria-hidden="true" />{t('Složení nabídky', 'Offer composition')}</span><p>{t('Bundle přidá existující publikovatelnou nabídku jako komponentu. Služba při publikaci znovu ověří existenci, účinnost i cykly.', 'A bundle adds an existing publishable offer as a component. The service rechecks existence, effectiveness and cycles at publication.')}</p></div><span className="badge badge-neutral">{draftRelationships.length}</span></div>
              {selectedRevision?.state === 'DRAFT' && <div className={styles.compositionControls}>
                <label className="sr-only" htmlFor="studio-relationship-kind">{t('Typ vazby', 'Relationship type')}</label><select id="studio-relationship-kind" className="input" value={relationshipKind} onChange={event => setRelationshipKind(event.target.value as RelationshipKind)}>{relationshipKinds.map(kind => <option key={kind}>{kind}</option>)}</select>
                <label className="sr-only" htmlFor="studio-relationship-target">{t('Cílová nabídka', 'Target offer')}</label><select id="studio-relationship-target" className="input" value={relationshipTargetId} onChange={event => setRelationshipTargetId(event.target.value)}><option value="">{t('Vyberte nabídku', 'Select an offer')}</option>{relationshipCandidates.map(item => <option key={item.id} value={item.id}>{item.code}</option>)}</select>
                <button type="button" className="btn btn-secondary" disabled={!relationshipTargetId || activeMutation !== null} onClick={addRelationship}><Plus size={13} aria-hidden="true" />{t('Přidat', 'Add')}</button>
              </div>}
              {selectedRevision?.state === 'DRAFT' && <div className={styles.bundleProposals}>
                <div className={styles.bundleProposalsHead}>
                  <span><Sparkles size={13} aria-hidden="true" />{t('Doporučené komponenty', 'Suggested components')}</span>
                  <small>{t('Deterministicky podle kompatibility trhu; návrh nic sám neuloží.', 'Deterministic market compatibility only; a proposal never saves itself.')}</small>
                </div>
                {bundleProposals.length === 0
                  ? <div className={styles.bundleProposalEmpty}>{t('Žádná další bezpečně kompatibilní komponenta.', 'No further safely compatible component.')}</div>
                  : <div className={styles.bundleProposalList}>{bundleProposals.slice(0, 3).map(proposal => <div className={styles.bundleProposal} key={proposal.offering.id}>
                    <div><strong>{proposal.offering.code}</strong><small>{proposal.reasons.slice(0, 2).join(' · ')}</small><em>{bundleImpacts.find(item => item.id === proposal.offering.id)?.impact.summary}</em></div>
                    <button type="button" className="btn btn-secondary" disabled={activeMutation !== null} onClick={() => applyBundleProposal(proposal.offering.id)}><Plus size={13} aria-hidden="true" />{t('Navrhnout', 'Propose')}</button>
                  </div>)}</div>}
              </div>}
              {draftRelationships.length === 0 ? <div className={styles.compositionEmpty}>{t('Žádné vazby. Samostatná nabídka zůstává beze změny.', 'No connections. A standalone offer remains unchanged.')}</div> : <div className={styles.relationships}>{draftRelationships.map(relationship => {
                const target = offerings.find(item => item.id === relationship.targetOfferingId)
                return <div className={styles.relationship} key={`${relationship.kind}:${relationship.targetOfferingId}`}><span className="badge badge-info">{relationship.kind}</span><span>{target?.code ?? relationship.targetOfferingId}</span>{selectedRevision?.state === 'DRAFT' && <button type="button" aria-label={t('Odebrat vazbu', 'Remove relationship')} disabled={activeMutation !== null} className={styles.removeRelationship} onClick={() => removeRelationship(relationship)}><X size={13} aria-hidden="true" /></button>}</div>
              })}</div>}
            </div>}
            {guidedFields.length > 0 && parsedDraft && <div className={styles.guidedForm}>
              <div className={styles.guidedHead}><span><Sparkles size={13} aria-hidden="true" />{t('Průvodce povinnými údaji', 'Guided essentials')}</span><small>{t('Pouze skalární pole; pole a složité struktury zůstávají níže v expertním dokumentu.', 'Scalar fields only; arrays and complex structures remain in the expert document below.')}</small></div>
              <div className={styles.fieldGrid}>{guidedFields.map(field => {
                const value = catalogFieldValue(parsedDraft, field.path)
                const id = `catalog-field-${field.path.join('-')}`
                return <label key={id} className={styles.field}><span>{field.label}{field.required && <b aria-label={t('Povinné', 'Required')}> *</b>}</span>
                  {field.type === 'boolean' ? <input id={id} type="checkbox" checked={value === true} onChange={event => updateGuidedField(field, event.target.checked)} disabled={selectedRevision?.state !== 'DRAFT'} />
                    : field.choices.length > 0 ? <select id={id} className="input" value={String(value ?? '')} onChange={event => updateGuidedField(field, event.target.value)} disabled={selectedRevision?.state !== 'DRAFT'}><option value="">{t('Vyberte hodnotu', 'Select a value')}</option>{field.choices.map(choice => <option key={choice}>{choice}</option>)}</select>
                      : <input id={id} className="input" inputMode={field.type === 'integer' || field.type === 'number' ? 'decimal' : undefined} value={String(value ?? '')} onChange={event => updateGuidedField(field, event.target.value)} disabled={selectedRevision?.state !== 'DRAFT'} />}
                  {field.description && <small>{field.description}</small>}
                </label>
              })}</div>
            </div>}
            <details className={styles.expertDetails}><summary>{t('Expert režim · úplný dokument', 'Expert mode · full document')}</summary>
              <textarea className={`input ${styles.editor}`} value={draftText} onChange={e => { setDraftText(e.target.value); setValidationState('idle'); setReview(null) }} disabled={!selectedRevision || selectedRevision.state !== 'DRAFT'} />
            </details>
            <div className={styles.actions}><button type="button" className="btn btn-secondary" disabled={!selectedRevision || activeMutation !== null} onClick={() => void validateDraft()}><CheckCircle2 size={13} aria-hidden="true" />{t('Ověřit schéma', 'Validate schema')}</button><button type="button" className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT' || activeMutation !== null} aria-busy={activeMutation === 'save-draft'} onClick={saveDraft}><Send size={13} aria-hidden="true" />{activeMutation === 'save-draft' ? t('Ukládám draft…', 'Saving draft…') : t('Uložit draft', 'Save draft')}</button></div>
          </Can>
          <Can permission="catalog:publish"><div className={styles.approvalPanel}><div className={styles.approvalHead}><ShieldCheck size={15} aria-hidden="true" /><span>{t('Nezávislé schválení', 'Independent approval')}</span></div><p>{t('Publikace je nevratné rozhodnutí. Služba ověří, že autor a schvalovatel jsou rozdílné identity — tento formulář to nemůže obejít.', 'Publication is an irreversible decision. The service verifies that maker and checker are different identities — this form cannot bypass it.')}</p><div className={styles.approvalMeta}><span>{t('Autor draftu', 'Draft maker')}: <b>{selectedRevision?.makerId ?? '—'}</b></span><span>{t('Stav ověření', 'Validation')}: <b>{validationState === 'valid' ? t('ověřeno', 'verified') : t('čeká na ověření', 'awaiting validation')}</b></span></div><div style={{ display: 'flex', gap: 7 }}><label className="sr-only" htmlFor="studio-publish-reason">{t('Důvod schválení', 'Approval reason')}</label><input id="studio-publish-reason" className="input" value={publishReason} onChange={e => setPublishReason(e.target.value)} placeholder={t('Důvod schválení', 'Approval reason')} /><button type="button" className="btn btn-primary" disabled={!selectedRevision || selectedRevision.state !== 'DRAFT' || !publishReason.trim() || activeMutation !== null} aria-busy={activeMutation === 'publish'} onClick={publish}><ShieldCheck size={13} aria-hidden="true" />{activeMutation === 'publish' ? t('Publikuji…', 'Publishing…') : t('Publikovat', 'Publish')}</button></div></div></Can>
        </div>
      </section>
    </div>

    <div className={styles.lowerGrid}>
      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Dopad změny', 'Change impact')}</div><h2 className={styles.panelTitle}><Eye size={15} aria-hidden="true" />{t('Draft proti živé nabídce', 'Draft against live offer')}</h2></div><span className={`badge ${structuralDiff.length ? 'badge-warning' : 'badge-success'}`}>{structuralDiff.length ? t(`${structuralDiff.length} změn`, `${structuralDiff.length} changes`) : t('Bez rozdílu', 'No difference')}</span></div>
        <div className={styles.panelBody}>
          <div className={styles.readiness}><div className={styles.readinessHead}><ListChecks size={15} aria-hidden="true" />{t('Připravenost k rozhodnutí', 'Decision readiness')}</div>{readiness.map(item => <div className={styles.readinessRow} key={item.label}><span>{item.ready ? <CheckCircle2 size={13} aria-hidden="true" /> : <CircleAlert size={13} aria-hidden="true" />}</span><span>{item.label}</span><b>{item.ready ? t('hotovo', 'ready') : t('čeká', 'pending')}</b></div>)}</div>
          <div className={styles.insightGrid}><div className={styles.insight}><b>{structuralDiff.length}</b><span>{t('změněných cest', 'changed paths')}</span></div><div className={styles.insight}><b>{parsedDraft ? '✓' : '—'}</b><span>{t('čitelnost draftu', 'draft parseability')}</span></div><div className={styles.insight}><b>{publishedRevision ? 'LIVE' : '—'}</b><span>{t('referenční revize', 'reference revision')}</span></div></div>
          {structuralDiff.length === 0 ? <div className={styles.schemaHint}>{t('Žádná strukturální změna proti živé revizi. Před publikací vždy ověřte obchodní význam.', 'No structural change from the live revision. Always verify business meaning before publication.')}</div> : <ul className={styles.diffList}>{structuralDiff.map(entry => <li key={`${entry.kind}:${entry.path}`}><strong>{entry.kind}</strong> <code>{entry.path}</code></li>)}</ul>}

          <div className={styles.aiPanel}>
            <div className={styles.aiHead}><div><div className={styles.aiTitle}><Bot size={15} aria-hidden="true" />{t('Catalog intelligence review', 'Catalog intelligence review')}</div><div className={styles.aiCopy}>{t('Připne přesný draft, vytvoří pouze návrh pro lidské posouzení a nikdy nemění ani nepublikuje nabídku.', 'Pins the exact draft, creates only a human-review proposal and never changes or publishes an offer.')}</div></div><span className={styles.aiGuard}><ShieldCheck size={11} aria-hidden="true" />HITL</span></div>
            <Can permission="catalog:author"><div className={styles.actions}><button className="btn btn-secondary" type="button" aria-busy={reviewing} aria-label={reviewing ? t('Kontroluji draft', 'Reviewing draft') : t('Spustit AI kontrolu draftu', 'Run AI review for draft')} disabled={!selectedRevision || selectedRevision.state !== 'DRAFT' || reviewing || reviewCapability !== 'available'} onClick={() => void reviewDraft()}><Sparkles size={13} aria-hidden="true" />{reviewing ? t('Kontroluji…', 'Reviewing…') : reviewCapability === 'checking' ? t('Ověřuji AI kapacitu…', 'Checking AI availability…') : reviewCapability === 'available' ? t('Spustit AI kontrolu', 'Run AI review') : t('Privátní AI kontrola nedostupná', 'Private AI review unavailable')}</button></div></Can>
            {reviewCapability === 'unavailable' && <div className={styles.aiUnavailable}><ShieldCheck size={13} aria-hidden="true" /><span>{t('Toto prostředí nemá schválený interní model pro neveřejné drafty. Nic se neposílá do hostovaného modelu — k dispozici zůstává deterministická kontrola schématu a dopadu.', 'This environment has no approved internal model for unpublished drafts. Nothing is sent to a hosted model — deterministic schema and change-impact checks remain available.')}</span></div>}
            {!selectedRevision && <div className={styles.schemaHint}>{t('Vyberte draft revizi; review nikdy nepracuje s neurčitým nebo živým obsahem.', 'Select a draft revision; review never works from an ambiguous or live document.')}</div>}
            {review && <div aria-live="polite"><div className={styles.findingText} style={{ marginTop: 11, fontWeight: 700 }}>{review.summary}</div>{review.findings.length === 0 && <div className={styles.schemaHint}>{t('Model nenašel strukturované nálezy. To nenahrazuje lidskou obchodní kontrolu.', 'The model found no structured findings. That never replaces human business review.')}</div>}{review.findings.map(finding => <div key={`${finding.category}:${finding.instancePath}`} className={`${styles.finding} ${finding.severity === 'HIGH' ? styles.findingHigh : finding.severity === 'WARNING' ? styles.findingWarning : ''}`}><div className={styles.findingTitle}><span>{finding.category}</span><span>{finding.severity}</span></div><div className={styles.findingText}>{finding.recommendation}</div><div className={styles.evidence}>{finding.instancePath} · {finding.evidence}</div></div>)}<div className={styles.provenance}><span>proposal {review.proposalId.slice(0, 8)}</span><span>model {review.model}</span><span>context {review.contextHash.slice(0, 12)}…</span></div></div>}
          </div>
        </div>
      </section>

      <section className={`card ${styles.panel}`}>
        <div className={styles.panelHead}><div><div className={styles.panelKicker}>{t('Pohled zákazníka', 'Customer view')}</div><h2 className={styles.panelTitle}><Boxes size={15} aria-hidden="true" />{t('Kontextový náhled', 'Contextual preview')}</h2></div><span className="badge badge-neutral">{offerSelections.length} {t('shod', 'matches')}</span></div>
        <div className={styles.panelBody}>
          <div className={styles.previewContext}>
            <div className={styles.previewContextTitle}><LockKeyhole size={13} aria-hidden="true" />{t('Simulovaný tržní kontext', 'Simulated market context')}</div>
            <p>{t('Pouze obchodní kritéria; žádné ID zákazníka, profil ani rozhodnutí o způsobilosti.', 'Business criteria only; no customer ID, profile or eligibility decision.')}</p>
            <div className={styles.previewContextGrid}>
              <label><span>{t('Značka', 'Brand')}</span><input className="input" value={previewContextInput.brands} onChange={event => updatePreviewContext('brands', event.target.value)} placeholder="retail" /></label>
              <label><span>{t('Země', 'Country')}</span><input className="input" value={previewContextInput.countries} onChange={event => updatePreviewContext('countries', event.target.value)} placeholder="CZ" /></label>
              <label><span>{t('Kanál', 'Channel')}</span><input className="input" value={previewContextInput.channels} onChange={event => updatePreviewContext('channels', event.target.value)} placeholder="WEB" /></label>
              <label><span>{t('Segment', 'Segment')}</span><input className="input" value={previewContextInput.segments} onChange={event => updatePreviewContext('segments', event.target.value)} placeholder="employee" /></label>
              <label><span>{t('Jazyk', 'Locale')}</span><input className="input" value={previewContextInput.locales} onChange={event => updatePreviewContext('locales', event.target.value)} placeholder="cs-CZ" /></label>
            </div>
          </div>
          <div className={styles.selectionList} aria-live="polite">
            {offerSelections.length === 0 && <div className={styles.schemaHint}>{t('Žádná nabídka přesně neodpovídá. Rozšiřte pouze vědomě tržní kontext — neveřejné nabídky se bez shody nezobrazují.', 'No offer matches exactly. Broaden market context only deliberately — private offers never appear without a match.')}</div>}
            {offerSelections.slice(0, 5).map((selection, index) => <button type="button" key={selection.offering.id} aria-pressed={selection.offering.id === offeringId} className={`${styles.selection} ${selection.offering.id === offeringId ? styles.selectionActive : ''}`} onClick={() => setOfferingId(selection.offering.id)}>
              <span className={styles.selectionRank}>#{index + 1}</span><span className={styles.selectionCopy}><strong>{selection.offering.code}</strong><small>{selection.reasons.join(' · ')}</small></span><span className="badge badge-info">{selection.specificity === 0 ? t('globální', 'global') : t('shoda', 'match')}</span>
            </button>)}
          </div>
          {selectedOfferExplanation
            ? <aside className={styles.explanation} aria-live="polite">
                <div className={styles.explanationTitle}><ShieldCheck size={14} aria-hidden="true" />{selectedOfferExplanation.title}</div>
                <p>{selectedOfferExplanation.summary}</p>
                <div className={styles.explanationTrace}>{selectedOfferExplanation.trace.map(item => <code key={item}>{item}</code>)}</div>
                <small>{selectedOfferExplanation.privacyNotice}</small>
              </aside>
            : <div className={styles.explanationHidden}><LockKeyhole size={13} aria-hidden="true" />{t('Pro tuto nabídku nevzniká vysvětlení: není součástí autorizovaného výsledku zadaného tržního kontextu.', 'No explanation is created for this offering: it is not part of the authorized result for the supplied market context.')}</div>}
          <div className={styles.preview}><div className={styles.previewEyebrow}>{selectedOffering?.market.channels?.join(' · ') || t('Všechny kanály', 'All channels')}</div><h3 className={styles.previewName}>{String((parsedDraft?.name as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.name as Record<string, string> | undefined)?.en ?? selectedOffering?.code ?? '—')}</h3><p className={styles.previewCopy}>{String((parsedDraft?.description as Record<string, string> | undefined)?.[language] ?? (parsedDraft?.description as Record<string, string> | undefined)?.en ?? t('Doplňte popis, aby byl dopad nabídky srozumitelný pro zákazníka i kontrolora.', 'Add a description so the offer is understandable to both customer and reviewer.'))}</p><div className={styles.previewFoot}>{t('Trh:', 'Market:')} {selectedOffering?.market.countries?.join(', ') || t('všechny země', 'all countries')} · {t('Ceny:', 'Prices:')} {Array.isArray(parsedDraft?.prices) ? parsedDraft.prices.length : 0}</div></div>
        </div>
      </section>
    </div>
  </AuthGuard>
}
