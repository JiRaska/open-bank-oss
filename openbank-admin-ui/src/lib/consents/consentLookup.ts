// SPDX-License-Identifier: Apache-2.0

export interface Consent {
  id: string
  partyId: string
  granteeId: string
  granteeType: string
  granteeName: string
  scopes: string[]
  accountIbans: string[] | null
  status: string
  validFrom: string
  validTo: string
  createdAt: string
}

export class InvalidConsentPayloadError extends Error {
  constructor() {
    super('invalid consent lookup payload')
    this.name = 'InvalidConsentPayloadError'
  }
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function isConsent(value: unknown): value is Consent {
  if (!value || typeof value !== 'object') return false
  const consent = value as Partial<Consent>
  return isNonEmptyString(consent.id)
    && isNonEmptyString(consent.partyId)
    && isNonEmptyString(consent.granteeId)
    && isNonEmptyString(consent.granteeType)
    && isNonEmptyString(consent.granteeName)
    && Array.isArray(consent.scopes)
    && consent.scopes.every(isNonEmptyString)
    && (consent.accountIbans === null
      || (Array.isArray(consent.accountIbans) && consent.accountIbans.every(isNonEmptyString)))
    && isNonEmptyString(consent.status)
    && isDate(consent.validFrom)
    && isDate(consent.validTo)
    && Date.parse(consent.validFrom) <= Date.parse(consent.validTo)
    && isDate(consent.createdAt)
}

/** Validate the consent-service response before it can become operator-visible evidence. */
export function parseConsentList(value: unknown): Consent[] {
  if (!Array.isArray(value) || !value.every(isConsent)) {
    throw new InvalidConsentPayloadError()
  }
  return value
}
