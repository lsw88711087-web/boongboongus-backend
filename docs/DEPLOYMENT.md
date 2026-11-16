# 붕붕어스 백엔드 배포 가이드

팀 배포 세션의 EC2·ECR·Nginx·Certbot 구성을 붕붕어스에 적용합니다. 첫 배포는 수동으로 검증하고 같은 절차를 GitHub Actions로 자동화합니다. 프론트는 별도 배포하며 백엔드 HTTPS 주소를 연결합니다. [팀 배포 세션](https://versed-hen-a8b.notion.site/CI-CD-HTTPS-40c148df0f788140a232cb028dae5ce9)

```text
팀 main → GitHub Actions 빌드·테스트 → ECR 이미지 → EC2 앱 컨테이너
프론트 HTTPS → API 도메인 → Nginx :443 → 127.0.0.1:8080 → Spring Boot → PostgreSQL
```

## 1. 배포할 커밋과 주소 확정

1. 관련 기능 PR을 팀의 `develop`에 머지하고, 팀 저장소에서 `develop → main` 릴리스 PR을 검토합니다. PR과 `main` 커밋의 `Backend CI / Build and test`가 성공한 것을 확인합니다.
2. AWS 계정, 서울 리전(`ap-northeast-2`), API 도메인, ECR 이름을 정합니다. 아래 예시는 Ubuntu x86_64 EC2 한 대에 PostgreSQL 18도 함께 실행하는 구성입니다. 팀에 운영 DB나 EC2가 이미 있다면 기존 인스턴스와 연결 설정을 사용하고, 운영 DB를 새로 초기화하는 단계는 건너뜁니다.
3. 프론트 담당자에게 배포 URL과 카카오 콜백의 전체 주소를 받습니다. 아래 `app.example.com`, `api.example.com`, 계정 ID는 팀 값으로 교체합니다. 주소와 비용은 팀 AWS 콘솔에서 확인합니다.

작성 시점에 게임 PR #40은 열려 있고 팀 `develop`은 `33a1ae2`, `main`은 `60f65ba`입니다. 저장소에는 PostgreSQL로 빌드하는 CI가 있고 EC2용 CD 워크플로는 추가해야 합니다. GitHub Pages의 배포는 API 문서 사이트입니다. [게임 PR #40](https://github.com/2026-line4thon-team6/boongboongus-backend/pull/40) · [팀 Actions](https://github.com/2026-line4thon-team6/boongboongus-backend/actions)

공개 전에는 승인된 이용약관·개인정보 문서가 등록됐는지 확인합니다. 현재 묶음의 `src/main/resources/legal-documents.yml`은 비어 있습니다. 가입 완료 경로를 쓰려면 승인된 약관과 개인정보 문서가 필요합니다. [프로젝트 README](../README.md)

**다음 단계 조건**  팀 `main`에 목표 기능이 있고 CI가 성공했으며, 배포할 커밋 SHA를 기록했습니다.

## 2. EC2, DNS, ECR 준비

- EC2 보안 그룹은 HTTP 80과 HTTPS 443을 열고, SSH를 쓰는 경우 팀 관리자의 IP만 22에 허용합니다. Spring 8080과 PostgreSQL 5432는 EC2 외부에서 직접 접근하지 않도록 합니다.
- EC2에 Elastic IP를 연결하고 실제 DNS를 관리하는 곳에서 API 서브도메인의 A 레코드를 그 IP로 지정합니다. Route 53 네임서버로 옮겨야 한다면 운영 중인 프론트·메일 레코드부터 옮깁니다. [AWS EC2 DNS 안내](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/routing-to-ec2-instance.html)
- 서울 리전에 프라이빗 ECR 리포지토리 `boongboongus-backend`를 만듭니다. EC2 인스턴스 역할에 해당 리포지토리의 pull 권한을 부여합니다. 로컬 업로드 담당자는 팀 AWS CLI 프로필의 push 권한을 사용합니다. [ECR 인증 안내](https://docs.aws.amazon.com/AmazonECR/latest/userguide/registry_auth.html)
- EC2에는 Docker Engine, Docker Compose 플러그인, AWS CLI, Nginx를 준비합니다. 로컬 업로드를 하는 Windows PC에도 AWS CLI가 필요합니다. SSH는 AWS 콘솔의 **연결 → SSH 클라이언트** 명령을 Git Bash에 복사합니다. [Ubuntu Docker 설치](https://docs.docker.com/engine/install/ubuntu/) · [AWS CLI 설치](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)

EC2에 접속한 다음 확인합니다.

```bash
sudo apt-get update
sudo apt-get install -y nginx
sudo systemctl enable --now nginx
docker version
docker compose version
aws sts get-caller-identity
sudo nginx -t
```

Git Bash에서 DNS를 확인합니다.

```bash
nslookup api.example.com 8.8.8.8
```

**다음 단계 조건**  Docker 서버와 Compose 버전이 출력되고, EC2의 AWS 호출이 인스턴스 역할로 성공하며, DNS가 EC2의 Elastic IP를 반환합니다.

## 3. 승인된 main 이미지 빌드와 ECR 업로드

Windows Git Bash에서 실행합니다. 팀 저장소의 `main`을 별도 폴더에 받아 기존 기능 작업 폴더를 건드리지 않습니다. `ACCOUNT_ID`를 팀 계정 값으로 바꿉니다.

```bash
cd /c/work
git clone --branch main --single-branch https://github.com/2026-line4thon-team6/boongboongus-backend.git boongboongus-release
cd boongboongus-release
git log -1 --oneline
```

출력된 커밋이 1단계에서 승인한 SHA와 일치하면 이어서 실행합니다.

```bash
ACCOUNT_ID='123456789012'
REGION='ap-northeast-2'
REGISTRY="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
IMAGE="$REGISTRY/boongboongus-backend:$(git rev-parse HEAD)"

aws ecr get-login-password --region "$REGION"   | docker login --username AWS --password-stdin "$REGISTRY" &&
docker build --platform linux/amd64 -t "$IMAGE" . &&
docker push "$IMAGE"
```

**다음 단계 조건**  Docker push가 성공하고 ECR에 승인된 커밋 SHA 태그가 보입니다. EC2가 ARM이라면 빌드 플랫폼도 EC2 아키텍처와 맞춥니다.

## 4. EC2에 앱과 PostgreSQL 시작

이하 명령은 SSH로 접속한 **EC2 터미널**에서 실행합니다. 예시 운영 디렉터리를 만들고 환경 파일의 권한을 제한합니다.

```bash
sudo install -d -o "$(id -u)" -g "$(id -g)" -m 0750 /opt/boongboongus
cd /opt/boongboongus
umask 077
touch app.env release.env
chmod 600 app.env release.env
nano app.env
```

`app.env` 기본 예시입니다. `DB_PASSWORD`, `AUTH_TOKEN_KEY`, 카카오 값을 채우세요. 신규 테스트 DB에서 사용할 비밀번호는 `openssl rand -hex 32`, 앱 토큰 서명 키는 `openssl rand -base64 32`로 별도 생성할 수 있습니다. 이전에 공유 채팅에 붙여 넣은 키는 교체한 값을 사용합니다. 운영 중인 서명 키는 계획된 교체 때만 바꿉니다.

```dotenv
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
SERVER_FORWARD_HEADERS_STRATEGY=framework
DB_HOST=db
DB_PORT=5432
DB_NAME=boongboongus
DB_USERNAME=boongboongus
DB_PASSWORD=
AUTH_TOKEN_KEY=
CORS_ALLOWED_ORIGINS=https://app.example.com
KAKAO_LOGIN_REST_API_KEY=
KAKAO_LOGIN_CLIENT_SECRET=
KAKAO_LOGIN_REDIRECT_URIS=https://app.example.com/oauth/callback
```

- 카카오 로그인 콜백은 카카오 개발자 설정과 프론트 라우터에 동일한 전체 URI를 등록합니다. Client Secret을 활성화한 경우 그 값도 입력합니다. [카카오 로그인 설정](https://developers.kakao.com/docs/latest/ko/kakaologin/prerequisite)
- 지도·경로·날씨는 `KAKAO_LOCAL_REST_API_KEY`, `KAKAO_MOBILITY_REST_API_KEY`, `KMA_SERVICE_KEY`를 설정합니다. 라디오는 `GROQ_API_KEY`, `ELEVENLABS_API_KEY`를 사용하고 DJ 연동은 배포할 기능에 맞게 `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`을 설정합니다. 게임은 별도 AI 키가 필요 없습니다. [환경변수 예시](../.env.example)
- 카드 사진을 활성화할 경우 EBS의 실제 마운트를 먼저 확인하고, 그 아래 사진 디렉터리를 UID/GID `10001:10001`이 쓸 수 있게 준비합니다. 아래 Compose의 `app`에 그 경로를 `/data/media`로 바인드 마운트하고, `MEDIA_STORAGE_ROOT=/data/media`, `MEDIA_PUBLIC_BASE_URL=https://api.example.com`, 별도의 `MEDIA_SIGNING_KEY`를 지정합니다. 장소 공유 링크가 필요하면 `PLACE_SHARE_BASE_URL`도 실제 공개 주소에 맞춥니다.
- 운영 DB가 이미 있으면 `DB_HOST` 등 운영 연결 정보와 Flyway 실행 권한을 확인합니다. 아래 예시에서 `db` 서비스와 `depends_on`, `postgres-data` 정의를 빼고 앱만 실행합니다.

`nano release.env`로 아래 한 줄을 작성합니다. 끝의 값은 3단계에서 ECR에 올린 전체 커밋 SHA입니다.

```dotenv
APP_IMAGE=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/boongboongus-backend:REPLACE_WITH_APPROVED_SHA
```

`nano compose.yaml`로 다음 예시를 작성합니다. 저장소의 로컬 Compose는 PostgreSQL만 시작하므로 이 파일은 EC2의 운영 디렉터리에 둡니다.

```yaml
name: boongboongus
services:
  db:
    image: postgres:18
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME:?Set DB_NAME}
      POSTGRES_USER: ${DB_USERNAME:?Set DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?Set DB_PASSWORD}
    command: ["postgres", "-c", "timezone=UTC"]
    volumes:
      - postgres-data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 10s
      timeout: 5s
      retries: 10
  app:
    image: ${APP_IMAGE:?Set APP_IMAGE}
    restart: unless-stopped
    env_file: app.env
    ports:
      - "127.0.0.1:8080:8080"
    depends_on:
      db:
        condition: service_healthy
    # 카드 사진을 활성화한 경우 실제 EBS 경로로 변경하고 아래 두 줄 사용
    # volumes:
    #   - /mnt/boongboongus/media:/data/media
volumes:
  postgres-data:
```

EC2에서 이미지 주소의 계정 ID를 팀 값으로 지정합니다. `config -q`는 환경변수 값을 화면에 출력하지 않고 설정을 검증합니다.

```bash
cd /opt/boongboongus
ACCOUNT_ID='123456789012'
REGION='ap-northeast-2'
REGISTRY="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"

aws ecr get-login-password --region "$REGION"   | docker login --username AWS --password-stdin "$REGISTRY"

compose() { docker compose --env-file app.env --env-file release.env "$@"; }
compose config -q && compose pull app && compose up -d --wait
compose ps
compose logs --tail=80 app
curl -fsS --retry 15 --retry-connrefused --retry-all-errors --retry-delay 2 http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/api/v1/health
```

**다음 단계 조건**  DB가 `healthy`이고 앱 로그에 Flyway 성공과 Spring Boot 시작이 보입니다. `/actuator/health`는 `status: UP`, `/api/v1/health`는 `data.status: UP`을 반환합니다. 하나라도 실패하면 Nginx 적용 전에 앱 로그와 DB 연결을 확인합니다.

## 5. Nginx와 HTTPS 연결

EC2에서 `sudo nano /etc/nginx/sites-available/boongboongus`로 파일을 만들고 도메인을 실제 값으로 바꿉니다.

```nginx
server {
    listen 80;
    server_name api.example.com;
    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header Forwarded "";
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header X-Forwarded-Prefix "";
    }
}
```

라디오의 `audioUrl`은 들어온 요청 주소에서 만들어집니다. 위 헤더와 `SERVER_FORWARD_HEADERS_STRATEGY=framework`를 함께 적용해야 HTTPS 프록시 뒤에서도 공개 주소가 생성됩니다. [Spring Boot 프록시 설정](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)

```bash
sudo ln -sfn /etc/nginx/sites-available/boongboongus /etc/nginx/sites-enabled/boongboongus
sudo nginx -t && sudo systemctl reload nginx
curl -fsS http://api.example.com/api/v1/health

sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx --redirect -d api.example.com
sudo nginx -t && sudo systemctl reload nginx
sudo certbot renew --dry-run
```

**다음 단계 조건**  외부 Git Bash에서 아래 두 명령이 성공하고 TLS 인증서 오류가 없습니다. HTTP는 HTTPS로 이동합니다. [Certbot 가이드](https://eff-certbot.readthedocs.io/en/stable/using.html)

```bash
curl -fsS https://api.example.com/api/v1/health
curl -I http://api.example.com/api/v1/health
```

`prod`에서는 Swagger가 꺼져 있습니다. 공개 API 명세나 Postman, 앱의 네트워크 탭으로 확인합니다. [API 문서](https://2026-line4thon-team6.github.io/boongboongus-backend/api/)

## 6. 프론트 배포 후 연결과 판정

1. 백엔드 담당자가 프론트에 **HTTPS API 주소**, **API 명세**, **배포 SHA**를 전달합니다. 프론트가 경로 뒤에 `/api/v1`을 붙이는지 확인해 base URL에서 경로가 중복되지 않게 합니다.
2. 프론트가 배포 주소와 카카오 콜백 경로를 공유하면, 백엔드 `CORS_ALLOWED_ORIGINS`에는 정확한 브라우저 Origin만 쉼표로 구분해 넣습니다. 예시는 `https://app.example.com`입니다. 콜백 전체 주소는 `KAKAO_LOGIN_REDIRECT_URIS`와 카카오 설정에 넣습니다. 프론트의 빌드 환경변수를 바꿨으면 프론트를 다시 배포합니다.
3. 서버에서 `app.env`를 바꿨으면 아래 명령으로 앱 컨테이너를 다시 만듭니다.

```bash
cd /opt/boongboongus
docker compose --env-file app.env --env-file release.env up -d --force-recreate --no-deps app
```

프론트 도메인을 실제 값으로 교체해 Git Bash에서 브라우저 사전 요청을 재현합니다.

```bash
curl -i -X OPTIONS 'https://api.example.com/api/v1/game-sessions'   -H 'Origin: https://app.example.com'   -H 'Access-Control-Request-Method: POST'   -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key,x-couple-id'
```

**연동 완료 조건**  사전 요청이 2xx이고 `Access-Control-Allow-Origin`이 프론트 주소와 일치합니다. 배포된 프론트에서 카카오 로그인 후 `GET /api/v1/me`가 200이어야 합니다. 이후 테스트 커플로 준비 주행을 만든 뒤 게임 생성 `202 → SUCCEEDED → 세션 조회 200`을 확인합니다. 라디오가 릴리스 범위에 있으면 음성 URL의 HTTPS와 Bearer 토큰을 넣은 MP3 재생도, 카드 사진이 범위에 있으면 업로드 후 조회도 확인합니다.

## 7. 업데이트·자동 배포·롤백

첫 수동 배포와 프론트 연결을 통과한 뒤 아래 흐름을 별도 워크플로에서 자동화합니다.

1. 팀 `main`만 배포 대상으로 둡니다. 처음에는 `workflow_dispatch`로 실행하고, 안정화되면 `main`의 성공한 CI 뒤에 배포가 이어지도록 설정합니다. 별도 워크플로로 분리한다면 통과한 CI의 커밋 SHA를 확인하고 그 SHA를 체크아웃합니다. 동시 배포는 한 번에 하나로 제한합니다.
2. GitHub의 `production` Environment에서 AWS OIDC 역할을 사용합니다. Job에 `permissions: contents: read`와 `id-token: write`를 부여하고, AWS 역할의 신뢰 정책을 팀 저장소의 해당 Environment로 한정합니다. `production`의 배포 브랜치도 `main`으로 제한합니다. 이미지 태그는 테스트한 커밋 SHA를 사용합니다. [GitHub AWS OIDC 설정](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
3. EC2는 인스턴스 역할로 이미지를 내려받습니다. 원격 실행을 자동화할 경우 SSM Run Command로 4단계의 pull·교체·헬스 확인을 실행하고, 명령 상태와 HTTPS 헬스 체크가 모두 성공해야 배포를 성공 처리합니다. SSM이 준비되기 전에는 담당자가 같은 명령을 SSH에서 실행합니다. [AWS SSM Run Command](https://docs.aws.amazon.com/systems-manager/latest/userguide/run-command.html)
4. DB 마이그레이션이 있는 릴리스는 운영 DB와 사진 데이터의 백업·복구 경로를 확인한 뒤 적용합니다. 직전 정상 이미지 태그를 유지하고 새 이미지가 실패하면 호환성을 확인한 뒤 `release.env`를 이전 태그로 되돌려 `compose pull app && compose up -d --no-deps app`을 실행합니다. Flyway가 적용한 스키마는 이미지 교체만으로 되돌아가지 않으므로, 기존 앱이 새 스키마와 호환되지 않으면 배포를 멈추고 복구 방법을 결정합니다.
