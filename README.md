# GrowAnt Server

GrowAnt의 Kotlin/Spring Boot 백엔드 서버입니다.

분봉 기능을 수정하거나 새 시세 공급자를 연결할 때는 [1분봉 개발 가이드](docs/market-minute-candle-development.md)를 먼저 확인합니다.
실제 시세 호출 전에는 [공급자 권리 Gate 0](docs/market-provider-rights-gate.md)에서 서면 허용 범위와 증거를 먼저 확정합니다.

## 필수 협업 규칙

> 이 절은 저장소의 브랜치, 커밋, Pull Request(PR), 검증 절차에 대한 단일 기준입니다.
> 사람과 Codex를 포함한 모든 작업자는 작업을 시작하기 전에 이 문서를 읽고 따라야 합니다.

문서에 없는 예외가 필요하면 임의로 우회하지 않습니다. 먼저 변경 이유와 안전한 검증 방법을 PR에 적고 이 문서를 함께 수정합니다.

### AI 작업 시작 게이트

루트 `AGENTS.md`는 Codex가 저장소 작업을 시작할 때 자동으로 확인하는 진입점이며, 이 README를 정책의 단일 기준으로 연결합니다. README가 자동으로 읽힐 것이라고 가정하지 않고 다음 절차를 강제합니다.

1. AI는 매 작업마다 이전 대화나 요약에 의존하지 않고 현재 브랜치의 `README.md` 전체를 다시 읽습니다.
2. 첫 파일 수정 전에 현재 브랜치와 이번 작업에 적용되는 브랜치·검증 규칙을 확인합니다.
3. `README.md`가 없거나 읽을 수 없거나 내용이 요청과 충돌하면 작업을 임의로 진행하지 않고 사용자에게 알립니다.
4. 작업 완료 시 변경 내용과 실행한 검증, 실행하지 못한 검증을 구분해 보고합니다.

이 README와 루트 `AGENTS.md`는 `main`에 함께 유지하고, 정책을 변경할 때는 두 파일의 연결이 깨지지 않았는지 확인합니다.

### 기본 원칙

- 표준 흐름은 `feature/* -> develop -> main`입니다.
- `main`에는 언제든 실행·배포할 수 있고 QA가 끝난 코드만 둡니다.
- `develop`에는 QA가 즉시 검증할 수 있는 완결된 API 슬라이스와 이를 지원하는 독립 검증 가능한 변경만 둡니다.
- 구현 중인 코드와 실험은 `feature/*`에서만 진행합니다.
- `main`과 `develop`에 직접 push하거나 force push하지 않습니다.
- 현재는 별도 `hotfix` 경로를 두지 않습니다. 긴급 수정도 같은 흐름과 검증을 거칩니다.

현재 프로젝트는 하나의 Gradle 모듈입니다. 이 문서의 "API 슬라이스"는 물리적인 서브모듈이 아니라 `auth`, `market`, `trading`, `portfolio`, `account`처럼 API를 완결하는 도메인 기능 단위를 뜻합니다.

`hotfix` 경로가 없으므로 QA가 끝나지 않은 변경이 `develop`에 있을 때 긴급 운영 수정의 배포가 늦어질 수 있습니다. 그렇더라도 검증되지 않은 `develop`을 `main`에 올리지 않습니다. 별도 긴급 배포가 필요해지면 먼저 이 문서에 hotfix와 역병합 절차를 추가한 뒤 사용합니다.

### 전환 상태와 최초 설정

현재 저장소에는 `main`만 있고 `develop`, GitHub Actions workflow, Ruleset은 아직 없습니다. 따라서 아래 자동화 정책은 목표 상태이며, 최초 설정을 마친 시점부터 강제됩니다.

최초 1회는 다음 순서로 전환합니다.

