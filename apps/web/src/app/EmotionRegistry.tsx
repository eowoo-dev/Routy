"use client";

import createCache from "@emotion/cache";
import { CacheProvider } from "@emotion/react";
import { useServerInsertedHTML } from "next/navigation";
import { useState, type ReactNode } from "react";

export default function EmotionRegistry({ children }: { children: ReactNode }) {
  const [cache] = useState(() => {
    const instance = createCache({ key: "routy", prepend: true });
    instance.compat = true;
    return instance;
  });

  useServerInsertedHTML(() => {
    const keys = Object.keys(cache.inserted).filter(
      (key) => typeof cache.inserted[key] === "string",
    );
    if (keys.length === 0) return null;

    const styles = keys.map((key) => cache.inserted[key]).join("");
    keys.forEach((key) => { cache.inserted[key] = true; });

    return (
      <style
        data-emotion={`${cache.key} ${keys.join(" ")}`}
        dangerouslySetInnerHTML={{ __html: styles }}
      />
    );
  });

  return <CacheProvider value={cache}>{children}</CacheProvider>;
}
