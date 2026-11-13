import "server-only";
import type { HealthResponse } from "@routy/contracts";

export function getHealth(): HealthResponse {
  return { status: "ok", service: "routy", timestamp: new Date().toISOString() };
}
