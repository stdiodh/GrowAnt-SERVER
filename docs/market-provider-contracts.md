# 국내주식 공급자 계약 파서

기준일: 2026-08-16
상태: P2a 합성 계약 검증 완료, 실제 공급자 호출·품질 비교 미실행

## 1. 이 단계의 목적

KIS와 토스증권의 공식 wire·JSON 계약을 실제 네트워크 연결 전에 코드와 합성 테스트로 고정한다. 이번 단계가 답하는 질문은 다음 세 가지다.

1. 공급자 비교에 필요한 공식 필드를 타입과 위치에 맞게 읽을 수 있는가?
2. 공급자가 주지 않는 `sequence`, `tradeCount`, `isFinal`을 만들지 않는가?
3. 잘못된 프레임, 타입, 페이지 중복을 조용히 수용하지 않는가?

이번 단계는 공급자의 안정성·속도·누락률을 증명하지 않는다. 실제 시세 요청, OAuth, WebSocket 연결, 데이터베이스 저장, 기존 `MarketDataProvider` bean 교체도 포함하지 않는다. 테스트 fixture의 종목·가격·시각은 모두 합성 값이다.

## 2. 기존 분봉 모델에 바로 넣지 않은 이유

현재 내부 tick과 분봉 모델은 각각 다음 값을 필수로 요구한다.

- `Tick`과 `TradeTick`: `sequence`
- `MinuteCandle`: `tradeCount`, `isFinal`, `revision`

그러나 KIS와 토스의 이번 조사 대상 응답은 이 값을 모두 주지 않는다. 공급자 응답을 기존 모델에 바로 매핑하면 로컬 sequence나 확정 여부를 사실처럼 만들 위험이 있다.

그래서 P2a는 다음처럼 분리한다.

```text
공식 wire/JSON
  -> 공급자별 계약 파서
  -> 공급자가 실제로 준 값만 가진 계약 모델
  -> (P2b) 수신 시각·연결 epoch·로컬 wire 순서와 함께 관측 저장
  -> (후속) 확정·정정 정책을 통과한 뒤 canonical 1분봉으로 변환
```

공통 모델 `ProviderOhlcv`는 문자열 decimal을 `BigDecimal`로만 바꾼다. 공급자별 시각 의미는 합치지 않는다.

- KIS: `businessDate: LocalDate`와 `tradeTime: LocalTime`
- 토스: offset을 포함한 `timestamp: OffsetDateTime`

KIS 시각을 `Asia/Seoul`의 `Instant`로 바꾸는 일은 공식 timestamp 의미와 서버 시계 상태를 확인하는 P2b에서 수행한다.

## 3. KIS 계약

### 3.1 H0STCNT0 WebSocket 체결

평문 데이터 프레임은 다음 형식이다.

```text
0|H0STCNT0|002|record-1의 46필드^record-2의 46필드
```

파서는 다음을 강제한다.

- `split('|', limit = 4)`로 헤더와 payload를 분리한다.
- 암호화 flag `0`만 이 파서가 받는다. `1`은 복호화 계층을 거치지 않으면 거절한다.
- TR ID는 `H0STCNT0`이어야 한다.
- 선언 건수는 양의 10진 정수여야 한다.
- payload 토큰 수는 반드시 `선언 건수 × 46`이어야 한다.
- 같은 초의 여러 체결을 시간으로 중복 제거하지 않고 `wireOrdinal` 순서로 모두 보존한다.

현재 typed model이 읽는 공식 필드는 다음과 같다. 나머지 필드도 46개 위치 검증에는 포함된다.

| 0-based index | 공식 필드 | 보존 값 |
| ---: | --- | --- |
| 0 | `MKSC_SHRN_ISCD` | 공급자가 보고한 종목코드 |
| 1 | `STCK_CNTG_HOUR` | 초 정밀도 체결 시각 |
| 2 | `STCK_PRPR` | 체결가격 |
| 12 | `CNTG_VOL` | 체결수량 |
| 13 | `ACML_VOL` | 누적 거래량 |
| 33 | `BSOP_DATE` | 영업일자 |
| 34 | `NEW_MKOP_CLS_CODE` | 장운영 구분 raw code |
| 43 | `HOUR_CLS_CODE` | 시간 구분 raw code |