1. 저장소 관리자가 현재 `main`에서 `develop`을 생성합니다.
2. 이후 모든 변경은 `develop`에서 만든 `feature/*`를 통해 반영합니다.
3. CI가 생기기 전 PR에는 단위·웹 테스트, 통합 테스트, 패키징, 수동 기동 검증 결과를 직접 기록합니다.
4. GitHub Actions를 추가하고 각 대상 PR에서 모든 check 이름이 실제로 보고되는지 확인합니다.
5. 마지막으로 Ruleset의 required status check를 활성화합니다.

Ruleset을 먼저 활성화하면 아직 존재하지 않는 check를 기다리느라 병합할 수 있으므로 반드시 이 순서를 지킵니다.

### 브랜치 전략

| 브랜치 | 역할 | 들어올 수 있는 변경 | 병합 조건 |
| --- | --- | --- | --- |
| `main` | 항상 실행·배포 가능한 안정 기준선 | `develop`의 승격 PR만 허용 | 전체 CI 재실행 성공, QA 확인, 코드 리뷰 승인 |
| `develop` | QA 환경에 배포할 변경 통합 | `feature/*` PR만 허용 | 기능은 API 슬라이스로 완결, 지원 변경은 독립 검증 가능, CI 성공 |
| `feature/<summary>` | 모든 기능 개발, 수정, 문서 작업 | 최신 `develop`에서 생성 | 완료 후 `develop`으로 PR하고 병합 뒤 삭제 |

프로덕션 동작을 바꾸는 PR은 "QA 가능한 API 슬라이스"로서 다음 조건을 모두 만족해야 합니다.

- 서버가 정상적으로 빌드되고 실행됩니다.
- 변경한 API의 정상·오류 시나리오를 직접 재현할 수 있습니다.
- 변경 범위에 해당하는 Controller, Service, 영속성 코드를 함께 반영합니다.
- DB schema를 바꾸면 기존 migration을 수정하지 않고 새 Flyway migration을 추가합니다.
- 변경 동작을 검증하는 `*Test` 또는 `*IT` 테스트가 있습니다.
- PR에 QA 절차, 요청 예시, 기대 결과가 적혀 있습니다.
- 기존 API를 깨뜨리는 미완성 코드나 실패하는 테스트가 없습니다.

문서, 테스트, CI, 빌드 설정만 바꾸는 PR은 해당 변경을 독립적으로 검증할 수 있어야 합니다. 위 항목 중 적용되지 않는 내용은 PR에 `N/A`와 이유를 적습니다.

여러 API 슬라이스가 `develop`에 합쳐진 뒤에는 그 전체가 한 번에 `main`으로 승격됩니다. 일부 기능만 선택 출시해야 하는 요구가 생기기 전까지 release 브랜치나 feature flag는 추가하지 않습니다.

### 브랜치 생성 규칙

새 브랜치는 항상 최신 `develop`에서 만듭니다.

```bash
git switch develop
git pull --ff-only origin develop
git switch -c feature/trading-order-validation
```

브랜치 이름은 `feature/<kebab-case-summary>` 형식을 사용합니다. 이슈 번호가 있으면 `feature/<issue-number>-<summary>`로 시작합니다.

- 올바른 예: `feature/auth-token-refresh`, `feature/42-trading-order-validation`
- 사용하지 않는 예: `feat/auth`, `dev-test`, `dh-branch`
- 하나의 브랜치에는 하나의 목적만 담습니다.
- PR을 열기 전에 최신 `develop`을 반영하고 충돌을 해결합니다.

### 커밋 규칙

모든 커밋은 AngularJS 형식을 따릅니다.

```text
<type>(<scope>): <subject>

<body>

<footer>
```

| Type | 사용 시점 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | README, API 설명 등 문서 수정 |
| `style` | 포맷팅 등 동작 변화가 없는 수정 |
| `refactor` | 기능 변화가 없는 구조 개선 |
| `test` | 테스트 추가 또는 수정 |
| `chore` | 빌드, 의존성, 도구 설정 등 프로덕션 로직 외 변경 |

커밋 제목은 다음 규칙을 지킵니다.

