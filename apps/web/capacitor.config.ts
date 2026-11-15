import type { CapacitorConfig } from "@capacitor/cli";
import identity from "@routy/contracts/identity";

const config: CapacitorConfig = {
  appId: identity.applicationIds.mobile,
  appName: "Routy",
  webDir: "out",
  backgroundColor: "#F2F5F1",
  ios: {
    // CSS owns all four safe areas; UIKit must not add another scroll-view inset.
    contentInset: "never",
  },
  plugins: {
    SystemBars: {
      insetsHandling: "css",
      initialViewportFitValueHint: "cover",
      style: "LIGHT",
      hidden: false,
    },
  },
  server: {
    hostname: "localhost",
    iosScheme: "capacitor",
    androidScheme: "https",
  },
};

export default config;
