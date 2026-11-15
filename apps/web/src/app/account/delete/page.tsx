import type { Metadata } from "next";
import DeleteAccount from "@/features/account/DeleteAccount";
export const metadata: Metadata = { title: "회원 탈퇴 | 루티", robots: { index: false, follow: true } };
export default function DeleteAccountPage() { return <DeleteAccount/>; }
