# AUTH API 명세 — 확정 범위

기준일: 2026-10-31. **설계 합의 내용을 기록한 문서이며 아래 AUTH API는 모두 미구현이다.**
FE/BE 공유 기준 문서다. 가입·로그인·유지·로그아웃의 확정 계약과 전체 AUTH 요구사항의 후속 범위를 구분한다.
ERD는 [회원·인증 ERD](AUTH_ERD_REVIEW.md)를 따른다. API 합의가 DB 적용을 의미하지 않는다.

## 1. 공통 계약

- 기본 경로 `/api/v1`, JSON 요청/응답. 회원 및 인증 요청 ID는 문자열로 전달한다.
- 회원은 필수 인증·약관 동의를 모두 마친 시점에 생성하고 자동 로그인한다.
- 이메일·소셜 가입 완료는 모두 `POST /auth/signup`. 최초 가입용 PENDING_TERMS 회원과 `/me/terms` 요청은 사용하지 않는다.
- 약관 원문·화면은 FE, 필수 문서 버전 검증·동의 이력 저장은 BE. 약관 조회 API는 만들지 않는다.
- 이메일은 앞뒤 공백 제거·소문자화한다. 비밀번호는 임의 정규화·공백 제거하지 않는다.
- Access JWT는 FE 메모리에 보관하고 `Authorization: Bearer <token>`으로 전달한다. BE는 서명/알고리즘/issuer/audience/만료/sub를 검증한다.
- Refresh는 랜덤 토큰, DB에는 해시만 저장하며 HttpOnly 쿠키로 전달한다. FE는 원문을 읽지 않는다.
- 로그인 실패는 계정 없음·비밀번호 불일치·소셜 전용 계정 모두 `401 INVALID_CREDENTIALS`로 통일한다.
- 아래 예시의 토큰은 자리표시자다. 인증 증명은 로그인 JWT가 아니며 목적·대상·만료·단일 소비를 검증한다.

## 2. 확정 API 목록

| 기능 | Method | 경로 | 요청·입력 | 성공 결과 | 인증 조건 |
|---|---|---|---|---|---|
| 가입 이메일 코드 발송 | POST | `/auth/email/send-code` | email, purpose=signup | challengeId, 유효시간, 재발송 대기시간 | 없음 |
| 이메일 코드 확인 | POST | `/auth/email/verify` | challengeId, code | verificationToken, 유효시간 | 코드 검증 |
| 이메일·소셜 가입 완료 | POST | `/auth/signup` | signupType, 가입 증명, 필수 약관 동의; EMAIL은 email/password 추가 | 회원 생성·자동 로그인 | 가입 증명 |
| 이메일 로그인 | POST | `/auth/login` | email, password | Access JWT·회원 정보·Refresh 쿠키 | 자격 증명 |
| 소셜 인증 시작 | GET | `/auth/{provider}/login` | provider, 선택적 next | 제공사로 302 | 없음 |
| 소셜 callback | GET | `/auth/{provider}/callback` | 제공사 인증 결과 | 기존 회원 로그인 또는 신규 가입 임시 상태 생성 후 FE로 302 | OAuth 검증 |
| 내 정보 조회 | GET | `/me` | 없음 | 현재 회원 정보 | Access JWT |
| 토큰 재발급 | POST | `/auth/refresh` | Refresh 쿠키 | 새 Access JWT·회전한 Refresh 쿠키 | Refresh 검증 |
| 로그아웃 | POST | `/auth/logout` | Refresh 쿠키 | 현재 로그인 종료·쿠키 삭제 | Refresh 쿠키 |

provider는 google/kakao/naver. 신규 소셜 가입 전용 API를 별도로 추가하지 않는다.

## 3. 이메일 인증

### POST /auth/email/send-code

```json
{"email":"user@example.com","purpose":"signup"}
```

성공 200. 응답 필드는 challengeId(문자열), expiresIn(초), resendAfter(초). 코드 원문은 응답하지 않는다.
성공은 메일 발송 시스템의 요청 수락이며 실제 수신함 도착 보장은 아니다.
- BE: 이메일 검증/중복 검사, 인증코드 발송, HMAC 저장, 만료·시도·재발송 제한.
- FE: 코드 입력 화면·대기시간 표시. 서버 제한을 대체하지 않는다.
- 재발송 시 해당 이메일·목적의 이전 미소비 인증 결과를 무효화한다.
- 이미 가입된 서비스 이메일은 409 EMAIL_ALREADY_USED. 로그인 오류 통일과 별개인 가입 중복 정책이다.

### POST /auth/email/verify

```json
{"challengeId":"101","code":"123456"}
```

