# Flyway migrations

정식 migration은 아직 없다. 2026-11-03 사용자 방침에 따라 DB/테이블은 MySQL에서 직접 SQL로 관리한다. Flyway 비활성, SQL init never, ddl-auto validate로 자동 DDL을 실행하지 않는다. 설계는 `docs/AUTH_ERD_REVIEW.md`, 환경은 `docs/DATABASE.md`를 따른다(backend 루트 기준 경로).
로컬 MySQL 서버에 root 계정으로 접속하며 mac DB는 생성되어 있다. 업무 테이블 적용 상태는 별도 확인한다. Docker 없이 개발하며 AWS RDS for MySQL은 향후 개발·운영 DB 모두에 사용할 예정이다.
`sql/01_core_tables_review.sql`은 과거 핵심 3테이블안이다. 최신 인증 8테이블 검토본은 02/03/04이며 현재 테이블 상태에 맞는 하나를 선택한다. 검토 SQL은 자동 실행 대상이 아니다.
Flyway 전환은 추후 별도 합의가 있을 때만 수행한다. 엔티티 구현만으로 migration 자동 적용을 추가하지 않는다.
적용된 migration은 수정하지 않고 새 버전을 추가한다. `ddl-auto=update`를 사용하지 않는다.
