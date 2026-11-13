import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const result = spawnSync(process.execPath, [require.resolve("next/dist/bin/next"), "build", "--webpack"], {
  cwd: process.cwd(),
  env: { ...process.env, NODE_ENV: "production", ROUTY_TARGET: "web" },
  stdio: "inherit",
});
if (result.error) throw result.error;
process.exit(result.status ?? 1);
