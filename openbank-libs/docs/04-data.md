

`OutboxTableShape.sentAtColumn` (výchozí `sent_at`) pojmenovává sloupec s časem doručení u tabulky starší než jednotný tvar; `incentive_outbox` v incentive používá `published_at` (#11902).

### Booking execution regimes

`BookingOperatingMode` in libs-domain owns the closed execution-regime vocabulary
`DOMESTIC`, `FOS` and `BRANCH`. Transaction projections, journal attribution and
offline reporting use that same type. It describes how a versioned booking context
executes; company eligibility, countries, branch identifiers and approvals remain
versioned product configuration, rather than being encoded in the enum.
