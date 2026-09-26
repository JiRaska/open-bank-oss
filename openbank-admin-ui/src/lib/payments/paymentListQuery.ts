// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

const LIST_QUERY_KEYS = ['status', 'debtorAccountId', 'limit', 'offset'] as const

/** Forward only the filters both payment list contracts explicitly expose. */
export function paymentListQuery(searchParams: URLSearchParams): string {
  const forwarded = new URLSearchParams()
  for (const key of LIST_QUERY_KEYS) {
    const value = searchParams.get(key)
    if (value !== null) forwarded.set(key, value)
  }
  const query = forwarded.toString()
  return query ? `?${query}` : ''
}
