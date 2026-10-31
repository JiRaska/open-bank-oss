# Image rewrite UPDATE regression

The pinned Kyverno CLI 1.19.1 supports per-result `operation: UPDATE` and supplies
`oldObject == object`. These native CEL tests verify unchanged image references
are not rewritten on Pod, Job, Deployment or CronJob UPDATE, including generated
controller rules and both container lists. The Job is synthetic, completed and deleting.
The old policy fails all four expectations; CREATE rewrite and exclusion coverage
remains in the parent harness, which runs these tests too.

The CLI cannot represent distinct old/new resources. These cases therefore do not
prove an intentional image change or newly added sidecar on UPDATE. That requires
an actual engine test with separate admission old/new objects; no live admission
or cluster change is performed by this suite.
