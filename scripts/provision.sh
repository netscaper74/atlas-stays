#!/usr/bin/env bash
# One Atlas database user and one database per attendee.
#
#   export PROJECT_ID=<atlas project id> CLUSTER_HOST=<cluster>.xxxxx.mongodb.net
#   ./scripts/provision.sh attendees.txt
#
# attendees.txt has one name per line (# comments and blank lines are skipped).
# Writes attendees.csv and envs/<slug>.env. Needs the Atlas CLI (atlas auth login), python3 and openssl.
# Users get readWrite on stays_<slug> only, and Atlas deletes them after DAYS days (default 3).
set -euo pipefail
: "${PROJECT_ID:?set PROJECT_ID}"
: "${CLUSTER_HOST:?set CLUSTER_HOST, e.g. jug-istanbul.abcde.mongodb.net}"
INPUT="${1:?usage: provision.sh attendees.txt}"
DAYS="${DAYS:-3}"
EXPIRES=$(python3 -c "import datetime as d;print((d.datetime.now(d.timezone.utc)+d.timedelta(days=$DAYS)).strftime('%Y-%m-%dT%H:%M:%SZ'))")

# Same rules as Config.slug() in Java, so ATTENDEE maps to the same database.
slug() {
  python3 - "$1" <<'PY'
import re, sys, unicodedata
s = sys.argv[1].replace("ı", "i").replace("İ", "I")
s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
s = re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:40]
print(s or "dev")
PY
}

mkdir -p envs
echo "attendee,slug,database,username,password,MONGODB_URI" > attendees.csv
while IFS= read -r name || [[ -n "$name" ]]; do
  name="${name%%#*}"; name="$(echo "$name" | xargs)"
  [[ -z "$name" ]] && continue
  s=$(slug "$name")
  db="stays_$s"; user="jug_$s"
  pass=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 20)
  atlas dbusers create --projectId "$PROJECT_ID" --username "$user" --password "$pass" \
    --role "readWrite@$db" --deleteAfter "$EXPIRES" --desc "JUG Istanbul workshop: $name" >/dev/null
  uri="mongodb+srv://$user:$pass@$CLUSTER_HOST/?retryWrites=true&w=majority&appName=atlas-stays"
  echo "$name,$s,$db,$user,$pass,$uri" >> attendees.csv
  {
    echo "MONGODB_URI=$uri"
    echo "ATTENDEE=$s"
    echo "EMBED_MODEL=${EMBED_MODEL:-voyage-4}"
    [[ -n "${LLM_API_KEY:-}" ]] && echo "LLM_API_KEY=$LLM_API_KEY"
    [[ -n "${LLM_BASE_URL:-}" ]] && echo "LLM_BASE_URL=$LLM_BASE_URL"
    [[ -n "${LLM_MODEL:-}" ]] && echo "LLM_MODEL=$LLM_MODEL"
    [[ -n "${ANTHROPIC_API_KEY:-}" ]] && echo "ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY"
    echo "PORT=8080"
  } > "envs/$s.env"
  echo "created $user -> $db" >&2
done < "$INPUT"
echo "Wrote attendees.csv and envs/*.env (users expire $EXPIRES). Keep both out of git." >&2
