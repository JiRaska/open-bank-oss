# Reactive authorization and feature guards

Authorization and feature-flag interceptors run their guard decision before invoking the target.
The shared invocation bridge supports Kotlin suspension, Mutiny Uni and CompletionStage while
preserving the calling Vert.x context. Deferred guard work must not block the event loop. A denied
operation does not enter its target; an asynchronous failure terminates the returned asynchronous
result instead of escaping the subscriber callback.

Cancellation is a separate outcome from denial and provider unavailability. FeatureClient rethrows
CancellationException before applying its existing provider-failure fallback. Runtime callers that
may execute on an event loop use enabledSuspending/requireEnabledSuspending; these offload a blocking
provider through runInterruptible on the IO dispatcher. The existing synchronous methods remain
available for callers that already own a worker thread. The additive extension methods have generated
JVM ABI descriptors; the normal ABI check must remain independent of snapshot generation.

The continuation bridge necessarily casts the erased continuation at the JVM interception boundary.
Throwable handling is restricted to terminating asynchronous results; cancellation still propagates.
Do not turn cancellation into a successful guard decision or invoke the target before a decision.

AuthorizeInterceptorReactiveDispatchTest and FeatureFlagSuspendDispatchTest cover dispatch,
cancellation and context behavior. FlagdProviderTest covers interrupted/failing provider recovery.
PartyReactiveGuardIT adds real HTTP/CDI tests for delayed allow and deny with Uni and CompletionStage:
a heartbeat on the single event loop must complete while a decision is pending, request identity and
headers must be retained, and the target must run only for an allowed decision on its original context.
These HTTP tests do not establish disconnected-client cancellation; that remains interceptor-level
coverage. Authorization mutation targets include the extracted dispatcher so moving logic does not
silently remove it from the enforced mutation lane.
