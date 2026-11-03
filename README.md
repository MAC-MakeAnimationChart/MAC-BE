# MAC Studio Backend

Java 17 / Spring Boot 4.1.1 / Gradle 기반 백엔드 초기 구조.
도메인별 controller → service → repository 및 dto/entity를 사용한다.

**2026-11-04 이메일 인증·가입·로그인·비밀번호 찾기/재설정·Refresh·로그아웃·/me와 이메일 soft delete 탈퇴는 로컬 mac 통합 검증 완료. 소셜 가입·로그인·탈퇴 재인증은 코드와 단위 테스트를 완료했으며 실제 제공사·소셜 DB 업무 흐름·브라우저 연동 검증은 남아 있다. [구현 상태](docs/IMPLEMENTATION_STATUS.md) 참고. 실제 SMTP 검증 및 데이터셋/차트/S3 API는 후속 범위다.**

전체 패키지·클래스·메서드 역할과 DTO/엔티티 경계는 [백엔드 구조 문서](docs/BACKEND_STRUCTURE.md)를 참고한다.

## 프론트엔드·백엔드 협업 문서

- [AUTH API 명세](docs/AUTH_API.md): 이메일 API 통합 검증 완료. 소셜은 FE 팝업/state/PKCE, BE code 교환/JWT 방식으로 코드 구현 완료 후 실제 제공사 검증 대기.
- [PowerShell 통합 시나리오](scripts/README.md): 실제 이메일 코드 또는 provider code를 입력해 가입·로그인·`/me` 흐름을 순차 검증.
- [회원·인증 ERD](docs/AUTH_ERD_REVIEW.md): 약관 동의 후 회원 생성 정책 및 8테이블 설계.
- [MVP 설계 및 연동 기준](docs/MVP_DESIGN.md): 확정된 ERD, 인증·저장 흐름, 화면별 책임, 구현 상태와 미확정 사항.
- 구현된 API의 요청·응답은 로컬 Swagger/OpenAPI에서 확인한다. 설계 문서의 예정 기능은 아직 호출할 수 없다.
- README와 `docs/`는 Git 공유 대상이다. 개인 AI 문서 없이도 협업에 필요한 설계를 확인할 수 있다.

## IntelliJ에서 열기
1. 이 `backend` 폴더 또는 `build.gradle`을 Open한다.
2. Gradle 프로젝트로 가져오고 Gradle JVM/Project SDK를 Java 17로 지정한다.
3. Gradle은 Wrapper를 사용한다. 별도 Gradle 설치는 불필요하다.
4. `MacStudioApplication.main()`을 실행한다. 기본 프로필은 local이다.
5. local은 로컬 MySQL mac DB에 연결한다. 아래 예시 파일을 복사한 뒤 개인 DB 접속 정보를 설정한다.

```powershell
# backend 프로젝트 루트에서 실행
.\gradlew.bat test bootJar --no-daemon
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

- Health: http://localhost:8080/api/v1/health
- Actuator: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI: http://localhost:8080/v3/api-docs

## MySQL 연결 및 설정
현재는 Docker 없이 PC의 MySQL 서버에 `root`로 접속하며, 이 서버에 `mac` DB를 생성해 둔 상태다. AWS RDS for MySQL은 향후 운영 환경에서만 사용하며 아직 구축하지 않았다. 환경 구분·SQL 적용·검증 계획은 [DB 환경 문서](docs/DATABASE.md)를 따른다.

`src/main/resources/application-local.yml`에 DB 주소·계정·비밀번호를 둔다.
이 파일은 `.gitignore` 대상이며 배포 JAR에서도 제외한다.
새 환경에서는 `application-local.yml.example`을 복사해 `application-local.yml`을 만든다.
예시에는 비밀번호가 없다. 실제 비밀값을 example 파일에 복사하지 않는다.

```powershell
# 사용자가 수동 생성한 mac DB에 SELECT로 연결 확인. 자동 DDL 실행 없음.
.\gradlew.bat mysqlConnectionTest --no-daemon
# 일반 테스트는 test 프로필로 실행하며 MySQL이 필요하지 않음.
.\gradlew.bat test bootJar --no-daemon
# 실제 MySQL에 연결하는 로컬 서버
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

- DB 생성 구문: `sql/00_create_database.sql`
- `ddl-auto=validate` 유지. local의 Flyway는 검토 SQL이 자동 적용되지 않게 비활성화.
- dev/prod YAML은 현재 불필요하여 제거. 배포 환경은 추후 별도 설계.
- local의 JWT 임시 키는 재시작 시 바뀜. S3/SMTP 연결은 이번 검증에 포함하지 않음.
- 설정 변경 후 서버를 재시작해야 새 DB 설정이 반영됨.

## 의존성
| 영역 | 구성 |
|---|---|
| HTTP | Spring MVC, Jakarta Validation |
| DB | Spring Data JPA, MySQL Connector/J, Flyway MySQL |
| 인증 | Spring Security, OAuth2 Resource Server, Nimbus JWT, BCrypt |
| S3 | AWS SDK v2 S3 + URLConnection client |
| 이메일 | Spring Mail |
| 문서·운영 | springdoc Swagger UI, Actuator |
| 개발 | Lombok, configuration processor |
| 테스트 | Spring Boot test starters, JUnit, Security test, 로컬 MySQL mac |

기본 테스트는 Docker/DB 없이 실행한다. mysqlConnectionTest는 로컬 MySQL을 사용한다.
`./gradlew.bat mysqlIntegrationTest --no-daemon`은 사용자가 수동 관리하는 로컬 `mac`에서 실행한다. UUID 테스트 데이터만 생성·정리하며 자동 DDL을 실행하지 않는다.

## Git 공유 범위
소스 코드, 빌드 설정, 비밀값 없는 환경 설정 예시와 협업용 실행 안내를 공유한다.
README와 `docs/`의 협업용 설계는 공유한다. 개인 AI 문서·내부 작업 메모·작업 기록, IDE 실행 설정, 로컬 자격 증명과 데이터 덤프는 Git에서 제외한다.
`.env.example`과 `application-local.yml.example`에는 실제 비밀값을 넣지 않는다.
API 연동은 local 프로필의 Swagger UI와 OpenAPI를 기준으로 확인한다.

## 기술 기준 참고
- [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [springdoc 설정과 호환 버전](https://springdoc.org/)
- [AWS SDK v2 BOM](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)
