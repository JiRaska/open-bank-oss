# Four-eyes: binding an approval to the request that consumed it

`AuthorizeInterceptor` pauses a `four_eyes_required` action with a pending approval and, on the
retry that carries `X-Approval-Id`, consumes the approval (`markExecuted`) before the resource body
runs. After that, the approval's status is `EXECUTED` — and so is every approval consumed by an
earlier call. A resource that re-checks the approval itself (the sanctions agent-maker CLEAR does,
to record the human checker as reviewer) therefore cannot tell "consumed now" from "consumed last
week" by the status alone.

`FourEyesConsumption` (request-scoped, `com.openbank.libs.authz`) answers that: the interceptor sets
`consumedApprovalId` to the id it consumed for the current request, and nothing else can set it
(`internal` setter). A resource binds to the approval by requiring the header id to equal it:

```kotlin
@Inject lateinit var fourEyesConsumption: Instance<FourEyesConsumption>

val consumed = fourEyesConsumption.takeIf { it.isResolvable }?.get()?.consumedApprovalId
val approval = approvalIdHeader()?.takeIf { it == consumed }?.let { approvals.find(it) }
```

It is `null` when four-eyes did not run for the call (the policy did not flag the action) or the
call was paused, so a replayed id is refused even if a policy regression stops gating the action.
Outside an HTTP request there is no request scope; the record is skipped with a warning and a
binding resource fails closed.

Two rules for a resource that reads the approval id itself:

- **Never take `X-Approval-Id` as a method parameter.** `@Authorize` fingerprints every business
  argument; the first call (no header) and the retry (with it) then never match, and every retry
  mints a new pending approval. Read it from `HttpHeaders`.
- **Accept `EXECUTED` only.** The interceptor consumes before the body runs; an `APPROVED` approval
  in the body was not consumed by four-eyes.

Reference: `SanctionsResourceV3.review`, `AgentClearFourEyesFlowIT` (#16593).
