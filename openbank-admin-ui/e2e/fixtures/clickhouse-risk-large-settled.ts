// SPDX-License-Identifier: Apache-2.0

// Captured shape of ClickHouse JSON output with date_time_output_format=iso for
// settled_at DateTime64(3, 'UTC'). Values are synthetic and contain no customer data.
export const clickHouseRiskLargeSettledRows = [
  {
    transaction_id: 'fixture-tx-001',
    settled_at: '2026-10-19T12:34:56.789Z',
    amount: '125000.00',
    currency_code: 'CZK',
    rail: 'SEPA',
    transaction_type: 'TRANSFER',
  },
]