`STCK_OPRC`, `STCK_HGPR`, `STCK_LWPR`는 당일 시가·고가·저가다. 1분 OHLC로 사용하지 않는다. 후속 집계는 같은 minute의 `STCK_PRPR`를 wire 순서대로 first/max/min/last로 계산하고 `CNTG_VOL`을 합산해야 한다.

KIS는 이 프레임에 provider event ID나 sequence를 주지 않는다. `BSOP_DATE + STCK_CNTG_HOUR`도 초 정밀도라 고유키가 아니다. P2b에서는 `connectionEpoch + frame-local sequence + wireOrdinal`을 로컬 관측 순서로 별도 기록하되 공급자 sequence라고 부르지 않는다.

### 3.2 REST 분봉

지원한 두 응답은 다음과 같다.

| 구분 | TR ID | 공식 범위 |
| --- | --- | --- |
| 당일 분봉 | `FHKST03010200` | 당일, 호출당 최대 30건 |
| 일별 분봉 | `FHKST03010230` | 날짜·시간 기준, 호출당 최대 120건, 실전 계좌 전용 |

두 API의 `output2`는 같은 8개 문자열 필드를 사용한다.

| 필드 | P2a 매핑 |
| --- | --- |
| `stck_bsop_date` | 영업일자 |
| `stck_cntg_hour` | 봉의 공급자 시각 |
| `stck_oprc` | open |
| `stck_hgpr` | high |
| `stck_lwpr` | low |
| `stck_prpr` | close/current |
| `cntg_vol` | volume |
| `acml_tr_pbmn` | 당일 누적 거래대금, 분 거래대금으로 변환하지 않음 |

응답 행에는 종목·시장·TR 문맥이 없으므로 typed request가 전체 비교 문맥을 붙인다. `KisTodayMinuteCandleRequest`와 `KisDailyMinuteCandleRequest`는 종목, `J/NX/UN`, 기준 날짜·시각과 함께 다음 값을 고정한다.

- `FID_PW_DATA_INCU_YN=Y`
- 당일: `FID_ETC_CLS_CODE=""`
- 일별: `FID_FAKE_TICK_INCU_YN=N`

`N`과 빈값이 혼재한 공식 예제가 있으므로 일별 허봉 제외 값은 먼저 KIS의 공식·서면 답변으로 의미를 확정한다. 시험 호출 권리까지 승인되면 비점수 one-shot contract probe로 `N` 요청의 수용 여부만 확인한 뒤 benchmark spec에 고정한다. probe 성공을 허봉·무체결 의미의 근거로 확대하지 않는다. 응답 순서도 공식 보장이 없어 parser는 wire 순서를 보존한다.

두 응답 모두 `isFinal`이 없다. 특히 당일 API는 공식 문서가 `output2[0].cntg_vol`이 현재 분 첫 체결 전후로 이동·갱신된다고 경고한다. 따라서 첫 행이나 과거 행을 parser가 자동으로 확정하지 않는다. `acml_tr_pbmn`도 분 거래대금이 아닌 누적값으로 별도 보존한다.

## 4. 토스증권 계약

### 4.1 요청 고정값

`TossDomesticMinuteCandleRequest`는 국내 6자리 종목 비교에서 다음 값을 항상 보낸다.

```text
symbol=<6자리 국내 ticker>
interval=1m
count=1..200
before=<optional ISO-8601 offset date-time>
adjusted=false
```

