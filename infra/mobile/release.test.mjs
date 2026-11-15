import { afterEach, test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { prepareSecrets, releaseConfig, requireSecrets, secretDirectory } from "./release.mjs";

const directories = [];
afterEach(() => { for (const dir of directories.splice(0)) rmSync(dir, { recursive: true, force: true }); });
function environment() {
  const directory = mkdtempSync(join(tmpdir(), "routy-release-test-")); directories.push(directory);
  return { RUNNER_TEMP: directory, ANDROID_KEYSTORE_BASE64: Buffer.from("fake-keystore").toString("base64"),
    ANDROID_KEYSTORE_PASSWORD: "not-a-real-password", ANDROID_KEY_ALIAS: "upload", ANDROID_KEY_PASSWORD: "not-a-real-password" };
}
test("default release targets verified closed track and creates increasing Android/iOS build number", () => {
  const a = releaseConfig({ GITHUB_RUN_NUMBER: "5" });
  const b = releaseConfig({ GITHUB_RUN_NUMBER: "6" });
  assert.equal(a.track, "0.1.0"); assert.equal(a.status, "draft"); assert.equal(a.packageName, "org.eowoo.routy");
  assert.equal(a.buildNumber, "1005"); assert.equal(Number(b.buildNumber), Number(a.buildNumber) + 1);
  assert.equal(releaseConfig({ RELEASE_BUILD_NUMBER: "7000" }).buildNumber, "7000");
});
test("public, open, internal and malformed track names cannot be uploaded", () => {
  for (const track of ["production", "PRODUCTION", "beta", "internal", "automotive:production", "0.1.0\nproduction", "../production"]) {
    assert.throws(() => releaseConfig({ PLAY_CLOSED_TRACK: track }), /closed testing/);
  }
});
test("invalid version numbers and release status are rejected before signing", () => {
  for (const value of ["0", "-1", "1.5", "2100000001", "99999999999999999999999", "1\ninject=value"]) assert.throws(() => releaseConfig({ RELEASE_BUILD_NUMBER: value }));
  assert.throws(() => releaseConfig({ GITHUB_RUN_NUMBER: "invalid" }));
  assert.throws(() => releaseConfig({ PLAY_RELEASE_STATUS: "inProgress" }));
  assert.throws(() => releaseConfig({ RELEASE_PLATFORM: "desktop" }));
});
test("secret preflight identifies missing names and does not leak supplied values", () => {
  assert.throws(() => requireSecrets({ PRESENT: "secret-value" }, ["PRESENT", "MISSING"]), error => error.message.includes("MISSING") && !error.message.includes("secret-value"));
  assert.throws(() => prepareSecrets("android", {}), /ANDROID_KEYSTORE_BASE64/);
});
test("build-only signing needs no Play credentials and keeps secrets out of outputs", () => {
  const env = environment(), output = prepareSecrets("android", env);
  assert.equal(readFileSync(output.ANDROID_KEYSTORE_PATH, "utf8"), "fake-keystore");
  assert.equal(output.PLAY_JSON_PATH, undefined);
  assert.ok(!JSON.stringify(output).includes(env.ANDROID_KEYSTORE_PASSWORD));
  if (process.platform !== "win32") assert.equal(statSync(output.ANDROID_KEYSTORE_PATH).mode & 0o777, 0o600);
});
test("upload requires valid service account JSON and cleans partially written signing files on error", () => {
  const env = { ...environment(), RELEASE_UPLOAD: "true", PLAY_SERVICE_ACCOUNT_JSON: "not-json" };
  assert.throws(() => prepareSecrets("android", env), /Invalid Play/);
  assert.ok(!readdirSync(env.RUNNER_TEMP).includes("routy-mobile-release"));
  env.PLAY_SERVICE_ACCOUNT_JSON = JSON.stringify({ type: "service_account", client_email: "test@example.test", private_key: "PRIVATE KEY placeholder" });
  const output = prepareSecrets("android", env);
  assert.equal(JSON.parse(readFileSync(output.PLAY_JSON_PATH, "utf8")).client_email, "test@example.test");
  assert.equal(secretDirectory(env), join(env.RUNNER_TEMP, "routy-mobile-release"));
});
test("iOS build-only accepts certificate/profile/team but upload also requires API key", () => {
  const env = { ...environment(), IOS_CERTIFICATE_BASE64: "Y2VydA==", IOS_CERTIFICATE_PASSWORD: "certificate-password", IOS_PROVISION_PROFILE_BASE64: "cHJvZmlsZQ==", APPLE_TEAM_ID: "ABCDEFGHIJ" };
  const output = prepareSecrets("ios", env);
  assert.equal(output.ASC_PRIVATE_KEY_PATH, undefined);
  assert.equal(readFileSync(output.IOS_PROFILE_PATH, "utf8"), "profile");
  assert.throws(() => prepareSecrets("ios", { ...env, RELEASE_UPLOAD: "true" }), /ASC_KEY_ID/);
  assert.throws(() => prepareSecrets("ios", { ...env, APPLE_TEAM_ID: "invalid" }), /Invalid Apple/);
});
