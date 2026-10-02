// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Detects source lines that render a raw identifier (a UUID or a shortened one) as the text a
// person reads. Used by `raw-identifier.guard.test.ts`; kept separate so its self-test can feed it
// known-positive and known-negative lines.
//
// Deliberately high-precision, not exhaustive: it looks for the three shapes that put an id on
// screen in this codebase, and ignores ids in URLs, keys and data-attributes.

export type RawIdFinding = { line: number; rule: string; text: string }

const ID = String.raw`[\w.?!\[\]'"]*?(?:\.id|Id|ID|_id)`

const RULES: { rule: string; re: RegExp }[] = [
  // `{party.id.slice(0, 8)}…` — a shortened UUID used as the label.
  { rule: 'shortened-id', re: new RegExp(String.raw`${ID}\??\.(?:slice|substring)\(\s*0\s*,\s*\d+\s*\)`) },
  // `<td>{l.instrumentId}</td>` / `<option>{a.id}</option>` — an id as a cell's or option's text.
  { rule: 'id-as-cell-text', re: new RegExp(String.raw`<(td|th|option|span|strong|li|p|div|h\d)\b[^>]*>\s*\{\s*${ID}\s*\}\s*</\1>`) },
  // t(`Běh ${id}`, …) / label={`Loan ${x.instrumentId}`} — an id interpolated into prose (not a URL).
  { rule: 'id-in-prose', re: new RegExp(String.raw`(?:\bt\(\s*|(?:label|title|subtitle|placeholder|aria-label)=\{\s*)\x60[^\x60/]*\$\{\s*${ID}\s*\}[^\x60/]*\x60`) },
]

/** A literal full UUID in JSX text, e.g. a hardcoded fixture id rendered by a page. */
const UUID_TEXT = />[^<{]*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[^<{]*</i

export function findRawIdentifiers(source: string): RawIdFinding[] {
  const out: RawIdFinding[] = []
  source.split('\n').forEach((text, i) => {
    const trimmed = text.trim()
    if (trimmed.startsWith('//') || trimmed.startsWith('*')) return
    // An id that is NOT a raw identifier (a parameter-set name, a control code) is waived on its
    // line with `raw-id-ok: <why>` — the reason is the review trail.
    if (/raw-id-ok:\s*\S/.test(text)) return
    // Form-validation messages keyed by field id (`errors.partyId`) are prose, not ids.
    if (/\{\s*errors\.\w+\s*\}/.test(text) && !/\.slice\(/.test(text)) return
    for (const { rule, re } of RULES) if (re.test(text)) out.push({ line: i + 1, rule, text: trimmed })
    if (UUID_TEXT.test(text)) out.push({ line: i + 1, rule: 'uuid-literal-text', text: trimmed })
  })
  return out
}
