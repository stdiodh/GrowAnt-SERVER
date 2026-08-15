# 시세 공급자 비교 관측 저장소

작성일: 2026-08-15

V4 pagination 증거 보강: 2026-08-16

이 문서는 KIS와 토스증권을 같은 조건에서 비교하기 전에 필요한 관측 저장 기반의 계약을 설명한다. 이 변경만으로 실제 공급자 연결이나 우승 공급자 선정이 끝나는 것은 아니다. 실제 API 자격 증명, 데이터 이용 권리, 공급자별 의미 확인과 한 거래일 실측은 후속 단계다.

브랜치, PR, 검증 정책은 루트 [README](../README.md)를 우선하며, 기존 분봉의 구조와 실시세 전환 차단 조건은 [1분봉 개발 가이드](market-minute-candle-development.md)를 따른다.

## 1. 해결하려는 문제

공급자별 결과를 기존 `minute_candles`에 바로 저장하면 다음 문제가 생긴다.

- 기본키가 `(ticker, bucket_start)`라 같은 시각의 KIS·토스 결과를 함께 보존할 수 없다.
- 어느 소스 커밋과 어떤 5종목·관측 구간으로 실행했는지 증명할 수 없다.
- 공급자 시각과 로컬 시각의 오차가 큰 표본이 지연 통계에 섞일 수 있다.
- 저장·벤치마크·재생 권리가 확인되지 않은 데이터를 실수로 기록할 수 있다.
- 재실행과 정리 과정에서 원본 증거가 덮이거나 보존 기간을 넘길 수 있다.

따라서 비교 표본은 canonical 분봉과 분리된 append-only 관측 저장소에 먼저 넣는다. 검증을 마친 뒤에도 이 저장소가 `minute_candles`를 자동으로 갱신하지는 않는다.

## 2. 이번 슬라이스의 완료 범위

- `(run_id, provider)`로 KIS·토스·독립 기준 데이터를 격리한다.
- benchmark spec, source commit, dirty 여부, 예정 관측 구간을 실행마다 고정한다.
- 순서가 있는 기대 종목 목록과 길이-prefix SHA-256으로 같은 종목 집합임을 검증한다.
- 권리 판단과 문서 증거 checksum을 실행에 고정하고 미확인 권리는 fail-closed로 처리한다.
- venue, session, 1분 간격, timestamp, 보정, 정정, 무체결, 거래량 의미를 타입으로 기록한다.
- NTP/chrony 표본과 활성화에 사용한 clock sequence를 보존한다.
- REST poll, tick, candle, 장애·복구 이벤트를 덮어쓰기 없이 기록한다.
- 여러 REST 페이지를 하나의 logical poll로 묶고 raw cursor 전달·종료 근거를 보존한다.
- 완료 또는 무효 실행의 안전한 집계값과 row checksum만 manifest로 내보낸다.
- 보존 기한이 지난 실행을 `INVALID`로 전환하고 증거를 삭제한 뒤 영구 cleanup audit을 남기는 저장소·서비스 경계를 제공한다.

이번 슬라이스에서 하지 않는 일은 다음과 같다.

- KIS·토스 인증 또는 실제 HTTP/WebSocket 호출
- 원본 payload, Authorization, 앱 키, URL·헤더 저장
- 기존 분봉 API나 `minute_candles` 변경
- 공급자 점수 계산, k6 실행 또는 최종 공급자 선정
- KOSPI 전체 구독·조회 최적화
- cleanup scheduler, 운영 job, 실패 알림·지표

## 3. 실행 불변값

실행을 만들 때 다음 값은 이후 수정하지 않는다.

| 구분 | 저장 값 | 목적 |
| --- | --- | --- |
| 실행 범위 | `run_id`, `provider`, `role`, `origin` | 후보·기준 공급자 격리 |
| 코드 | source commit SHA, dirty 여부 | 같은 구현인지 확인 |
| 명세 | benchmark spec ID와 SHA-256 | 같은 실험 규칙인지 확인 |
| 시간 | `window_start`, `window_end` | 같은 장·같은 구간 비교 |
| 종목 | 기대 개수, 순서별 ticker, 집합 SHA-256 | 5종목 누락·추가·순서 변경 검출 |
| 권리 | 6개 판단과 근거 ID·SHA-256 | 허용되지 않은 저장·시험 차단 |
| 보존 | `retention_until` | 삭제 후보 판정과 수동·운영 job의 정리 기준 |

