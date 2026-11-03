# Backend PowerShell integration scenarios

별도 IDE 라이선스 없이 PowerShell에서 백엔드 회원 인증 흐름을 수동 통합 검증한다. 서버를 local 프로필로 실행한 뒤 `backend` 디렉터리에서 실행한다.

## 이메일 회원 전체 흐름

```powershell
.\scripts\Test-EmailMemberFlow.ps1
```

기본 이메일은 `gimyongjun796@gmail.com`이다. 실행 중 비밀번호와 실제 수신한 이메일 인증코드를 콘솔에서 입력한다. 코드를 잘못 입력하면 동일 challenge에서 기본 5회까지 다시 입력할 수 있다.

검증 순서:

1. 이메일 코드 발송
2. 이메일 코드 검증
3. EMAIL 회원가입과 즉시 로그인
4. `/me` 확인
5. 로그아웃
6. 이메일·비밀번호 로그인
7. `/me` 확인
8. Refresh 회전

이미 가입됐거나 탈퇴 이력이 있는 이메일은 다시 가입할 수 없다. 다른 주소를 사용하려면 다음처럼 실행한다.

```powershell
.\scripts\Test-EmailMemberFlow.ps1 -Email "new-test@example.com"
```

## 프론트 없이 가능한 소셜 경계

```powershell
.\scripts\Test-SocialAuthBoundaries.ps1
```

제공사 code 없이 Origin 보호, public `/auth/social` 라우팅, JWT가 필요한 `/auth/social/reauth` 경계를 확인한다. 제공사 자격 증명 상태에 따라 public 요청은 `400 INVALID_CODE`, `502 PROVIDER_ERROR`, `503 SOCIAL_AUTH_NOT_CONFIGURED` 중 하나가 정상이다.

## 실제 소셜 code 교환

```powershell
.\scripts\Test-SocialMemberFlow.ps1 -Provider google
```

실행 중 provider authorization code와 PKCE verifier를 입력한다. Naver는 verifier 대신 FE에서 검증한 state를 입력한다. code는 일회성이며 스크립트나 로그에 저장하지 않는다.

기존 identity는 로그인과 `/me`를 확인하고, 신규 identity는 SOCIAL 가입·자동 로그인·`/me`까지 진행한다. `-TestReauth`를 주면 탈퇴하지 않고 새 provider code로 재인증 쿠키 발급까지만 확인한다.

## 공통 주의

- local 서버와 현재 JPA 매핑을 만족하는 수동 관리 스키마가 필요하다. 이메일 시나리오는 SMTP, 실제 소셜 시나리오는 provider 자격 증명·callback 설정이 추가로 필요하다.
- 토큰·비밀번호·인증코드·provider code는 출력하지 않는다.
- 회원 탈퇴는 동일 이메일 재가입을 영구 차단하므로 자동 시나리오에서 제외한다.
- 로그아웃 후에도 기존 Access JWT는 최대 10분간 서명상 유효할 수 있다. 스크립트는 이를 로그아웃 실패로 판단하지 않는다.
