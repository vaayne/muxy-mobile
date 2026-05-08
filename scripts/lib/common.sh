#!/usr/bin/env bash
# Shared helpers for scripts/release-*.sh.
# Source this file from a release script: `source "$(dirname "$0")/lib/common.sh"`

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

log() { printf "\n\033[1;36m==>\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33mwarn:\033[0m %s\n" "$*" >&2; }
die() { printf "\033[1;31merror:\033[0m %s\n" "$*" >&2; exit 1; }

TIMINGS=()
RUN_START=0
STEP_NAME=""
STEP_START=0

format_duration() {
  local s="$1"
  if (( s < 60 )); then
    printf "%ds" "$s"
  elif (( s < 3600 )); then
    printf "%dm %02ds" $((s / 60)) $((s % 60))
  else
    printf "%dh %02dm %02ds" $((s / 3600)) $(((s % 3600) / 60)) $((s % 60))
  fi
}

run_started() {
  RUN_START=$(date +%s)
}

step() {
  if [[ -n "$STEP_NAME" ]]; then
    local elapsed=$(( $(date +%s) - STEP_START ))
    TIMINGS+=("$STEP_NAME|$elapsed")
  fi
  STEP_NAME="$1"
  STEP_START=$(date +%s)
  log "$STEP_NAME"
}

step_done() {
  if [[ -n "$STEP_NAME" ]]; then
    local elapsed=$(( $(date +%s) - STEP_START ))
    TIMINGS+=("$STEP_NAME|$elapsed")
    STEP_NAME=""
  fi
}

print_summary() {
  step_done
  local total=$(( $(date +%s) - RUN_START ))
  local title="${1:-Release Summary}"
  shift || true

  printf "\n\033[1;32m"
  printf "==========================================\n"
  printf " %s\n" "$title"
  printf "==========================================\033[0m\n"

  while [[ $# -gt 0 ]]; do
    printf "  \033[1m%-18s\033[0m %s\n" "$1:" "$2"
    shift 2 || break
  done

  printf "\n  \033[1mSteps:\033[0m\n"
  local entry name secs
  for entry in "${TIMINGS[@]}"; do
    name="${entry%|*}"
    secs="${entry##*|}"
    printf "    %-42s  %s\n" "$name" "$(format_duration "$secs")"
  done

  printf "\n  \033[1mTotal:\033[0m %s\n\n" "$(format_duration "$total")"
}

load_env() {
  local env_file="$REPO_ROOT/.env"
  if [[ ! -f "$env_file" ]]; then
    die ".env not found at $env_file. Copy .env.example to .env and fill in values."
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    die "Required env var '$name' is not set. See .env.example."
  fi
}

require_file() {
  local var_name="$1"
  require_var "$var_name"
  local path="${!var_name}"
  if [[ "$path" != /* ]]; then
    path="$REPO_ROOT/$path"
  fi
  if [[ ! -f "$path" ]]; then
    die "$var_name points to '${!var_name}' but the file does not exist (resolved to '$path')."
  fi
  printf -v "$var_name" '%s' "$path"
}

confirm() {
  local prompt="${1:-Continue?}"
  read -r -p "$prompt [y/N] " response
  [[ "$response" =~ ^[Yy]$ ]]
}

validate_version() {
  local version="$1"
  if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    die "Version must be in X.Y.Z format (got: $version)"
  fi
}

validate_numeric() {
  local label="$1" value="$2"
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    die "$label must be numeric (got: $value)"
  fi
}
