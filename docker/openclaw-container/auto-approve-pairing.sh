#!/bin/bash
set -eu

log() {
    echo "[pairing-bootstrap] $1"
}

enabled="$(printf '%s' "${AUTO_APPROVE_PROXY_PAIRING:-true}" | tr '[:upper:]' '[:lower:]')"
if [[ "$enabled" != "true" ]]; then
    log "AUTO_APPROVE_PROXY_PAIRING is disabled"
    exit 0
fi

window_seconds="${AUTO_APPROVE_PROXY_PAIRING_WINDOW_SECONDS:-180}"
if ! [[ "$window_seconds" =~ ^[0-9]+$ ]]; then
    log "Invalid AUTO_APPROVE_PROXY_PAIRING_WINDOW_SECONDS='$window_seconds', defaulting to 180"
    window_seconds=180
fi

devices_dir="/home/openclaw/.openclaw/devices"
paired_file="$devices_dir/paired.json"
mkdir -p "$devices_dir"

already_paired() {
    [[ -s "$paired_file" ]] && jq -e '((type == "array") or (type == "object")) and (length > 0)' "$paired_file" >/dev/null 2>&1
}

approve_latest() {
    su - openclaw -s /bin/sh -c "HOME=/home/openclaw openclaw devices approve --latest" >/tmp/openclaw-auto-pair.log 2>&1
}

deadline=$((SECONDS + window_seconds))
log "Watching for middleware pairing requests for ${window_seconds}s"

while (( SECONDS < deadline )); do
    if already_paired; then
        log "Existing paired device found; skipping auto-approval"
        exit 0
    fi

    if approve_latest; then
        log "Approved latest pairing request"
        exit 0
    fi

    sleep 2
done

log "No pairing request approved during bootstrap window"
