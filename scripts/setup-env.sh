#!/usr/bin/env bash
# Writes a .env for docker-compose, with the encryption key already generated.
#
# The key is the one value that cannot be a sensible default: it has to be random, it has to be
# kept, and losing it makes every stored credential unreadable. Generating it by hand is the step
# people skip, and the backend then refuses to start with a message about a variable they have
# never heard of.
#
#   ./scripts/setup-env.sh
#
# Existing values are never overwritten — re-running it fills in what is missing and leaves the
# rest alone, so it is safe on a .env you have already edited.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example"
fi

# Reads a value from .env, ignoring commented-out lines.
current() {
  grep -E "^$1=" .env | tail -1 | cut -d= -f2- || true
}

set_value() {
  local key="$1" value="$2"
  if grep -qE "^$key=" .env; then
    # A temp file rather than sed -i: the value is base64 and may contain / and +, which would be
    # read as delimiters or backreferences in a sed replacement.
    awk -v k="$key" -v v="$value" -F= '
      $1 == k { print k "=" v; next } { print }
    ' .env > .env.tmp && mv .env.tmp .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

if [ -z "$(current CONCENTUS_SECRET_KEY)" ]; then
  if command -v openssl >/dev/null 2>&1; then
    key="$(openssl rand -base64 32)"
  else
    # Every image that runs this has one of the two; not worth a third fallback.
    key="$(head -c 32 /dev/urandom | base64)"
  fi
  set_value CONCENTUS_SECRET_KEY "$key"
  echo "Generated CONCENTUS_SECRET_KEY. Keep it with the database — changing it makes every"
  echo "stored credential unreadable."
else
  echo "CONCENTUS_SECRET_KEY already set — left alone."
fi

missing=()
[ -z "$(current CLAUDE_CODE_OAUTH_TOKEN)" ] && missing+=("CLAUDE_CODE_OAUTH_TOKEN  (run: claude setup-token)")
[ -z "$(current CONCENTUS_ADMIN_EMAIL)" ] && missing+=("CONCENTUS_ADMIN_EMAIL    (the account you sign in with)")

if [ ${#missing[@]} -gt 0 ]; then
  echo
  echo "Still to fill in, in .env:"
  for m in "${missing[@]}"; do echo "  - $m"; done
  echo
  echo "Then: docker compose up --build"
else
  echo
  echo "Ready. Run: docker compose up --build"
fi
