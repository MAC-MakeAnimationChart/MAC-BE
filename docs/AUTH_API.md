# AUTH API 명세 — 확정 범위

설계 기준일: 2026-10-31. 구현 점검: 2026-11-04. **이메일 인증·EMAIL 가입·로그인·비밀번호 찾기/재설정·Refresh·로그아웃·/me·soft delete 탈퇴는 로컬 mac 통합 검증 완료. 소셜 code 교환·SOCIAL 가입·소셜 탈퇴 재인증은 코드와 단위 테스트를 완료했으며 실제 제공사 및 소셜 DB 업무 흐름 검증 전이다.** [현재 상태](IMPLEMENTATION_STATUS.md) 참고.
FE/BE 공유 기준 문서다. 가입·로그인·유지·로그아웃의 확정 계약과 전체 AUTH 요구사항의 후속 범위를 구분한다.
ERD는 [회원·인증 ERD](AUTH_ERD_REVIEW.md)를 따른다. API 합의가 DB 적용을 의미하지 않는다.

## 1. 공통 계약

- 인증 POST는 정확한 허용 Origin 및 X-Requested-With: MAC을 요구한다. refresh/logout 외 본문은 application/json. Origin 누락은 403으로 거절한다.
- 현재 Refresh 쿠키 구현: mac_refresh, HttpOnly, Path=/api/v1/auth, SameSite=Lax, Secure는 설정값(기본 true), Domain 미지정. 상세 개발 기본값은 구현 상태 문서를 따른다.

- 기본 경로 `/api/v1`, JSON 요청/응답. 회원 및 인증 요청 ID는 문자열로 전달한다.
- 회원은 필수 인증·약관 동의를 모두 마친 시점에 생성하고 자동 로그인한다.
- 이메일·소셜 가입 완료는 모두 `POST /auth/signup`. 최초 가입용 PENDING_TERMS 회원과 `/me/terms` 요청은 사용하지 않는다.
- 약관 원문·화면은 FE, 필수 문서 버전 검증·동의 이력 저장은 BE. 약관 조회 API는 만들지 않는다.
- 이메일은 앞뒤 공백 제거·소문자화한다. 비밀번호는 임의 정규화·공백 제거하지 않는다.
- Access JWT는 FE 메모리에 보관하고 `Authorization: Bearer <token>`으로 전달한다. BE는 서명/알고리즘/issuer/audience/만료/sub를 검증한다.
- Refresh는 랜덤 토큰, DB에는 해시만 저장하며 HttpOnly 쿠키로 전달한다. FE는 원문을 읽지 않는다.
- 로그인 실패는 계정 없음·비밀번호 불일치·소셜 전용 계정 모두 `401 INVALID_CREDENTIALS`로 통일한다.
- 소셜 계정은 제공사 인증만 사용하는 간편 로그인 계정이다. 서비스 비밀번호를 설정·변경·복구하지 않으며 이메일·비밀번호 로그인 대상으로 사용하지 않는다.
- 아래 예시의 토큰은 자리표시자다. 인증 증명은 로그인 JWT가 아니며 목적·대상·만료·단일 소비를 검증한다.

## 2. 확정 API 목록

