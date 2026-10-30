// Copyright 2026 OpenBank OSS contributors
// SPDX-License-Identifier: Apache-2.0
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Guard: Czech text in the admin UI must live inside a bilingual shape.
 *
 * The UI has two languages and one switch. Copy written in JSX goes through `t(cs, en)`;
 * data that travels as values (catalogues, generated JSON) carries `{ cs, en }` / `bi(cs, en)`
 * and is resolved with `localize()` / `pick()` (src/lib/i18n/bilingual.ts). A string literal or
 * JSX text containing Czech diacritics anywhere else renders Czech on the EN switch — the
 * defect the owner reported on /docs/cluster and /regulatory.
 *
 * The check parses each file with the TypeScript compiler (so comments are ignored for free)
 * and counts offending literals per file. The existing tail is frozen in
 * czech-ui-strings-baseline.json and may only shrink: a file above its baseline fails, and a
 * file below it fails too until the baseline is lowered, so the ratchet cannot silently loosen.
 * Regenerate (only ever downward): UPDATE_CZECH_BASELINE=1 npx vitest run src/test/czech-ui-strings.guard.test.ts
 */
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import baseline from './czech-ui-strings-baseline.json'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const SCAN_DIRS = ['src/app', 'src/components']
// Czech-specific letters with a háček/kroužek, or a long vowel inside a word (Platební, Ročně).
const CZECH = /[ěščřžůťďňĚŠČŘŽŮŤĎŇ]|[a-zA-Z][áíéýú]|[áíéýú][a-z]/
// Call names whose arguments are bilingual by construction.
const BILINGUAL_CALLS = new Set(['t', 'bi', 'L', 'tr', 'pickLang'])
// Property names that hold the Czech half of a bilingual pair: `cs`, or any `<field>Cs`.
const CS_KEY = /^(cs|[a-z][A-Za-z]*Cs)$/
// A declaration whose doc comment says `@czech-artefact <reason>` produces Czech-only output on
// purpose (a filing addressed to a Czech regulator). The reason is mandatory and reviewed.
const ARTEFACT_TAG = /@czech-artefact\s+[A-Za-z]/

