#!/usr/bin/env bash
# Fails when a jpackage application image would not start. Two checks are possible:
#
#   --input <dir>   the directory handed to jpackage --input, i.e. what the installer will carry.
#                   Works on every OS and is the cheap place to catch a jar that never reached the
#                   image.
#   --image <dir>   a built application image; adds a launch of its own launcher when --launch is
#                   given, so a classpath mistake that jpackage accepted still fails the job.
#   --installer <file>  the finished installer (.deb, .msi or .dmg). It is unpacked and searched, so
#                   the asset that is about to be published is checked, not just the input
#                   directory jpackage was handed.
#
# The shared desktop module is checked explicitly: it is a project dependency, so when its jar had
# not been built yet the copy step skipped it silently and every installer shipped broken, failing
# at startup with NoClassDefFoundError: com/nexus/desktop/shared/ui/ThemeManager.
#
# Usage: scripts/verify-app-image.sh --input <dir>|--image <dir> [--launch] [--launch-timeout 25]
set -uo pipefail

mode=""
directory=""
launch=false
launch_timeout=25

while [ "$#" -gt 0 ]; do
  case "$1" in
    --input|--image|--installer) mode="${1#--}"; directory="${2:-}"; shift 2 ;;
    --launch) launch=true; shift ;;
    --launch-timeout) launch_timeout="${2:-25}"; shift 2 ;;
    *) echo "usage: $0 --input <dir>|--image <dir> [--launch] [--launch-timeout <seconds>]" >&2; exit 2 ;;
  esac
done

if [ -z "$mode" ] || [ -z "$directory" ]; then
  echo "usage: $0 --input <dir>|--image <dir>|--installer <file> [--launch] [--launch-timeout <seconds>]" >&2
  exit 2
fi

unpacked=""
detach=""
cleanup() {
  [ -n "$detach" ] && hdiutil detach "$detach" -quiet >/dev/null 2>&1
  [ -n "$unpacked" ] && rm -rf "$unpacked"
}
trap cleanup EXIT