- `type`과 `scope`를 소문자로 작성합니다.
- `subject`는 50자 이내의 명령형으로 작성하고 마침표를 붙이지 않습니다.
- 한 커밋에는 한 가지 목적만 담습니다.
- 본문이 필요하면 무엇을(What), 왜(Why) 바꿨는지 적습니다.
- 호환성을 깨는 변경은 footer에 `BREAKING CHANGE:`를 적습니다.
- 관련 이슈는 footer에 `Closes #123`처럼 연결합니다.

```text
feat(trading): 주문 검증 API 추가
fix(auth): 만료 토큰의 오류 응답 수정
test(account): 자산 합계 통합 테스트 추가
docs(workflow): 브랜치 운영 규칙 추가
```

### PR 규칙

모든 변경은 PR로 병합합니다. PR 제목도 `<type>(<scope>): <subject>` 형식을 따릅니다.

PR 본문에는 다음 내용을 반드시 포함합니다.

- `What`: 바뀐 기능, 구조, 파일, 동작
- `Why`: 변경이 필요한 이유와 기존 문제
- `Key Code (Before & After)`: 핵심 동작 변화가 있을 때의 비교
- 영향받는 API와 데이터베이스 migration·호환성 여부
- 실행한 테스트 명령과 결과
- QA 절차, 요청 예시, 기대 결과
- 리뷰어가 중점적으로 확인할 위험과 판단 사항
- 관련 이슈가 있으면 이슈 번호(`Closes #...`)

본문 설명은 한글로 작성하고, 코드 비교 블록 안의 예시는 영어로 작성합니다. 제목은 `##`, 소제목은 `###` 수준을 사용합니다.

#### `feature/* -> develop`

- Draft PR은 일찍 열 수 있지만 QA 가능한 상태가 되기 전에는 병합하지 않습니다.
- 관련 `*Test`와 `*IT`를 모두 통과해야 합니다.
- 최소 1명의 코드 리뷰 승인이 필요합니다.
- 모든 리뷰 대화가 해결되어야 합니다.
- 기본 병합 방식은 **Squash merge**입니다. 최종 커밋 제목도 커밋 규칙을 따릅니다.

#### `develop -> main`

- PR의 source branch는 반드시 `develop`이어야 합니다. `feature/* -> main`은 금지합니다.
- `develop`에서 통과한 결과를 재사용하지 않고 PR의 최신 커밋에서 전체 CI를 다시 실행합니다.
- 전체 회귀 테스트, PostgreSQL 통합·동시성 테스트, 패키징, 컨테이너 기동 검사를 모두 통과해야 합니다.
- 최소 1명의 코드 리뷰 승인과 QA 완료 기록이 필요합니다.
- 승격 PR을 연 뒤에는 병합 또는 종료할 때까지 `develop`에 다른 PR을 병합하지 않습니다.
- QA 기록에는 담당자, 환경, 실행 일시, 검증한 `develop` commit SHA, 결과를 적습니다.
- PR의 `develop` SHA가 바뀌면 기존 QA 결과를 무효화하고 전체 CI와 QA를 다시 수행합니다.
- 배포 방법과 문제가 생겼을 때의 rollback 방법을 적습니다.
- 미해결 리뷰, 알려진 치명적 결함, 임시 우회가 있으면 병합하지 않습니다.
- **Merge commit**으로 병합해 `develop`의 이력을 보존합니다. Squash merge하지 않습니다. 이 병합 방식은 현재 사람의 운영 절차로 확인합니다.

### CI 필수 검사 계획

현재 저장소에는 GitHub Actions workflow가 없습니다. Gradle에는 `unitTest`와 `integrationTest`가 분리되어 있지만, 아래 workflow와 GitHub Ruleset이 추가되기 전까지 원격에서 자동으로 강제되지는 않습니다.

테스트 파일 이름은 다음 규칙으로 구분합니다.

