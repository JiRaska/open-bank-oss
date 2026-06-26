// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Client-side IBAN validation. Why this exists: the account-service rejects any
// non-IBAN string at the domain boundary with a raw `Invalid IBAN: <value>`
// (openbank-libs Iban.kt). If the operator types `*`, `CZ` or any free-text into
// an IBAN-bound field, that raw exception used to surface verbatim in the UI. We
// validate the shape *before* calling the BFF so a malformed value never reaches
// the IBAN endpoint — the user gets an inline hint instead of a leaked backend
// error. ISO 13616: 2 country letters + 2 check digits + up to 30 BBAN chars,
// verified with the mod-97 checksum.

/** Strip spaces and upper-case. IBANs are printed in groups of 4 but stored flat. */
export function normalizeIban(raw: string): string {
  return raw.replace(/\s+/g, '').toUpperCase()
}

/** Cheap structural check: country code + check digits + alphanumeric BBAN. */
export function hasIbanShape(raw: string): boolean {
  return /^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/.test(normalizeIban(raw))
}

/** RFC 4122 canonical UUID (account-service identifies a party by UUID). */
export function looksLikeUuid(raw: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(raw.trim())
}

/** Full ISO 13616 mod-97 validation (shape + checksum). */
export function isValidIban(raw: string): boolean {
  const v = normalizeIban(raw)
  if (!hasIbanShape(v)) return false
  // Move the first four chars to the end, then replace letters with 2-digit codes.
  const rearranged = v.slice(4) + v.slice(0, 4)
  const numeric = rearranged.replace(/[A-Z]/g, ch => String(ch.charCodeAt(0) - 55))
  // mod-97 over a long numeric string, processed in chunks to avoid BigInt overflow.
  let remainder = 0
  for (let i = 0; i < numeric.length; i += 7) {
    remainder = Number(`${remainder}${numeric.slice(i, i + 7)}`) % 97
  }
  return remainder === 1
}
