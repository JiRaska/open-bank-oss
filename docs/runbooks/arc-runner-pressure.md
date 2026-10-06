# CI runner queue pressure

This runbook covers `FinOpsCiRunnerQueueHigh`.

1. Confirm that assigned runners remain high relative to running runners
   for longer than the alert window. Record the time and affected workflow
   groups; a brief CI burst can clear without intervention.
2. Compare queued jobs, runner capacity, and recent controller events.
   Separate a normal burst from runners that fail to register or jobs that
   remain assigned after a runner disappears.
3. Check whether the concurrent CI queue is delaying required checks. If it
   is, tell the repository owner which runs are affected and retain the
   controller event and job evidence for diagnosis.
4. Verify that the assigned-to-running ratio and job queue recover. Record
   the cause before proposing a capacity or alert-threshold change.

Changing runner capacity or controller settings requires the normal
infrastructure review.
