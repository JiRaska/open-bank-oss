# Request concurrency permit lifecycle

The shared HTTP rate-limit filter allocates one permit lease per admitted request. Response filtering, request completion and connection close may all attempt release; an atomic guard makes release idempotent so the semaphore and active-request gauge change exactly once. This also returns permits when a streaming or aborted response never follows the ordinary response-filter path. `X-RateLimit-Remaining` reports the permits available at response filtering time, not a future reservation.
