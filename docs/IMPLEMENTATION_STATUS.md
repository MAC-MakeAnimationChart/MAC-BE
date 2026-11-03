# 구현 상태 — 2026-11-04

이메일 인증 시작 → 코드 확인 → 회원 DB 저장 → 로그인과 로그인 실패 후 비밀번호 재설정 흐름의 구현·로컬 mac 통합 검증을 완료했다.

## 구현 범위

| API | 역할 |
|---|---|
| POST /api/v1/auth/email/send-code | 이메일 중복·재발송 제한 검사, 코드 HMAC 저장, 메일 발송 |
| POST /api/v1/auth/email/verify | 코드·만료·횟수 검사, 가입 증명 발급 |
| POST /api/v1/auth/signup | 증명 소비·필수 약관 검증·회원/동의 저장·자동 로그인 |
| POST /api/v1/auth/login | BCrypt 비밀번호 및 ACTIVE 상태 확인, 토큰 발급 |
| POST /api/v1/auth/password/reset | password_reset 증명 소비, BCrypt 비밀번호 교체, 모든 Refresh 폐기 |
| POST /api/v1/auth/social | 제공사 code 교환, 기존 소셜 회원 로그인 또는 신규 가입 대기 쿠키 발급 |
| POST /api/v1/auth/refresh | Refresh 회전, 재사용 시 해당 family 폐기 |
| POST /api/v1/auth/logout | 현재 family 폐기·쿠키 삭제 |
| POST /api/v1/auth/social/reauth | 현재 회원의 연결된 제공사 신원 재확인, 탈퇴용 일회성 쿠키 발급 |
| POST /api/v1/auth/withdraw | 비밀번호 또는 소셜 재인증, 회원 soft delete, 모든 Refresh 폐기, 쿠키 삭제 |
| GET /api/v1/me | Bearer JWT 회원 조회 |

DTO는 요청/응답 전용이며 entity와 분리했다. Controller → Service → Repository → Entity 구조이며 메서드별 역할 주석은 한국어다. [전체 구조](BACKEND_STRUCTURE.md), [API 계약](AUTH_API.md), [DB 및 local 설정](DATABASE.md)을 참고한다.

## 회원 탈퇴 API 추가

- Bearer JWT와 현재 비밀번호를 재확인하고 `acknowledgeEmailReuseBlocked=true`를 요구한다.
- member 상태를 WITHDRAWN으로 바꾸고 withdrawn_at을 기록하며 auth_version을 증가시킨다. 정규화 이메일은 보존한다.
- 모든 활성 Refresh Token을 폐기하고 Refresh 쿠키를 삭제한다.
- 소셜 계정은 먼저 `/auth/social/reauth`에서 동일 provider+subject를 확인한 일회성 쿠키를 받아야 한다.
- 스키마는 사용자 수동 SQL로 관리한다. 2026-11-04 현재 로컬 mac에서 JPA validate와 탈퇴 통합 시나리오가 통과하며, 테스트가 DDL을 자동 실행하지는 않는다.

## 이번 보완

- 이메일 로그인 실패 후 `password_reset` 코드 발송 → 공통 verify → 비밀번호 교체 API 흐름 구현.
- 회원 존재 여부를 숨기는 동일 발송 응답, 목적 결합 HMAC, 회원·이메일·auth_version 결합 일회성 증명 적용.
- 재설정 성공 시 auth_version 증가, 모든 Refresh와 다른 재설정 증명 폐기. 자동 로그인은 하지 않는다.

- DB 없는 smoke 테스트의 신규 서비스 의존성 대체.
- Origin 누락 시 403, 필터 URL 판별을 실제 요청 URI 기준으로 통일.
- Refresh family를 잠금 조회하여 MySQL REPEATABLE READ의 이전 스냅샷으로 새 토큰을 놓치지 않도록 수정.
- HTTP 404/405 등의 상태를 공통 예외 처리에서 보존. Security 401/403 JSON 응답.
- 이메일 이름 잠금 해제 실패 시 연결을 폐기하여 풀로 잠금 연결이 돌아가지 않도록 처리.
- 사용자 지시에 따라 개발·테스트 DB를 로컬 mac으로 통일. 자동 DDL 없음.

## 소셜 인증 구현