기본 5종목은 프로젝트 추적 목록인 `005930`, `000660`, `035720`, `035420`, `005380`을 사용한다. KOSPI 전체로 확대할 때는 같은 형식으로 master snapshot의 종목 수와 checksum을 새 실행에 고정한다. 실행 중 종목 집합을 바꾸지 않는다.

## 4. 상태와 활성화 게이트

```text
PLANNED --(권리·의미·종목·clock 통과)--> RUNNING
PLANNED -------------------------------> INVALID
RUNNING --(예정 구간 완주)-------------> COMPLETED
RUNNING --(clock 불건강·실패)----------> INVALID
```

활성화는 다음 조건을 모두 확인한다.

1. 여섯 권리 판단이 모두 확정돼 `UNKNOWN`이 없고, 이번 벤치마크에 필요한 저장·비교 권리는 `ALLOWED`다. 사용하지 않는 replay·CI·내부 표시·외부 배포는 `DENIED`일 수 있다.
2. 역할별 timestamp 의미가 정확하다. 실시간 역할은 공급자 체결 시각, REST 기준 봉 역할은 공급자 봉 시각을 요구한다.
3. venue·session·interval·보정·정정·무체결·volume 의미가 문서 근거와 함께 확정됐다.
4. 기대 종목의 개수, 0부터 연속인 순서, checksum이 실행 명세와 같다.
5. 최신 clock 표본이 동기화 상태이며 `abs(offset) + uncertainty <= 100ms`이고 freshness 범위 안이다.
6. 권리·의미·종목·clock 입력을 모두 읽은 뒤의 최종 decision time이 예정 관측 시작 시각을 넘지 않는다.

실행 행을 먼저 잠그고 권리·의미·종목·clock 입력을 읽은 뒤 decision time을 마지막에 읽으며, 마지막 clock sequence 확인과 `RUNNING` 전환을 같은 트랜잭션에서 수행한다. 따라서 입력 조회 중 잠금 대기나 GC로 window 시작을 넘기면 이전 시각으로 활성화할 수 없다. 검증 직후 더 최신의 불건강 표본이 들어오려 해도 같은 행 잠금 뒤에 직렬화되며, sequence 불일치면 활성화가 실패한다. 실행 중 새 clock 표본이 기준을 벗어나면 해당 표본은 증거로 남기고 실행을 `INVALID`로 전환한다. 로컬 시각이 뒤로 이동한 표본도 append-only 증거로 남기되 latest pointer는 이동시키지 않고 같은 트랜잭션에서 실행을 무효화한다.

`started_at`은 트랜잭션 커밋 시각이 아니라 위 최종 decision time이다. 애플리케이션 시계만으로는 decision 직후의 JVM 정지나 DB 커밋 지연까지 포함해 `window_start` 이전 커밋을 증명할 수 없다. 따라서 후속 공급자 harness는 충분한 사전 여유를 두고 활성화하고, 활성화 호출이 반환된 뒤에만 구독·polling을 시작하며, 시작 경계를 놓친 실행은 비교 증거로 채택하지 않아야 한다. 이 foundation은 커밋 시각 보장을 주장하지 않는다.

시계 후퇴 중의 자동·수동 무효화 시각은 `started_at` 또는 `created_at`보다 이르지 않도록 단조 하한을 적용한다. 반대로 정상 완료 시각은 보정하지 않고 실제 `window_end` 이후이며 건강한 clock 표본이 있을 때만 허용한다.

완료 처리도 실행 행을 먼저 잠근 뒤 completion decision time을 읽는다. 따라서 진행 중인 append가 잠금을 오래 보유해 clock freshness 또는 retention 경계를 넘기면 잠금 전의 과거 시각으로 완료할 수 없다.

`activate`·`complete`의 잠금·판정·전환과 `recordClockSample`의 append·무효화는 Spring transaction proxy를 통해 호출될 때 각각 하나의 트랜잭션으로 묶인다. foundation은 서비스와 정책을 Spring bean으로 등록하고 프록시 적용을 단위 테스트로 확인한다. 후속 공급자 어댑터는 이 서비스를 직접 생성하거나 우회하지 않아야 하며, 실제 PostgreSQL 동시성 IT가 통과하기 전에는 운영 보장으로 표시하지 않는다.

