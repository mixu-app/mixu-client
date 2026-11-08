#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Submodule pin audit for mixu-client.
#
# Fails when a mount is missing, off its pinned commit, in conflict, or carries
# uncommitted changes. `git submodule status` alone reports the first three but
# its "-dirty" suffix depends on describe output, so dirtiness is checked with an
# explicit `git status` per mount.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1
bad=0

while IFS= read -r line; do
  path="$(printf '%s' "$line" | awk '{print $2}')"
  case "$line" in
  -*)
    printf 'UNINITIALISED: %s (run `git submodule update --init --recursive`)\n' "$path"
    bad=1
    ;;
  +*)
    printf 'PIN MISMATCH: %s (checked-out commit is not the pinned commit)\n' "$path"
    bad=1
    ;;
  U*)
    printf 'CONFLICT: %s\n' "$path"
    bad=1
    ;;
  esac
done < <(git submodule status --recursive)

while IFS= read -r path; do
  [ -n "$path" ] || continue
  if [ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]; then
    printf 'DIRTY MOUNT: %s (uncommitted changes inside a read-only mount)\n' "$path"
    bad=1
  fi
done < <(git submodule foreach --recursive --quiet 'printf "%s\n" "$displaypath"' 2>/dev/null)

if [ "$bad" -eq 0 ]; then
  printf 'pins ok: %s mounts complete and clean\n' "$(git submodule status --recursive | wc -l)"
fi
exit "$bad"