- `*Test`: 단위 테스트와 Controller/Web 계층 테스트
- `*IT`: PostgreSQL Testcontainers를 사용하는 통합·동시성 테스트

새 Testcontainers 테스트는 반드시 `*IT`로 끝나야 합니다. `PostgresIntegrationTest`는 테스트 메서드가 없는 추상 지원 클래스이므로 `*Test` 필터에 선택되어도 실행되지 않습니다.

| 필수 check | `develop` PR | `main` PR | 검증 내용 |
| --- | :---: | :---: | --- |
| `source-branch-policy` | O | O | `develop`에는 `feature/*`, `main`에는 `develop`만 들어오는지 검사 |
| `unit-web-tests` | O | O | `./gradlew unitTest` |
| `postgres-integration-tests` | O | O | Docker 환경에서 `./gradlew integrationTest` |
| `package` | O | O | Java 21에서 `./gradlew clean bootJar` 및 `build/libs/*.jar` 생성 확인 |
| `container-smoke` | O | O | Compose build·기동 후 Nginx를 통한 로그인 API와 응답 계약 확인 |

각 항목은 별도 job과 별도 required check로 실행합니다. 하나라도 실패하면 병합할 수 없습니다. `main` PR에서는 모든 검사를 새로 실행합니다. `bootJar` 자체는 테스트를 실행하지 않으므로 두 테스트 job을 생략할 수 없습니다.

모든 job은 Ubuntu runner, Temurin JDK 21, 저장소의 Gradle wrapper 9.5.1을 사용합니다. 통합·smoke job에는 Docker daemon이 필요하고, Compose의 optional `env_file` 문법을 지원하는 Docker Compose 2.24 이상을 사용합니다. PR 검증에는 secret을 주입하지 않고 최소 `contents: read` 권한의 `pull_request` event를 사용합니다. 신뢰하지 않는 PR 코드를 실행하는 workflow에 `pull_request_target`을 사용하지 않습니다.

CI 구현 시 다음 순서로 보강합니다.

1. 현재 `unitTest`와 `integrationTest` task를 각각 독립 job으로 실행합니다.
2. 이름 의존을 완전히 없앨 필요가 생기면 `src/integrationTest` source set 또는 JUnit tag로 분리합니다.
3. `container-smoke`는 고유한 `COMPOSE_PROJECT_NAME`으로 PostgreSQL·Redis·서버·Nginx를 빌드하고 기동합니다.
4. 준비 지연을 고려해 제한 시간 동안 `http://127.0.0.1/api/auth/login`을 재시도합니다. `Content-Type: application/json`과 `{"provider":"kakao","nickname":"ci-smoke"}`를 보내 `2xx`, `success=true`, 비어 있지 않은 `data.token`을 모두 확인합니다.
5. 실패하면 backend, PostgreSQL, Nginx 로그를 남기고, 성공·실패와 관계없이 해당 CI project만 `docker compose down --volumes --remove-orphans`로 정리합니다.
6. `main`과 `develop`의 각 PR에서 check가 보고되는 것을 확인한 뒤 Ruleset에 required status check로 등록합니다.

### GitHub 브랜치 보호 계획

`main`과 `develop`에 다음 Ruleset을 적용합니다.

- PR 없이 병합할 수 없게 설정합니다.
- 직접 push, force push, 브랜치 삭제를 금지합니다.
- 최소 1명의 승인과 모든 리뷰 대화 해결을 요구합니다.
- 새 커밋이 추가되면 기존 승인을 무효화합니다.
- 관리자에게도 같은 규칙을 적용합니다.
- 대상 브랜치별 CI check를 필수로 지정합니다.
- `main` PR은 `source-branch-policy`로 source가 `develop`인지 추가 검증합니다.

GitHub 설정은 문서만으로 적용되지 않으므로 저장소 관리자가 Ruleset을 별도로 활성화해야 합니다.

### Codex AI 리뷰 운영 계획