## 5. 테이블과 데이터 경계

`window_start`와 `window_end`는 비교 점수에 포함할 반개구간 `[start, end)`이다. 공급자 시계 오차나 복구 지연 때문에 서로 다른 후보가 다른 행을 포함하지 않도록 관측 종류별 포함 시각을 고정한다.

| 관측 종류 | window 포함 기준 | 이유 |
| --- | --- | --- |
| REST poll | `request_started_at` | 이 실행에서 시작한 공급자 호출만 비교 |
| tick | `socket_received_at` | 아직 검증되지 않은 공급자 시계가 표본 포함 여부를 바꾸지 않게 함 |
| candle | `bucket_start` | 종료 직후 확정·백필된 마지막 1분 봉도 대상 구간에 포함 |
| fault/recovery | `observed_at` | 같은 장애 주입·복구 관측 구간 비교 |

활성화용 clock 표본은 window 시작 전에 필요하므로 clock table에는 이 gate를 적용하지 않는다. 실행 중 clock 표본도 시각 품질 증거로 보존한다. REST 응답 완료나 candle 정규화는 window 종료 뒤일 수 있지만 해당 run이 여전히 `RUNNING`일 때만 저장된다. 따라서 benchmark spec은 마지막 요청·봉·복구가 기록될 수 있는 종료·정리 시점을 함께 정의해야 한다.

V3 migration은 기존 V2 migration 파일을 수정하지 않고 다음 테이블만 추가한다.

| 테이블 | 보존 내용 |
| --- | --- |
| `market_observation_runs` | 실행 명세, 권리, 상태, clock pointer, 보존 기한 |
| `market_observation_expected_tickers` | 순서가 있는 기대 종목 |
| `market_observation_semantics` | 공급자 데이터 의미와 문서 근거 |
| `market_observation_clock_samples` | offset·uncertainty·동기화 상태 |
| `market_observation_rest_polls` | 요청 시각·결과·429 메타데이터·원본 반환 봉 수·실행 대상 봉 수·logical poll/page/cursor/종료 근거 |
| `market_observation_ticks` | 기대/보고 종목, 연결 epoch, 로컬 수신 sequence, 정규화 결과 |
| `market_observation_candles` | REST 봉 또는 로컬 집계 봉의 revision별 표본 |
| `market_observation_fault_events` | disconnect·resubscribe·backfill·복구 결과 |
| `market_observation_cleanup_audits` | 만료 삭제 결과와 테이블별 삭제 건수 |

REST 요청 UUID와 tick의 `(connection_epoch, local_receive_sequence)`는 로컬 identity다. 공급자 event ID와 revision은 중복될 수 있으므로 unique key로 사용하지 않는다. `PROVIDER_REST` 봉은 같은 scope·ticker의 REST poll을 반드시 참조한다. 독립 기준 데이터는 후보 공급자의 source 값으로 섞지 않고 별도 provider scope와 권리를 가진다.

가격·수량은 실측 계산에 필요해 관측 테이블에는 존재하지만 manifest에는 포함하지 않는다. manifest에는 실행 명세, clock·semantics·rights의 제한된 메타데이터, 문서 근거의 checksum, 테이블별 건수와 전체 row checksum만 기록한다. 내부 evidence ID도 자격 증명 오입력에 대비해 manifest에는 내보내지 않는다.

이 manifest는 접근 제한된 내부 증거다. row checksum도 1분 시계열에서 계산한 값이므로 공급자의 benchmark 공개 허용을 받기 전에는 PR·Velog·공개 artifact에 올리지 않는다. 외부 공개가 허용되면 가격을 복원할 수 없는 일별 집계만 담은 별도 redacted scorecard를 만들고, 내부 manifest나 cursor·row checksum을 공개 근거로 대신 사용하지 않는다.

