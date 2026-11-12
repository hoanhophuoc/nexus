#!/usr/bin/env bash
# Fails when a container reference in this repository can move on its own. Every image must carry an
# exact version tag (one containing a digit) or a digest, so a rebuild months later resolves the same
# bits. Locally built images are not references and are not checked here.
#
# Usage: scripts/check-image-pins.sh [file ...]
# Without arguments it checks the files this repository pulls images in.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

files=("$@")
if [ "${#files[@]}" -eq 0 ]; then
  # shellcheck disable=SC2012 # these globs name the files that pull images, nothing else.
  mapfile -t files < <(ls -1 Dockerfile Dockerfile.* docker-compose*.yml .github/workflows/*.yml 2>/dev/null | sort -u)
fi

violations=0
fail() {
  echo "unpinned container image at $1: $2"
  violations=$((violations + 1))
}

for file in "${files[@]}"; do
  [ -f "$file" ] || continue
  lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    reference=""
    case "$line" in
      *image:*) reference=$(printf '%s\n' "${line#*image:}" | awk '{print $1}' | tr -d '"'"'"',') ;;
    esac
    if [ -z "$reference" ] && [[ "${line^^}" =~ ^[[:space:]]*FROM[[:space:]] ]]; then
      reference=$(printf '%s\n' "${line#*FROM }" | awk '{print $1}')
      # A stage alias (FROM build AS x) is not an image reference.
      if [[ "$reference" != *:* && "$reference" != */* ]]; then
        continue
      fi
    fi
    [ -n "$reference" ] || continue
    if [[ "$reference" == *@sha256:* ]]; then
      continue
    fi
    if [[ "$reference" != *:* ]]; then
      fail "$file:$lineno" "$reference (no tag)"
      continue
    fi
    tag="${reference##*:}"
    if [ "${tag,,}" = "latest" ]; then
      fail "$file:$lineno" "$reference (latest)"
    elif [[ ! "$tag" =~ [0-9] ]]; then
      fail "$file:$lineno" "$reference (tag '$tag' pins no version)"
    fi
  done < "$file"
done

if [ "$violations" -gt 0 ]; then
  echo "Found ${violations} unpinned image reference(s). Pin an exact release tag or a digest; see docs/ci-cd.md."
  exit 1
fi
echo "Pinned images: every container reference carries a version tag or digest."