Codex Code Review는 PR diff와 저장소의 `AGENTS.md` 규칙을 함께 읽고 높은 우선순위의 버그, 보안, 호환성 위험을 찾는 보조 리뷰어입니다. 로컬에서는 `/review`로 커밋 전 변경이나 기준 브랜치 대비 diff를 읽기 전용으로 검토할 수 있고, GitHub PR에서는 `@codex review` 또는 자동 리뷰를 사용할 수 있습니다.

Codex 리뷰는 테스트, 브랜치 보호, 사람의 코드 리뷰와 QA 승인을 대체하지 않습니다. AI 의견은 반드시 코드와 테스트로 확인하고, 적용하지 않는 의견에는 근거를 남깁니다.

도입 순서는 다음과 같습니다.

1. 루트 `AGENTS.md`에 이 README를 먼저 읽도록 지시하고 저장소 전용 Code Review Rules를 둡니다.
2. Codex cloud에 GitHub 저장소를 연결하고 [Code review 설정](https://chatgpt.com/codex/settings/code-review)에서 저장소를 활성화합니다.
3. 2주 동안 `@codex review`를 수동으로 요청하는 비차단 시범 운영을 합니다.
4. 실제 결함 탐지, 중복 지적, 오탐, 리뷰 지연을 기록해 `AGENTS.md` 규칙을 좁고 구체적으로 조정합니다.
5. 신뢰할 만한 결과가 쌓이면 `develop`과 `main` PR의 자동 리뷰를 켭니다.
6. Codex의 P0/P1 지적은 수정하거나 수용하지 않은 근거를 PR에 남긴 뒤 사람이 최종 병합 여부를 판단합니다.

공식 사용법은 [GitHub PR을 Codex로 리뷰하기](https://learn.chatgpt.com/docs/third-party/github)와 [Codex용 Custom Code Review rules](https://developers.openai.com/blog/custom-code-review-rules-for-codex)를 참고합니다.

### 도입 체크리스트

#### 1단계: 초기 브랜치와 규칙

- [ ] 관리자가 현재 `main`에서 `develop` 브랜치 생성
- [x] 루트 README에 브랜치, 커밋, PR, CI 정책 작성
- [x] 루트 `AGENTS.md`에서 README 필독 및 AI 리뷰 규칙 연결
- [ ] `.github/pull_request_template.md` 추가

#### 2단계: CI 구축

- [ ] PR용 GitHub Actions workflow 추가
- [ ] 허용 source branch를 검사하는 `source-branch-policy` 추가
- [ ] `unit-web-tests`와 `postgres-integration-tests` 분리
- [ ] `package` 및 `container-smoke` 추가
- [ ] 각 target PR에서 check 이름과 성공 결과 확인

#### 3단계: 브랜치 보호

- [ ] `main`, `develop` GitHub Ruleset 적용
- [ ] 실패하는 샘플 변경으로 각 required check의 병합 차단 검증

#### 4단계: Codex 리뷰 도입

- [ ] Codex cloud와 GitHub 저장소 연결
- [ ] 수동 `@codex review` 시범 운영
- [ ] 저장소 규칙 위반 1건, 안전한 예외 1건, 무관한 변경 1건으로 리뷰 품질 확인
- [ ] 결과 검토 후 자동 리뷰 활성화 여부 결정

### 작업 전후 체크리스트

작업 전:

- [ ] 이 문서의 필수 협업 규칙을 읽었습니다.
- [ ] 최신 `develop`에서 올바른 `feature/*` 브랜치를 만들었습니다.
- [ ] 변경 범위와 QA 가능한 완료 조건을 정했습니다.

PR 전:

- [ ] 커밋과 PR 제목이 규칙을 따릅니다.
- [ ] 관련 `*Test`와 `*IT`가 통과합니다.
- [ ] PR에 테스트 결과와 QA 절차를 적었습니다.
- [ ] API·DB 호환성, 인증·인가, 사용자 데이터 격리를 확인했습니다.
- [ ] Codex 또는 사람의 리뷰 지적을 처리했습니다.