현재 run 행의 `rights_evidence_id`와 `rights_evidence_sha256`은 여섯 권리 및 KRX·NXT 같은 상위 권리 근거를 묶은 비공개 canonical bundle을 가리키는 포인터다. foundation은 이 checksum과 enum을 저장하지만 bundle 내부의 항목별 scope·유효기간·답변 권한을 DB에서 다시 검증하지는 않는다. 실제 공급자 adapter를 활성화하기 전에는 승인 registry가 bundle을 검증해 같은 여섯 결정과 scope를 만든다는 경로를 추가해야 하며, 그 전까지 실제 관측은 `RIGHTS_BLOCKED`다.

모든 observation append와 `COMPLETED` 전환은 해당 관측 시각 이하에서 가장 최근인 clock 표본이 freshness·동기화·오차 기준을 만족해야 한다. DB 저장 전에 다음 clock 표본이 들어와도 관측 당시 표본으로 판정하며, 정상 tick은 그 as-of sequence를 직접 참조해야 한다. sampler가 멈춰 유효 표본이 오래되면 append와 완료를 거절한다. `PROVIDER_REST` 봉은 성공하고 정규화된 동일 요청의 `[requested_from, requested_to)` 안에 있어야 하고, 봉 관측 시각이 부모 REST 응답 관측보다 빠를 수 없다. 공급자 payload 전체의 `returned_candle_count`와 run window에 포함해 저장할 `eligible_candle_count`를 분리하고, 완료 전에는 후자와 실제 저장 봉 수가 일치해야 한다. 따라서 페이지가 window 밖 봉을 함께 반환해도 원본 개수는 보존하면서 완료가 영구 차단되지 않는다.

완료 경로는 run을 먼저 `FOR UPDATE`로 잠근 뒤 별도 READ COMMITTED statement에서 REST poll과 봉 수를 다시 읽는다. 완료가 이미 시작된 append를 기다렸다면 그 commit을 새 snapshot에서 확인하므로, 반환 봉이 덜 저장된 실행을 terminal 상태로 닫지 않는다.

### 5.1 V4 REST pagination provenance

V3 migration 파일을 수정하지 않는 forward-only `V4__market_observation_rest_poll_pagination.sql`이 기존 REST poll 테이블에 다음 다섯 값을 추가한다.

| 값 | 의미 |
| --- | --- |
| `poll_run_id` | 여러 HTTP page가 속한 하나의 logical poll ID. 첫 page의 `request_id`와 같다. |
| `page_ordinal` | 0부터 연속인 page 순서 |
| `request_cursor` | page 0은 동일 window의 초기 요청 상한(`before`, 없으면 null), page 1 이상은 직전 page가 반환해 이번 요청에 그대로 사용한 raw cursor |
| `next_cursor` | 공급자가 이번 page에 반환한 raw cursor |
| `poll_terminal` | 공급자 이력이 소진됐는지가 아니라 GrowAnt가 목표 범위를 확보했거나 실패로 이번 poll을 끝냈다는 로컬 사실 |

`next_cursor = null`만으로 logical poll 완료를 판단하지 않는다. 필요한 시간 범위를 이미 확보했다면 공급자가 더 오래된 cursor를 반환해도 `poll_terminal = true`로 종료할 수 있어야 불필요하게 전체 과거 이력을 조회하지 않는다. 반대로 non-terminal page는 성공 응답과 `next_cursor`가 모두 필요하다.

continuation page는 직전 page의 성공·비종료 상태, 동일 ticker와 요청 구간, raw `next_cursor == request_cursor`, 응답 정규화 이후의 다음 요청 시작을 모두 만족해야 저장된다. 완료 전에는 logical poll마다 page 0부터 순서가 연속이고 terminal page가 정확히 하나이며 마지막 ordinal인지 다시 검사한다. inclusive cursor 경계의 동일 봉은 각 HTTP page 증거로 각각 저장하고, 후속 snapshot 병합에서만 수치상 같은 값을 중복 제거한다.

V3의 기존 행은 각각 독립적으로 완료된 단일 page poll인 `poll_run_id=request_id`, `page_ordinal=0`, `poll_terminal=true`로 backfill한다. V3는 실패 요청에도 0보다 큰 `eligible_candle_count`를 허용했으므로 migration은 그 기존 값을 증거 그대로 보존한다. 실패 요청은 원래 `PROVIDER_REST` candle의 부모가 될 수 없고 완료 count에도 포함되지 않는다. V4 Kotlin 모델은 신규 실패 page에 `eligible_candle_count=0`을 강제하지만, DB 제약은 손실 없는 V3 backfill을 위해 legacy 값을 허용한다. evidence manifest에는 cursor 문자열을 노출하지 않고 page 수인 `rest-poll`, logical poll 수인 `rest-poll-run`, 그리고 cursor provenance를 포함한 row checksum만 기록한다.

