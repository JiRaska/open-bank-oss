# openbank-loyalty-service — service notes

The Lístek ledger (ADR-0282). Read the ADR for the decisions; this file is the operational tail —
what will bite you in this code specifically.

## What a Lístek is, and the four things it must never become

A Lístek is a closed-loop unit of bank obligation: earned for financial health, redeemed against
the reviewed `BenefitCatalog`, expiring after a published period. Four absences keep it outside
the definition of electronic money, and each is a property of the code rather than a policy
someone remembers:

- **No cash-out.** `Leaves` has no conversion toward any monetary type. `LeafDomainTest` asserts
  the absence by reflection, because a KDoc is not a control.
- **No price in any currency.** `Benefit` has no currency or amount field, asserted the same way.
- **No transfer between parties.** There is no path in this service that moves value from one
  `party_id` to another. ADR-0282 D9's household gift will be the single exception and is not
  built.
- **No purchase.** Lístky are minted by `EarnLeavesUseCase` alone, from a catalogued achievement.

Adding any of the four is not a feature change, it is a change of regulatory class. It needs an
ADR and a legal review, not a pull request.

## The traps in this code

- **`EarnOutcome.Capped` is not an error and not a success.** The annual cap refusing an award is
  a legitimate, expected outcome that writes nothing. It has its own type, its own HTTP shape
  (200 with `outcome: CAPPED`) and its own metric (`openbank_loyalty_earn_capped_total`). Never
  fold it into either neighbour: a programme where every party has hit the cap looks exactly like
  a quiet week from any success metric, and that is the state you most need to see. The platform
  has already shipped the opposite arrangement once, where a disabled push adapter returned
  `success = true` and every undelivered notification was counted as delivered.
- **`BenefitGrantStatus.GRANTED` means owed and published, never applied.** The delivering engines
  (billing, interest, fx) are money-path and are not called from here yet. Nothing in this service
  or its events is a delivery receipt. If you wire an engine, give its confirmation its own status
  value; do not repurpose `GRANTED`.
- **`remaining_leaves` is the only column ever updated in place.** Everything else is append-only.
  The FIFO consumption pointer lives there so a burn can debit the oldest lots while `leaves`
  keeps the original award for audit. A burn writes its own row; it never rewrites an earn.
- **Both entity classes carry an application-assigned `@Id`, so `persist` is INSERT-only.** Panache
  reactive cannot distinguish transient from detached on a non-null assigned id, so a `persist` on
  an existing row fails at flush with a duplicate-key violation rather than updating. The one
  in-place change goes through an explicit `update` query. If you ever need a real upsert here,
  it is `Panache.getSession().flatMap { it.merge(entity) }`, never `persist`.
- **Every column is named explicitly and this service sets no `physical-naming-strategy`.** The two
  facts belong together and `check-entity-column-names.py` enforces the pairing. Hibernate's
  implicit name is the property verbatim, folded to lower case, so `occurredAt` would ask for
  `occurredat`. It is right for single-word properties and wrong for multi-word ones, which is
  exactly why such a class reads as consistent.
- **The scheduled methods are `suspend fun`.** A plain `@Scheduled` method has no Vert.x context,
  so a reactive Panache call inside `runBlocking` throws `HR000068` and the job aborts silently
  having done nothing. A test that calls the method directly cannot see this, because the direct
  call supplies the context the scheduler does not.
- **`openbank.outbox.dispatch-enabled` is `true` in `application.yaml` and `false` in code.** The
  default is false by house convention; a service that forgets the yaml key dispatches nothing,
  forever, with `attempt_count` stuck at 0 and no error anywhere. The integration test sets it
  back to false on purpose, because the rows it asserts on are the evidence and a background
  dispatch would delete them mid-assertion.

## What is not built

- No consumer wires any earn source yet. `LeafEarnSource` declaring a variant is not a claim that
  a producer exists — read `infrastructure/`, not the catalogue, to find out what is live.
- No engine delivers a benefit. See the `GRANTED` note above.
- No provisioning journal is posted. `ProvisioningSummaryUseCase` computes and publishes the
  number; `openbank-billing-service` owns the balanced journal into the ledger, because that
  service is money-path and this one is not.
- No gitops manifest, so nothing deploys this yet. That is deliberate for the first slice, and it
  is why `check-deploy-coverage.sh` is silent about it: the gate's subject is a released component
  that HAS a gitops workload but no build.