| 기능 | Method | 경로 | 요청·입력 | 성공 결과 | 인증 조건 |
|---|---|---|---|---|---|
| 가입 이메일 코드 발송 | POST | `/auth/email/send-code` | email, purpose=signup | challengeId, 유효시간, 재발송 대기시간 | 없음 |
| 이메일 코드 확인 | POST | `/auth/email/verify` | challengeId, code | verificationToken, 유효시간 | 코드 검증 |
| 이메일·소셜 가입 완료 | POST | `/auth/signup` | signupType, 가입 증명, 필수 약관 동의; EMAIL은 email/password 추가 | 회원 생성·자동 로그인 | 가입 증명 |
| 이메일 로그인 | POST | `/auth/login` | email, password | Access JWT·회원 정보·Refresh 쿠키 | 자격 증명 |
| 비밀번호 재설정 | POST | `/auth/password/reset` | email, verificationToken, newPassword | 비밀번호 교체·모든 Refresh 폐기 | password_reset 이메일 증명 |
| 소셜 code 교환 | POST | `/auth/social` | provider, code, codeVerifier, redirectUri, Naver state | 기존 회원 로그인 200 또는 신규 가입 대기 202 | FE state/PKCE 완료 + BE 제공사 검증 |
| 소셜 탈퇴 재인증 | POST | `/auth/social/reauth` | 소셜 code 교환과 동일 | 짧은 HttpOnly 재인증 쿠키 | Access JWT + 동일 제공사 신원 |
| 내 정보 조회 | GET | `/me` | 없음 | 현재 회원 정보 | Access JWT |
| 토큰 재발급 | POST | `/auth/refresh` | Refresh 쿠키 | 새 Access JWT·회전한 Refresh 쿠키 | Refresh 검증 |
| 로그아웃 | POST | `/auth/logout` | Refresh 쿠키 | 현재 로그인 종료·쿠키 삭제 | Refresh 쿠키 |
| 회원 탈퇴 | POST | `/auth/withdraw` | acknowledgeEmailReuseBlocked, 이메일 회원은 password | 회원 soft delete·모든 Refresh 폐기·쿠키 삭제 | Access JWT + 비밀번호 또는 소셜 재인증 쿠키 |

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
- `purpose`는 가입 `signup` 또는 비밀번호 찾기 `password_reset`이다.
- 가입 목적에서 이미 가입된 서비스 이메일은 409 EMAIL_ALREADY_USED. 로그인 오류 통일과 별개인 가입 중복 정책이다.
- 재설정 목적은 회원 존재 여부를 노출하지 않도록 미가입·탈퇴·소셜 전용 이메일에도 동일한 200 구조를 반환하지만 메일과 유효한 증명은 만들지 않는다.

### POST /auth/email/verify

```json
{"challengeId":"101","code":"123456"}
```

성공 200. verificationToken과 expiresIn(초)을 반환한다. 코드 예시는 실제 인증코드가 아니다.
- 이메일 인증만 완료하고 회원은 생성하지 않는다.
- 증명은 발급 목적과 인증한 이메일에만 사용할 수 있다. 이미 성공한 코드를 반복 소비해 증명을 계속 발급하지 않는다.
- 이메일을 바꾸면 이전 이메일의 증명은 사용할 수 없다.
- FE는 verify → signup을 연속 호출해도 되며 별도 화면을 추가할 필요는 없다.
- 코드 오입력 1회로 인증 화면을 종료하지 않는다. `400 INVALID_VERIFICATION`이면 같은 challengeId로 입력을 다시 받되, FE도 제출 횟수를 제한하고 한도 도달·만료 시 재발송 흐름으로 안내한다. 실제 시도 횟수와 challenge 취소는 BE가 최종 통제한다.
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

소셜 가입은 signupType=SOCIAL과 agreedTerms만 JSON으로 전달한다. email/password/provider_subject 또는 가입 증명 원문을 FE 입력으로 받지 않고, `/auth/social`이 설정한 짧은 수명의 HttpOnly 가입 쿠키로 서버의 검증된 OAuth 임시 기록을 조회한다.

```json
{
  "signupType":"SOCIAL",
  "agreedTerms":[
    {"type":"TERMS","version":"v1"},
    {"type":"PRIVACY","version":"v1"}
  ]
}
```

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

## 6. 비밀번호 찾기·재설정

이 흐름은 서비스 비밀번호가 있는 ACTIVE 이메일 회원만 대상으로 한다. FE는 로그인 실패 화면에서 비밀번호 찾기로 이동시킨 뒤 아래 순서로 호출한다.

1. `POST /auth/email/send-code`에 `{"email":"user@example.com","purpose":"password_reset"}` 전송.
2. 수신 코드를 기존 `POST /auth/email/verify`에 전송하여 목적에 결합된 `verificationToken` 획득.
3. 새 비밀번호를 FE에서 두 번 입력받아 일치 여부를 확인하고, 일치한 값 하나만 다음 API로 전송.

