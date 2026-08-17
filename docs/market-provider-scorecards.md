# 공급자 scorecard 초안

이 문서는 `benchmark/market-provider-scorecards/v1`의 네 scorecard를 설명한다. 파일은 [공급자 비교 계획](market-provider-benchmark.md)의 문서화된 기준선을 기계가 읽을 수 있게 옮긴 초안이며, 공급자 호출·점수 계산·우승자 결정을 허용하는 frozen 기준이 아니다.

## 현재 상태

- 네 파일은 모두 `DRAFT`, `scoredRunsAllowed=false`, `providerRankingAllowed=false`다.
- schema v1은 문서화된 기준선을 안전하게 검토하기 위한 **DRAFT 전용 authoring schema**다. 파일을 `FROZEN`으로 바꾸거나 `loadFrozen()`으로 읽는 모든 시도는 내용의 완성도와 관계없이 거부한다.
- 모든 hard gate는 `definitionState=UNRESOLVED`, `metricId=null`, `executable=false`다.
- 영역별 scoring metric, good·bad, 정성 rubric과 통계 알고리즘이 미결이므로 `metrics`와 각 영역의 `metricIds`는 비어 있다.
- `GROWANT_LOAD`는 공급자 역할이 아니라 GrowAnt 서버·DB의 별도 수용성 gate이며 공급자 점수에 합산하지 않는다.
- 기준 문서와 scorecard는 같은 변경 묶음에서 관리하지만, scorecard가 `DRAFT`인 동안 독립적인 실행 기준으로 사용할 수 없다.

Draft provenance는 기준 문서의 저장소 상대 경로와 exact-byte SHA-256으로 고정한다. feature PR은 squash될 수 있으므로 아직 병합되지 않은 중간 commit SHA를 기준값으로 사용하지 않는다.

## 역할과 문서화된 비중

| 역할 | scoring mode | 영역 비중 |
| --- | --- | --- |
| `REALTIME_POC` | `WEIGHTED_RANKING` | 정확성·복구 40, 지연·연결 30, 백필 20, 구현 복잡성 10 |
| `CANDLE_REFERENCE` | `WEIGHTED_RANKING` | 정확성 40, 게시·안정화 30, 과거 범위·pagination 20, 구현 복잡성 10 |
| `KOSPI_FEED` | `WEIGHTED_RANKING` | coverage·SLA 30, 정확성·복구 25, 지연 15, 권리·총비용 20, 운영 복잡성 10 |
| `GROWANT_LOAD` | `GATE_ONLY` | 공급자 점수 영역 없음 |

세 공급자 역할의 공통 gate는 실제 시험 호출 허가, 여섯 권리 판단, 독립 기준 정확성, 비밀정보 보호, timestamp 의미 확정과 관측 서버 NTP 절대 offset 100ms 이하다. 여섯 권리는 `storage`, `benchmark`, `replay`, `ci`, `internalDisplay`, `externalDistribution`이며 모두 `UNKNOWN`이 아니어야 한다. 실제 run이 사용하는 purpose만 `ALLOWED`여야 하고 scored 관측은 최소 `storage`와 `benchmark`가 `ALLOWED`여야 한다.

문서화된 공통 점수 정책도 유지한다. lower-is-better는 `clamp(100 × (bad - x) / (bad - good), 0, 100)`으로 환산하고 higher-is-better는 반대 방향의 선형식을 사용한다. 영역은 하위 지표의 산술평균, 총점은 영역 비중의 가중합이다. hard-gate 결측은 역할 탈락, 선택 metric 결측은 0점이다. 5거래일 hard gate는 worst day, 점수는 일별 median으로 판정하며 min·median·max와 95% 신뢰구간을 함께 공개한다. 점수 차가 3점 이하이면 우승자를 강제하지 않는다. 다만 하위 metric과 신뢰구간 알고리즘이 미결이므로 이 정책도 아직 실행할 수 없다.

## 역할별 기준선

### `REALTIME_POC`

- 삼성전자 `005930`, SK하이닉스 `000660`, 카카오 `035720`, NAVER `035420`, 현대차 `005380`를 같은 최소 5거래일에 동시 관측
- KIS·키움·LS는 후보별 물리 WebSocket 한 개로 같은 5종목 구독
- 30초·2분·10분 단절을 각각 최소 3회 수행하고 장중과 종가 단일가 구간 포함
- WebSocket 체결 age 구간 상한 p95 2초, p99 5초
- 최종 미복구 gap과 설명되지 않은 OHLCV 불일치 0건
- 재연결·재구독 10초 이내
- 최대 10분 또는 50 bucket 백필 60초 이내
- 5종목 8시간 soak에서 누수 없음
- KIS·키움·LS가 권리 확인 뒤 후보이며 REST-only 토스는 이 역할에서 제외

### `CANDLE_REFERENCE`

