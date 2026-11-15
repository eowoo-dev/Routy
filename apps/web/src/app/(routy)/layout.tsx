import { Suspense, type ReactNode } from "react";
import RoutyApp from "@/features/routy/RoutyApp";

export default function RoutyLayout({ children }: { children: ReactNode }) {
  return <><Suspense fallback={<main role="status">루티 화면을 불러오고 있어요…</main>}><RoutyApp /></Suspense>{children}</>;
}