### POST /auth/password/reset

```json
{
  "email":"user@example.com",
  "verificationToken":"<비밀번호 재설정 증명>",
  "newPassword":"<새 비밀번호>"
}
```

성공은 `204 No Content`이며 자동 로그인하지 않는다. FE는 증명과 비밀번호 입력을 메모리에서 제거하고 새 비밀번호로 로그인하도록 안내한다.

- BE는 증명의 목적·회원·이메일·`auth_version`·만료·미소비 상태를 잠금 아래 검증하고 한 번만 소비한다.
- 새 비밀번호는 가입과 같은 정책으로 검사하고 BCrypt 해시만 저장한다. 확인 비밀번호는 UX용이므로 BE 요청에 포함하지 않는다.
- 성공 시 `auth_version`을 증가시키고 해당 회원의 모든 Refresh 토큰과 남은 비밀번호 재설정 증명을 폐기한다.
- 이미 발급된 Access JWT는 최대 10분까지 서명상 유효할 수 있다. 보호 API는 현재 ACTIVE 상태와 `auth_version`을 확인한다.
- 증명 만료·재사용·대상 불일치는 `400 INVALID_PASSWORD_RESET_PROOF`로 통일한다.
- 코드/증명/비밀번호 원문을 로그나 영구 저장소에 남기지 않는다.

## 7. 소셜 가입·로그인

### FE 책임

- 팝업으로 제공사 인증 화면을 열고 FE callback 주소에서 code/state를 받는다.
- state를 충분한 난수로 생성해 시작 브라우저에 묶고 만료·일회성 소비를 검증한다. 검증 실패 시 BE를 호출하지 않는다.
- PKCE를 지원하는 제공사는 verifier/challenge를 생성하고 verifier를 가입·로그인 완료 전까지만 임시 보관한다.
- 로그인 시작에 사용한 redirectUri를 그대로 `/auth/social`에 전달한다.
- Naver는 공식 token endpoint가 code 교환에도 state를 요구하므로 FE에서 검증한 동일 state 원문을 요청에 함께 전달한다. BE는 이를 Naver token 요청에 전달하지만 FE 대신 state 일회성 검증을 수행하지 않는다.
- 신규 가입 202 응답이면 약관 화면을 표시한 뒤 SOCIAL `/auth/signup`을 호출한다.

### BE 책임

- FE가 전달한 provider/code/codeVerifier/redirectUri와 Naver state를 형식·길이·provider별 요구사항 및 서버 allowlist로 검사한다.
- client secret은 BE에만 보관하고 제공사 token/userinfo API를 직접 호출한다. code 만료·재사용·client/redirect 불일치와 제공사 응답을 검증한다.
- FE가 전달한 이메일·provider subject를 받거나 신뢰하지 않는다. 제공사에서 검증한 subject와 신뢰 가능한 이메일만 사용한다.
- 이미 연결된 provider+provider_subject면 ACTIVE 회원을 잠금 재확인하고 200 AuthResponse와 Refresh 쿠키를 발급한다.
- 신규 신원이면 OAuth 임시 기록과 무작위 가입 토큰 해시를 저장하고, 원문은 짧은 수명의 `mac_social_signup` HttpOnly 쿠키로만 전달한다. 202 응답에는 비밀 증명을 포함하지 않는다.
- `mac_social_signup`은 Secure 설정, Path=/api/v1/auth, SameSite=Lax, 기본 10분 Max-Age를 사용한다. SOCIAL 가입 성공 시 삭제되며 만료된 값은 서버에서 사용할 수 없다.
- FE가 state 검증을 담당한다는 신뢰 경계를 문서와 테스트에 유지한다. BE는 자체 OAuth state 검증을 수행했다고 표시하지 않는다.

### POST /auth/social

