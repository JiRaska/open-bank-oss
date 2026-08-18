// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')

describe('payments money-path form accessibility contract', () => {
  it('associates domestic and SEPA labels with every visible control', () => {
    const ids = [
      'domestic-transfer-scope', 'domestic-technical-account-code', 'domestic-debtor-account-id',
      'domestic-debtor-account-number', 'domestic-debtor-name', 'domestic-creditor-account-number',
      'domestic-creditor-name', 'domestic-amount', 'domestic-message', 'domestic-variable-symbol',
      'domestic-specific-symbol', 'domestic-constant-symbol', 'domestic-priority', 'domestic-end-to-end',
      'domestic-statement-label', 'sepa-debtor-iban', 'sepa-creditor-iban', 'sepa-creditor-name',
      'sepa-amount', 'sepa-bic', 'sepa-end-to-end', 'sepa-remittance', 'sepa-vop-payee-name',
      'payments-sct-search', 'payments-search',
    ]
    for (const id of ids) {
      expect(page).toContain(`id="${id}"`)
      expect(page).toContain(`htmlFor="${id}"`)
    }
    expect(page).toContain("id=\"domestic-debtor-bank-code\" aria-label={t('Kód banky plátce', 'Debtor bank code')}")
    expect(page).toContain("id=\"domestic-creditor-bank-code\" aria-label={t('Kód banky příjemce', 'Creditor bank code')}")
  })
})
