# 붕붕어스 Backend

커플이 함께하는 드라이브와 여행 기록 서비스 **붕붕어스**의 백엔드입니다.

Java 21 · Spring Boot 4.1.2 · Gradle Wrapper 9.8.2 · Spring Data JPA · PostgreSQL 18 · Swagger UI

## 바로 실행

JDK 21과 PostgreSQL이 필요합니다. Docker Desktop을 실행하고 다음 순서로 시작합니다.

```powershell
# Windows PowerShell
Copy-Item .env.example .env
# .env의 DB_PASSWORD 수정
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

```bash
# macOS / Linux
cp .env.example .env
# .env의 DB_PASSWORD 수정
docker compose up -d --wait postgres
chmod +x gradlew
./gradlew bootRun
```

| 주소 | 용도 |
| --- | --- |
| http://localhost:8080/api/v1/health | API 응답 및 공통 응답 형식 확인 |
| `POST /api/v1/auth/authorizations` | 카카오 로그인 시작 및 로그인 URL 발급 |
| http://localhost:8080/api/v1/app-config | 로그인 전 앱 설정과 기능 활성화 여부 조회 |
| http://localhost:8080/api/v1/legal-documents | 현재 약관 목록과 본문 조회 |
| http://localhost:8080/api/v1/faqs | FAQ 질문과 답변 조회 |
| http://localhost:8080/actuator/health | 서버·DB 상태 확인 |
| http://localhost:8080/swagger-ui.html | 구현된 API 문서 |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |

앱 설정 API는 `application.yml`의 `app.public-config` 값을 반환합니다. 앱 내 음악 재생과 휴게소 상세는 연동 전까지 비활성화합니다. 주행·인증·커플 등 서비스 기능은 이후 도메인별로 구현합니다. Swagger에는 실제 구현된 API만 표시됩니다.

약관은 `src/main/resources/legal-documents.yml`의 `app.legal.documents`에 등록하고 백엔드를 배포하면 반영됩니다. 각 항목에 `type`, `version`, `title`, `url`, `content`, `effective-date`를 넣습니다. `TERMS`와 `PRIVACY`는 필수, `OPEN_SOURCE`는 고지용입니다. 목록과 버전 생략 상세는 한국 날짜 기준으로 시행일이 가장 최근인 문서를 반환합니다. 시행일이 같으면 파일에서 뒤에 등록한 항목을 사용합니다. 상세 경로 `/api/v1/legal-documents/{type}?version=...`에서 등록된 특정 버전을 확인할 수 있으므로, 문서를 바꿀 때는 기존 버전을 보관하고 새 버전을 추가합니다. 팀이 확정한 원문을 등록하기 전에는 목록이 비어 있습니다.

FAQ는 `src/main/resources/faqs.yml`의 `app.faq.items`에 `id`, `question`, `answer`, `order`를 등록하고 백엔드를 배포하면 반영됩니다. 조회 결과는 `order` 오름차순이고, 등록된 항목이 없으면 빈 목록을 반환합니다.


### 카카오 인증 코드 교환

`POST /api/v1/auth/token`에 `Idempotency-Key`(16~128자)와 `authSessionId`, `authorizationCode`, `state`, `codeVerifier`를 보냅니다. 카카오 설정의 Client Secret이 활성 상태라면 서버에 `KAKAO_LOGIN_CLIENT_SECRET`을 설정합니다. `AUTH_TOKEN_KEY`에는 32바이트 이상을 무작위로 생성해 Base64로 인코딩한 서버 키를 설정합니다. 키는 재시작 후에도 유지하며 Git에 올리지 않습니다.

액세스 JWT는 30분, 랜덤 리프레시 토큰은 14일 동안 유효합니다. 리프레시 토큰 해시는 세션에 저장되며, 성공 응답은 동일 요청 재시도를 위해 암호화되어 24시간 동안 재사용됩니다. 로그인 시작 시도 소비, 회원 생성, 서비스 세션 저장은 한 DB 트랜잭션에서 처리합니다. 카카오 코드 교환 이후 응답을 저장하기 전에 장애가 나면 새 로그인 시도가 필요할 수 있습니다.

호출 제한 및 만료 데이터 정리는 후속 작업입니다. 일반 로그아웃 시 기존 액세스 JWT는 만료까지 유효한 정책을 적용합니다.


### 토큰 갱신

`POST /api/v1/auth/refresh`에 `Idempotency-Key`(16~128자)와 `refreshToken`을 보냅니다. 성공 시 30분 액세스 JWT와 갱신 시점부터 14일간 유효한 새 리프레시 토큰을 반환합니다. 응답을 받지 못하면 동일 토큰과 키로 30초 안에 재시도할 수 있습니다. 이 조건을 벗어나 사용된 토큰이 다시 들어오면 해당 세션을 폐기합니다. 앱에서는 동시에 하나의 갱신 요청만 보내고 성공 시 두 토큰을 교체합니다.

### 현재 기기 로그아웃

`POST /api/v1/auth/logout`에 `Idempotency-Key`(16~128자), `refreshToken`, `deviceId`를 보냅니다. 서버는 해당 세션의 갱신을 막고, 지정한 기기의 푸시 등록이 세션 소유자에 속하면 해제합니다. 성공 또는 이미 종료된 세션은 본문 없이 `204`를 반환합니다. 앱은 성공 후 두 토큰을 지웁니다. 기존 액세스 JWT는 만료까지 유효합니다. 푸시 등록 API는 커플·기기 작업에서 연결합니다.

### 내 계정 조회

`GET /api/v1/me`에 `Authorization: Bearer <accessToken>`을 보내면 현재 회원 프로필을 반환합니다. 가입 절차를 아직 마치지 않은 회원도 조회할 수 있습니다. 액세스 JWT는 DB 조회 없이 검증하며, 프로필은 DB에서 읽습니다. 토큰이 없거나 유효하지 않으면 `401 AUTHENTICATION_REQUIRED`, 만료됐으면 `401 ACCESS_TOKEN_EXPIRED`를 반환합니다.

### 가입 완료와 프로필 수정

`PUT /api/v1/me/onboarding`은 Bearer 액세스 토큰, `nickname`, `birthDate`(미수집 시 null), `consents`를 받습니다. `GET /api/v1/legal-documents`와 같은 문서 설정에서 현재 `TERMS`·`PRIVACY` 버전을 확인합니다. 승인된 두 문서를 `src/main/resources/legal-documents.yml`에 등록해야 가입을 완료할 수 있습니다. 동의 이력과 가입 프로필은 함께 저장됩니다.

`PATCH /api/v1/me`는 `nickname`과 `birthDate` 중 전달한 필드만 바꿉니다. `birthDate: null`은 생년월일을 지우고, 빈 요청과 미래 생년월일은 거절합니다. 두 API 모두 갱신된 내 프로필을 반환합니다.

### 탈퇴용 카카오 본인 재인증

`POST /api/v1/auth/reauth/authorizations`에 Bearer 액세스 토큰과 `Idempotency-Key`(16~128자)를 보내고, 본문에 `provider=KAKAO`, `redirectUri`, `codeChallenge`를 전달합니다. 현재 회원에 연결된 10분짜리 재인증 시도와 카카오 로그인 URL을 반환합니다.

카카오 콜백 뒤 `POST /api/v1/auth/reauth`에 Bearer 액세스 토큰, 새 `Idempotency-Key`, `authSessionId`, `authorizationCode`, `state`, `codeVerifier`를 보냅니다. 카카오 계정이 현재 회원과 일치하면 탈퇴 목적의 5분짜리 `reauthToken`을 반환합니다. 계정이 다르면 `403 REAUTH_SUBJECT_MISMATCH`를 반환합니다. 계정 삭제와 증명 소비는 회원 탈퇴 API에서 연결합니다.

### 회원 탈퇴 연동 상태

`DELETE /api/v1/me`는 Bearer 액세스 토큰과 `X-Reauth-Token`으로 본인 증명을 확인합니다. 준비·진행·일시중단 주행이 있으면 `409 ACTIVE_DRIVE_EXISTS`입니다. 현재는 조건을 통과해도 `503 SERVICE_UNAVAILABLE`을 반환합니다. 카카오 및 연결된 공급자 해제, 주행 시작과의 동시 처리, 커플·개인 데이터 정리, 탈퇴 계정의 JWT 차단이 연결된 뒤에만 탈퇴를 완료합니다.

## PostgreSQL 개발 환경

카카오 로그인을 시작하려면 서버의 `.env` 또는 환경변수에 `KAKAO_LOGIN_REST_API_KEY`와 `KAKAO_LOGIN_REDIRECT_URIS`를 설정합니다. 콜백 주소는 카카오디벨로퍼스에 등록한 값과 정확히 일치해야 하며, 여러 주소는 쉼표로 구분합니다. 요청에는 `provider=KAKAO`, `purpose=LOGIN`, `redirectUri`와 앱이 생성한 S256 `codeChallenge`(43자)를 보냅니다. 성공하면 `201`과 카카오 로그인 URL, `authSessionId`, `state`, 10분 후의 만료 시각을 반환합니다. Flyway는 로그인 시도를 저장할 `auth_sessions` 테이블을 생성합니다. 토큰 교환, 만료 세션 정리와 로그인 시작 요청의 호출 제한은 후속 작업입니다.

기본 `local`과 `dev`는 PostgreSQL에 연결합니다. 위에서 만든 `.env`와 DB를 그대로 사용하며, `dev` 프로필로 실행할 때는 다음 명령을 사용합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

macOS/Linux에서는 `./gradlew`를 사용합니다. 이미 설치된 PostgreSQL에 연결하려면 Docker 실행을 생략하고 `.env`의 호스트·포트·DB 이름·계정을 해당 서버에 맞춥니다. `.env`는 Spring과 Docker Compose가 읽습니다. 값에 따옴표를 붙이지 않습니다. 실제 비밀값은 Git에 올리지 않습니다.

| 프로필 | 데이터베이스 | 스키마 | Swagger |
| --- | --- | --- | --- |
| `local` (기본) | `.env`의 PostgreSQL | `validate` | 켜짐 |
| `dev` | `.env`의 PostgreSQL | `validate` | 켜짐 |
| `test` | 기본 H2 PostgreSQL 모드 / CI는 PostgreSQL | `validate` | 켜짐 |
| `prod` | 환경변수의 PostgreSQL | `validate` | 꺼짐 |

H2는 자동 테스트 의존성에만 포함됩니다. 애플리케이션 시작 시 Flyway가 `src/main/resources/db/migration`의 SQL을 버전 순서대로 적용합니다. 첫 마이그레이션은 `drives`와 `drive_idempotency`를 생성합니다. 운영 배포 전에는 신규 스키마와 마이그레이션 계정 권한을 확인합니다. GitHub CI는 PostgreSQL 서비스에 연결해서 테스트합니다.

### 주행 준비 API

- `POST /api/v1/drives`는 주행 준비 세션을 생성합니다. 커플당 준비·주행·일시중단 상태를 합쳐 한 건만 저장할 수 있습니다.
- `GET /api/v1/drives/active`는 현재 커플의 진행 가능한 세션을 조회합니다. 해당 세션이 없으면 `data.drive`가 `null`입니다.
- `POST /api/v1/drives/{driveId}/cancel`은 `expectedVersion`이 일치하는 준비 세션을 취소합니다.

생성·취소에는 `Idempotency-Key`와 `X-Couple-Id`가 필요합니다. 동일 사용자와 커플이 24시간 안에 같은 키로 같은 요청을 다시 보내면 원래 응답 데이터를 돌려줍니다. 키가 만료되면 새 요청으로 처리합니다.

인증 모듈은 서블릿 `Principal`에 검증한 서비스 사용자 ID를 설정하고, 커플 모듈은 `CurrentCoupleLookup`으로 현재 연결과 두 멤버를 제공해야 합니다. 경로를 지정하는 준비 요청은 `DriveRouteLookup`이 제공하는 접근 가능한 경로를 사용합니다. 현재 통합 테스트는 서버 측 테스트 구현으로 각 연동을 검증합니다.

PostgreSQL 데이터는 Docker의 `postgres-data` 볼륨에 보존됩니다. PostgreSQL18 공식 이미지의 볼륨 경로는 `/var/lib/postgresql`입니다. 초기 DB·계정·비밀번호 환경변수는 빈 볼륨을 처음 초기화할 때 적용됩니다. [공식 이미지 안내](https://hub.docker.com/_/postgres)를 참고하세요.

운영 환경은 `SPRING_PROFILES_ACTIVE=prod`, `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`를 설정합니다. `DB_PORT` 기본값은5432, `SERVER_PORT`는8080입니다. DB 세션·JDBC 시간대는 UTC입니다. 프론트 허용 Origin은 쉼표로 구분합니다. 인증·접근 제어는 아직 구현 전이므로 서비스 공개 전에 추가합니다.

## 빌드와 테스트

```powershell
.\gradlew.bat clean build
java -jar build/libs/app.jar
```

로컬 빌드·자동 테스트는 별도 DB 실행 없이 수행할 수 있습니다. 통합 테스트는 상태 확인·DB 연결, 공통 오류, 입력 검증, CORS, OpenAPI를 확인합니다. 결과는 `build/reports/tests/test/index.html`에 생성됩니다. JAR 직접 실행에는 위에서 구성한 PostgreSQL과 `.env`가 필요합니다.

컨테이너 이미지는 `docker build -t boongboongus-backend .`로 만듭니다. 컨테이너 기본 프로필은 `prod`이며 운영 환경변수를 전달해야 합니다. 컨테이너에서 `DB_HOST=localhost`는 DB 컨테이너를 가리키지 않으므로 실제 DB 호스트를 사용합니다.

## 코드 구조와 API 규칙

```text
com.boongboongus.backend
├── common/api        성공·오류 응답
├── common/exception  공통 예외 처리
├── common/web        요청 ID 필터
├── config            CORS·OpenAPI
└── domain/health     상태 확인 API
```

기능 추가 시 `domain/{기능명}` 아래에 controller/service/repository/entity/dto를 필요한 만큼 만듭니다. JSON 성공 응답은 `{data, meta}`, 오류는 `{error, meta}`입니다. `meta`는 `requestId`와 UTC `serverTime`을 포함합니다. 입력 검증 오류는422, 잘못된 JSON은400, 없는 경로는404입니다. `X-Request-Id` 응답 헤더로 요청을 추적합니다.

기본 프론트 Origin은 `http://localhost:3000`, `http://localhost:5173`입니다. 쿠키 인증 대신 향후 Bearer 인증을 연결하는 구성이며, 인증 구현에 맞춰 CORS를 조정합니다.