if [ "$mode" = "installer" ]; then
  installer="$directory"
  if [ ! -f "$installer" ]; then
    echo "no installer at $installer" >&2
    exit 1
  fi
  unpacked="$(mktemp -d)"
  case "$installer" in
    *.deb)
      if command -v dpkg-deb >/dev/null 2>&1; then
        dpkg-deb -x "$installer" "$unpacked"
      else
        # Any Debian-derived host can unpack a .deb with ar and a tar reader.
        (cd "$unpacked" && ar x "$installer")
        data="$(find "$unpacked" -maxdepth 1 -name 'data.tar*' -print | head -1)"
        if command -v 7z >/dev/null 2>&1; then 7z x -y -o"$unpacked" "$data" >/dev/null; fi
        tar -xf "$unpacked/data.tar" -C "$unpacked" 2>/dev/null || tar -xf "$data" -C "$unpacked"
      fi
      ;;
    *.msi)
      # An administrative install (msiexec /a) lays out exactly the files the package installs, so
      # the check reads the directory a user would get. It goes through cmd because bash on Windows
      # rewrites a lone /a as a path, and msiexec then waits on a usage dialog instead of exiting.
      # 7-Zip is the fallback for hosts without msiexec.
      payload="$unpacked/payload"
      mkdir -p "$payload"
      msi_status="skipped"
      if [ -x /c/Windows/System32/msiexec.exe ] || command -v msiexec >/dev/null 2>&1; then
        msi="$(cygpath -w "$installer" 2>/dev/null || printf '%s' "$installer")"
        target="$(cygpath -w "$payload" 2>/dev/null || printf '%s' "$payload")"
        log="$(cygpath -w "$unpacked/msiexec.log" 2>/dev/null || printf '%s' "$unpacked/msiexec.log")"
        timeout 240 cmd //c "msiexec /a \"$msi\" /qn /l*v \"$log\" TARGETDIR=\"$target\"" >/dev/null 2>&1
        msi_status="$?"
      fi
      if [ -z "$(find "$payload" -name '*.jar' -print 2>/dev/null | head -1)" ]; then
        seven_zip="$(command -v 7z || command -v 7za || true)"
        [ -n "$seven_zip" ] || seven_zip='/c/Program Files/7-Zip/7z.exe'
        [ -x "$seven_zip" ] || command -v "$seven_zip" >/dev/null 2>&1 || {
          echo "no way to unpack $installer" >&2
          exit 1
        }
        "$seven_zip" x -y -o"$unpacked/msi" "$installer" >/dev/null 2>&1 || true
        for cab in "$unpacked"/msi/*.cab; do
          [ -f "$cab" ] || continue
          "$seven_zip" x -y -o"$payload" "$cab" >/dev/null 2>&1 || true
        done
        if [ -z "$(find "$payload" -name '*.jar' -print 2>/dev/null | head -1)" ]; then
          echo "msiexec status: $msi_status; 7-Zip unpacked: $(ls "$unpacked/msi" 2>/dev/null | head -5 | tr '\n' ' ')" >&2
          [ -f "$unpacked/msiexec.log" ] && tail -20 "$unpacked/msiexec.log" >&2
        fi
      fi
      ;;
    *.dmg)
      mkdir -p "$unpacked/mount"
      hdiutil attach "$installer" -nobrowse -readonly -mountpoint "$unpacked/mount" -quiet
      detach="$unpacked/mount"
      ;;
    *)
      echo "unsupported installer type: $installer" >&2
      exit 1
      ;;
  esac
  directory="$unpacked"
  jars="$directory"
else
  if [ "$mode" = "image" ]; then
    jars="$directory/lib/app"
  else
    jars="$directory"
  fi
fi

if [ ! -d "$jars" ]; then
  echo "no packaged classes at $jars" >&2
  exit 1
fi

if [ "$mode" = "installer" ] && [ -z "$(find "$jars" -maxdepth 8 -name '*.jar' -print 2>/dev/null | head -1)" ]; then
  echo "could not unpack any jar from $installer" >&2
  exit 1
fi

list_jar() {
  if command -v jar >/dev/null 2>&1; then
    jar tf "$1" 2>/dev/null
  elif command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$1" 2>/dev/null
  else
    return 1
  fi
}

failures=0
fail() {
  echo "app image check failed: $1"
  failures=$((failures + 1))
}

# An unpacked installer buries the jars a few levels down, so search the whole tree there.
find_depth=1
[ "$mode" = "installer" ] && find_depth=8
shared_jar="$(find "$jars" -maxdepth "$find_depth" -name 'desktop-shared-*.jar' -print 2>/dev/null | head -1)"
if [ -z "$shared_jar" ]; then
  fail "desktop-shared jar is missing from $jars"
elif ! list_jar "$shared_jar" | grep -qx 'com/nexus/desktop/shared/ui/ThemeManager.class'; then
  fail "$shared_jar does not contain com/nexus/desktop/shared/ui/ThemeManager.class"
fi

app_jar="$(find "$jars" -maxdepth "$find_depth" \( -name 'desktop-user-*.jar' -o -name 'desktop-admin-*.jar' \) \
  -print 2>/dev/null | head -1)"
if [ -z "$app_jar" ]; then
  fail "neither the client nor the admin module jar is in $jars"
fi

if [ "$launch" = true ]; then
  if [ "$mode" != "image" ]; then
    echo "--launch needs --image <dir>, not --input" >&2
    exit 2
  fi
  launcher="$directory/bin/$(basename "$directory")"
  if [ ! -x "$launcher" ]; then
    fail "no executable launcher at $launcher"
  elif ! command -v xvfb-run >/dev/null 2>&1; then
    fail "xvfb-run is not installed, so the launcher cannot be tried headlessly"
  else
    # A JavaFX application shows its first window and stays open, so the timeout killing it is the
    # success path; anything else means the launcher gave up.
    timeout "$launch_timeout" xvfb-run -a "$launcher" >"${TMPDIR:-/tmp}/app-image-launch.log" 2>&1
    status="$?"
    if [ "$status" -ne 124 ]; then
      echo "--- launcher output ---"
      tail -40 "${TMPDIR:-/tmp}/app-image-launch.log"
      fail "$launcher exited with status $status instead of staying open"
    fi
  fi
fi

if [ "$failures" -gt 0 ]; then
  echo "Found $failures problem(s) in $directory."
  exit 1
fi
echo "App image check passed for $directory."
