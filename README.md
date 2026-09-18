# formlimpic

## 로컬 PostgreSQL 실행

Docker Desktop(Linux 컨테이너)과 Docker Compose가 필요합니다.
Spring Boot는 호스트에서, PostgreSQL 18은 Docker에서 실행합니다.

```powershell
docker compose up -d --wait
.\gradlew.bat bootRun
```

기본 연결: `localhost:5432`, DB/사용자: `formlimpic`, 비밀번호: `formlimpic-local`.
이 기본 계정은 로컬 개발용입니다. DB 포트는 로컬 컴퓨터에서만 접근 가능합니다.

비밀번호를 변경하려면 최초 DB 실행 전에 같은 PowerShell 세션에서
`$env:DB_PASSWORD = '새 비밀번호'`를 설정하고 위 명령을 실행합니다.
`DB_USERNAME`도 같은 방식으로 변경할 수 있습니다.
Spring 연결 주소는 `DB_URL`로 덮어쓸 수 있습니다.
Compose의 `.env`는 Spring Boot에 자동으로 전달되지 않습니다.

```powershell
docker compose ps
docker compose logs postgres
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT current_database(), clock_timestamp();"'
docker compose down
```

데이터는 `postgres_data` 볼륨에 보관되어 컨테이너를 내려도 유지됩니다.
`docker compose down -v`는 DB 데이터까지 삭제하므로 주의하세요.
이미 초기화된 볼륨에서는 환경변수만 바꿔도 DB 계정/비밀번호가 변경되지 않습니다.
운영 환경에서는 별도 비밀번호와 백업 구성을 적용해야 합니다.
