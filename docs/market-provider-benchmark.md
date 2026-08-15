# 국내주식 시세 공급자 비교와 KOSPI 전체 확장 시험

이 문서는 5종목 POC에서 실제 시세 공급자를 비교하고, 향후 KOSPI 전체를 수집할 때 같은 선택이 유효한지 검증하는 절차를 정의한다. 매수·매도는 범위에 포함하지 않는다.

## 0. 현재 상태

- 운영 코드의 실제 공급자는 아직 `sim`뿐이며 KIS·키움·토스·LS 어댑터는 없다.
- 현재 분봉 조회 API는 PostgreSQL에 저장된 행만 반환하며 요청 시 공급자 API로 fallback하지 않는다.
- 이 변경에서 추가한 B2/B3 k6 도구는 GrowAnt REST read leg만 가압한다.
- 후보별 관측 저장소 P1은 [별도 PR #4](https://github.com/stdiodh/GrowAnt-SERVER/pull/4)에서 구현·검증을 마쳤지만 아직 `develop`에 병합되지 않았다. replay write orchestrator, 서버·DB 지표 수집과 실제 장 5일 비교는 구현·실행 전이다.
- 따라서 현재 단계에서는 공급자 우승자를 선언하지 않는다.

2026-08-15 공개 이용 범위 기준으로 토스는 본인 매매 목적 밖의 저장·벤치마크·외부 표시가 `RIGHTS_BLOCKED`다. KIS는 개인 시세의 제3자 제공이 금지되고 내부 저장·벤치마크 범위는 서면 확인 전 `UNKNOWN`이다. 따라서 GrowAnt 공개 운영에 채택 가능한 개인용 무료 API는 현재 없고, 실제 공급자 호출도 Gate 0을 통과한 후보만 수행한다.

## 1. 별도로 결정해야 하는 세 역할

한 공급자를 억지로 모든 용도에 사용하지 않는다.

| 결정 | 후보 | 목적 |
| --- | --- | --- |
| 5종목 실시간 POC 공급자 | 권리 허가를 받은 KIS·키움·LS | WebSocket 체결, 자체 1분봉 집계, 재연결과 REST 복구 검증 |
| 완성 분봉 비교 공급자 | 권리 허가를 받은 KIS·키움·토스·LS | 공급자가 만든 확정 봉과 자체 집계 봉 대조 |
| KOSPI 전체 운영 공급자 | KRX 승인·코스콤 계약 경로 또는 전체 시장 계약이 가능한 공급자 | 전 종목 제공, 저장·표시·재배포 권리와 SLA 확보 |

토스의 현재 공개 운영 명세는 REST-only이므로 실시간 WebSocket 순위에 넣지 않는다. 서면 예외 승인을 받는 경우에만 분 종료 뒤 봉이 처음 나타나는 시간과 더 이상 바뀌지 않는 시간을 측정하는 비교군으로 사용한다. KIS 원시 체결 봉과 가격을 비교할 때는 `adjusted=false`를 명시하고, venue·session 의미를 서면으로 확인하기 전에는 KRX 정규장 OHLCV 정확성 비교군으로 판정하지 않는다.

## 2. 점수보다 먼저 적용하는 탈락 조건

다음 중 하나라도 충족하지 못하면 속도 점수를 계산하지 않는다.

- 시험·저장·내부 표시 범위가 서면으로 확인되지 않음
- 공개 운영 후보인데 KOSPI 전체 제공 또는 재배포 계약이 불가능함
- 같은 거래일의 확정 봉에 미복구 gap이 남음
- 공급자 봉과 자체 집계 봉의 OHLCV가 설명 없이 다름
- 공식 호출 한도 안에서 재연결과 REST 백필을 끝내지 못함
- 토큰·앱 키·계좌 정보 또는 허가받지 않은 원시 시세가 Git과 보고서에 포함됨
- 계약된 독립 기준 데이터로 최종 정확성을 판정할 수 없음

개인용 API로 5종목 POC가 성공해도 KOSPI 전체 공개 운영 공급자로 자동 승격하지 않는다.
다중 공급자 합의는 이상치를 찾는 잠정 근거일 뿐이다. 공통 upstream 오류를 놓칠 수 있으므로 독립 기준이 없으면 후보를 shortlist할 수는 있어도 `정확성 미확정` 표시 없이 최종 공급자로 채택하지 않는다.

## 3. 후보별 관측 저장소를 먼저 분리

현재 V2 `minute_candles`의 기본키는 `(ticker, bucket_start)`뿐이다. 같은 시각의 KIS·키움·토스·LS 봉을 이 테이블에 넣으면 충돌하거나 높은 revision이 다른 후보 값을 덮으므로 비교 증거로 사용할 수 없다.

실제 후보를 호출하기 전에 canonical V2와 분리된 시험 전용 관측 저장소를 준비한다. 이 기반은 별도 PR #4에 구현되어 있으므로 해당 PR을 병합하고 아래 식별·권리 gate가 유지되는지 확인한 뒤 사용한다. 최소 식별자는 다음과 같다.

```text
공통: run_id + provider + venue + session + interval + ticker
tick: connection_epoch + provider_event_id 또는 local_receive_sequence
REST poll: request_id + observed_at
candle observation: bucket_start + provider_revision 또는 observation_sequence
fault/recovery event: fault_id + event_sequence
```

관측 저장소는 동일 체결의 중복 수신, 같은 봉의 반복 polling과 revision, 재연결 전후 사건을 덮어쓰지 않는다. PR #4의 P1은 header·URL·credential·raw payload를 저장하지 않고 typed observation과 안전한 count/checksum manifest만 남긴다. 여섯 권리 판단은 모두 `UNKNOWN`이 아니어야 하고 이 시험에 필요한 저장·벤치마크는 `ALLOWED`여야 한다. 재생·CI·내부 표시·외부 제공은 실제로 사용할 때만 `ALLOWED`인 경로를 열며 `DENIED`인 용도에는 데이터를 쓰지 않는다. 허용되지 않은 실제 가격·시각열 대신 복원할 수 없는 완전 합성 fixture와 허용된 집계 지표만 사용한다. 시장 데이터는 개인정보가 아니므로 단순 비식별화가 이용 권리를 새로 만들지 않는다.

## 4. 공급자에는 정상 사용량만 적용

k6로 증권사나 거래소 endpoint를 직접 가압하지 않는다. 실제 공급자는 Gate 0을 통과한 뒤 같은 장비와 네트워크에서 정상 사용량으로 관측한다.

- KIS·키움·LS: 공급자당 물리 WebSocket 한 개로 같은 5종목 구독
- REST 분봉 안정성 비교: 공급자별 1 TPS 이하의 공통 pace 사용
- 토스 1 TPS round-robin: 종목별 관측 간격이 5초이므로 `마지막 미관측 < 실제 게시 ≤ 최초 관측` 구간과 ticker 순번 회전을 함께 기록
- 게시 지연 정밀 측정: 공급자별 공식 한도와 서면 허용 범위 안에서 별도 pace를 정하고 공통 1 TPS 결과와 섞지 않음
- 2xx·401·429·5xx, 공급자 body 오류 코드, 실제로 받은 `Retry-After`와 rate-limit header 기록. KIS는 `EGW00201`을 공식 quota 신호로 사용
- 인증 발급 endpoint는 벤치마크 반복 대상에서 제외하고 토큰을 재사용

REST 봉은 새 분이 끝난 뒤 10분 동안 1 TPS round-robin 관측을 계속한다. 분 종료 30초 이후 3회 연속 같은 값이면 `provisionally_stable`로 표시하지만 조기 종료하지 않는다. `last_changed_at`은 10분 관측 창이 끝나야 확정하고, 장 종료 30분 뒤와 다음 거래일 13:30 KST에 공급자 봉과 독립 기준을 다시 대조한다. 이후 값이 달라지면 late revision으로 기록하고 기존 안정화 판정을 무효화한다.

기록할 시각과 상태:

1. 공급자 체결 시각
2. 서버 socket 수신 시각
3. 정규화 완료 시각
4. 봉 확정과 DB 저장 시각
5. REST 봉 최초 관측과 마지막 변경 시각
6. disconnect·resubscribe·backfill 시작과 완료 시각

## 5. 5종목 실제 장 비교

삼성전자 `005930`, SK하이닉스 `000660`, 카카오 `035720`, NAVER `035420`, 현대차 `005380`를 같은 KRX 정규장에서 비교한다.

최소 산출물:

- accepted·duplicate·too-late·invalid·misrouted tick 수
- 공급자 시각부터 서버 수신까지 p50·p95·p99
- 종목별 마지막 tick·마지막 확정 봉·gap
- 재연결과 구독 복구 시간
- 마지막 저장 시점부터 REST 백필 완료 시간
- 자체 집계 봉과 같은 공급자 봉의 일치율
- 계약된 독립 기준과의 정확성 대조, 다중 공급자 합의의 이상치 탐지 결과
- 호출 상태와 rate-limit 준수 결과

같은 공급자의 체결과 같은 공급자의 분봉이 일치한다는 사실만으로 원천 누락이 없다고 판정하지 않는다. venue, 수정주가, 정정·취소, 무체결 분과 종가 단일가 의미를 통일한 독립 기준이 필요하다.

실제 공급자 성능은 동일한 거래일에 동시에 관측해야 한다. 서로 다른 날짜의 시장 거래량 차이를 공급자 차이로 해석하지 않는다. 잠정 결정도 최소 5거래일을 사용하고 평시, 고거래량 구간과 종가 단일가를 포함한다. VI·거래정지·신규 상장처럼 시험 기간에 발생하지 않은 경계는 합성 오류 주입으로 별도 검증한다.

KRX 정규장 용량 모델은 `[09:00, 15:30)` 390개를 가정하지만 정확히 15:30에 체결되는 종가 단일가를 어느 bucket에 넣는지는 공급자 표기 규칙과 canonical 정책을 먼저 확정한다. 시작 시각 표기인지 종료 시각 표기인지 확인하지 않은 상태에서 390개 또는 391개를 정답으로 강제하지 않는다.

## 6. KOSPI 전체 용량 모델

상장 종목 수는 고정 상수가 아니므로 계약된 종목 마스터에서 매일 갱신한다. universe는 KOSPI 시장에서 제품이 지원할 보통주·우선주·REIT·SPAC·DR 등 상품 유형을 명시적으로 열거하고 ETF·ETN 포함 여부를 분리한다. 시험 종목 수는 `실제 대상 수 × 1.2` 이상을 다음 100단위로 올려 결정한다. 아래 1,000종목은 현재 계획용 기준일 뿐 검증된 영구 상한이 아니다.

현재 로컬 실측값인 관계 크기 `157.118B/봉`, WAL `220.862B/봉`, KRX 정규장 390분, 연 252거래일을 단순 적용하면 다음과 같다.

| 종목 수 | 하루 분봉 | 연간 분봉 | 관계 크기/년 | WAL/년 |
| ---: | ---: | ---: | ---: | ---: |
| 5 | 1,950 | 491,400 | 0.08GB | 0.11GB |
| 50 | 19,500 | 4,914,000 | 0.77GB | 1.09GB |
| 200 | 78,000 | 19,656,000 | 3.09GB | 4.34GB |
| 1,000 | 390,000 | 98,280,000 | 15.44GB | 21.71GB |

관계 크기는 현재 단일 공급자 V2 테이블과 기본키 인덱스를 포함하지만 replica, PITR/WAL 보관, 백업, vacuum 여유와 원시 체결 저장은 포함하지 않는다. WAL 열은 연간 디스크 점유량이 아니라 연간 생성량 추정이다. provider·venue·session·interval·partition index가 추가된 미래 schema에서는 다시 실측하며, 운영 디스크를 이 숫자만으로 결정하지 않는다.

평균 write 수보다 분 경계 burst가 중요하다. 1,000종목이면 1분마다 최대 1,000개 확정 봉이 짧은 구간에 저장되므로 실제 ingestion 경로의 개별 upsert, revision 보정과 DB 실패 재시도를 함께 재생한다.

## 7. 50·200·1,000종목 replay

공급자의 구독 한도를 넘겨 실제 API를 시험하지 않는다. 공급자 계약이 실제 관측 데이터의 저장·파생·재생·CI 사용을 모두 허용하는 경우에만 허용 범위에서 fixture를 만든다. 그렇지 않으면 실제 가격·시각열을 복원할 수 없고 공급자 데이터를 복제하지 않는 완전 합성 fixture를 만든다.

각 규모에서 다음을 재생한다.

- 실제와 유사한 체결 간격과 burst
- 같은 시각의 여러 체결, 중복과 역순 체결
- WebSocket 단절, 401·429·5xx와 malformed payload
- REST gap과 실시간 버퍼 경계 중복
- 분 경계의 동시 확정과 revision 보정
- 수집 worker 종료와 shard 소유권 이전

정확성 실패 결과는 속도 순위에서 제외한다. 각 후보 시나리오는 같은 장비에서 A-B-A-B 순서를 섞어 최소 5회 실행한다.

replay는 GrowAnt 내부 수집·DB 확장성만 증명한다. 공급자의 KOSPI 전체 제공 권리, 실제 구독 용량, 전송 SLA와 장애 복구 능력은 계약과 상품 사양으로 별도 입증한다.

## 8. k6 B2/B3 read leg는 GrowAnt만 가압

[`market-candles-b2-load.sh`](../scripts/market-candles-b2-load.sh)는 provider endpoint가 아니라 GrowAnt의 JWT 분봉 API만 호출한다. 알려진 KIS·토스·키움·LS·코스콤·KRX 도메인은 실행 전에 거절한다. localhost 이외의 GrowAnt 시험 환경은 `ALLOW_NON_LOCAL_TARGET=true`를 명시해야 한다.

기본 요청 분포는 종목 수와 관계없이 70%·25%·5%가 되도록 결정적으로 반복된다. `TICKER_DISTRIBUTION=uniform`은 균등 분포이고 `hot-80-20`은 상위 20% ticker에 요청 80%를 보낸다.

| 구간 | 비중 | 기본 최소 봉 수 |
| --- | ---: | ---: |
| 최신 48분 | 70% | 48 |
| 최근 1거래일 | 25% | 390 |
| 5거래일 | 5% | 1,950 |

기본 executor는 `ramping-arrival-rate`이며 10분 동안 25→50→100 RPS를 거쳐 0으로 내린다. VU를 실제 사용자 수로 해석하지 않고 목표 RPS와 요청 분포로 용량을 표현한다.

예시:

```bash
RUN_ID='b2-5ticker-read-r1' \
BASE_URL='http://127.0.0.1:8080' \
TOKEN='<JWT>' \
TICKERS='005930,000660,035720,035420,005380' \
EXPECTED_TICKER_COUNT=5 \
TICKER_DISTRIBUTION='uniform' \
RECENT_FROM='2026-08-07T14:42:00+09:00' \
RECENT_TO='2026-08-07T15:30:00+09:00' \
DAY_FROM='2026-08-07T09:00:00+09:00' \
DAY_TO='2026-08-07T15:30:00+09:00' \
WEEK_FROM='2026-08-03T09:00:00+09:00' \
WEEK_TO='2026-08-08T09:00:00+09:00' \
./scripts/market-candles-b2-load.sh
```

보고서 경로:

```text
build/reports/market-data/<RUN_ID>/
├── b2-load-manifest.json
├── b2-k6-config.json
├── b2-k6.log
└── b2-rest-candles-k6-summary.json
```

`RUN_ID`가 이미 있으면 이전 증거를 덮어쓰지 않고 실패한다. manifest에는 ticker 목록 대신 개수와 SHA-256만 기록하며 JWT 값은 저장하지 않는다.

k6의 응답 검사는 HTTP 200, `success=true`, 최소 봉 수까지다. 시간 정렬, OHLCV, revision과 DB 전체 필드 대조는 시험 전후의 별도 증거 검사로 확인한다. 이 스크립트는 read leg만 실행하므로 replay write의 시작·종료·checksum과 서버 지표 수집을 하나의 run ID로 묶는 orchestrator는 별도 구현해야 B2/B3 read-write 시험이 완성된다.

## 9. B2와 B3의 차이

| 시험 | 수집 규모 | 사용자 조회 | 목적 |
| --- | ---: | --- | --- |
| B2 | 실제 또는 replay 5종목 | 10분 단계 부하 | 공급자 어댑터와 기존 API의 통합 기준선 |
| B3-50 | replay 50종목 | 같은 요청 분포 | 단일 collector와 minute-boundary burst 검증 |
| B3-200 | replay 200종목 | 같은 요청 분포 | shard 필요 시점과 DB pool 대기 확인 |
| B3-1000 | replay 1,000종목 | 인기 20% 집중·균등 분포 분리 | KOSPI 전체 상한에서 포화점과 복구 시간 확인 |
| B3-universe | 실제 계약 종목 master 수 × 1.2 이상 | 인기 집중·균등·분 경계 burst | 계약한 전체 시장 범위와 여유 용량의 최종 검증 |

각 단계에서 CPU, heap, GC, Hikari active·idle·pending·acquire, query p95·p99, WAL, pending age, revision conflict, cache hit ratio와 egress를 같은 타임라인으로 수집한다.

50·200·전체 universe 시험 전에는 각 규모의 결정적 종목 master, 카탈로그, 추적 대상과 seed/replay DB를 격리된 환경에 준비한다. 현재 기본 카탈로그가 5종목인 서버에 임의의 1,000개 ticker를 보내는 것은 B3가 아니라 4xx 오류 시험이다.

## 10. 최종 판정

탈락 조건을 통과한 후보만 점수화한다. 서로 기능이 다른 후보를 한 표에 억지로 넣지 않고 다음 세 역할을 별도로 판정한다.

| 판정 역할 | 필수 hard gate | 점수 영역 |
| --- | --- | --- |
| 5종목 실시간 POC | WebSocket 체결 age 상한 p95 2초·p99 5초, 확정 봉 미복구 gap 0, 재연결 10초·최대 10분/50 bucket 백필 60초, 8시간 누수 없음 | 정확성·복구 40, 지연·연결 30, 백필 20, 구현 복잡성 10 |
| 완성 분봉 비교군 | 1 TPS·5초 polling의 최초 관측 검열 상한 p95 15초, 마지막 변경 p95 30초, 독립 기준과 설명되지 않은 OHLCV 불일치 0 | 정확성 40, 게시·안정화 30, 과거 범위·pagination 20, 구현 복잡성 10 |
| KOSPI 전체 운영 feed | 계약 universe·권리·SLA와 P6의 coverage·RPO/RTO·비용 gate 전부 충족 | coverage·SLA 30, 정확성·복구 25, 지연 15, 권리·총비용 20, 운영 복잡성 10 |

세 역할의 공통 gate는 시험·저장·표시 권리, 독립 기준 정확성 판정, 비밀정보 보호, timestamp 의미 확정과 관측 서버 NTP 절대 offset 100ms 이하다. GrowAnt adapter를 붙인 역할은 추가로 k6 HTTP 오류율 1% 미만, 기본 응답 검사 99% 초과, dropped iteration 0과 임시 API p95 200ms 미만을 통과해야 한다. 이 API 수치는 운영 SLA가 아니라 같은 환경의 회귀선이다.

첫 실제 관측 전에 역할별 `benchmark-scorecard-<role>.json`에 각 지표의 방향, good·bad 경계, 필수·선택 여부와 비용 상한을 기록하고 SHA-256을 남긴다. 역할이 제공하지 않는 기능은 평가 대상이 아니며, 해당 역할의 필수 지표가 없을 때만 그 역할에서 탈락한다. 결과를 본 뒤 경계를 바꾸면 새 run으로 다시 시작한다.

공급자 시각이 초 단위라면 실제 age를 단일 숫자로 만들지 않고 timestamp 양자화와 NTP 불확도를 포함한 구간으로 계산해 상한을 판정한다. 통제 단절은 30초·2분·10분을 각각 최소 3회 수행하고 장중과 종가 단일가 구간을 모두 포함한다. 단절 동안 거래가 없던 bucket은 독립 기준으로 분류하며 누락량을 임의로 0으로 만들지 않는다.

lower-is-better 지표는 `clamp(100 × (bad - x) / (bad - good), 0, 100)`, higher-is-better 지표는 반대 방향의 선형식으로 환산한다. 영역 점수는 사전에 정한 하위 지표의 산술평균, 총점은 역할별 비중의 가중합이다. 역할별 hard gate 결측은 해당 역할 탈락, 선택 점수 지표 결측은 0점으로 처리한다. 5거래일 hard gate는 가장 나쁜 날로 판정하고 점수는 일별 중앙값을 사용하며 min·median·max와 95% 신뢰구간을 함께 공개한다. 비용은 실제 universe에서의 연간 총비용으로 비교한다.

결정 기록에는 원본 요약, scorecard checksum, commit SHA, 환경, 거래일, 종목 master checksum, 실행 순서, 호출 한도, 문서 확인일·버전, 실패와 제외 이유를 함께 남긴다. 점수 차가 3점 이하면 우승자를 강제로 정하지 않고 표본을 추가하거나 계약 조건으로 동률을 해소한다. 5종목 POC 우승자와 KOSPI 전체 운영 공급자가 다르면 두 역할을 분리한다.

## 11. 공식 근거

- KIS, [REST·WebSocket 유량 안내](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/d0d1a83f-6f8d-4437-9700-6d26702fd989), [개인 Open API 이용약관](https://apiportal.koreainvestment.com/api/terms/public?termsType=MARKET), [제휴 안내](https://apiportal.koreainvestment.com/provider-info)
- 키움, [REST·실시간 시세 유량 안내](https://openapi.kiwoom.com/intro?dummyVal=0)
- 토스증권, [현재 운영 방식과 호출 한도](https://openapi.tossinvest.com/openapi-docs/overview.md), [canonical OpenAPI 명세](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json), [이용 범위](https://corp.tossinvest.com/ko/open-api)
- LS증권, [t8412 N분봉](https://openapi.ls-sec.co.kr/apiservice?group_id=73142d9f-1983-48d2-8543-89b75535d34c&api_id=12320341-ad85-429a-90bd-5b3771c5e89f), [실시간 WebSocket](https://openapi.ls-sec.co.kr/apiservice?api_id=9a2800c3-9bf2-4d67-8d83-905074f06646)
- KRX, [실시간 시장데이터 상품](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA002.jsp), [시장데이터 이용 계약 절차](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA003.jsp), [정보 이용 라이선스](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA004.jsp)
- KRX, [정규시장 매매거래시간](https://global.krx.co.kr/contents/GLB/06/0602/0602010201/GLB0602010201T1.jsp)

위 공개 근거는 2026-08-15에 확인했다. 재현성 기준은 토스 OpenAPI `1.2.14` SHA-256 `d29f9079a557c0b6affcec330aa131f93b09fd49932354668e3dc4524cd42180`, overview SHA-256 `abaff9cc15487a61417cf1b79a6777ab550337cd1c53e3a2838cfccd6e660b1d`, KIS 공식 예제 저장소 commit `b093e42ba32d1df5f5ddad7a71cb715cbc800832`다. 공식 한도와 명세는 바뀔 수 있으므로 실제 결정 직전에 다시 확인하고, 공개 문서에 없는 전체 시장 상품 범위·전송 사양·SLA는 P6 계약 견적서와 기술 명세를 증거로 첨부한다.

## 12. 실행 순서와 단계별 종료 조건

| 단계 | 구현·조사 | 종료 조건 |
| --- | --- | --- |
| Gate 0 | KIS·키움·토스·LS와 KRX 승인·코스콤/NXT 계약 경로의 시험, 저장, 벤치마크, 재생, CI, 내부 표시, 외부 제공과 파생 OHLCV 권리 확인 | 현재 토스는 `RIGHTS_BLOCKED`, KIS 저장·벤치마크는 `UNKNOWN`; 후보별 서면 근거에서 필요한 권리가 `ALLOWED`가 됨 |
| P1 | 시험 전용 관측 저장소, NTP 상태, 공통 시계와 데이터 의미표 구현 | 코드 기반은 PR #4에서 검증 완료·병합 전. 실제 run의 NTP offset 100ms 이하와 후보별 timestamp·venue·session·봉·정정·무체결 의미가 확정됨 |
| P2a | 실제 시세를 포함하지 않는 합성 KIS·키움·LS WebSocket/REST와 토스 REST contract adapter 구현 | payload mapping, pagination, 401·quota·5xx, reconnect 단위 테스트 통과 |
| P2b | Gate 0을 통과한 후보만 실제 read-only adapter에 연결 | credential이 Git·로그·manifest에 없고 contract probe와 권리 범위가 run에 고정됨 |
| P3 | 같은 5거래일에 공급자를 동시에 관측하고 통제 단절·backfill 수행 | 공급자 지연·정확성·복구 gate를 통과한 shortlist와 탈락 이유가 재현 가능한 증거로 남음 |
| P4 | replay write와 B2 read를 하나의 run ID로 묶고 shortlist별 5종목 8시간 soak 수행 | 설명되지 않은 불일치 0건, 미복구 gap 0, 누수 없음, 병목 지표 확보 후 5종목 POC 공급자 결정 |
| P5 | 완전 합성 fixture로 50→200→1,000→실제 master×1.2 B3 수행 | 종목 규모별 포화점, shard·partition·cache 도입 시점 결정 |
| P6 | Gate 0을 통과한 전체 시장 후보를 동일 universe에서 비공개 shadow 수집하고 장애 훈련 | 아래 전체 시장 gate와 비용 상한을 충족하거나, 시험하지 못한 후보의 제외 근거가 기록됨 |
| Decision | KOSPI 전체 운영 공급자를 별도 ADR로 결정 | 점수 차 3점 초과 또는 추가 표본·계약 조건으로 동률 해소 |

P6는 최소 20거래일 동안 계약 universe 일일 coverage 100%, backfill 뒤 설명되지 않은 확정 봉 gap·stale 0건, RPO 0을 요구한다. 10분 전송 중단 뒤 backlog drain과 정상화 RTO는 10분 이내, 계약 가용성은 월 99.9% 이상이어야 한다. `MONTHLY_MARKET_DATA_BUDGET_KRW`는 Gate 0에서 숫자로 승인하고 후보의 feed·라이선스·재배포·회선·운영 비용 합계가 이를 넘으면 탈락한다.

다음 순서는 Gate 0 서면 확인과 PR #4 병합이다. 기다리는 동안 P2a 합성 adapter contract test는 진행할 수 있지만, 실제 공급자 호출·저장·벤치마크는 필요한 권리가 `ALLOWED`가 된 후보에만 연다. POC 우승자 결정은 P4 이후, KOSPI 전체 운영 공급자 결정은 P6 이후에만 가능하다. 현재 운영 우승자는 없다.