```json
{
  "provider":"google",
  "code":"<provider authorization code>",
  "codeVerifier":"<PKCE verifier>",
  "redirectUri":"https://app.example.com/auth/callback/google"
}
```

Naver 요청은 `codeVerifier` 대신 FE가 이미 검증한 `state`를 추가한다.

```json
{
  "provider":"naver",
  "code":"<provider authorization code>",
  "redirectUri":"https://app.example.com/auth/callback/naver",
  "state":"<validated OAuth state>"
}
```

- 기존 회원: 200, 이메일 로그인과 같은 AuthResponse 및 Refresh 쿠키.
- 신규 회원: 202, Refresh/Access 없이 아래 공개 정보와 `mac_social_signup` 쿠키.

```json
{
  "signupRequired":true,
  "provider":"GOOGLE",
  "email":"user@example.com"
}
```

email은 제공사가 신뢰 가능한 이메일을 제공하지 않으면 null이다. displayName/plan/termsAgreed/needsTerms는 현재 회원 응답 계약에 추가하지 않는다.

현재 오류 계약:
- `400 INVALID_CODE`: 만료·재사용·교환 거절 등 유효하지 않은 provider code.
- `400 INVALID_REQUEST`: provider별 codeVerifier 또는 Naver state 누락·형식 오류.
- `400 INVALID_REDIRECT_URI`: 서버 allowlist에 없는 redirectUri.
- `409 EMAIL_CONFLICT`: 신규 소셜 신원의 신뢰 가능한 이메일이 기존 회원과 중복.
- `409 SOCIAL_IDENTITY_ALREADY_USED`: 가입 경쟁 중 같은 제공사 신원이 먼저 가입됨.
- `502 PROVIDER_ERROR`: 제공사 token/userinfo 장애 또는 유효하지 않은 제공사 응답.
- `503 SOCIAL_AUTH_NOT_CONFIGURED`: 해당 제공사 서버 자격 증명 미설정.

분산 rate limit은 아직 구현하지 않았으므로 429 계약은 운영 인프라 결정 후 추가한다.

오류 message는 비밀값·제공사 원문을 포함하지 않는 한국어 사용자 문구로 반환한다.

- 이미 연결된 provider+provider_subject면 기존 회원 로그인. 신규 가입용 증명은 발급하지 않는다.
- 신규 가입 대기 시 member/identity/consent/refresh를 생성하지 않으며 로그인으로 취급하지 않는다.
- FE에서 필수 약관 동의 후 공통 `/auth/signup`을 호출한다. 가입 쿠키가 만료되면 소셜 인증부터 재시작한다.
- 처음 보는 소셜 ID의 신뢰 가능한 제공사 이메일이 기존 서비스 이메일과 중복되면 `409 EMAIL_CONFLICT`, 자동 병합 없음. 없거나 신뢰할 수 없는 이메일은 NULL 가입 가능.
- 이메일 중복 검사보다 기존 소셜 연결 조회가 먼저다. 가입 최종 시점에도 중복을 다시 검사한다.
- 로그인 후 명시적 소셜 연결/해제는 후속 계정 관리 기능이다.

## 8. 내 정보·재발급·로그아웃

| API | 성공 | BE 처리 | FE 처리 |
|---|---|---|---|
| GET /me | 200, 로그인 결과의 member 구조 | JWT와 현재 회원 상태 확인 | 회원 정보 표시 |
| POST /auth/refresh | 200, 로그인과 같은 결과 | 기존 Refresh 폐기·새 토큰 발급, family 절대 기한 유지 | 재발급 요청 하나로 조정, 인증 실패한 요청만 제한적으로 재시도 |
| POST /auth/logout | 204, 본문 없음 | 현재 Refresh family 폐기·쿠키 삭제, 반복 요청 멱등 처리 | 메모리 Access·회원 정보 제거 |