토스 공식 기본값은 `adjusted=true`다. `false` 고정은 토스의 요구가 아니라 원시가격을 비교하기 위한 GrowAnt 정책이다. `nextBefore`는 raw 문자열과 비교용 `OffsetDateTime`을 함께 보존하고 다음 요청에는 raw 값을 그대로 사용한다. `before`의 `+` 인코딩은 후속 HTTP client의 URI builder가 담당하며 문자열 연결로 URL을 만들지 않는다.

중요하게도 토스 `/candles`에는 venue 선택 파라미터와 응답 venue가 없다. 6자리 국내 종목이라는 사실만으로 KRX 체결만 집계한 봉이라고 볼 수 없다. KRX·NXT·통합/SOR 의미가 공식 근거나 서면 답변으로 확정되기 전에는 KIS `J` 응답과 동일 venue 점수를 계산하지 않는다.

### 4.2 응답과 페이지 경계

필수 응답은 `result.candles` 배열이다. 각 봉에서 다음 값만 읽는다.

- `timestamp`: offset이 포함된 봉 시작 시각
- `openPrice`, `highPrice`, `lowPrice`, `closePrice`, `volume`: 최대 30자의 decimal 문자열
- `currency`: 알려지지 않은 미래 enum도 raw 문자열로 보존
- `nextBefore`: 문자열 또는 `null`; 필드 누락도 공식 schema상 허용

응답에는 `symbol`, `interval`, `adjusted`, `tradeCount`, `isFinal`, event ID가 없다. 요청 종목은 page context에 붙이고 나머지는 만들지 않는다.

`before`는 inclusive이고 공식 예시는 마지막 봉 시각을 `nextBefore`로 돌려준다. 따라서 다음 페이지에 경계 봉이 다시 나타날 수 있다.

`TossCandlePageMerger`는 다음 규칙을 사용한다.

1. 같은 종목의 페이지만 병합한다.
2. 동일 instant의 숫자상 동일한 OHLCV와 같은 currency는 한 건으로 만든다.
3. 동일 instant의 값이 다르면 조용히 덮지 않고 충돌로 실패한다.
4. 결과는 명시적으로 최신 시각부터 정렬한다.
5. `nextBefore`가 이전 cursor보다 과거로 진행하지 않으면 순환으로 거절한다.

