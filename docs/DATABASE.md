# DB 환경 및 적용 계획

기준일: 2026-11-03. 사용자 최신 지시가 이전 별도 테스트 DB 방침을 대체한다.

| 구분 | 현재 | 향후 계획 |
|---|---|---|
| 서버 | Docker 없이 PC에 설치한 MySQL | AWS RDS for MySQL |
| DB | 로컬 개발·통합 테스트 모두 `mac` | 개발 DB와 운영 DB를 구분하여 RDS에 구성 |
| 계정 | 로컬 root 계정 | 환경별 애플리케이션 계정 |
| 설정 | Git 제외 application-local.yml | 환경별 외부 비밀값 주입 |

root는 접속 계정이며 mac이 DB 이름이다. RDS 이전 완료 후 로컬 DB를 제거할 계획이다. 이번 작업에서 RDS 구축이나 로컬 DB 삭제는 수행하지 않았다.

## 수동 스키마 관리

- CREATE/ALTER/DROP은 사용자가 MySQL에서 직접 실행한다.
- 공통 설정은 `spring.flyway.enabled=false`, `spring.sql.init.mode=never`, `spring.jpa.hibernate.ddl-auto=validate`다. validate는 구조 검사만 수행한다.
- 앱·테스트 자동 DDL 경로는 제거했다. sql/ 검토 파일 및 test/resources/db 참고 SQL은 자동 실행하지 않는다. 이미 적용한 SQL을 재실행하지 않는다.
- API의 회원·인증·토큰 INSERT/UPDATE는 정상적인 데이터 처리이며 스키마 자동 생성과 다르다.
- mac에는 member/member_identity/member_consent/auth_challenge/refresh_token/oauth_transaction/dataset/chart가 있다. JPA 검증은 매핑된 엔티티 기준으로 전체 DB 제약 검증을 뜻하지 않는다.

## 테스트

- 기본 test는 DB 없이 실행한다. mysqlConnectionTest는 기존 mac에 SELECT만 수행한다.
- mysqlIntegrationTest는 application-local.yml 계정으로 localhost/127.0.0.1의 mac에 연결한다. 별도 테스트 DB는 필요 없다.
- 데이터 계층 테스트는 롤백한다. 커밋이 필요한 인증·동시성 테스트는 UUID 이메일/제공사 식별자를 사용하고 자신이 만든 데이터만 삭제한다. 기존 개발 데이터는 정리 대상이 아니다.
- 메일 전송만 대체하며 API·서비스·JWT·MySQL은 실제 코드로 검증한다. 프로세스 강제 종료 시 테스트 데이터가 남을 수 있다.
- 결과는 [구현 상태](IMPLEMENTATION_STATUS.md)를 따른다.

## local 설정

개인 DB 비밀번호는 기존 application-local.yml에 유지한다. [공유 예시](../src/main/resources/application-local.yml.example)의 spring.mail과 app.auth를 기존 같은 최상위 키 아래 병합한다. spring/app 키를 중복 선언하지 않는다.

- SMTP: MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_SMTP_AUTH, MAIL_STARTTLS, MAIL_FROM.
- AUTH_HMAC_SECRET_BASE64: 최소 32바이트 무작위 키의 Base64. JWT 키와 별도 사용하며 실제 키는 커밋하지 않는다.
- 로컬 HTTP: app.auth.policy.secure-cookie=false. HTTPS 배포는 true.
- CORS_ALLOWED_ORIGINS는 프론트 주소와 정확히 일치해야 한다. 인증 POST는 Origin 및 X-Requested-With: MAC 헤더가 필요하다.
- .env.example은 참고 파일이며 Spring이 자동 로딩하지 않는다. IDE 실행 설정이나 프로세스 환경 변수에 넣는다.

실제 SMTP 수신과 브라우저 쿠키 동작은 별도 확인이 필요하다. TTL·요청 제한·약관 v1은 개발 기본값이며 운영 전 확정한다.