// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0352 phase 5b: the Studio IR editor. Deliberately holds NO product-IR rules of its own — the only
// validator is the catalog's `POST /api/v2/product-ir/lint`, which runs the parser a revision write runs
// and `obp lint` runs. This module only tracks which text a lint verdict belongs to, so a verdict can
// never be applied to text the server did not see, and builds the DRAFT write. It never signs or
// publishes: publication stays the two-approval publish-ir flow (ADR-0352 §7).

import { catalogRevisionEditorDocument } from '@/lib/catalog-structural-diff'
import type { ProductRevision, RevisionRequest } from '@/lib/product-catalog-v2'
import type { LintProductIrResponse } from '@/lib/generated/product-catalog-v2'

export type IrTextParse =
  | { ok: true; ir: Record<string, unknown> }
  | { ok: false; error: string }

/** JSON syntax only; whether it is a valid product IR is the server's call. */
export function parseIrText(text: string): IrTextParse {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'a product IR document is a JSON object' }
  }
  return { ok: true, ir: value as Record<string, unknown> }
}

/** The editor's starting text: the revision's IR, pretty-printed, or empty when it carries none. */
export function irEditorText(revision: ProductRevision | undefined): string {
  const behaviour = revision?.content.behaviour
  return behaviour ? JSON.stringify(behaviour, null, 2) : ''
}

/** A lint verdict, pinned to the exact text it was computed for. */
export interface IrLintVerdict {
  text: string
  result: LintProductIrResponse
}

export type IrSaveGate =
  | { allowed: true; ir: Record<string, unknown>; revisionHash: string }
  | { allowed: false; reason: 'NOT_DRAFT' | 'NOT_LINTED' | 'STALE_LINT' | 'INVALID' }

/**
 * Save is allowed only for a DRAFT revision, and only for text the server linted valid as it is now.
 * Editing after a lint makes the verdict stale, never silently reused.
 */
export function irSaveGate(revision: ProductRevision | undefined, text: string, verdict: IrLintVerdict | null): IrSaveGate {
  if (!revision || revision.state !== 'DRAFT') return { allowed: false, reason: 'NOT_DRAFT' }
  if (!verdict) return { allowed: false, reason: 'NOT_LINTED' }
  if (verdict.text !== text) return { allowed: false, reason: 'STALE_LINT' }
  const parsed = parseIrText(text)
  if (!verdict.result.valid || !verdict.result.revisionHash || !parsed.ok) return { allowed: false, reason: 'INVALID' }
  return { allowed: true, ir: parsed.ok ? parsed.ir : {}, revisionHash: verdict.result.revisionHash }
}

/**
 * The DRAFT write: the revision's current content with `behaviour` replaced, everything else as stored.
 * The server re-validates on write; this never touches state, approvals or signatures.
 */
export function revisionRequestWithBehaviour(revision: ProductRevision, ir: Record<string, unknown>): RevisionRequest {
  if (revision.state !== 'DRAFT') throw new Error('only a DRAFT revision can be edited')
  return { ...catalogRevisionEditorDocument(revision), behaviour: ir } as unknown as RevisionRequest
}
