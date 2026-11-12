#!/usr/bin/env bash
# Enforces the GA-only release policy for pins that are not Gradle dependencies: GitHub Action
# references (the release version is the comment next to the commit SHA), workflow container
# images, Compose images, and Dockerfile base images.
#
# Usage: scripts/check-ga-pins.sh [file ...]
# Without arguments it checks the files this repository actually pins versions in.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# Delimiters matter: "1.8.4" and "9.8.2-jdk27" are releases, "4.0.0-M1" and "28-ea+12" are not.
pattern='(^|[-._])(alpha|beta|rc|cr|m[0-9]+|ea|preview|snapshot|dev|canary|nightly|next)([0-9]|[-._]|$)'
violations=0

check() { # $1 = file:line, $2 = token
  local where="$1" token="${2,,}"
  token="${token%"${token##*[![:space:]]}"}"
  if [[ "$token" =~ $pattern ]]; then
    echo "pre-release pin at ${where}: ${2}"
    violations=$((violations + 1))
  fi
}

files=("$@")
if [ "${#files[@]}" -eq 0 ]; then
  # shellcheck disable=SC2012 # these globs name the files that pin versions, nothing else.
  mapfile -t files < <(ls -1 .github/workflows/*.yml .github/dependabot.yml \
    Dockerfile docker-compose*.yml 2>/dev/null | sort -u)
fi

for file in "${files[@]}"; do
  [ -f "$file" ] || continue
  lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    where="$file:$lineno"
    # Action pins carry their release version in the trailing comment next to the SHA.
    if [[ "$line" == *uses:* && "$line" == *"#"* ]]; then
      check "$where" "${line##*#}"
    fi
    # Container references: "image: name:tag", "FROM name:tag", and docker run arguments.
    # shellcheck disable=SC2046 # word splitting is how the leading space is dropped here.
    for image in $(printf '%s\n' "$line" \
      | grep -oE '(^|[[:space:]])[a-zA-Z0-9][a-zA-Z0-9./_-]*:[a-zA-Z0-9][a-zA-Z0-9._-]*' || true); do
      check "$where" "${image##*:}"
    done
  done < "$file"
done

if [ "$violations" -gt 0 ]; then
  echo "Found ${violations} pre-release pin(s). Ship the newest GA release instead; see docs/ci-cd.md."
  exit 1
fi
echo "GA-only pins: no pre-release action, image or base image found."
