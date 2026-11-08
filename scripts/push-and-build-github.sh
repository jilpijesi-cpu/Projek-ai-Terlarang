#!/usr/bin/env bash
# Mempertahankan riwayat Git dari repository GitHub yang sudah ada, melakukan commit ke branch pengembangan,
# mem-push perubahan, memantau GitHub Actions CI, dan melaporkan commit hash, URL workflow, serta artefak APK.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

REPO="${GITHUB_REPO:-jilpijesi-cpu/Proyek-AI-Mythos}"
TOKEN="${GITHUB_TOKEN:-}"
BRANCH="${GITHUB_BRANCH:-main}"

if [ -z "$REPO" ] || [ -z "$TOKEN" ]; then
  echo "ERROR: GITHUB_TOKEN belum diatur di .env / AI Studio Secrets untuk push ke ${REPO}." >&2
  exit 1
fi

REMOTE_URL="https://x-access-token:${TOKEN}@github.com/${REPO}.git"

if [ ! -d .git ]; then
  git init
fi

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
fi

echo "[1/4] Mengambil riwayat Git dari repository $REPO (tanpa menghapus riwayat)..."
git fetch origin --prune

DEFAULT_BRANCH="$(git remote show origin | awk '/HEAD branch/ {print $NF}')"
if [ -z "$DEFAULT_BRANCH" ] || [ "$DEFAULT_BRANCH" = "(unknown)" ]; then
  DEFAULT_BRANCH="main"
fi

if git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
  git reset --soft "refs/remotes/origin/$BRANCH"
elif git show-ref --verify --quiet "refs/remotes/origin/$DEFAULT_BRANCH"; then
  git reset --soft "refs/remotes/origin/$DEFAULT_BRANCH"
fi

git checkout -B "$BRANCH"
git config user.name "${GIT_AUTHOR_NAME:-Mythos Agent}"
git config user.email "${GIT_AUTHOR_EMAIL:-mythos-agent@users.noreply.github.com}"

echo "[2/4] Menambahkan perubahan dan membuat commit pada branch $BRANCH..."
git add -A
if git diff --cached --quiet; then
  echo "Tidak ada perubahan baru untuk di-commit."
else
  git commit -m "feat(mythos-v6): security hardening, CaseLedger, Indonesian playbooks, Google Workspace OAuth 2.0, and extended GitHub tools"
fi

COMMIT_SHA="$(git rev-parse HEAD)"
echo "Commit SHA: $COMMIT_SHA"

echo "[3/4] Mem-push branch $BRANCH ke repository $REPO..."
git push -u origin "$BRANCH"

echo "[4/4] Memantau GitHub Actions workflow untuk commit $COMMIT_SHA..."
python3 - "$REPO" "$TOKEN" "$COMMIT_SHA" <<'PY'
import json, sys, time, urllib.request

repo, token, sha = sys.argv[1], sys.argv[2], sys.argv[3]
headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}

def get_json(url):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))

run_id = None
html_url = None
for _ in range(30):
    data = get_json(f"https://api.github.com/repos/{repo}/actions/runs?head_sha={sha}&per_page=5")
    runs = data.get("workflow_runs", [])
    if runs:
        run_id = runs[0]["id"]
        html_url = runs[0]["html_url"]
        break
    time.sleep(4)

if not run_id:
    print(f"Workflow belum ditemukan untuk commit {sha}. Periksa https://github.com/{repo}/actions")
    sys.exit(1)

print(f"Workflow URL: {html_url}")
while True:
    run = get_json(f"https://api.github.com/repos/{repo}/actions/runs/{run_id}")
    status = run.get("status")
    conclusion = run.get("conclusion")
    print(f"Status: {status} | Conclusion: {conclusion}")
    if status == "completed":
        break
    time.sleep(10)

arts = get_json(f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/artifacts").get("artifacts", [])
print("\n=== HASIL AKHIR GITHUB ACTIONS ===")
print(f"Commit Hash : {sha}")
print(f"Workflow URL: {html_url}")
print(f"Conclusion  : {conclusion}")
for a in arts:
    print(f"Artifact    : {a['name']} -> https://github.com/{repo}/actions/runs/{run_id}/artifacts/{a['id']}")
if conclusion != "success":
    sys.exit(1)
PY