성공 200. verificationToken과 expiresIn(초)을 반환한다. 코드 예시는 실제 인증코드가 아니다.
- 이메일 인증만 완료하고 회원은 생성하지 않는다.
- 가입 증명은 인증한 이메일의 가입에만 사용할 수 있다. 이미 성공한 코드를 반복 소비해 증명을 계속 발급하지 않는다.
- 이메일을 바꾸면 이전 이메일의 증명은 사용할 수 없다.
- FE는 verify → signup을 연속 호출해도 되며 별도 화면을 추가할 필요는 없다.
- 코드 길이/TTL/오입력 횟수/재발송 제한의 구체적 숫자는 운영 설정 확정 항목이다. 앞선 초안 숫자를 확정 계약으로 간주하지 않는다.

## 4. 공통 회원가입

### POST /auth/signup

이메일 가입:
```json
{
  "signupType":"EMAIL",
  "email":"user@example.com",
  "password":"<입력한 비밀번호>",
  "verificationToken":"<이메일 가입 증명>",
  "agreedTerms":[
    {"type":"TERMS","version":"v1"},
    {"type":"PRIVACY","version":"v1"}
  ]
}
```

소셜 가입은 signupType=SOCIAL, 소셜 가입 증명, agreedTerms를 전달한다. email/password/provider_subject를 FE 입력으로 신뢰하지 않고 서버의 검증된 OAuth 임시 기록에서 조회한다.
소셜 증명의 요청 필드명과 callback 이후 안전한 전달 방식은 아직 합의되지 않았으므로 임의의 신규 경로나 JSON 계약을 확정하지 않는다. 가입 완료 경로 자체는 동일한 `/auth/signup`이다.

성공 201. 응답 공통 구조:
```json
{
  "accessToken":"<JWT>",
  "tokenType":"Bearer",
  "expiresIn":600,
  "member":{
    "id":"10",
    "email":"user@example.com",
    "hasPassword":true,
    "providers":[]
  }
}
```

Refresh는 응답 JSON 대신 HttpOnly 쿠키로 전달한다. 소셜 회원의 email은 null일 수 있으며 hasPassword=false, providers는 연결된 GOOGLE/KAKAO/NAVER 목록이다.
최초 가입용 needsTerms 회원 필드는 제거한다. 최초 동의 전에는 회원 자체가 없다.

BE 처리:
1. signupType별 인증 증명·만료·목적·대상·미소비 상태 검증.
2. 이메일/소셜 ID 중복 및 필수 약관 버전 재검증. EMAIL은 비밀번호 정책 검증.
3. 회원·필요한 소셜 연결·동의 이력·Refresh 저장과 증명 소비를 한 DB 트랜잭션으로 처리.
4. 커밋 성공 후 로그인 결과 반환. 요청 경쟁은 잠금/조건부 소비와 UNIQUE로 차단.

FE 처리:
- 비밀번호 확인값은 FE에서 비교하며 전송하지 않는다.
- 약관 원문을 보여주고 실제 버전을 전송한다. agreedTerms 목록 포함은 동의 의미이며 필수 항목 누락은 가입 실패다.
- 비밀번호·가입 증명은 영구 저장하지 않는다. 중도 이탈 시 회원은 생성되지 않고 임시 기록만 만료된다.
- 가입 성공 후 로그인 상태를 반영하고 화면 이동. 응답 유실 후 재가입이 중복으로 거절되면 일반 로그인으로 진행할 수 있다.

## 5. 이메일 로그인

### POST /auth/login

```json
{"email":"user@example.com","password":"<입력한 비밀번호>"}
```

성공 200, 회원가입과 동일한 로그인 결과 및 Refresh 쿠키를 반환한다.
계정 없음/비밀번호 불일치/비밀번호 없는 소셜 전용 계정은 모두 아래 응답이다.

```json
{"code":"INVALID_CREDENTIALS","message":"이메일 또는 비밀번호를 확인해 주세요."}
```

HTTP 401. BE는 존재 여부나 연결 제공사를 실패 응답으로 구분하지 않으며 FE도 원인별 안내를 만들지 않는다.
DELETING 회원에게 토큰을 발급하지 않는다.

## 6. 소셜 가입·로그인

