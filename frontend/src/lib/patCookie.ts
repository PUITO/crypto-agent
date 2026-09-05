/** GitHub PAT 仅存浏览器 Cookie，不上传到服务器配置库。 */
const COOKIE = "ca_github_pat";
const REPO_COOKIE = "ca_sync_repo";

export function setPat(pat: string, days = 30) {
  const maxAge = days * 86400;
  document.cookie = `${COOKIE}=${encodeURIComponent(pat)}; path=/; max-age=${maxAge}; SameSite=Strict`;
}

export function getPat(): string {
  const m = document.cookie.match(new RegExp(`(?:^|; )${COOKIE}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : "";
}

export function clearPat() {
  document.cookie = `${COOKIE}=; path=/; max-age=0`;
}

export function setSyncRepo(repo: string, days = 365) {
  document.cookie = `${REPO_COOKIE}=${encodeURIComponent(repo)}; path=/; max-age=${days * 86400}; SameSite=Strict`;
}

export function getSyncRepo(): string {
  const m = document.cookie.match(new RegExp(`(?:^|; )${REPO_COOKIE}=([^;]*)`));
  return m ? decodeURIComponent(m[1]) : "";
}

export function syncAuthHeaders(): Record<string, string> {
  const h: Record<string, string> = {};
  const pat = getPat();
  const repo = getSyncRepo();
  if (pat) h["X-GitHub-PAT"] = pat;
  if (repo) h["X-Sync-Repo"] = repo;
  return h;
}
