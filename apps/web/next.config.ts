import type { NextConfig } from "next";
import path from "node:path";

const target = process.env.ROUTY_TARGET ?? "web";

const nextConfig: NextConfig = {
  devIndicators: false,
  env: {
    NEXT_PUBLIC_ROUTY_TARGET: target,
  },
  output: target === "app" ? "export" : "standalone",
  outputFileTracingRoot: path.resolve(process.cwd(), "../.."),
  transpilePackages: ["@routy/contracts"],
  images: {
    unoptimized: target === "app",
  },
};

export default nextConfig;