- FE는 인증 시작 경로로 이동. BE는 state·시작 브라우저 연결·필요한 PKCE/nonce를 관리한다.
- next는 허용된 서비스 내부 경로만 받으며 외부 URL/우회 인코딩을 거절한다.
- callback은 제공사 코드 교환·신원·만료·중복 처리를 검증한다. FE가 보낸 소셜 ID/이메일만으로 인증하지 않는다.
- 이미 연결된 provider+provider_subject면 기존 회원 로그인. 신규 가입용 증명은 발급하지 않는다.
- 신규 소셜 사용자는 OAuth 임시 기록에 검증된 신원과 가입 증명을 보관한다. 이 시점에 member/identity/consent/refresh를 생성하지 않으며 로그인 토큰을 발급하지 않는다.
- FE에서 필수 약관 동의 후 공통 /auth/signup을 호출한다. 가입 증명이 만료되면 소셜 인증부터 재시작한다.
- 서비스 토큰과 가입 증명을 redirect URL에 넣지 않는다. 신규 가입 결과/증명을 시작 브라우저에 안전하게 전달하는 상세 계약은 구현 전 보완한다.
- 기존 회원 로그인은 callback에서 Refresh 쿠키 설정 후 FE로 이동하고, FE가 /auth/refresh로 Access JWT와 회원 정보를 수신하는 흐름을 사용한다. 신규 가입 대기자는 Refresh가 없으므로 같은 흐름으로 인증 완료 처리하지 않는다.
- 처음 보는 소셜 ID의 신뢰 가능한 제공사 이메일이 기존 서비스 이메일과 중복되면 신규 가입 거절, 자동 병합 없음. 없거나 신뢰할 수 없는 이메일은 NULL 가입 가능.
- 이메일 중복 검사보다 기존 소셜 연결 조회가 먼저다. 가입 최종 시점에도 중복을 다시 검사한다.
- 로그인 후 명시적 소셜 연결/해제는 후속 계정 관리 기능이다.

## 7. 내 정보·재발급·로그아웃

| API | 성공 | BE 처리 | FE 처리 |
|---|---|---|---|
| GET /me | 200, 로그인 결과의 member 구조 | JWT와 현재 회원 상태 확인 | 회원 정보 표시 |
| POST /auth/refresh | 200, 로그인과 같은 결과 | 기존 Refresh 폐기·새 토큰 발급, family 절대 기한 유지 | 재발급 요청 하나로 조정, 인증 실패한 요청만 제한적으로 재시도 |
| POST /auth/logout | 204, 본문 없음 | 현재 Refresh family 폐기·쿠키 삭제, 반복 요청 멱등 처리 | 메모리 Access·회원 정보 제거 |

- 다른 기기 로그인은 유지한다. 로그아웃 후 기존 Access JWT는 최대 10분까지 유효할 수 있다.
- Refresh가 없거나 유효하지 않은 재발급은 401. FE는 무한 재시도하지 않고 재로그인을 안내한다. 통신 장애를 인증 거절과 혼동하지 않는다.
- 업무 요청의 타임아웃/결과 불명 실패를 무조건 재전송하지 않는다. 변경 요청의 재시도는 멱등성 계약을 따른다.
- Refresh 재사용은 해당 family 폐기로 처리하며, 이전 토큰 기록은 감지에 필요한 기간 보존한다.
- HTTPS Secure/HttpOnly 쿠키 및 Origin/CSRF 방어가 필요하다. 쿠키 이름/Path/SameSite/CORS 허용 도메인은 배포 구성에 맞춰 구현 전에 확정한다.

## 8. FE/BE 책임 요약

| 항목 | BE | FE |
|---|---|---|
| 회원 생성 | 필수 인증·동의 검증 후 원자적 생성 | 가입 전 상태를 로그인으로 취급하지 않음 |
| 약관 | 문서 종류·버전·동의 시각 저장 | 원문·버전·동의 UI 관리 |
| 인증 증명 | 해시·목적·대상·만료·단일 소비 | 임시 보관, 변경 이메일에 재사용 금지 |
| 소셜 | 제공사 신원 검증·임시 상태·중복 방지 | 인증 이동·약관 화면·가입 요청 |
| 로그인 | Access/Refresh 발급, 오류 통일 | 메모리 Access·Bearer 요청·통일된 오류 표시 |
| 로그아웃 | 현재 family 폐기·쿠키 삭제 | 메모리 로그인 상태 제거 |

## 9. 후속 AUTH 범위와 미확정 세부값

처음 요청한 전체 AUTH 범위를 누락하거나 완료로 간주하지 않는다. 다음 기능은 요구사항으로 유지하지만 이번 확정 API 상세 명세에는 포함하지 않는다.
- 비밀번호 찾기/재설정, 비밀번호 변경/소셜 회원 최초 설정.
- 이메일 변경 및 새 주소 인증.
- 소셜 연결/해제, 마지막 로그인 수단 보호.
- 민감 작업 재인증, 회원 탈퇴 및 저장 데이터 삭제 연계.

구현 전 보완할 세부 계약: 소셜 신규 가입 결과/증명의 안전한 전달과 필드명, Refresh TTL, 이메일 코드/가입 증명 TTL·시도 제한, 비밀번호 세부 정책, 쿠키/CORS/CSRF 설정, 로그인 오류 이외의 상세 오류 코드/공통 추적 필드.
이 항목들은 확정된 가입 정책이나 공통 signup 경로를 뒤집는 결정이 아니라 아직 합의하지 않은 세부값이다. 근거 없이 확정으로 기록하지 않는다.
