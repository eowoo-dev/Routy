import type { CapacitorConfig } from "@capacitor/cli";
import identity from "@routy/contracts/identity";

const config: CapacitorConfig = {
  appId: identity.applicationIds.mobile,
  appName: "Routy",
  webDir: "out",
  server: {
    hostname: "localhost",
    iosScheme: "capacitor",
    androidScheme: "https",
  },
};

export default config;