- 다른 기기 로그인은 유지한다. 로그아웃 후 기존 Access JWT는 최대 10분까지 유효할 수 있다.
- Refresh가 없거나 유효하지 않은 재발급은 401. FE는 무한 재시도하지 않고 재로그인을 안내한다. 통신 장애를 인증 거절과 혼동하지 않는다.
- 업무 요청의 타임아웃/결과 불명 실패를 무조건 재전송하지 않는다. 변경 요청의 재시도는 멱등성 계약을 따른다.
- Refresh 재사용은 해당 family 폐기로 처리하며, 이전 토큰 기록은 감지에 필요한 기간 보존한다.
- HTTPS Secure/HttpOnly 쿠키 및 Origin/CSRF 방어 코드가 작성되어 있다. 현재 코드 값은 위 공통 계약과 구현 상태 문서를 따르며 운영 도메인·쿠키 정책 검증은 아직 필요하다.

## 9. 회원 탈퇴

### POST /auth/withdraw

이메일 회원 요청:
```json
{
  "password":"<현재 비밀번호>",
  "acknowledgeEmailReuseBlocked":true
}
```

소셜 회원은 먼저 `/auth/social/reauth`를 완료한 뒤 비밀번호 없이 확인값만 보낸다.

```json
{"acknowledgeEmailReuseBlocked":true}
```

Bearer Access JWT, 정확한 허용 Origin, `X-Requested-With: MAC`, JSON 본문을 요구한다. 성공은 204이며 Refresh 쿠키를 삭제한다.

- 현재 비밀번호를 BCrypt로 재확인한다. 불일치는 `401 INVALID_CREDENTIALS`다.
- `acknowledgeEmailReuseBlocked`는 반드시 true여야 하며 누락/false는 `400 INVALID_REQUEST`다.
- 회원 행을 `WITHDRAWN`으로 전환하고 `withdrawn_at`을 UTC로 기록한다. 이메일과 회원 행은 삭제하지 않는다.
- 회원의 모든 활성 Refresh Token을 폐기하고 `auth_version`을 증가시킨다.
- 탈퇴 회원은 로그인·refresh·`/me`를 사용할 수 없다. 기존 Access JWT 서명 자체는 최대 10분 유효할 수 있으므로 모든 보호 업무 서비스는 ACTIVE 회원 상태를 확인해야 한다.
- 동일 이메일의 코드 발송과 가입은 기존 중복 정책인 `409 EMAIL_ALREADY_USED`로 거절한다.
- 서비스 비밀번호가 없는 소셜 계정은 먼저 `/auth/social/reauth`를 완료해야 한다. 유효한 재인증 쿠키가 없거나 만료·소비됐으면 `400 SOCIAL_REAUTH_REQUIRED`다.
- 현재 구현은 인증 soft delete와 토큰 폐기까지다. dataset/S3 삭제·재시도는 해당 저장 기능 구현 전까지 완료된 것으로 간주하지 않는다.
- FE 탈퇴 모달은 `탈퇴 후 동일한 이메일로 다시 가입할 수 없습니다.` 경고를 최종 제출 전에 표시하고 확인값을 true로 전송한다.

### POST /auth/social/reauth

- Access JWT와 `/auth/social`과 동일한 provider/code/codeVerifier/redirectUri 입력을 요구한다. Naver는 state도 전달한다.
- FE는 일반 로그인 code를 재사용하지 않고 탈퇴 시점에 새 authorization flow를 시작한다. Kakao는 `prompt=login`, Google은 제공 가능한 계정 선택/재동의 옵션을 사용한다.
- 제공사에서 검증한 provider+subject가 현재 회원의 연결과 정확히 같을 때만 짧은 수명의 HttpOnly 재인증 증명을 발급한다.
- 성공은 204이며 기본 5분의 `mac_social_reauth` HttpOnly 쿠키를 발급한다.
- 이후 `/auth/withdraw`는 이메일 회원의 password 또는 소셜 회원의 재인증 쿠키 중 해당 로그인 방식의 증명을 한 번만 소비한다. 소셜 요청 본문에는 password를 보내지 않는다.
- public 로그인 code 교환과 민감 작업 재인증을 같은 인증 조건으로 섞지 않기 위해 별도 경로로 둔다.
- 현재 BE가 보장하는 것은 `탈퇴 시점에 새로 교환한 code의 provider+subject가 현재 연결과 동일함`이다. Naver는 강제 비밀번호 재입력 파라미터를 공식 API에서 제공하지 않고, Google도 계정 세션에 따라 비밀번호 입력이 생략될 수 있다. 제공사별 강한 최근 인증 시각 보장은 OIDC `auth_time` 검증 등 별도 강화 범위다.

