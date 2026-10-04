# IRRBB snapshot read

`GET /api/v1/risk/snapshots/{id}/irrbb` derives interest-rate risk in the banking book from a tied-out snapshot. The result is calculated on read and is not stored. Access requires `risk.snapshot.read` for the snapshot.

`curveSetId` is optional. When omitted, the service selects the newest curve set recorded for the snapshot's own `asOf` date. It never borrows a set from another day. Supply an explicit set ID to reproduce a particular calculation. An unknown snapshot or curve set returns 404; an untied snapshot returns 409; a missing same-day set or a set from another date returns 400. Optional `tier1Capital` must be a positive decimal. Without it, the outlier test may use the snapshot's own-funds Tier 1 in CZK; `tier1Source` and `tier1Gap` identify the source or why no usable figure exists.

Read `outlierTest.status` with the figures: `OK`, `EARLY_WARNING`, `BREACH`, or `NOT_EVALUABLE`. The last state means an input or comparable figure is missing; it is not a pass. The test uses the declared `irrbb-eve-outlier` limit when available, exposing its `limitId`, `threshold`, and `earlyWarning`. Ratios are meaningful only when the numerator and Tier 1 use the same currency.

Inspect `dataGaps` before using ΔEVE or ΔNII in a decision. The response identifies flat extrapolation beyond each curve's final pillar, including affected flows and their base present value; it also declares simplified deposit behaviour, absent prepayment modelling, included commercial margin, and instruments not yet projected. Treasury money-market deals are currently outside this IRRBB projection. The admin UI snapshot detail page shows the scenarios, outlier status, assumptions, and these gaps.

See ADR-0313 and ADR-0314 for the scenario and cash-flow model. The six shock scenarios and their configured sizes are described in the response assumptions; this endpoint does not infer model completeness from a successful HTTP response.
