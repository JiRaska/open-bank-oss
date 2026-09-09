// SPDX-License-Identifier: Apache-2.0

export interface MerchantCatalogueRow {
  descriptorKey: string
  cleanName: string
  logoUrl: string | null
  logoContentHash: string | null
  category: string | null
  lat: number | null
  lon: number | null
  city: string | null
  country: string | null
  updatedAt: string
}

export interface MerchantCataloguePage { data: MerchantCatalogueRow[]; total: number }
export interface UnmatchedMerchantDescriptor { descriptorKey: string; occurrences: number }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid merchant ${field}`)
  return value
}

function nullableString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  return requiredString(record, field)
}

function nullableNumber(record: Record<string, unknown>, field: string): number | null {
  const value = record[field]
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Invalid merchant ${field}`)
  return value
}

function parseMerchant(value: unknown): MerchantCatalogueRow {
  if (!isRecord(value)) throw new Error('Invalid merchant row')
  const lat = nullableNumber(value, 'lat')
  const lon = nullableNumber(value, 'lon')
  const updatedAt = requiredString(value, 'updatedAt')
  if ((lat === null) !== (lon === null) || (lat !== null && (lat < -90 || lat > 90)) || (lon !== null && (lon < -180 || lon > 180))) throw new Error('Invalid merchant location')
  if (Number.isNaN(Date.parse(updatedAt))) throw new Error('Invalid merchant updatedAt')
  return {
    descriptorKey: requiredString(value, 'descriptorKey'), cleanName: requiredString(value, 'cleanName'),
    logoUrl: nullableString(value, 'logoUrl'), logoContentHash: nullableString(value, 'logoContentHash'),
    category: nullableString(value, 'category'), lat, lon, city: nullableString(value, 'city'),
    country: nullableString(value, 'country'), updatedAt,
  }
}

export function parseMerchantCataloguePage(raw: unknown, pageSize: number): MerchantCataloguePage {
  if (!isRecord(raw) || !Array.isArray(raw.data) || typeof raw.total !== 'number' || !Number.isInteger(raw.total) || raw.total < 0) throw new Error('Invalid merchant page')
  if (raw.data.length > pageSize || raw.data.length > raw.total) throw new Error('Invalid merchant page window')
  return { data: raw.data.map(parseMerchant), total: raw.total }
}

export function parseUnmatchedMerchantDescriptors(raw: unknown): UnmatchedMerchantDescriptor[] {
  if (!Array.isArray(raw)) throw new Error('Invalid unmatched merchant list')
  return raw.map(value => {
    if (!isRecord(value)) throw new Error('Invalid unmatched merchant')
    const occurrences = value.occurrences
    if (typeof occurrences !== 'number' || !Number.isInteger(occurrences) || occurrences <= 0) throw new Error('Invalid merchant occurrences')
    return { descriptorKey: requiredString(value, 'descriptorKey'), occurrences }
  })
}