function isCzechBranchOfLanguageTernary(node: ts.Node, child: ts.Node): boolean {
  if (!ts.isConditionalExpression(node)) return false
  const cond = node.condition.getText()
  if (!/\b(language|lang|locale)\b/.test(cond)) return false
  const csIsFalse = /!==?\s*['"]cs['"]|[^!]===?\s*['"]en['"]/.test(cond)
  const csIsTrue = /[^!]===?\s*['"]cs['"]|['"]cs['"]\s*===?/.test(cond)
  if (csIsFalse) return child === node.whenFalse
  if (csIsTrue) return child === node.whenTrue
  return false
}

function isBilingualContext(node: ts.Node): boolean {
  let child: ts.Node = node
  for (let cur = node.parent; cur; child = cur, cur = cur.parent) {
    if (ts.isCallExpression(cur)) {
      const callee = cur.expression
      const name = ts.isIdentifier(callee) ? callee.text
        : ts.isPropertyAccessExpression(callee) ? callee.name.text : ''
      if (BILINGUAL_CALLS.has(name)) return true
    }
    if (ts.isPropertyAssignment(cur) && cur.initializer === child) {
      const key = cur.name.getText().replace(/['"]/g, '')
      if (CS_KEY.test(key)) return true
    }
    if (isCzechBranchOfLanguageTernary(cur, child)) return true
    if (ts.isFunctionDeclaration(cur) || ts.isVariableStatement(cur)) {
      const ranges = ts.getLeadingCommentRanges(cur.getSourceFile().text, cur.getFullStart()) ?? []
      if (ranges.some(rg => ARTEFACT_TAG.test(cur.getSourceFile().text.slice(rg.pos, rg.end)))) return true
    }
    // Import specifiers, type literals and enum-like keys never render.
    if (ts.isImportDeclaration(cur) || ts.isLiteralTypeNode(cur)) return true
    if (ts.isSourceFile(cur)) return false
  }
  return false
}

export interface Finding { line: number; text: string }

export function findCzechLiterals(source: string, fileName = 'inline.tsx'): Finding[] {
  const sf = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true,
    fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
  const out: Finding[] = []
  const visit = (node: ts.Node) => {
    let text: string | null = null
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) text = node.text
    else if (ts.isTemplateExpression(node)) text = [node.head.text, ...node.templateSpans.map(s => s.literal.text)].join(' ')
    else if (ts.isJsxText(node)) text = node.text
    if (text !== null && CZECH.test(text) && !isBilingualContext(node)) {
      out.push({ line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1, text: text.trim().slice(0, 80) })
    }
    // A template expression is reported as one unit; do not descend into its spans' literals.
    if (!ts.isTemplateExpression(node)) ts.forEachChild(node, visit)
    else node.templateSpans.forEach(s => visit(s.expression))
  }
  visit(sf)
  return out
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) walk(full, acc)
    else if (/\.(ts|tsx)$/.test(entry) && !/\.(test|spec)\.tsx?$/.test(entry)) acc.push(full)
  }
  return acc
}

function scan(): Record<string, Finding[]> {
  const result: Record<string, Finding[]> = {}
  for (const d of SCAN_DIRS) {
    for (const file of walk(path.join(ROOT, d))) {
      const findings = findCzechLiterals(readFileSync(file, 'utf8'), file)
      if (findings.length) result[path.relative(ROOT, file)] = findings
    }
  }
  return result
}

describe('czech-ui-strings guard — detector', () => {
  it('flags Czech in JSX text, plain literals and templates (known positive)', () => {
    const src = [
      "export const A = () => <p>Implementovaný náhled</p>",
      "const label = 'Měsíčně'",
      "const s = `Období ${x} · nevyvážená`",
      "const o = { name: 'Platební statistika' }",
    ].join('\n')
    expect(findCzechLiterals(src).map(f => f.line)).toEqual([1, 2, 3, 4])
  })

  it('accepts every bilingual shape (known negative)', () => {
    const src = [
      "export const A = () => <p>{t('Implementovaný náhled', 'Implemented preview')}</p>",
      "const label = bi('Měsíčně', 'Monthly')",
      "const o = { cs: 'Platební statistika', en: 'Payment statistics' }",
      "const x = language === 'cs' ? 'Čtvrtletně' : 'Quarterly'",
      "const y = lang !== 'cs' ? 'Annually' : 'Ročně'",
      "// komentář s háčky se nepočítá",
      "const plain = 'Payment statistics'",
      "const nav = { nameCs: 'Přehled', nameEn: 'Dashboard' }",
      "/** @czech-artefact filed with the CNB in Czech */",
      "function exportRows() { return [{ field: 'Název' }] }",
    ].join('\n')
    expect(findCzechLiterals(src)).toEqual([])
  })

  it('does not treat the English branch of a language ternary as Czech-safe', () => {
    expect(findCzechLiterals("const x = language === 'cs' ? 'Quarterly' : 'Čtvrtletně'")).toHaveLength(1)
  })

  it('requires a reason on @czech-artefact', () => {
    expect(findCzechLiterals("/** @czech-artefact */\nfunction f() { return 'Název' }")).toHaveLength(1)
  })
})

describe('czech-ui-strings guard — ratchet over src/app and src/components', () => {
  const found = scan()
  if (process.env.UPDATE_CZECH_BASELINE) {
    const counts = Object.fromEntries(Object.entries(found).sort().map(([f, x]) => [f, x.length]))
    writeFileSync(path.join(import.meta.dirname, 'czech-ui-strings-baseline.json'), JSON.stringify(counts, null, 2) + '\n')
  }
  const frozen = baseline as Record<string, number>

  it('no file has more unwrapped Czech literals than its baseline', () => {
    const regressions = Object.entries(found)
      .filter(([file, f]) => f.length > (frozen[file] ?? 0))
      .map(([file, f]) => `${file}: ${f.length} > baseline ${frozen[file] ?? 0}\n` +
        f.slice(0, 5).map(x => `    L${x.line}: ${x.text}`).join('\n'))
    expect(regressions, 'Wrap Czech UI text in t(cs, en) or a { cs, en } / bi() value:\n' + regressions.join('\n')).toEqual([])
  })

  it('the baseline is not stale (lower it when a file improves)', () => {
    const stale = Object.entries(frozen)
      .filter(([file, n]) => (found[file]?.length ?? 0) < n)
      .map(([file, n]) => `${file}: baseline ${n}, now ${found[file]?.length ?? 0}`)
    expect(stale, 'Lower czech-ui-strings-baseline.json:\n' + stale.join('\n')).toEqual([])
  })

  it('the pages the owner reported are fully bilingual', () => {
    for (const file of ['src/app/docs/cluster/page.tsx', 'src/app/regulatory/page.tsx']) {
      expect(found[file] ?? [], file).toEqual([])
    }
  })
})