- 후보별 다섯 종목 합계 총 1 TPS, 동일 ticker 순서 round-robin
- target candle마다 분 종료 뒤 10분 동안 조기 종료 없이 관측
- 장 종료 30분 뒤와 다음 거래일 13:30 KST에 같은 target candle set 재조회
- 최초 관측 검열 구간 상한 p95 15초, 마지막 변경 p95 30초
- 설명되지 않은 OHLCV 불일치와 최종 미복구 gap 0건
- 고빈도 diagnostic run은 별도 `run_id`를 사용하고 점수에서 제외

### `KOSPI_FEED`

- 최소 20거래일 동안 계약 universe 일일 coverage 100%
- backfill 뒤 설명되지 않은 확정 봉 gap·stale 0건
- RPO 0은 단위와 판정 정의가 미결인 `CONCEPT` gate
- 10분 전송 중단 뒤 backlog drain과 정상화 RTO 10분 이내
- 계약 월 가용성 99.9% 이상
- 월 총비용은 숫자가 확정되지 않은 `MONTHLY_MARKET_DATA_BUDGET_KRW` 이하

후속 schema/version에서 `KOSPI_FEED`를 동결할 때는 승인된 월 예산 숫자를 scorecard에 직접 고정하고 `VARIABLE` gate를 immutable numeric gate로 바꿔야 한다.

### `GROWANT_LOAD`

- 공급자 endpoint를 직접 가압하지 않고 같은 canonical fixture와 서버 조건을 사용
- 25·50·100 RPS 각각 warm-up 2분, `constant-arrival-rate` plateau 10분, cooldown 2분, 최소 3회 반복
- HTTP 오류율 1% 미만, 응답 검사 99% 초과, dropped iteration 0, 임시 API p95 200ms 미만
- 기본 요청 비중은 최신 48분 70%, 1거래일 25%, 5거래일 5%
- 규모는 5→50→200→1,000→실제 master×1.2 이상으로 확장하며 각 단계는 독립 gate다.

현재 ramping run은 25·50·100 RPS 정상상태를 입증하지 않는다. replay write, read와 서버 지표를 같은 run ID로 묶는 orchestrator가 없으므로 B2/B3 read-write 시험도 아직 완성되지 않았다.

## 후속 실행 schema의 필수 조건

schema v1에 값을 채워 실행 schema로 승격하지 않는다. 첫 scored run 전에 다음 구조와 정책을 구현한 **새 schema/version**을 만들고 별도 리뷰해야 한다.

- 역할별 필수 gate catalog와 실행 protocol catalog: 후보 자격, target universe·session·candle set, 호출 pace, fault schedule, 관측·판정 deadline과 `GROWANT_LOAD` plateau
- 하위 metric의 ID·정의·단위·원천·방향·good·bad와 필수·선택 여부
- 정성 항목의 선택지별 근거와 점수 rubric
- timestamp 양자화·검열 구간, 결측과 late evidence 처리
- provider·day·ticker·bucket·fault repetition 집계 순서
- worst-day hard gate와 daily-median 점수 판정의 구체 알고리즘
- percentile과 95% 신뢰구간의 표본 단위·계산 알고리즘
- 미지원 기능, target window, 실행 환경과 비용 처리 정책
- KOSPI RPO 정의, 계약 universe와 승인된 월 예산

schema v1에서는 null을 임의 값으로 채우거나 hard gate를 실행 가능하게 바꾸지 않는다. 후속 schema/version의 frozen validator는 최소한 다음 상태를 거부해야 한다.

- 남아 있는 `unresolvedDecisionIds`
- 비어 있는 ranked 영역의 `metricIds`
- 정의·단위·원천·good·bad 또는 rubric이 없는 metric
- 합계가 100이 아니거나 문서 비중과 다른 영역 weight
- metric과 연결되지 않았거나 `executable=false`인 hard gate
- checksum이 일치하지 않는 파일

위 목록은 후속 schema의 최소 검증 조건이다. 현재 schema v1에서는 이 조건을 우연히 만족하더라도 `FROZEN`과 `loadFrozen()`을 허용하지 않는다.

## 버전과 checksum

현재 schema v1·버전 `1.0.0-draft.1`은 변경 추적용이며 scored run에 사용할 수 없다. manifest의 role file hash는 재직렬화한 JSON이 아니라 저장소의 UTF-8·LF exact bytes에 대해 계산한다. JSON 안에는 자기 checksum을 넣지 않는다.

후속 schema/version에서 모든 미결을 해소하고 별도 리뷰를 받은 뒤에만 immutable frozen version과 hash를 만든다. run manifest에는 scorecard ID·version·SHA-256·Git commit·상대 경로를 기록하고 한 byte라도 다르면 fail-closed한다. 결과를 본 뒤 기준을 바꾸면 새 version과 checksum으로 새 run을 시작하며 기존 run을 새 기준으로 재채점하지 않는다.
