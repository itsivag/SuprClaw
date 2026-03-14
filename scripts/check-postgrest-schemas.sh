#!/bin/bash
set -euo pipefail

# Audits the self-hosted Supabase PostgREST schema list against live PostgreSQL state.
#
# Run this on the Supabase host. By default it is read-only and exits non-zero when drift exists.
# Use --repair to rewrite PGRST_DB_SCHEMAS in .env and recreate the rest service.

STACK_DIR="${STACK_DIR:-/opt/supabase/podman}"
DB_CONTAINER="${DB_CONTAINER:-supabase-db}"
BASE_SCHEMAS="${BASE_SCHEMAS:-public,storage,graphql_public}"
REPAIR=false

usage() {
    cat <<'EOF'
Usage: check-postgrest-schemas.sh [--repair] [--stack-dir DIR] [--db-container NAME]

Options:
  --repair            Rewrite PGRST_DB_SCHEMAS in .env and recreate the rest service
  --stack-dir DIR     Supabase Podman stack directory (default: /opt/supabase/podman)
  --db-container NAME PostgreSQL container name (default: supabase-db)
  -h, --help          Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repair)
            REPAIR=true
            shift
            ;;
        --stack-dir)
            STACK_DIR="$2"
            shift 2
            ;;
        --db-container)
            DB_CONTAINER="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "[ERROR] Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

ENV_FILE="$STACK_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "[ERROR] Missing env file: $ENV_FILE" >&2
    exit 1
fi

if ! command -v podman >/dev/null 2>&1; then
    echo "[ERROR] podman is required" >&2
    exit 1
fi

trim_csv_to_lines() {
    tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | sed '/^$/d'
}

current_value="$(grep '^PGRST_DB_SCHEMAS=' "$ENV_FILE" | head -n 1 | cut -d= -f2- || true)"
current_normalized="$(
    printf '%s' "$current_value" |
        trim_csv_to_lines |
        awk '!seen[$0]++' |
        paste -sd, -
)"

live_tenant_schemas="$(
    podman exec "$DB_CONTAINER" psql -U postgres -d postgres -At -c \
        "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'proj\\_%' ESCAPE '\\' ORDER BY schema_name;" |
        sed '/^$/d' |
        sort -u
)"

desired_value="$BASE_SCHEMAS"
if [[ -n "$live_tenant_schemas" ]]; then
    desired_value="$desired_value,$(printf '%s\n' "$live_tenant_schemas" | paste -sd, -)"
fi

current_tenant_schemas="$(
    printf '%s' "$current_value" |
        trim_csv_to_lines |
        grep '^proj_' |
        sort -u || true
)"

stale_schemas="$(
    comm -23 \
        <(printf '%s\n' "$current_tenant_schemas" | sed '/^$/d') \
        <(printf '%s\n' "$live_tenant_schemas" | sed '/^$/d') || true
)"

missing_schemas="$(
    comm -13 \
        <(printf '%s\n' "$current_tenant_schemas" | sed '/^$/d') \
        <(printf '%s\n' "$live_tenant_schemas" | sed '/^$/d') || true
)"

stale_display="${stale_schemas//$'\n'/, }"
missing_display="${missing_schemas//$'\n'/, }"
[[ -n "$stale_display" ]] || stale_display="<none>"
[[ -n "$missing_display" ]] || missing_display="<none>"

echo "PostgREST schema audit"
echo "  stack_dir: $STACK_DIR"
echo "  db_container: $DB_CONTAINER"
echo "  current_env: ${current_normalized:-<empty>}"
echo "  desired_env: $desired_value"
echo "  stale_proj_schemas: $stale_display"
echo "  missing_proj_schemas: $missing_display"

if [[ "$current_normalized" == "$desired_value" ]]; then
    echo "[OK] PGRST_DB_SCHEMAS matches live PostgreSQL schemas."
    exit 0
fi

echo "[WARN] PGRST_DB_SCHEMAS drift detected."

if [[ "$REPAIR" != true ]]; then
    echo "Run again with --repair to rewrite $ENV_FILE and recreate the rest service."
    exit 1
fi

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

if grep -q '^PGRST_DB_SCHEMAS=' "$ENV_FILE"; then
    sed "s|^PGRST_DB_SCHEMAS=.*|PGRST_DB_SCHEMAS=$desired_value|" "$ENV_FILE" > "$tmp_file"
else
    cat "$ENV_FILE" > "$tmp_file"
    printf '\nPGRST_DB_SCHEMAS=%s\n' "$desired_value" >> "$tmp_file"
fi

mv "$tmp_file" "$ENV_FILE"

(
    cd "$STACK_DIR"
    podman compose up -d --force-recreate rest
)

echo "[OK] Repaired PGRST_DB_SCHEMAS and recreated rest."
