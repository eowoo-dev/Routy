import type { Metadata, Viewport } from "next";
import identity from "@routy/contracts/identity";
import type { ReactNode } from "react";
import EmotionRegistry from "./EmotionRegistry";
import GlobalStyles from "./GlobalStyles";
import { Providers } from "./providers";

export const metadata: Metadata = {
  title: "Routy | 나에게 맞는 길, 나아지는 운전",
  description: "맞춤형 운전 연습과 주행 복습을 연결하는 루티",
  applicationName: "Routy",
  metadataBase: new URL(identity.service.webOrigin),
  authors: [{ name: identity.team.nameEn, url: identity.team.website }],
  creator: identity.team.nameEn,
  publisher: identity.team.nameEn,
  referrer: "no-referrer",
  icons: {
    icon: [
      { url: "/favicon.ico", sizes: "16x16 32x32 48x48", type: "image/x-icon" },
      { url: "/icon.svg", type: "image/svg+xml" },
    ],
    apple: [{ url: "/apple-touch-icon.png", sizes: "180x180", type: "image/png" }],
  },
  manifest: "/site.webmanifest",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#175F55",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <EmotionRegistry>
          <GlobalStyles />
          <Providers><div className="safe-area-screen">{children}</div></Providers>
        </EmotionRegistry>
      </body>
    </html>
  );
}
