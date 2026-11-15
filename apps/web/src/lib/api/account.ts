import { Capacitor } from "@capacitor/core";
import { apiUrl } from "./apiUrl";
import { createAccountClient, type SessionStore } from "./accountClient";

function storageKey() { return `org.eowoo.routy.session:${new URL(apiUrl("")).origin}`; }
const store: SessionStore = {
  async get() {
    const { SecureStorage } = await import("@aparajita/capacitor-secure-storage");
    const value = await SecureStorage.get(storageKey(), false, false);
    return typeof value === "string" && /^[a-f0-9]{64}$/.test(value) ? value : null;
  },
  async set(token) {
    const { SecureStorage, KeychainAccess } = await import("@aparajita/capacitor-secure-storage");
    await SecureStorage.set(storageKey(), token, false, false, KeychainAccess.whenUnlockedThisDeviceOnly);
  },
  async remove() {
    const { SecureStorage } = await import("@aparajita/capacitor-secure-storage");
    await SecureStorage.remove(storageKey(), false);
  },
};
const client = createAccountClient({ native: () => Capacitor.isNativePlatform(), store, url: apiUrl, fetch: (...args) => fetch(...args) });
export const request = client.request;

export async function downloadNativeArchive(sessionId: string) {
  const { Filesystem, Directory } = await import("@capacitor/filesystem");
  const { Share } = await import("@capacitor/share");
  const headers = await client.authorization();
  if (!headers.Authorization) throw new Error("로그인 후 다시 시도해주세요.");
  const url = apiUrl(`account/driving-records/${encodeURIComponent(sessionId)}/archive`);
  if (new URL(url).protocol !== "https:") throw new Error("HTTPS 서버 주소를 확인해주세요.");
  const result = await Filesystem.downloadFile({
    url, path: `routy-${sessionId}.ndjson`, directory: Directory.Cache,
    headers: { ...headers, Origin: Capacitor.getPlatform() === "ios" ? "capacitor://localhost" : "https://localhost" },
  });
  if (!result.path) throw new Error("상세 평가를 내려받지 못했어요.");
  try { await Share.share({ title: "루티 상세 평가", files: [result.path] }); }
  finally { await Filesystem.deleteFile({ path: `routy-${sessionId}.ndjson`, directory: Directory.Cache }).catch(() => undefined); }
}