cleanup 후보 조회와 잠금 후 삭제 판정은 애플리케이션 `requestedAt`과 PostgreSQL `CURRENT_TIMESTAMP`가 모두 `retention_until`에 도달해야 통과한다. 애플리케이션 시계만 앞으로 이동해도 아직 DB 시각상 유효한 증거를 조기 삭제하지 않으며, 두 시계가 어긋나면 삭제를 늦추는 방향으로 실패한다.

이번 슬라이스는 `cleanupExpired` 호출 경계만 제공하며 자동 실행기는 포함하지 않는다. bounded scheduler 또는 운영 job, 실패 알림과 지표를 연결하기 전에는 `retention_until` 이후 원본이 자동 삭제된다고 보장할 수 없다. 실제 공급자 데이터를 넣기 전 이 운영 경로를 별도 차단 조건으로 확인한다.

## 6. 검증 순서

코드 슬라이스는 다음 순서로 검증한다.

```bash
./gradlew unitTest --no-build-cache
./gradlew integrationTest --no-build-cache
./gradlew clean bootJar --no-build-cache
```

통합 테스트는 PostgreSQL에서 다음을 확인해야 한다.

- run·provider 격리와 기존 `minute_candles` 불변
- PLANNED/RUNNING/terminal 상태 및 권리·보존 기한 append gate
- 기대 종목 밖 데이터 거절과 misroute 보존
- REST request identity, tick local sequence, revision append-only 규칙
- logical poll의 연속 page, raw cursor 전달, terminal·page별 candle 수 완료 규칙
- 활성화와 append/terminal 전환의 실제 row lock 순서
- 만료된 RUNNING 실행의 무효화·삭제·cleanup audit
- 같은 표본의 결정적 checksum과 다른 scope의 독립 checksum

Docker를 사용할 수 없는 환경에서 단위·컴파일만 통과했다고 PostgreSQL 검증 완료로 표시하지 않는다. 임시 로컬 PostgreSQL smoke는 migration 문법 확인을 보조할 뿐 Testcontainers 동시성 IT를 대체하지 않는다.

## 7. 후속 단계와 공급자 선정

1. [공급자 권리 Gate 0](market-provider-rights-gate.md)에 따라 KIS·토스의 저장·벤치마크·재생·내부 표시 권리를 서면 근거로 실행에 입력한다.
2. 공급자 문서와 작은 실제 표본으로 timestamp, venue/session, adjusted, 정정·무체결·volume 의미를 확정한다.
3. 권리 승인 전에는 KIS·토스 합성 응답을 parser→V4 관측 저장소까지 결정적으로 재생해 pagination, 오류, 단절·백필 경계를 먼저 검증한다.
4. 권리 승인 뒤 같은 5종목·같은 정규장 구간을 각 provider scope에 수집한다. 토스는 REST 1분봉, KIS는 WebSocket 체결과 REST 복구 경로를 별도로 평가한다.
5. 지연·누락·revision·429·재연결·복구를 비교하고, 서버 조회 부하는 동일 DB dataset으로 k6에서 따로 측정한다.
6. 5종목 실험을 통과한 후보만 KOSPI 50→200→전체 단계로 확대한다. 종목 master checksum, 구독 상한, polling budget, DB write·retention, 8시간 soak를 매 단계 다시 측정한다.
7. 화면 MVP와 KOSPI 운영은 요구가 다르므로 필요하면 공급자를 따로 선정한다. 무료 개인 키를 공개 다중 사용자 시세 재배포 권한으로 해석하지 않는다.

k6는 GrowAnt REST API의 응답 처리량과 지연을 비교하는 도구이지 공급자 WebSocket 품질을 대신 측정하지 않는다. 최종 scorecard는 공급자 관측 결과와 서버 k6 결과를 분리해 기록한 뒤 권리, 정확성·복구, 지연, 호출·구독 한도, 비용 순으로 판단한다.
