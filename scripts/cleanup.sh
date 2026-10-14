#!/usr/bin/env bash
# Deletes the workshop users and drops their databases.
#
#   export PROJECT_ID=<atlas project id>
#   ADMIN_URI='mongodb+srv://admin:...@<cluster>/' ./scripts/cleanup.sh attendees.csv
set -euo pipefail
: "${PROJECT_ID:?set PROJECT_ID}"
: "${ADMIN_URI:?set ADMIN_URI to a user that can drop databases}"
CSV="${1:-attendees.csv}"
tail -n +2 "$CSV" | while IFS=, read -r name slug db user pass uri; do
  atlas dbusers delete "$user" --projectId "$PROJECT_ID" --force >/dev/null 2>&1 || echo "user $user already gone" >&2
  mongosh "$ADMIN_URI" --quiet --eval "db.getSiblingDB('$db').dropDatabase()" >/dev/null
  echo "removed $user and $db" >&2
done
