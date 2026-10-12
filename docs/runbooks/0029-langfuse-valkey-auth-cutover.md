# Langfuse Valkey authentication cutover (#11980)

The Langfuse BullMQ queue is a single Valkey pod backed by `emptyDir`. Restarting
that pod discards queued work. The `litellm` Argo Application normally auto-syncs
the entire `ai-platform` directory; updating the web, worker, and Valkey manifests
in one merge gives no safe ordering or pause for queue reconciliation. Argo sync
waves wait for a preceding Deployment to become Healthy, so they cannot safely
bridge a temporary client/server authentication mismatch.

The service owner must use a maintenance window and record the following evidence
in the private deployment record before merging the AUTH manifest change:

1. Merge and sync the ExternalSecret declaration separately. Verify the
   ExternalSecret is Ready and its target Secret contains a **nonempty** password.
   Do not print the value or put it in a ticket, log, or command argument.
2. In a separate reviewed change, remove `spec.syncPolicy.automated` from the
   `litellm` Application. Confirm the live Application has stopped auto-syncing.
   The required `langfuse-valkey-auth-cutover` gate rejects the AUTH change if
   the *base commit* still has auto-sync, so a same-change pause cannot pass.
3. Stop new ingestion and background enqueues by an owner-approved method.
   Account for waiting, active, delayed, and failed BullMQ jobs, and reconcile
   each unfinished trace against its durable destination. Prove the queue is
   drained and no new jobs can arrive. If this cannot be proved, stop the cutover;
   this repository does not provide a safe automatic drain procedure.
4. Merge the separately reviewed AUTH change only after its required checks pass.
   Manually sync the web, worker, and Valkey resources during the maintenance
   window. Their order may cause a temporary connection failure; keep ingestion
   stopped until all three are healthy. Valkey readiness must authenticate with
   the projected password, rather than accepting a TCP connection alone.
5. Verify an unauthenticated `PING` receives `NOAUTH`, an authenticated `PING`
   succeeds, and the worker and exporter reconnect without AUTH errors. Verify
   fresh traces reach their durable destination before resuming ingestion.
6. Restore auto-sync in another reviewed change only after the runtime checks.

Rollback is also a maintenance action: stop ingestion, reconcile any new queue
work, then restore the prior manifests. A Valkey rollback restarts the `emptyDir`
pod and may discard work. Do not roll back blindly or expose the password in
diagnostic output.