이 병합은 한 번의 pagination snapshot 안에서만 사용한다. 각 HTTP poll/page는 먼저 `pollRunId + pageOrdinal`로 append하고, 서로 다른 poll의 동일 timestamp 값 변경은 revision 표본으로 보존한다. [PR #4](https://github.com/stdiodh/GrowAnt-SERVER/pull/4)의 forward-only V4에는 page identity, request/next cursor와 local terminal 증거가 구현돼 있다. 다만 이 PR의 parser 결과를 V4 observation 모델로 변환·저장하는 adapter는 아직 없으며, PR #4 병합 후 최신 `develop`을 반영하기 전에는 그 스키마를 사용할 수 없다. 진행 중 봉이 언제 확정되는지는 공식 응답에 없으므로 P2b에서 반복 poll의 최초·마지막 변경 시각을 관측해야 한다. 공식상 허용되는 `count=1`은 단일 page에는 쓸 수 있지만 inclusive cursor가 진행하지 않을 수 있으므로 연속 pagination은 기본 `count=200`을 사용하고 cursor 정체를 실패로 기록한다.

## 5. 현재 자동 검증

```bash
./gradlew unitTest \
  --tests 'com.growant.market.kis.KisMarketDataContractsTest' \
  --tests 'com.growant.market.toss.TossCandleContractsTest'
```

합성 테스트가 확인하는 항목은 다음과 같다.

- KIS 46필드 단일·다건 프레임, 동일 초 복수 체결, wire 순서
- KIS 암호화·TR ID·건수·필드 수·날짜·시각·숫자 오류 거절
- KIS 당일·일별 REST 공통 8필드와 시장·날짜·시각을 포함한 요청 문맥 보존
- KIS 성공 빈 배열과 공급자 오류 응답
- 토스 `1m`, `adjusted=false`, count 범위 고정
- 토스 필수 문자열 타입, decimal, offset timestamp, unknown currency
- 토스 nullable/missing `nextBefore`
- inclusive 경계 중복 제거, 충돌 감지, cursor 순환 차단

이 테스트는 외부 API endpoint를 호출하지 않고 credential을 사용하지 않는다.

## 6. 실제 프로젝트 적용 순서

### Gate 0: 이용 권리

실제 API 호출 전에 각 공급자로부터 시험 호출 자체의 허용 범위를 먼저 확인한다. 이어 PR #4의 canonical 여섯 권리인 `storage`, `benchmark`(허용된 파생 포함), `replay`, `ci`, `internalDisplay`, `externalDistribution`과 보존기간을 서면으로 확인한다. 외부 사용자 차트와 재배포 권리는 별도 계약 대상으로 본다.

- KIS 개인 약관과 토스 공개 이용 안내만으로 GrowAnt 사용자 대상 시세 제공 권리가 생기지 않는다.
- 호출이 무과금이거나 계좌 고객에게 열려 있다는 사실은 저장·재배포 허가가 아니다.
- 승인 근거가 없으면 실제 P2b run을 활성화하지 않는다.

run의 canonical rights bundle은 위 여섯 권리를 각각 `ALLOWED` 또는 `DENIED`로 확정한다. 그 run이 실제로 사용하는 권리는 모두 `ALLOWED`여야 하고, scored 관측 run은 최소 `storage`와 `benchmark`가 `ALLOWED`여야 한다. 사용하지 않는 내부 표시·외부 제공 등의 권리는 `DENIED`여도 되지만 `UNKNOWN`은 어떤 항목에도 허용하지 않는다. 시험 호출 허가는 이 여섯 enum 밖의 선행조건으로 별도 증거를 남긴다.

권리만으로 충분하지 않다. 별도 [P1 관측 저장소 PR #4](https://github.com/stdiodh/GrowAnt-SERVER/pull/4)의 activation 조건에 맞춰 venue, session, timestamp source·정밀도·zone, 수정주가, 정정, 무체결 분, volume 의미, benchmark spec과 clock 상태도 모두 확정해야 한다. 하나라도 `UNKNOWN`이면 live run을 시작하지 않는다.

공식 문서끼리 요청 문법이 충돌하는 항목은 scored P2b와 분리한다. 시험 호출 권리가 승인된 뒤에만 비점수 `P2a-live contract probe`를 한 번 실행하고, raw payload·가격·수량은 저장하지 않은 채 endpoint/TR, 요청 옵션, HTTP status, provider result code와 field presence만 redacted evidence로 남긴다. 이 probe는 요청이 수용되는지만 확인하며 venue·무체결·확정 의미는 공급자의 공식·서면 근거로 확정해야 한다. 그 결과와 문서 checksum으로 semantics와 benchmark spec을 동결한다. 실제 `PROVIDER` run은 PR #4의 임시 봉인을 해제하는 V5 rights registry와 provider permit이 canonical bundle의 scope·유효기간·상위 계약을 원자 검증한 뒤에만 활성화한다.

### P2b: 동일 5종목 관측

권리, semantics, clock, read-only credential이 준비되면 격리된 observation store에만 연결한다. canonical `minute_candles`와 사용자 API에는 아직 쓰지 않는다.

불변 benchmark spec은 source commit과 checksum을 포함하고 다음 조건을 고정한다.

- 종목: `005930`, `000660`, `035720`, `035420`, `005380`
- 기간: 동일한 5개 독립 거래일
- 공통 점수 cadence: 공급자별 총 `1 TPS`의 고정 pace로 5종목을 round-robin한다. 즉 한 공급자에 매초 한 종목만 요청하고, 정상 순환에서 각 종목은 약 5초마다 요청한다.
- 공통 점수 관측: 각 target candle의 분 종료 뒤부터 `bucket_end + 10분`까지 위 1 TPS round-robin을 중단 없이 유지한다. 이후 모든 공급자에 같은 `session_close + 30분`, 다음 거래일(`T+1`) `13:30 Asia/Seoul` snapshot을 사용한다. T+1 뒤 공급자 수정은 원 run의 관측값을 소급 변경하지 않고 late evidence로 분리한다.
- 복구: WebSocket 역할은 연결을, REST 역할은 poller의 network path를 `30초`, `2분`, `10분` 동안 계획대로 차단한다. 각 지속시간을 공급자별 5거래일 동안 최소 3회씩 실행하고 동일한 재연결·재구독·백필 판정을 적용한다.
- run 무효 조건: clock error budget 초과, 권리·semantics 변경, rate-limit 위반 또는 공통 harness의 ticker·window 요청 누락. 정상 요청에서 발생한 공급자 timeout·오류·봉 부재는 run 무효가 아니라 해당 후보의 실패 관측이다.

각 provider/day run은 점수 대상 봉의 범위와 관측 시간을 섞지 않고 다음 값을 별도로 고정한다.

- `targetCandleSession`: 거래일, venue, time zone, session 시작·종료와 auction·시간외 포함 여부
- `observationDeadlines`: 각 봉의 `bucket_end + 10분`, 그 거래일의 `session_close + 30분`, 다음 공식 거래일(`T+1`) `13:30 Asia/Seoul`; 마지막 시각에 공급자 관측값을 동결
- `adjudicationDeadline`: 계약된 독립 기준으로 gap·revision·OHLCV를 판정할 별도 마감. T+1 공급자 관측 시각과 같다고 가정하지 않고 첫 scored run 전에 고정
- `runWindow`: `targetCandleSession`의 모든 bucket과 위 세 관측 시각을 포함하되 target candle set을 넓히지 않는다. exclusive `windowEnd`에 필요한 고정 completion grace는 scorecard에서 결과를 보기 전에 동결한다.

`T+1` 관측은 전 거래일 target candle을 판정하기 위한 deadline이며 target session을 다음 날까지 늘리지 않는다. provider/day run별로 위 값과 실제 checkpoint 완료 여부를 기록한다. 공통 harness가 요청을 발행하지 못했거나 clock·run manifest가 깨진 날만 모든 후보의 공통 점수에서 무효화하고 동일 조건으로 재실행한다. 정상 발행된 요청의 timeout, 공급자 오류와 봉 부재는 해당 후보의 fault 또는 `not_observed_within_window`로 남기며 재실행으로 지우지 않는다.

REST 공급자별 더 촘촘한 관측은 공통 분봉 점수와 물리적으로 구분한 `scoreEligible=false` precision diagnostic으로만 실행한다. KIS `H0STCNT0` 수신은 별도 `REALTIME_POC` scored run이며 이 diagnostic에 포함하지 않는다.

- KIS REST diagnostic: KRX `J`의 `bucket_end + 5초`·`bucket_end + 65초` 대조
- 토스: venue 의미를 확정한 `1m`, `adjusted=false` 요청을 종목당 1초 cadence로 polling

이 diagnostic의 호출량·지연·누락 결과는 공급자 동작을 해석하는 근거로만 쓰며 공통 순위, 채택 점수 또는 공통 `1 TPS` 결과에 합산하지 않는다. 공급자 한도와 승인된 권리 범위도 별도로 지킨다.

```text
KIS H0STCNT0 -> frame parser -> REALTIME_POC scored tick 관측 -> 로컬 1분 집계
KIS REST     -> candle parser -> CANDLE_REFERENCE 공통 관측 + 별도 +5초/+65초 diagnostic
Toss REST    -> fixed request -> CANDLE_REFERENCE 공통 관측 + 별도 정밀 polling diagnostic
```

관측 저장에는 raw payload, credential, Authorization header, URL query를 넣지 않는다. 대신 권리 범위 안에서 비교에 필요한 typed ticker·price·quantity·OHLCV와 공급자 시각, 로컬 수신 시각, connection epoch, wire ordinal, HTTP 결과, 오류 종류와 안전한 checksum을 저장한다. PR #4의 V4는 `pollRunId`, `pageOrdinal`, request/next cursor를 typed column으로 보존하지만 parser→observation adapter와 V5 provider permit은 아직 없다. PR #4 병합, adapter 통합 검증, V5 registry와 권리 승인까지 끝난 뒤에만 토스 pagination 실측을 시작한다.

P2a는 공급자 decimal 문자열을 `BigDecimal`로 보존하지만 P1 관측 스키마는 가격 `INTEGER`, 수량·volume `BIGINT`다. P2b adapter는 정수성과 범위를 `intValueExact`/`longValueExact`에 해당하는 방식으로 확인한다. 소수·범위초과 값은 반올림하거나 버리지 않고 fault로 기록하며, 실제로 소수가 허용돼야 한다면 기존 migration을 수정하지 않고 새 `NUMERIC` 관측 migration을 먼저 설계한다.

### P3: 서버 부하와 공급자 결정

k6는 KIS·토스 endpoint를 가압하지 않는다. 서버 용량 비교는 모든 후보에 동일한 canonical 합성 fixture를 사용한다. 공급자별 shape가 adapter 비용에 미치는 영향은 별도 adapter stress로 측정해 공급자 품질과 서버 용량을 섞지 않는다.

실측 파생 fixture는 해당 공급자의 replay 권리가 `ALLOWED`일 때만 내보낸다. 허용되지 않으면 결정적 합성 fixture만 사용한다.

최종 점수는 다음을 분리한다.

- 공급자 관측: 최초 가용 지연, 마지막 변경 지연, 누락·중복, 재연결, REST 대조율
- GrowAnt 부하: write 처리량, read p95/p99, 오류율, DB 증가량, 8시간 soak
- 권리: 저장·파생·내부 표시·외부 표시·재배포 가능 범위
- 확장성: 5종목뿐 아니라 50·200·1,000종목 replay와 KOSPI 전체 계약 경로

기술 점수가 높아도 권리가 불충분하면 운영 공급자로 채택하지 않는다. 개인용 KIS·토스 API만으로 KOSPI 전체 저지연 공개 차트를 운영하는 결론도 내리지 않는다. 전 종목 확장은 KRX·코스콤·NXT 또는 B2B full-market feed 계약을 별도로 비교한다.

## 7. 공식 근거

KIS:

- [H0STCNT0 공식 포털](https://apiportal.koreainvestment.com/apiservice-apiservice?/tryitout/H0STCNT0)
- [공식 GitHub H0STCNT0 컬럼, pinned commit](https://github.com/koreainvestment/open-trading-api/blob/b093e42ba32d1df5f5ddad7a71cb715cbc800832/examples_llm/domestic_stock/ccnl_krx/ccnl_krx.py#L69-L83)
- [주식당일분봉조회](https://apiportal.koreainvestment.com/apiservice-apiservice?/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice)
- [주식일별분봉조회](https://apiportal.koreainvestment.com/apiservice-apiservice?/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice)
- [KIS 개인 이용약관](https://apiportal.koreainvestment.com/api/terms/public?termsType=MARKET)
- [KIS 제휴 안내](https://apiportal.koreainvestment.com/provider-info)

토스증권:

- [Canonical OpenAPI 1.2.14](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json) — SHA-256 `d29f9079a557c0b6affcec330aa131f93b09fd49932354668e3dc4524cd42180`
- [Open API overview](https://openapi.tossinvest.com/openapi-docs/overview.md) — SHA-256 `abaff9cc15487a61417cf1b79a6777ab550337cd1c53e3a2838cfccd6e660b1d`
- [Market Data 문서](https://developers.tossinvest.com/docs/market-data)
- [토스증권 Open API 이용 안내](https://corp.tossinvest.com/ko/open-api)
