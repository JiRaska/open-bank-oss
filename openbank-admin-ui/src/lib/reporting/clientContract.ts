// SPDX-License-Identifier: Apache-2.0

export type CatalogueParam = { name: string; labelCs: string; labelEn: string; type: 'date' | 'month' | 'number' | 'enum'; required: boolean; defaultValue?: string; options?: readonly string[] }
export type CatalogueColumn = { key: string; labelCs: string; labelEn: string; format: 'text' | 'number' | 'money' | 'datetime' }
export type CatalogueReport = { id: string; titleCs: string; titleEn: string; descriptionCs: string; descriptionEn: string; permission: string; params: readonly CatalogueParam[]; columns: readonly CatalogueColumn[] }
export type ReportResult = { available: boolean; reportId: string; columns: readonly CatalogueColumn[]; rows: Record<string, unknown>[]; generatedAt: string | null; rowCount: number; truncated: boolean; error?: string }

const IDENTIFIER = /^[a-z][A-Za-z0-9_-]{0,127}$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const FORMATS = ['text', 'number', 'money', 'datetime'] as const
const PARAM_TYPES = ['date', 'month', 'number', 'enum'] as const

function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object'); return value as Record<string, unknown> }
function text(value: unknown, max = 2_000): string { if (typeof value !== 'string' || value.trim() === '' || value.length > max) throw new Error('Invalid text'); return value }
function identifier(value: unknown): string { const result = text(value, 128); if (!IDENTIFIER.test(result)) throw new Error('Invalid identifier'); return result }
function enumValue<const T extends readonly string[]>(value: unknown, values: T): T[number] { if (typeof value !== 'string' || !values.includes(value)) throw new Error('Invalid enum'); return value as T[number] }
function column(value: unknown): CatalogueColumn { const item = object(value); return { key: identifier(item.key), labelCs: text(item.labelCs), labelEn: text(item.labelEn), format: enumValue(item.format, FORMATS) } }
function columns(value: unknown): CatalogueColumn[] { if (!Array.isArray(value) || value.length === 0 || value.length > 100) throw new Error('Invalid columns'); const parsed = value.map(column); if (new Set(parsed.map(item => item.key)).size !== parsed.length) throw new Error('Duplicate column'); return parsed }
function parameter(value: unknown): CatalogueParam {
  const item = object(value); const type = enumValue(item.type, PARAM_TYPES)
  if (typeof item.required !== 'boolean') throw new Error('Invalid required flag')
  let options: string[] | undefined
  if (type === 'enum') { if (!Array.isArray(item.options) || item.options.length === 0 || item.options.length > 100) throw new Error('Invalid enum options'); options = item.options.map(option => text(option, 200)); if (new Set(options).size !== options.length) throw new Error('Duplicate enum option') }
  else if (item.options != null) throw new Error('Unexpected options')
  const defaultValue = item.defaultValue == null ? undefined : text(item.defaultValue, 500)
  if (defaultValue && type === 'enum' && !options?.includes(defaultValue)) throw new Error('Invalid enum default')
  return { name: identifier(item.name), labelCs: text(item.labelCs), labelEn: text(item.labelEn), type, required: item.required, ...(defaultValue ? { defaultValue } : {}), ...(options ? { options } : {}) }
}

export function parseReportCatalogue(value: unknown): CatalogueReport[] {
  const raw = object(value)
  if (!Array.isArray(raw.reports) || raw.reports.length === 0 || raw.reports.length > 100) throw new Error('Invalid report catalogue')
  const reports = raw.reports.map(value => { const item = object(value); if (!Array.isArray(item.params) || item.params.length > 50) throw new Error('Invalid params'); const params = item.params.map(parameter); if (new Set(params.map(p => p.name)).size !== params.length) throw new Error('Duplicate param'); return { id: identifier(item.id), titleCs: text(item.titleCs), titleEn: text(item.titleEn), descriptionCs: text(item.descriptionCs, 10_000), descriptionEn: text(item.descriptionEn, 10_000), permission: text(item.permission, 200), params, columns: columns(item.columns) } })
  if (new Set(reports.map(report => report.id)).size !== reports.length) throw new Error('Duplicate report')
  return reports
}

function sameColumns(actual: CatalogueColumn[], expected: readonly CatalogueColumn[]): boolean { return JSON.stringify(actual) === JSON.stringify(expected) }
export function parseReportResult(value: unknown, report: CatalogueReport): ReportResult {
  const raw = object(value)
  if (typeof raw.available !== 'boolean' || raw.reportId !== report.id || typeof raw.truncated !== 'boolean') throw new Error('Invalid report identity')
  const resultColumns = columns(raw.columns)
  if (!sameColumns(resultColumns, report.columns)) throw new Error('Contradictory report columns')
  if (!Array.isArray(raw.rows) || raw.rows.length > 1_000 || !Number.isInteger(raw.rowCount) || (raw.rowCount as number) < 0 || raw.rowCount !== raw.rows.length) throw new Error('Contradictory row count')
  const keys = new Set(report.columns.map(item => item.key))
  const rows = raw.rows.map(value => { const row = object(value); if (Object.keys(row).some(key => !keys.has(key))) throw new Error('Unexpected row column'); for (const col of report.columns) { const cell = row[col.key]; if (cell == null) continue; if (typeof cell !== 'string' && typeof cell !== 'number' && typeof cell !== 'boolean') throw new Error('Invalid cell'); if ((col.format === 'number' || col.format === 'money') && !Number.isFinite(Number(cell))) throw new Error('Invalid numeric cell'); if (col.format === 'datetime' && (typeof cell !== 'string' || !RFC3339.test(cell) || !Number.isFinite(Date.parse(cell)))) throw new Error('Invalid datetime cell') } return row })
  if (!raw.available && (rows.length !== 0 || raw.generatedAt !== null || raw.truncated)) throw new Error('Contradictory unavailable result')
  if (raw.available && (typeof raw.generatedAt !== 'string' || !RFC3339.test(raw.generatedAt) || !Number.isFinite(Date.parse(raw.generatedAt)))) throw new Error('Invalid generatedAt')
  const error = raw.error == null ? undefined : text(raw.error, 2_000)
  return { available: raw.available, reportId: report.id, columns: resultColumns, rows, generatedAt: raw.generatedAt as string | null, rowCount: raw.rowCount as number, truncated: raw.truncated, ...(error ? { error } : {}) }
}
