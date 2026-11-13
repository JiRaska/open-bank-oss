#!/usr/bin/env bash
# Helpers for safely reusing providerPactTest discovery from the advisory warm-up.

service_ci_provider_task_list_is_proven() {
  local listing="$1" service="$2"
  [ -s "$listing" ] || return 1
  awk -v task="> Task :${service}:tasks" \
      -v project="Tasks runnable from project ':${service}'" '
    $0 == task { if (saw_task) bad = 1; saw_task = 1; next }
    saw_task && !in_project && /^> Task / { bad = 1 }
    $0 == project { if (!saw_task || in_project || found) bad = 1; in_project = 1; next }
    /^Tasks runnable from project / { bad = 1 }
    in_project && /^> Task / { bad = 1 }
    in_project && $0 ~ /^[[:space:]]*providerPactTest([[:space:]]|$)/ { found = 1 }
    /^BUILD SUCCESSFUL([[:space:]]|$)/ {
      if (!saw_task || !in_project || !found) bad = 1
      else success = 1
      in_project = 0
    }
    /^BUILD FAILED([[:space:]]|$)/ { bad = 1 }
    END { exit !(saw_task && found && success && !bad) }
  ' "$listing"
}

service_ci_provider_task_reuse_is_proven() {
  local proof="$1" service="$2"
  [ -f "$proof" ] && [ "$(cat "$proof")" = "$service" ]
}

service_ci_record_provider_task_proof() {
  local status="$1" listing="$2" service="$3" proof="$4"
  [ "$status" -eq 0 ] || return 1
  service_ci_provider_task_list_is_proven "$listing" "$service" || return 1
  printf '%s' "$service" > "$proof"
}
