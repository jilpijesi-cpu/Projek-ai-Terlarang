#!/usr/bin/env bash
# Mengganti `uses: pemilik/repo@tag` di workflow dengan SHA commit (anti tag yang digeser), tag asli disimpan sebagai komentar.
# Pakai (butuh jaringan + git):   bash scripts/pin-actions.sh            -> ubah .github/workflows/*.yml
#                                 bash scripts/pin-actions.sh --dry-run  -> hanya tampilkan perubahan
set -euo pipefail
GIT="${GIT:-git}"
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
cd "$(dirname "$0")/.."

resolve() { # $1=pemilik/repo  $2=tag/branch  -> SHA commit (tag beranotasi dikupas ke commit-nya)
  local out sha
  out="$(GIT_TERMINAL_PROMPT=0 $GIT ls-remote "https://github.com/$1.git" "refs/tags/$2" "refs/tags/$2^{}" "refs/heads/$2" </dev/null)" || return 1
  sha="$(printf '%s\n' "$out" | awk '$2 ~ /\^\{\}$/ {print $1; exit}')"
  [ -n "$sha" ] || sha="$(printf '%s\n' "$out" | awk 'NR==1 {print $1}')"
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || return 1
  printf '%s' "$sha"
}

tmp=""
cleanup() { [ -n "$tmp" ] && rm -f "$tmp" || true; }
trap cleanup EXIT

status=0
for f in .github/workflows/*.yml .github/workflows/*.yaml; do
  [ -f "$f" ] || continue
  tmp="$(mktemp)"
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^([[:space:]]*-?[[:space:]]*uses:[[:space:]]*)[\"\']?([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(/[A-Za-z0-9_./-]+)?@([A-Za-z0-9_.-]+)[\"\']?[[:space:]]*(#.*)?$ ]]; then
      pre="${BASH_REMATCH[1]}"; repo="${BASH_REMATCH[2]}"; sub="${BASH_REMATCH[3]:-}"; ref="${BASH_REMATCH[4]}"
      if [[ "$ref" =~ ^[0-9a-f]{40}$ ]]; then printf '%s\n' "$line" >> "$tmp"; continue; fi   # sudah SHA
      if sha="$(resolve "$repo" "$ref")"; then
        printf '%s%s%s@%s # %s\n' "$pre" "$repo" "$sub" "$sha" "$ref" >> "$tmp"
        echo "$f: $repo$sub@$ref -> ${sha:0:12}"
      else
        echo "GAGAL menyelesaikan $repo@$ref (jaringan? tag tidak ada?); baris dibiarkan." >&2
        printf '%s\n' "$line" >> "$tmp"; status=1
      fi
    else
      printf '%s\n' "$line" >> "$tmp"
    fi
  done < "$f"
  if [ "$DRY" = 1 ]; then rm -f "$tmp"; else cat "$tmp" > "$f"; rm -f "$tmp"; fi
  tmp=""
done
exit $status