## GitHub 협업

팀 작업 기준은 [CONTRIBUTING.md](CONTRIBUTING.md), AI 공통 지침은 [AGENTS.md](AGENTS.md)를 참고합니다.

- `main`·`develop` 대상 push/PR: GitHub Actions가 Java21·PostgreSQL18로 `clean build` 실행
- 작업 흐름: 이슈 생성 → 번호가 포함된 작업 브랜치 → PR → 리뷰·CI → 머지 → 이슈 완료 확인
- 브랜치 예시: `feat/12-drive-start`, `fix/34-card-expiration`
- 커밋 예시: `feat: 주행 시작 API 구현` (이슈 번호는 선택)
- PR 제목 예시: `feat: 주행 시작 API 구현 (#12)`
- PR 본문에 `Refs #12`로 이슈 참조. 일반 PR은 `develop` 대상이며 완료 조건 확인 후 이슈 종료
- 기능 개발·버그 수정·일반 작업 이슈는 기본 내용이 채워진 단일 Markdown 편집창으로 작성
- PR도 기본 본문 템플릿을 사용하며 전체 내용을 한 번에 수정·붙여넣기 가능

브랜치 보호는 저장소 관리자가 GitHub Settings → Rules → Rulesets에서 별도로 설정합니다. PR 승인1명과 `Build and test` 체크 필수를 권장합니다. 이 저장소 파일만으로 GitHub의 보호 규칙이나 Secrets가 설정되지는 않습니다.