## 10. FE/BE 책임 요약

| 항목 | BE | FE |
|---|---|---|
| 회원 생성 | 필수 인증·동의 검증 후 원자적 생성 | 가입 전 상태를 로그인으로 취급하지 않음 |
| 약관 | 문서 종류·버전·동의 시각 저장 | 원문·버전·동의 UI 관리 |
| 인증 증명 | 해시·목적·대상·만료·단일 소비 | 임시 보관, 변경 이메일에 재사용 금지 |
| 소셜 | code 교환·제공사 신원 검증·임시 상태·중복 방지 | 팝업·callback·state/PKCE 검증·약관 화면·가입 요청 |
| 로그인 | Access/Refresh 발급, 오류 통일 | 메모리 Access·Bearer 요청·통일된 오류 표시 |
| 비밀번호 찾기 | 회원 존재를 숨긴 발송 응답, 목적 결합 증명, BCrypt 교체·전체 Refresh 폐기 | 코드 입력, 새 비밀번호 2회 일치 확인, 성공 후 재로그인 |
| 로그아웃 | 현재 family 폐기·쿠키 삭제 | 메모리 로그인 상태 제거 |
| 회원 탈퇴 | 재인증·회원 soft delete·전체 Refresh 폐기·동일 이메일 재가입 차단 | 재가입 불가 경고·명시적 확인·성공 후 로그인 상태 제거 |

## 11. 후속 AUTH 범위와 미확정 세부값

2026-11-04 MVP 범위 결정에 따라 다음을 구분한다.
- 이메일 회원의 로그인 실패 후 비밀번호 찾기/재설정은 구현됐다. 로그인 상태에서의 별도 비밀번호 변경 API는 후속이다.
- 소셜 회원은 서비스 비밀번호를 사용하지 않으므로 최초 비밀번호 설정·변경·복구 기능을 만들지 않는다.
- 회원 탈퇴 인증 API와 소셜 제공사 재인증 코드는 구현됐다. 실제 제공사 연동 검증과 데이터셋/S3 정리 및 실패 재시도는 후속이다.
- 회원 탈퇴 완료 후 member 행은 hard delete하지 않고 soft delete 상태와 탈퇴 시각을 보존한다. 모든 로그인과 토큰 발급을 거절한다.
- 탈퇴 회원의 정규화 이메일은 재가입 차단을 위해 보존하며, 이메일 코드 발송과 `/auth/signup` 모두 `409 EMAIL_ALREADY_USED`로 거절한다. 동일 이메일 재가입을 허용하지 않는다.
- FE 탈퇴 확인 모달은 최종 제출 전에 `탈퇴 후 동일한 이메일로 다시 가입할 수 없습니다.` 경고를 명확히 표시해야 한다. 이 경고는 API 오류에만 의존하지 않는 필수 연동 요구사항이다.
- 이메일 변경 및 새 주소 인증.
- 소셜 연결/해제, 마지막 로그인 수단 보호.
- 회원 탈퇴 외 민감 작업 재인증.

운영 전 보완할 세부 계약: 분산 rate limit, 회원 탈퇴 연관 데이터 삭제 정합성, Refresh TTL, 이메일 코드 TTL·시도 제한, 가입 비밀번호 정책, 운영 CORS/허용 redirectUri, 공통 추적 필드.
이 항목들은 확정된 가입 정책이나 공통 signup 경로를 뒤집는 결정이 아니라 아직 합의하지 않은 세부값이다. 근거 없이 확정으로 기록하지 않는다.
