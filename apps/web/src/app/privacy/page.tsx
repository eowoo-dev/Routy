import type { Metadata } from "next";
import Link from "next/link";
import { AccountPageShell } from "@/features/account/AccountPageShell";
import { deletionItems, privacy } from "@/shared/privacy";

export const metadata: Metadata = { title: "개인정보처리방침 | 루티", description: "루티의 개인정보 처리 항목, 보관 기간과 회원 탈퇴 및 삭제 절차를 안내합니다." };
export default function PrivacyPage() { return <AccountPageShell>
  <p className="eyebrow">내 정보에 대한 약속</p><h1>개인정보처리방침</h1><p className="muted">시행일: {privacy.effectiveDate} · 서비스: 루티(Routy)</p>
  <p>{privacy.operator}는 운전 연습 기록과 평가 복습을 제공하기 위해 아래와 같이 개인정보를 처리합니다.</p>
  <div className="notice"><p>개인정보 문의 담당자는 {privacy.contact}이며, <a className="policy-link" href={`mailto:${privacy.email}`}>{privacy.email}</a>로 문의할 수 있습니다.</p><Link className="policy-link" href="/account/delete">계정과 주행 기록 삭제하기</Link></div>
  <section><h2>1. 처리 목적과 수집 항목</h2><ul>
    <li>회원 가입과 본인 확인: 이메일, 이름, 비밀번호를 입력받습니다. 비밀번호는 원문이 아닌 해시로 저장합니다.</li>
    <li>로그인 유지: 브라우저의 세션 쿠키 또는 앱의 세션 토큰과 서버에 저장하는 토큰 해시 및 만료 시각을 사용합니다.</li>
    <li>차량 연결: 임의로 생성한 차량 앱 설치 ID, 설치 인증 비밀값의 해시, 연결 계정, 일회용 연결 코드의 해시와 처리 상태를 사용합니다.</li>
    <li>주행 기록과 복습: 차량 앱이 전송하는 주행 시작 및 종료 시각, 주행 시간, 속도, 조향, 기어, 방향지시등 등 센서 관측을 바탕으로 한 평가 결과와 상세 기록, 시연 시나리오 정보를 저장합니다. 전송된 평가 자료에 위치나 진행 방향이 포함된 경우 해당 자료에도 포함됩니다.</li>
    <li>서비스 보안과 장애 대응: 요청 시각, 접속 IP, 요청 주소, 기기 및 브라우저 정보와 오류 정보가 서버 또는 통신 제공자의 로그에서 처리될 수 있습니다. 요청 제한을 위한 식별값은 해시로 관리합니다.</li>
  </ul><p>가입 및 로그인은 이용자가 직접 입력할 때, 차량 정보와 주행 기록은 차량 앱에서 연결 또는 업로드할 때 처리합니다. 현재 광고나 광고 식별자를 이용한 맞춤형 광고 기능은 제공하지 않습니다.</p></section>
  <section><h2>2. 보유 기간</h2><ul>
    <li>계정과 연결된 주행 기록 및 상세 파일: 회원 탈퇴 시까지 보관합니다.</li>
    <li>로그인 세션: 발급 후 최대 30일 동안 유효하며 로그아웃 또는 탈퇴하면 폐기합니다.</li>
    <li>차량 연결 코드: 발급 후 5분 동안만 유효합니다. 연결 이력과 코드 해시는 차량 등록 해제 또는 탈퇴 시 삭제합니다.</li>
    <li>운영자가 관리하는 접속 및 보안 로그: 최대 {privacy.accessLogDays}일 동안 보관합니다. 로그에 비밀번호나 인증 토큰 원문을 기록하지 않도록 관리합니다.</li>
    <li>기존 DB 백업 사본: 생성일로부터 최대 {privacy.backupDays}일 이내 만료 및 삭제합니다. 탈퇴 시 운영 DB 삭제와 백업 사본 만료는 구분됩니다.</li>
  </ul></section>
  <section id="deletion"><h2>3. 회원 탈퇴와 삭제</h2><p>앱과 웹의 ‘회원 탈퇴’에서 로그인한 뒤 현재 비밀번호와 삭제 동의를 확인하면 운영 DB에서 다음 항목을 삭제합니다.</p><ul>{deletionItems.map(item => <li key={item}>{item}</li>)}</ul>
    <p>모든 로그인 세션과 이전 차량 설치 인증을 폐기합니다. 차량 기기에 남아 있는 원본 파일이나 외부로 내보낸 사본은 원격 삭제되지 않으므로, 차량 앱 데이터 초기화 또는 해당 사본 삭제를 별도로 진행해야 합니다.</p>
    <p>삭제된 기록의 재전송을 차단하기 위해 계정과의 연결을 제거한 차량 설치 ID의 단방향 해시만 서비스 운영 종료 시까지 분리 보관합니다. 이 차단 정보에는 계정 ID, 이메일과 주행 내용이 포함되지 않습니다. 백업 복구 시 탈퇴를 다시 반영하기 위한 계정 ID 해시와 삭제 시각은 최대 {privacy.backupDays}일 동안 분리 관리합니다. 백업을 복구할 때에는 삭제 원장을 먼저 재적용한 뒤 서비스를 재개합니다.</p>
    <p>전자적 기록은 운영 DB와 보관 파일에서 삭제하는 방식으로 파기하며, 백업과 로그는 정해진 보관 기간이 끝나면 삭제합니다. 복구할 수 없는 삭제 작업이므로 완료 후 기록을 되돌릴 수 없습니다.</p>
    <Link className="button" href="/account/delete">회원 탈퇴 및 데이터 삭제</Link></section>
  <section><h2>4. 서버와 외부 서비스</h2><p>루티의 주 운영 서버와 DB는 {privacy.hostingCountry}에 설치된 자체 NAS에서 운영합니다.</p><p>Cloudflare, Inc.의 DNS 및 Tunnel 서비스를 이용해 외부 HTTPS 접속을 서버로 전달합니다. 이 과정에서 접속 IP, 요청 정보와 전송 내용이 Cloudflare의 글로벌 네트워크에서 처리될 수 있습니다. 대한민국에 있는 DB 저장 위치와 통신 경로에서의 국외 처리는 서로 다릅니다.</p><p>Cloudflare의 처리 지역과 로그 보유 조건에는 해당 서비스의 설정 및 계약이 적용됩니다. 자세한 내용은 <a className="policy-link" href="https://www.cloudflare.com/privacypolicy/" target="_blank" rel="noopener noreferrer">Cloudflare 개인정보 안내</a>를 확인하거나 담당자에게 문의할 수 있습니다. 개인정보를 판매하거나 광고 목적으로 제3자에게 제공하지 않습니다.</p></section>
  <section><h2>5. 쿠키와 앱 저장소</h2><p>웹에서는 로그인 유지에 필요한 HttpOnly 세션 쿠키를 사용합니다. 브라우저 설정에서 쿠키를 차단하거나 삭제할 수 있으나 로그인이 제한될 수 있습니다. 스마트폰 앱은 OS의 보안 저장소를 사용해 세션 토큰을 보관하고, 로그아웃 또는 탈퇴 시 삭제합니다.</p></section>
  <section><h2>6. 이용자의 권리와 행사 방법</h2><p>이용자는 개인정보의 열람, 정정, 삭제와 처리 정지를 요청할 수 있습니다. 계정과 주행 기록 삭제는 앱 또는 웹에서 직접 진행할 수 있으며, 그 밖의 요청은 담당자에게 이메일로 접수할 수 있습니다. 요청 처리에 필요한 범위에서 본인 확인을 진행합니다.</p></section>
  <section><h2>7. 보호 조치</h2><p>서비스는 HTTPS 통신, 비밀번호 및 인증정보의 해시 저장, 계정별 접근 통제, 관리자 접근 제한과 백업 관리를 적용합니다. 앱의 로그인 토큰을 URL의 쿼리에 포함하지 않습니다.</p></section>
  <section><h2>8. 문의와 방침 변경</h2><p>운영자: {privacy.operator}<br/>개인정보 담당자: {privacy.contact}<br/>문의: <a className="policy-link" href={`mailto:${privacy.email}`}>{privacy.email}</a></p><p>이 방침이 변경되면 시행일과 변경 내용을 서비스에서 안내합니다. 개인정보 처리와 관련한 권리 침해 상담은 <a className="policy-link" href="https://www.privacy.go.kr/" target="_blank" rel="noopener noreferrer">개인정보 포털</a>을 통해서도 확인할 수 있습니다.</p></section>
</AccountPageShell>; }
