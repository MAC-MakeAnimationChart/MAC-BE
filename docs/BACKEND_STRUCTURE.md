# 백엔드 구조와 현재 구현 상태

기준일: 2026-11-04. 이메일 흐름과 이메일 탈퇴 로컬 통합 검증 완료. 상세 결과·설정은 [구현 상태](IMPLEMENTATION_STATUS.md)를 따른다.

## 패키지별 코드

| 패키지 | 현재 코드와 역할 |
|---|---|
| global/config | SecurityConfig, JwtConfig, S3/OpenAPI 설정 |
| global/exception, dto | ApiException/ApiExceptionHandler, ErrorResponse |
| member/controller, service, dto | MemberController → MemberService → MemberResponse, GET /api/v1/me |
| member/entity, repository | Member/MemberConsent/MemberIdentity, 상태·동의·연결 저장 |
| auth/controller | EmailVerificationController(send-code/verify), AuthController(signup/social/social reauth/login/password reset/refresh/logout/withdraw) |
| auth/dto | 이메일 발송·확인·가입·로그인·비밀번호 재설정·소셜·탈퇴 요청/응답, AgreedTermsRequest, AuthResponse |
| auth/config | AuthConfig, EmailVerificationProperties, AuthPolicy, AuthRequestFilter |
| auth/service | EmailVerificationService, AccountAuthService, SocialAuthService, SocialSignupService, HttpSocialProviderClient, TokenService, AuthCrypto, VerificationMailSender, EmailRequestLock |
| auth/entity, repository | AuthChallenge, RefreshToken, OAuthTransaction과 enum·저장소 |
| health | 실제 상태 확인 API |
| dataset/chart/storage | 계층 패키지 준비, 업무 API 미구현 |

## 입력부터 저장까지

Controller가 DTO를 검증하고 Service를 호출한다. Service가 트랜잭션·인증·해시 비교를 관리하며 Repository를 통해 Entity를 저장한다. Entity를 HTTP 응답으로 반환하지 않는다.

- 발송: 이메일 이름 잠금 → 제한/중복 확인 → 이전 증명 취소·새 AuthChallenge 저장 → DB 커밋 → SMTP 제출. 실패 시 새 기록 취소.
- 확인: AuthChallenge 잠금 조회 → 상태·만료·횟수 확인 → HMAC 비교. 실패 횟수는 커밋한 뒤 오류를 반환하고, 성공 시 가입 증명 원문을 응답하며 해시만 저장한다.
- 가입: 약관·비밀번호 정책 검사 → 가입 증명 잠금·소비 → Member·MemberConsent·Refresh 저장. 같은 트랜잭션에서 수행한다.
- 로그인: 이메일 조회·BCrypt 비교·회원 상태 검사 → 회원 잠금·상태 재검사 → JWT와 Refresh 발급.
- 비밀번호 재설정: 회원 존재를 숨긴 목적별 코드 발송 → 공통 코드 확인 → 회원·증명 잠금 → BCrypt 교체·auth_version 증가·전체 Refresh/나머지 증명 폐기.
- 소셜: FE가 검증한 state 흐름에서 code/verifier/redirectUri(Naver는 state도 포함)를 전달 → BE가 allowlist·제공사 code 교환·신원 검증 → 기존 identity는 JWT 로그인, 신규 identity는 OAuth 임시 기록과 HttpOnly 가입 쿠키만 발급 → 약관 동의 후 공통 signup에서 최초 회원 생성. 코드와 단위 테스트는 완료했으며 실제 제공사 및 소셜 DB 업무 흐름 검증이 남아 있다.
- 토큰: Access는 JSON, Refresh는 HttpOnly 쿠키로 전달. Refresh 해시만 저장하며 재사용 시 잠금 조회로 해당 family를 폐기한다. 동시 재사용 검증 완료.
- /me: JWT sub → 회원 상태·연결 제공사 조회 → MemberResponse 반환.

## AuthChallenge 메서드

| 메서드 | 역할 |
|---|---|
| createSignupEmail | 정규화된 이메일·코드 HMAC·만료로 PENDING 기록 생성 |
| createPasswordResetEmail | ACTIVE 이메일 회원·auth_version·코드 HMAC·만료로 PENDING 기록 생성 |
| requireCodeVerifiable | 상태·만료·시도 한도 검사 |
| recordFailedAttempt | 오입력 누적, 한도 도달 시 CANCELLED |
| markVerified | 서비스의 코드 일치 확인 후 증명 해시·만료 저장 |
| consumeSignupProof | 목적·대상·만료·상태 확인 후 CONSUMED |
| consumePasswordResetProof | 회원·이메일·auth_version·목적·만료 확인 후 CONSUMED |
| cancel | 미소비 기록 취소 |

## 검증과 DB 관리

2026-11-04 로컬 mac 통합 테스트 19개 통과(회원 연결 4, 인증 상태 5, 이메일 전체 흐름 10). 기본 테스트 29개와 JAR도 강제 재실행으로 통과했으며 상세 결과는 구현 상태 문서를 따른다.

DB/테이블은 사용자 수동 SQL로 관리한다. Flyway false, SQL init never, JPA validate. 개발·테스트 모두 mac을 사용하며 테스트 소유 데이터만 정리한다.

## 남은 작업

실제 SMTP 수신·브라우저 쿠키·소셜 제공사 연동 검증과 운영 정책 확정이 남아 있다. RDS 구축과 dataset/S3 탈퇴 정리는 후속 범위다. 로그인 상태의 별도 비밀번호 변경은 후속이며, 로그인 실패 후 복구·재설정은 구현 완료다.
