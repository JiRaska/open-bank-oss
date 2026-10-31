# Historical FINREP broker verification

The dedicated workflow is manually dispatched on reviewed `main` only. It verifies
the independently pinned historical consumer Pact against the checked-out provider
and publishes only after complete interaction outcomes, final zero-skip test XML,
and post-build source revalidation agree. Automatic Pact publication is disabled.
Broker credentials remain in the runner environment; public evidence excludes broker
origins, HAL links, credentials, and raw transport responses.

## Publication attempts

Only the first attempt of a GitHub run is permitted. The publisher claims its local
attempt marker before making one POST, and does not retry an ambiguous request.
GitHub reruns are rejected. The marker protects this run; it is not a durable
deduplication service across separate dispatches or replacement runners.

After an interrupted request, transport failure, or failed receipt readback, do not
automatically dispatch a replacement run. A maintainer must first investigate the
previous broker receipt and its exact provider version, consumer version, content,
and build URL. Authorize a new manual dispatch only after resolving the previous
outcome. A green controlled loopback test does not establish broker publication.

## Evidence limits

Checkout proofs check the reviewed runner context, committed source bytes and index,
and allowed generated build directories. They are not cryptographic attestations
of the runner, dependency resolver, compiler, or complete toolchain. The workflow
must fetch trusted `main` freshly and build without cached task outputs.
