import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.noonmaru.routy",
  appName: "Routy",
  webDir: "out",
  server: {
    androidScheme: "https",
  },
};

export default config;
