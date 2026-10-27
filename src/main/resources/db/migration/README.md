# Flyway migrations

아직 스키마를 적용하지 않았다. `.ai/ERD.md`가 다음 구현의 설계 기준이다.
현재 검토용 핵심 테이블 SQL은 `sql/01_core_tables_review.sql`이며 자동 실행 대상이 아니다.
local에서 Flyway는 비활성화한다. 테이블 검토 후 정식 migration을 만들 때 활성화한다.
엔티티 구현 시 `V1__create_initial_schema.sql`부터 MySQL용 DDL을 추가한다.
적용된 migration은 수정하지 않고 새 버전을 추가한다. `ddl-auto=update`를 사용하지 않는다.
