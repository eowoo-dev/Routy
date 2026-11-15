import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const identity = JSON.parse(readFileSync(join(root, "packages/contracts/identity.json"), "utf8"));

export function releaseConfig(env = process.env) {
  const platform = env.RELEASE_PLATFORM ?? "both";
  if (!["android", "ios", "both"].includes(platform)) throw Error("Invalid release platform");
  const base = env.MOBILE_BUILD_NUMBER_BASE || "1000";
  const run = env.GITHUB_RUN_NUMBER || "1";
  if (!/^\d+$/.test(base) || !/^\d+$/.test(run)) throw Error("Invalid automatic build number");
  const number = env.RELEASE_BUILD_NUMBER || String(Number(base) + Number(run));
  if (!/^[1-9]\d*$/.test(number) || !Number.isSafeInteger(Number(number)) || Number(number) > 2_100_000_000) throw Error("Build number must be between 1 and 2100000000");
  const track = env.PLAY_CLOSED_TRACK || "0.1.0";
  // This workflow only targets the explicitly configured closed testing track.
  if (["production", "beta", "internal"].includes(track.toLowerCase()) || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(track)) throw Error("A closed testing API track name is required");
  const status = env.PLAY_RELEASE_STATUS || "draft";
  if (!["draft", "completed"].includes(status)) throw Error("Invalid closed release status");
  return { platform, buildNumber: number, track, status, packageName: identity.applicationIds.mobile };
}

export function requireSecrets(env, names) {
  const missing = names.filter(name => !env[name]?.trim());
  if (missing.length) throw Error(`Missing GitHub Actions settings: ${missing.join(", ")}`);
}

function secretFile(path, value, base64 = false) {
  if (base64 && (!/^[A-Za-z0-9+/=\s]+$/.test(value) || !Buffer.from(value, "base64").length)) throw Error("Invalid base64 secret file");
  writeFileSync(path, base64 ? Buffer.from(value, "base64") : value, { mode: 0o600 });
  chmodSync(path, 0o600);
}

export function secretDirectory(env = process.env) {
  if (!env.RUNNER_TEMP) throw Error("RUNNER_TEMP is required");
  return join(resolve(env.RUNNER_TEMP), "routy-mobile-release");
}

export function prepareSecrets(platform, env = process.env) {
  const upload = env.RELEASE_UPLOAD === "true";
  const names = platform === "android"
    ? ["ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", ...(upload ? ["PLAY_SERVICE_ACCOUNT_JSON"] : [])]
    : platform === "ios"
      ? ["IOS_CERTIFICATE_BASE64", "IOS_CERTIFICATE_PASSWORD", "IOS_PROVISION_PROFILE_BASE64", "APPLE_TEAM_ID", ...(upload ? ["ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY_BASE64"] : [])]
      : null;
  if (!names) throw Error("Select a single platform for signing");
  requireSecrets(env, names);
  const directory = secretDirectory(env);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  chmodSync(directory, 0o700);
  const values = {};
  try {
    if (platform === "android") {
      values.ANDROID_KEYSTORE_PATH = join(directory, "upload.jks");
      secretFile(values.ANDROID_KEYSTORE_PATH, env.ANDROID_KEYSTORE_BASE64, true);
      if (upload) {
        let service;
        try { service = JSON.parse(env.PLAY_SERVICE_ACCOUNT_JSON); } catch { throw Error("Invalid Play service account JSON"); }
        if (service.type !== "service_account" || typeof service.client_email !== "string" || !service.private_key?.includes("PRIVATE KEY")) throw Error("Invalid Play service account JSON");
        values.PLAY_JSON_PATH = join(directory, "play.json");
        secretFile(values.PLAY_JSON_PATH, env.PLAY_SERVICE_ACCOUNT_JSON);
      }
    } else {
      if (!/^[A-Z0-9]{10}$/.test(env.APPLE_TEAM_ID) || (upload && !/^[A-Z0-9]+$/.test(env.ASC_KEY_ID))) throw Error("Invalid Apple team or API key identifier");
      values.IOS_CERTIFICATE_PATH = join(directory, "distribution.p12");
      values.IOS_PROFILE_PATH = join(directory, "app.mobileprovision");
      secretFile(values.IOS_CERTIFICATE_PATH, env.IOS_CERTIFICATE_BASE64, true);
      secretFile(values.IOS_PROFILE_PATH, env.IOS_PROVISION_PROFILE_BASE64, true);
      if (upload) {
        values.ASC_PRIVATE_KEY_PATH = join(directory, "AuthKey.p8");
        secretFile(values.ASC_PRIVATE_KEY_PATH, env.ASC_PRIVATE_KEY_BASE64, true);
      }
    }
    return values;
  } catch (error) { rmSync(directory, { recursive: true, force: true }); throw error; }
}

function emit(values, file) {
  if (!file) throw Error("GitHub Actions output file is required");
  for (const [key, value] of Object.entries(values)) {
    if (String(value).includes("\n") || String(value).includes("\r")) throw Error("Multiline output is not supported");
    appendFileSync(file, `${key}=${value}\n`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const command = process.argv[2];
    if (command === "config") emit(releaseConfig(), process.env.GITHUB_OUTPUT);
    else if (command === "prepare") emit(prepareSecrets(process.argv[3]), process.env.GITHUB_ENV);
    else if (command === "cleanup") {
      const directory = secretDirectory();
      if (existsSync(directory)) rmSync(directory, { recursive: true, force: true });
    } else throw Error("Unknown release command");
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
