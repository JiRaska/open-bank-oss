// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

const PARTY_TYPES = new Set(['INDIVIDUAL', 'SOLE_TRADER', 'COMPANY', 'TRUST'])
const PARTY_STATUSES = new Set(['PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'MERGED'])
const KYC_STATUSES = new Set(['NOT_STARTED', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'EXPIRED'])

export interface PartyEvidence {
  id: string
  partyType: string
  status: string
  legalName: string
  tradingName?: string
  email: string
  phone?: string
  kycStatus: string
  taxId?: string
  registrationNumber?: string
  nationality?: string
  dateOfBirth?: string
  address?: { line1: string; city: string; postalCode: string; countryCode: string }
  createdAt: string
  updatedAt: string
  piiMasked: boolean
  piiRevealAllowed: boolean
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isOptionalText(value: unknown): value is string | null | undefined {
  return value == null || isText(value)
}

function optionalText(value: unknown): string | undefined {
  return isText(value) ? value : undefined
}

function isTimestamp(value: unknown): value is string {
  return isText(value) && Number.isFinite(Date.parse(value))
}

export function parsePartyEvidence(value: unknown, requestedId: string): PartyEvidence | null {
  if (!isRecord(value) || value.id !== requestedId) return null
  if (!PARTY_TYPES.has(String(value.partyType)) || !PARTY_STATUSES.has(String(value.status))) return null
  if (!isText(value.legalName) || !isText(value.email) || !KYC_STATUSES.has(String(value.kycStatus))) return null
  if (!isTimestamp(value.createdAt) || !isTimestamp(value.updatedAt)) return null
  const optionalFields = ['tradingName', 'phone', 'taxId', 'registrationNumber', 'nationality', 'dateOfBirth']
  if (!optionalFields.every(field => isOptionalText(value[field]))) return null

  let address: PartyEvidence['address']
  if (value.address !== undefined && value.address !== null) {
    if (!isRecord(value.address)) return null
    const rawAddress = value.address
    if (!['line1', 'city', 'postalCode', 'countryCode'].every(field => isText(rawAddress[field]))) return null
    address = {
      line1: String(rawAddress.line1), city: String(rawAddress.city),
      postalCode: String(rawAddress.postalCode), countryCode: String(rawAddress.countryCode),
    }
  }

  return {
    id: requestedId, partyType: String(value.partyType), status: String(value.status),
    legalName: value.legalName, tradingName: optionalText(value.tradingName),
    email: value.email, phone: optionalText(value.phone), kycStatus: String(value.kycStatus),
    taxId: optionalText(value.taxId), registrationNumber: optionalText(value.registrationNumber),
    nationality: optionalText(value.nationality), dateOfBirth: optionalText(value.dateOfBirth),
    address, createdAt: value.createdAt, updatedAt: value.updatedAt,
    piiMasked: value.piiMasked === true,
    piiRevealAllowed: value.piiRevealAllowed === true,
  }
}
