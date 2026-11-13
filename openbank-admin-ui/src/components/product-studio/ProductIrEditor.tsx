// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { FileJson, ShieldCheck } from 'lucide-react'
import { catalogV2Operation, type ProductRevision } from '@/lib/product-catalog-v2'
import { irEditorText, irSaveGate, parseIrText, revisionRequestWithBehaviour, type IrLintVerdict } from '@/lib/product-ir-editor'

/**
 * ADR-0352 phase 5b: edit a revision's product IR. Lint is the catalog's server-side parser (the one
 * `obp lint` and the revision write run); Save writes the DRAFT only. Publication, approvals and the
 * signature stay in the existing two-approval publish-ir flow — this component has no path to them.
 */
export function ProductIrEditor({ revision, t, onSaved }: {
  revision: ProductRevision | undefined
  t: (cs: string, en: string) => string
  onSaved: () => Promise<void> | void
}) {
  const [text, setText] = useState(() => irEditorText(revision))
  const [verdict, setVerdict] = useState<IrLintVerdict | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setText(irEditorText(revision))
    setVerdict(null)
    setMessage('')
  }, [revision?.id, revision?.revision]) // eslint-disable-line react-hooks/exhaustive-deps

  const editable = revision?.state === 'DRAFT'
  const gate = irSaveGate(revision, text, verdict)
  const current = verdict && verdict.text === text ? verdict.result : null

  const lint = async () => {
    const parsed = parseIrText(text)
    if (!parsed.ok) { setVerdict(null); setMessage(t('IR není validní JSON', 'IR is not valid JSON') + `: ${parsed.error}`); return }
    setBusy(true); setMessage('')
    try {
      const result = await catalogV2Operation('lintProductIrV2', { body: { ir: parsed.ir } })
      setVerdict({ text, result })
    } catch (error) {
      setVerdict(null)
      setMessage(error instanceof Error ? error.message : String(error))
    } finally { setBusy(false) }
  }

  const save = async () => {
    if (!revision || !gate.allowed) return
    setBusy(true); setMessage('')
    try {
      await catalogV2Operation('replaceOfferingRevisionV2', {
        pathParameters: { offeringId: revision.offeringId, revisionId: revision.id },
        headers: { 'If-Match': `"${revision.revision}"` },
        body: revisionRequestWithBehaviour(revision, gate.ir),
      })
      setMessage(t('IR uloženo do draftu', 'IR saved to the draft'))
      await onSaved()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error))
    } finally { setBusy(false) }
  }

  return (
    <section aria-label={t('Editor produktového IR', 'Product IR editor')} data-testid="product-ir-editor">
      <h3><FileJson size={14} aria-hidden /> {t('Chování produktu (IR)', 'Product behaviour (IR)')}</h3>
      <p>{t('Kontrola běží na serveru stejným parserem jako obp lint. Uložení vytvoří jen draft; publikace vyžaduje dvě schválení a podpis.',
        'Lint runs on the server with the same parser as obp lint. Saving writes the draft only; publishing needs two approvals and a signature.')}</p>
      <textarea
        className="input"
        aria-label={t('IR dokument', 'IR document')}
        value={text}
        onChange={e => setText(e.target.value)}
        disabled={!editable || busy}
        rows={14}
        spellCheck={false}
      />
      <div>
        <button type="button" className="btn btn-secondary" onClick={() => void lint()} disabled={!editable || busy || !text.trim()}>
          <ShieldCheck size={14} aria-hidden /> {t('Zkontrolovat IR', 'Lint IR')}
        </button>
        <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!gate.allowed || busy}>
          {t('Uložit IR do draftu', 'Save IR to draft')}
        </button>
      </div>
      {!editable && revision && <p>{t('Upravit lze jen revizi ve stavu DRAFT.', 'Only a DRAFT revision can be edited.')}</p>}
      {verdict && verdict.text !== text && <p data-testid="ir-lint-stale">{t('IR se od kontroly změnilo; zkontrolujte znovu.', 'IR changed since the lint; lint again.')}</p>}
      {current?.valid && (
        <dl data-testid="ir-lint-valid">
          <dt>{t('Produkt', 'Product')}</dt><dd>{current.productCode}</dd>
          <dt>irVersion</dt><dd>{current.irVersion}</dd>
          <dt>revisionHash</dt><dd><code>{current.revisionHash}</code></dd>
        </dl>
      )}
      {current && !current.valid && (
        <ul data-testid="ir-lint-problems">
          {current.problems.map(problem => <li key={`${problem.path}:${problem.message}`}><code>{problem.path}</code> {problem.message}</li>)}
        </ul>
      )}
      {message && <p role="status">{message}</p>}
    </section>
  )
}
