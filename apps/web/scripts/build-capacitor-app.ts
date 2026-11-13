import { spawnSync } from "node:child_process";
import { constants } from "node:fs";
import { access, cp, lstat, mkdir, rename, rm } from "node:fs/promises";
import { createRequire } from "node:module";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import nextEnv from "@next/env";

const projectRoot = process.cwd();
const staging = resolve(projectRoot, ".app-build");
const pending = resolve(projectRoot, ".app-out-next");
const output = resolve(projectRoot, "out");
const excluded = ["src/app/api", "src/app/admin", "src/server", "src/generated", "src/proxy.ts"]
  .map((path) => path.split("/").join(sep));

async function cleanGenerated(path: string) {
  if (dirname(path) !== projectRoot || ![".app-build", ".app-out-next", "out"].includes(basename(path))) {
    throw new Error(`Unsafe generated path: ${path}`);
  }
  const stat = await lstat(path).catch((error: NodeJS.ErrnoException) => {
    if (error.code !== "ENOENT") throw error;
    return null;
  });
  if (stat?.isSymbolicLink()) throw new Error(`Refusing to clean symlink: ${path}`);
  await rm(path, { recursive: true, force: true });
}

async function main() {
  nextEnv.loadEnvConfig(projectRoot, false);
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL?.trim();
  if (!configured) throw new Error("앱 빌드에는 NEXT_PUBLIC_API_BASE_URL의 실제 HTTPS API 주소가 필요합니다.");
  const url = new URL(configured);
  if (url.protocol !== "https:" || !/^\/api\/?$/.test(url.pathname)
    || url.username || url.password || url.search || url.hash) {
    throw new Error("앱 API 주소는 인증정보·쿼리 없이 https://호스트/api 형식이어야 합니다.");
  }

  await cleanGenerated(staging);
  await cleanGenerated(pending);
  await mkdir(staging, { recursive: true });
  try {
    for (const directory of ["src", "public"]) {
      await cp(join(projectRoot, directory), join(staging, directory), {
        recursive: true,
        filter: (source) => {
          const path = relative(projectRoot, source);
          return !excluded.some((item) => path === item || path.startsWith(item + sep));
        },
      });
    }
    for (const file of ["next.config.ts", "package.json", "tsconfig.json"]) {
      await cp(join(projectRoot, file), join(staging, file));
    }
    for (const path of excluded) {
      try {
        await access(join(staging, path), constants.F_OK);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
        throw error;
      }
      throw new Error(`앱 빌드에 서버 전용 경로가 포함됐습니다: ${path}`);
    }
    const require = createRequire(import.meta.url);
    const result = spawnSync(process.execPath, [require.resolve("next/dist/bin/next"), "build", staging, "--webpack"], {
      cwd: projectRoot,
      env: { ...process.env, NODE_ENV: "production", ROUTY_TARGET: "app", NEXT_PUBLIC_API_BASE_URL: configured.replace(/\/+$/, "") },
      stdio: "inherit",
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(`앱 빌드 실패: ${result.status}`);
    await access(join(staging, "out/index.html"), constants.R_OK);
    await cp(join(staging, "out"), pending, { recursive: true });
    await cleanGenerated(output);
    await rename(pending, output);
    console.info("Capacitor 정적 빌드 완료: out/");
  } finally {
    await cleanGenerated(staging);
    await cleanGenerated(pending);
  }
}

await main();
