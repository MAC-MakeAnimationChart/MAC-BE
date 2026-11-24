# DB 환경 및 적용 계획

기준일: 2026-11-18. 사용자 최신 지시와 실제 AWS 구성 상태가 이전 RDS 계획 문구를 대체한다.

| 구분 | 현재 | 향후 계획 |
|---|---|---|
| 서버 | Docker 없이 PC에 설치한 MySQL | AWS RDS for MySQL 8.4.11 `mac-mysql` 생성 완료 |
| DB | 로컬 개발·통합 테스트 모두 `mac` | RDS 안에 `mac_dev`, `mac_prod` 생성 예정 |
| 계정 | 로컬 root 계정 | RDS 환경별 애플리케이션 계정 생성 예정 |
| 설정 | Git 제외 application-local.yml | 환경별 외부 비밀값 주입 |

root는 로컬 접속 계정이며 mac이 로컬 DB 이름이다. RDS 인스턴스와 네트워크는 구축했지만 스키마·애플리케이션 계정·테이블은 아직 만들지 않았다. RDS 이전과 검증이 끝난 뒤 로컬 DB 제거를 별도로 결정한다.

## AWS RDS 현재 기준

- 서울 리전, MySQL Community 8.4.11, `db.t4g.micro`, Single-AZ, gp3 20GiB, 최대 50GiB 자동 확장.
- private DB subnet group을 사용하고 퍼블릭 액세스는 비활성화한다.
- `mac-rds-sg`는 `mac-app-sg`에서 들어오는 TCP 3306만 허용하며 인터넷 CIDR을 소스로 열지 않는다.
- 사용자 지정 파라미터 그룹 `mac-mysql84-params`는 `time_zone=Asia/Seoul`, `require_secure_transport=ON`, `character_set_server=utf8mb4`, `collation_server=utf8mb4_0900_ai_ci`를 사용한다.
- 애플리케이션은 마스터 계정을 사용하지 않는다. `mac_dev_app`, `mac_prod_app`을 분리하고 운영 계정에는 업무 DML만 부여한다.
- 로컬 PC에서 RDS를 직접 공개 연결하지 않는다. 관리 접속은 EC2와 SSM Session Manager가 준비된 뒤 포트 포워딩을 사용한다.
- 삭제 방지, 자동 백업 7일, 최종 삭제 시 스냅샷 생성 여부는 운영 전 체크리스트에서 다시 확인한다.

## 수동 스키마 관리

- CREATE/ALTER/DROP은 사용자가 MySQL에서 직접 실행한다.
- `sql/06_full_schema_drop_create.sql`은 기존 8개 업무 테이블과 데이터를 모두 삭제하고 최신 최종 구조를 다시 만드는 파괴적 초기화 스크립트다. 백업과 대상 DB 확인 후에만 수동 실행한다.
- 이미 최신 8테이블을 만든 로컬 DB에는 최초 저장 멱등성 컬럼을 위한 `sql/07_dataset_creation_idempotency_alter_review.sql`을 사전 조회부터 단계별로 적용한다. dataset 행이 있으면 고유 UUID 백필 전 NOT NULL 단계로 진행하지 않는다.
- 기존 데이터를 보존해야 하는 환경에서는 전체 초기화 스크립트를 사용하지 않고 검토된 단계별 ALTER를 사용한다.
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
- 2026-11-04 강제 재실행 기준 기본 테스트 29개와 로컬 mac 통합 테스트 19개가 모두 통과했다. 통합 범위는 회원 연결 4개, 인증 상태 5개, 이메일 전체 흐름 10개이며 비밀번호 재설정과 세션 폐기를 포함한다. 소셜 제공사·소셜 DB 업무 흐름 검증은 포함하지 않는다.

## local 설정

개인 DB 비밀번호는 기존 application-local.yml에 유지한다. [공유 예시](../src/main/resources/application-local.yml.example)의 spring.mail과 app.auth를 기존 같은 최상위 키 아래 병합한다. spring/app 키를 중복 선언하지 않는다.

- SMTP: MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_SMTP_AUTH, MAIL_STARTTLS, MAIL_FROM.
- AUTH_HMAC_SECRET_BASE64: 최소 32바이트 무작위 키의 Base64. JWT 키와 별도 사용하며 실제 키는 커밋하지 않는다.
- 로컬 HTTP: app.auth.policy.secure-cookie=false. HTTPS 배포는 true.
- CORS_ALLOWED_ORIGINS는 프론트 주소와 정확히 일치해야 한다. 인증 POST는 Origin 및 X-Requested-With: MAC 헤더가 필요하다.
- .env.example은 참고 파일이며 Spring이 자동 로딩하지 않는다. IDE 실행 설정이나 프로세스 환경 변수에 넣는다.

실제 SMTP 수신과 브라우저 쿠키 동작은 별도 확인이 필요하다. TTL·요청 제한·약관 v1은 개발 기본값이며 운영 전 확정한다.