- FE는 제공사 팝업/callback/state 단일 소비/PKCE challenge를 담당하고, BE는 code 교환과 userinfo 신원 검증을 담당한다.
- Google/Kakao는 `codeVerifier`, Naver는 token API 요구에 따라 FE가 검증한 `state`를 `/auth/social` 및 `/auth/social/reauth`에 전달한다.
- 신규 신원은 약관 동의 전 member/JWT/Refresh를 만들지 않는다. 202와 `mac_social_signup` 쿠키만 받고 SOCIAL `/auth/signup`에서 최초 생성한다.
- 기존 이메일과 충돌하는 제공사 이메일을 자동 병합하지 않는다. 소셜 계정은 서비스 비밀번호가 없다.
- OAuth code/access token/verifier/client secret은 DB에 저장하지 않는다. DB에는 제공사 검증 결과와 가입/재인증 증명 해시만 저장한다.
- 소셜 탈퇴 재인증은 탈퇴 시점의 새 code와 현재 연결 subject 일치를 보장한다. Kakao `prompt=login` 등 FE의 제공사별 재인증 옵션이 필요하며, Naver/Google의 강제 비밀번호 재입력·OIDC auth_time 검증은 현재 보장 범위가 아니다.

## 실제 검증

- `gradlew.bat test bootJar --no-daemon --offline`: BUILD SUCCESSFUL, 기본 21개 통과, JAR 생성 성공.
- 2026-11-04 `gradlew.bat test bootJar --no-daemon --rerun-tasks`: BUILD SUCCESSFUL, 기본 29개 통과(실패·오류·건너뜀 0) 및 JAR 생성. 목적 분리 HMAC과 가입/재설정 HTML+plain 인증메일 MIME 생성 검증 포함.
- 2026-11-04 `gradlew.bat mysqlIntegrationTest --no-daemon --rerun-tasks`: BUILD SUCCESSFUL, 19개 통과(회원 연결 4, 인증 상태 5, 전체 흐름 10; 실패·오류·건너뜀 0). 비밀번호 재설정·증명 재사용 차단·모든 Refresh 폐기·회원 존재 비노출과 이메일 탈퇴까지 포함한다.
- 전체 흐름: 오입력 횟수 커밋, 증명 단일 소비, 필수 약관 오류 후 재시도, 발송 실패 취소/쿨다운, BCrypt 저장, JWT /me, 삭제 중 회원 거절, 로그인 오류 통일, 로그아웃, Refresh 회전·재사용·동시 요청, 잘못된 HTTP 요청.
- 메일 전송만 대체 객체로 실행. 실제 컨트롤러·서비스·JWT·로컬 MySQL 사용. UUID 테스트 소유 데이터만 롤백/정리.
- `scripts/Test-EmailMemberFlow.ps1`, `Test-SocialAuthBoundaries.ps1`, `Test-SocialMemberFlow.ps1`을 추가했다. 실제 SMTP 코드/provider code를 입력해 PowerShell에서 순차 실행하는 수동 시나리오이며 아직 실행 결과를 통과로 기록하지 않는다.

## 설정 및 남은 범위

공유 application-local.yml.example에 SMTP/HMAC/메일 발신자/로컬 쿠키 설정을 추가했다. 개인 application-local.yml의 기존 자격 증명은 수정·출력하지 않았다. 실제 SMTP 계정과 HMAC 키 주입 후 메일 수신 및 브라우저 쿠키를 확인해야 한다. 인증 POST는 정확한 Origin, X-Requested-With: MAC, refresh/logout 외 application/json을 요구한다.

TTL·횟수 제한·약관 v1은 개발 기본값이다. IP 제한은 단일 인스턴스 메모리 기반이며 운영 다중 인스턴스/프록시 정책은 후속이다. 실제 SMTP 전송은 DB 트랜잭션과 원자적이지 않다.

현재 로컬 mac 스키마는 매핑된 member 및 oauth_transaction 컬럼에 대한 JPA validate를 통과한다. 이메일 탈퇴와 비밀번호 찾기·재설정은 MySQL 통합 검증까지 완료했지만 소셜 가입·로그인·재인증은 단위 테스트만 완료했으며, 실제 Google/Kakao/Naver 자격 증명·제공사 API·소셜 DB 업무 흐름·브라우저 쿠키/팝업 연동은 미검증이다. dataset/S3 삭제·재시도와 로그인 상태의 별도 비밀번호 변경 API는 미구현이다. 소셜 계정은 서비스 비밀번호가 없는 간편 로그인으로 운영한다. 이번 구현은 기존 8테이블 구조로 가능해 DDL과 프론트엔드 코드를 변경하지 않았다.
