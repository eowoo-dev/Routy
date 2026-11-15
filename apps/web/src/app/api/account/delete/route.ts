import { api, jsonBody, response } from "@/server/driving-records/http";
import { ApiProblem, object } from "@/server/driving-records/validation";
import { checkPassword, limited, requireAccount, sameOrigin, setSession } from "@/server/account/auth";
import { isMobileRequest } from "@/server/account/origins";
import { eraseAccount } from "@/server/account/erasure-transaction";
import { db } from "@/server/db";

export const runtime = "nodejs";
export function POST(request: Request) { return api(async () => {
  sameOrigin(request);
  const user = await requireAccount(request);
  await limited("delete-account", user.id, 5);
  const body = object(await jsonBody(request, 4096));
  if (Object.keys(body).some(key => !["password", "confirm"].includes(key)) || body.confirm !== true) {
    throw new ApiProblem(400, "CONFIRMATION_REQUIRED", "삭제되는 항목을 확인하고 탈퇴에 동의해주세요.");
  }
  if (typeof body.password !== "string" || body.password.length < 10 || body.password.length > 128 || !await checkPassword(body.password, user.passwordHash)) {
    throw new ApiProblem(403, "REAUTH_FAILED", "현재 비밀번호를 확인해주세요.");
  }
  const erased = await db.$transaction(tx => eraseAccount(tx, user.id), { timeout: 120_000 });
  if (!erased) throw new ApiProblem(401, "LOGIN_REQUIRED", "이미 탈퇴했거나 로그인이 만료됐습니다.");
  const result = response({ deleted: true });
  return isMobileRequest(request) ? result : setSession(result, null);
}); }
