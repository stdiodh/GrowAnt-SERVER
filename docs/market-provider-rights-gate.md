# 국내주식 시세 공급자 권리 Gate 0

확인 기준일: 2026-08-16

이 문서는 KIS와 토스증권을 동일한 5종목·1분봉 조건으로 실측하기 전에 필요한 서면 권리 확인 절차다. 법률 자문이나 공급자의 허가를 대신하지 않는다. 실제 문의 발송, 개인정보·계약 본문 보관, 자격 증명 입력은 이 문서의 범위가 아니다.

## 1. 현재 결론: `RIGHTS_BLOCKED`

현재 공개된 공식 문서만으로는 GrowAnt 목적의 실제 시세 호출·저장·비교·재생·표시 권리가 확인되지 않았다. 따라서 다음 상태를 유지한다.

- KIS: `RIGHTS_BLOCKED`
- 토스증권: `RIGHTS_BLOCKED`
- 허용: 공식 문서 검토, 합성 fixture 기반 계약 테스트와 로컬 부하 테스트
- 금지: 실제 토큰 발급을 전제로 한 시세 호출, 실제 가격·수량 저장, 실제 시세 재생, 차트 표시, 실제 시세나 공급자별 실측 결과의 PR·Velog 공개

`RIGHTS_BLOCKED`는 개인 투자자가 자기 투자 목적으로 API를 쓸 수 없다는 뜻이 아니다. GrowAnt의 내부 공급자 비교와 향후 다중 사용자 차트 제공 목적에 대한 권리가 아직 증명되지 않았다는 뜻이다.

### 1.1 공식 근거

| 주체 | 현재 공식 근거 | GrowAnt 판단 |
| --- | --- | --- |
| KIS | [Open API 서비스 소개](https://apiportal.koreainvestment.com/about-open-api)는 개인·일반법인의 본인 계좌 및 자기 투자 프로그램 이용 경로를 안내하고, 제공받은 시세의 제3자 제공은 제한한다. | 자기 투자 목적의 허용을 내부 공급자 비교시험이나 공개 차트 권리로 확대 해석하지 않는다. 일반법인 내부 benchmark가 이 범위에 드는지는 `UNKNOWN`이다. |
| KIS | [제휴 안내](https://apiportal.koreainvestment.com/provider)와 [제휴 제안 등록](https://apiportal.koreainvestment.com/provider-apply)은 제3자 서비스 목적의 제휴기관과 브랜드 앱 시세 표출 범위를 별도로 다루며, 이 경우 KRX 등 정보이용계약 확인이 필요하다고 안내한다. 비제도권 핀테크사의 제휴가 불가하다는 예시도 있다. | GrowAnt의 법적 주체, 내부 시험과 외부 서비스의 구분, 필요한 KIS·거래소 계약 경로를 각각 서면으로 확인해야 한다. |
| 토스증권 | [Open API 이용 안내](https://home.tossinvest.com/ko/open-api)는 API를 본인 매매 목적으로 제한하고 외부 배포·상업 이용을 금지한다. | 내부 경쟁 공급자 비교가 본인 매매에 포함된다고 가정하지 않는다. |
| 토스증권 | [공식 Open API 개요](https://openapi.tossinvest.com/openapi-docs/overview.md)와 [canonical OAS](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)는 캔들 API의 기술 계약을 제공하지만 저장·파생·재배포 권리를 부여하지 않는다. | 기술적으로 호출 가능하다는 사실을 이용 허가로 해석하지 않는다. |
| KRX·코스콤 | [KRX 데이터 수신·계약 안내](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA003.jsp)는 프로그램 개발·재배포·수익사업 등 단순 참고 이외 이용에 별도 계약이 필요하다고 안내한다. | 공급자 회신과 별도로 상위 시세 권리 및 필요한 계약 체결 여부를 확인한다. |
| NXT | [NEXTRADE Market Data Portal](https://portal.nextrade.co.kr/mdclient/home.do)은 NXT 시세 이용에 데이터 이용계약 절차를 제공한다. | 토스 캔들에 NXT 시세가 포함되면 NXT 권리도 별도로 해결한다. |

공식 문서의 내용이나 checksum이 바뀌면 이전 판단을 재사용하지 않는다. 각 실행 직전에 문서를 다시 수집하고 새 증거로 판정한다.

## 2. Gate 0의 결과물

공급자별로 다음 6개 판단을 `UNKNOWN`, `ALLOWED`, `DENIED` 중 하나로 확정한다. 이름은 관측 저장 모델의 `ObservationRights`와 같다.

| 질문 | 모델 필드 | 의미 |
| --- | --- | --- |
| Q1 저장 | `storage` | 원본·정규화 시세와 관측 메타데이터 저장 |
| Q2 벤치마크·파생 | `benchmark` | 1분봉 생성, 공급자 비교, 지연·누락·복구 통계 산출 |
| Q3 재생 | `replay` | 허용된 시세를 비공개 GrowAnt 환경에 재생 |
| Q4 CI | `ci` | 실제 시세를 CI 입력·로그·artifact로 사용하는 행위 |
| Q5 내부 표시 | `internalDisplay` | 지정 개발자에게 비공개 차트를 표시하는 행위 |
| Q6 외부 배포 | `externalDistribution` | 공개 차트와 외부 비교 결과 배포 |

회신에 조건이 붙으면 별도 `CONDITIONAL` 값을 만들지 않는다. 모든 조건을 불변 실행 명세에 반영하고 충족을 증명할 수 있을 때만 `ALLOWED`로 기록한다. 조건이 불명확하거나 아직 충족되지 않았으면 `UNKNOWN`, 명시적으로 금지됐으면 `DENIED`다.

실제 관측을 활성화하려면 6개 판단에 `UNKNOWN`이 없어야 하고, 최소한 `storage`와 `benchmark`가 `ALLOWED`여야 한다. 사용하지 않는 기능은 `DENIED`일 수 있다. 다만 전체 목표에서 실제 시세 재생을 사용할 계획이면 `replay`도 `ALLOWED`여야 한다. 공개 차트인 Q6-A를 범위로 새로 만든 bundle에서 `externalDistribution=DENIED`이면 GrowAnt 공개 차트의 운영 후보가 될 수 없다. 내부 비교 run에서 Q6-B만 범위로 판정한 값은 PR·Velog 공개 여부에만 적용하며 Q6-A의 운영 권리로 재사용하지 않는다.

## 3. 발송 전 사용자가 입력할 값

다음 항목을 공식 문의 채널에만 입력한다. 빈칸이 있으면 문의를 보내지 않으며 저장소에도 기록하지 않는다.

- 법적 이용 주체: 개인, 개인사업자, 법인 중 하나
- 사업자·법인명과 업종, 제도권 금융회사 여부
- 사용할 계정 유형: 개인 또는 법인
- 현재 KRX·코스콤·NXT 정보이용계약의 계약 유형과 유효기간
- 내부 접근자 수와 역할
- 저장 위치, 국가·리전, 클라우드 또는 하위처리자
- 실제 데이터 보관 희망 기간과 삭제·백업 방식
- 내부 연구 이후 공개 차트 제공 또는 상업화 계획
- 문의 담당자 이름, 회신 이메일, 전화번호

계좌번호, App Key, App Secret, Client ID, Client Secret, 토큰, 고정 IP, 주민등록번호는 최초 권리 문의에 넣지 않는다. 공급자가 이후 본인확인을 요구하면 저장소 밖의 승인된 보안 채널로만 전달한다.

## 4. 공통 권리 요청서

### 4.1 제목

```text
[GrowAnt Gate 0 권리 확인] 국내주식 5종목·1분봉·5거래일 내부 공급자 비교시험
```

### 4.2 시험 범위

- 목적: 매수·매도 없이 시세 수집 안정성, 지연, 누락, 복구와 GrowAnt 서버 부하 연구
- 종목: `005930`, `000660`, `035720`, `035420`, `005380`
- 기간: 서면 승인 이후 최초 5개 독립 정상 거래일
- 구간: 두 공급자에 공통으로 정의할 수 있는 동일 정규장 구간
- KIS 경로: KRX 체결 WebSocket `H0STCNT0`, 당일·일별 분봉 REST 복구 경로
- 토스 경로: `GET /api/v1/candles`, `interval=1m`, `adjusted=false`
- KIS 구독: 5종목만 등록
- 토스 호출: 전체 최대 5 TPS이면서 실제 `X-RateLimit-Limit`의 25% 이하
- 과부하 대응: `429`에서 `Retry-After` 준수, backoff와 jitter 적용
- 저장: 암호화된 비공개 저장소, 지정 내부 개발자만 접근
- 보관 요청: 실제 시세 90일, 가격을 복원할 수 없는 집계 통계는 프로젝트 존속 기간
- 재생: 공급자 API가 아니라 비공개 GrowAnt 서버에만 수행
- k6: 저장한 허용 데이터 또는 합성 fixture를 GrowAnt API에 재생하며 공급자 endpoint를 부하 테스트하지 않음
- 외부 공개: 별도 허용 전 실제 가격·수량·원시 payload·차트 스크린샷을 공개하지 않음

### 4.3 복사해 보낼 서두

```text
GrowAnt는 매수·매도 기능 없이 국내주식 차트 수집, 서버 저장 및 부하 대응을
연구하는 프로젝트입니다. 귀사의 서면 허용 전에는 실제 시세 API 호출과
실데이터 저장을 수행하지 않습니다.

아래 시험 범위를 기준으로 Q1~Q6 각각을 ALLOW, ALLOW WITH CONDITIONS,
DENY 중 하나로 답변해 주시고, 근거 약관·계약명·상위 데이터 권리자,
추가 계약·비용, 허용 기간과 종료 후 삭제 의무를 함께 알려주시기 바랍니다.

본 회신이 Open API 및 시세 이용 권한을 판단할 수 있는 담당 부서의
승인된 답변인지, 별도 KRX·코스콤·NXT 계약이 필요한지도 확인 부탁드립니다.
```

## 5. 6개 권리 질문

### Q1. 저장 — `storage`

```text
귀사 API 응답의 국내주식 체결 또는 1분 OHLCV를 암호화된 비공개 저장소에
최대 90일간 저장하는 것을 허용합니까?

1. 원본 wire payload 저장
2. 정규화한 1분 OHLCV 저장
3. HTTP 상태, 요청·수신시각, rate-limit 헤더, 장애 코드 등 비가격 운영
   메타데이터 저장

각 항목의 허용 여부와 최대 보관기간, 저장 국가, 접근자·기기 수, 백업과
시험 종료 후 삭제 의무를 구분해 주십시오.
```

정규화 시세와 측정 메타데이터 중 하나라도 불명확하면 `storage=UNKNOWN`이다. 원본 저장만 금지되고 정규화 저장이 명시적으로 허용되면 원본을 즉시 폐기하는 조건을 실행 명세에 고정한 뒤 `ALLOWED`로 판단할 수 있다.

### Q2. 벤치마크·파생 — `benchmark`

```text
귀사 데이터로 다음 결과를 생성·보관하고 다른 후보 공급자와 내부 비교하는
것을 허용합니까?

1. 체결 데이터의 1분 OHLCV 집계
2. 지연시간, 누락률, 중복률, 오류율, 복구시간
3. 가격·거래량을 복원할 수 없는 일별 p50·p95·p99와 비율 통계

파생 1분봉이 여전히 시세정보로 취급되는지, 비복원 집계 지표의 보관과
공급자명을 포함한 비교 결과 공개가 가능한지도 각각 답변해 주십시오.
```

“개발 가능” 또는 “가공 가능”처럼 행위와 보존 범위를 특정하지 않은 답변은 `UNKNOWN`이다.

### Q3. 재생 — `replay`

```text
저장이 허용된 데이터를 외부에 공개하지 않고 로컬 또는 비공개 staging의
GrowAnt 서버에 가속 재생하여 k6로 GrowAnt 서버의 조회·캐시·WebSocket
부하를 시험하는 것을 허용합니까?

k6는 귀사 API를 호출하지 않고 실제 가격값을 로그·리포트·CI artifact에
기록하지 않습니다. 이 재생이 허용되는 환경, 배속, 접근자 수와 보관기간을
알려주십시오.
```

일반적인 “테스트 허용” 답변만으로 가속 재생 권리까지 추정하지 않는다.

### Q4. CI — `ci`

```text
GrowAnt의 GitHub CI에는 실수신 시세를 넣지 않고 인위적으로 만든 숫자와
공개 API 스키마만 사용합니다. 공식 문서의 필드 구조를 따른 합성 fixture를
단위·통합 테스트에 사용하는 것을 허용합니까?

실제 수신값을 private CI 입력, 로그 또는 artifact로 사용하는 권리는 별도로
허용됩니까?
```

현재 GrowAnt 정책은 실제 시세를 CI에서 사용하지 않는 것이므로 실제 데이터에 대한 `ci`는 `DENIED`로 기록한다. 합성 fixture는 공급자 실데이터 권리 판정의 대상이 아니다.

### Q5. 내부 표시 — `internalDisplay`

```text
최대 [내부 접근자 수]명의 지정 개발자가 비공개 네트워크에서 1분봉 차트를
확인하는 내부 QA 표시를 허용합니까?

사용자·단말 과금, 동시접속 제한, 출처·지연 표시, 화면 캡처와 내보내기
제한이 있다면 명시해 주십시오.
```

접근자·환경·표시 범위가 빠진 답변은 `UNKNOWN`이다. 내부 화면을 전혀 사용하지 않을 실행만 `DENIED`로 확정할 수 있다.

### Q6. 외부 배포 — `externalDistribution`

```text
A. 향후 GrowAnt의 로그인 또는 비로그인 사용자에게 귀사 데이터로 만든
   1분 차트를 제공할 수 있습니까? 허용 사용자 범위, 요구 지연시간,
   출처표시, 사용자·단말 과금과 별도 거래소 계약을 알려주십시오.

B. 실제 가격·거래량·원시 payload를 공개하지 않고 귀사명과 일별 지연
   p50·p95, 누락률, 오류율, 복구시간 같은 비복원 비교 결과를 GitHub PR과
   기술 블로그에 공개할 수 있습니까? 경쟁 공급자와 비교하는 benchmark
   공개 허용 여부도 구분해 주십시오.
```

A와 B 중 하나라도 실행에 포함되면 해당 항목의 명시적 허용이 필요하다. A가 금지된 공급자는 내부 관측은 가능할 수 있어도 GrowAnt 운영 공급자 후보에서는 제외한다. B가 불명확하면 공급자명과 실측 결과를 PR·Velog에 공개하지 않는다.

`externalDistribution`은 해당 실행 명세에 선언된 외부 출력만 판정한다. B만 허용된 내부 비교 실행의 `ALLOWED`를 A의 공개 차트 권리로 재사용하지 않으며, 공개 차트 단계에서는 새 범위·증거·checksum으로 다시 판정한다.

## 6. KIS 추가 질문

공통 질문과 함께 다음 항목을 번호별로 회신받는다.

1. GrowAnt의 내부 공급자 비교가 개인·일반법인의 “본인 자산 투자 목적”에 포함되는가?
2. 포함되지 않는다면 사용할 수 있는 계정·계약 유형은 무엇인가?
3. 비제도권 개발 프로젝트가 시세 전용 계약으로 이용할 공식 경로가 존재하는가?
4. KRX·코스콤 최종이용사 계약만으로 충분한가, KIS와의 별도 제휴·시세 계약도 필요한가?
5. `H0STCNT0` 체결의 원본 저장과 1분봉 집계를 각각 허용하는가?
6. 당일·일별 분봉 REST를 누락 복구와 정확성 기준으로 사용하는 것을 허용하는가?
7. KIS와 다른 공급자를 이름으로 비교한 비가격 benchmark 결과를 공개할 수 있는가?

공식 문의는 [KIS 제휴 제안 등록](https://apiportal.koreainvestment.com/provider-apply) 또는 해당 페이지에 안내된 `openapi@koreainvestment.com`을 사용한다. 제휴 자격이 불명확하면 신청서를 제출하기 전에 이메일로 시세 전용 연구 경로 존재 여부부터 묻는다. 공식 페이지 안내에 따라 영업기밀은 제휴 제안 본문에 넣지 않는다.

## 7. 토스증권 추가 질문

공통 질문과 함께 다음 항목을 번호별로 회신받는다.

1. 본인 매매 목적 외 이용 제한에 대해 이번 비공개 비교시험의 서면 허용이 가능한가?
2. `/api/v1/candles`의 국내주식 봉은 KRX, NXT 또는 두 시장 통합 데이터 중 무엇인가?
3. 통합 데이터라면 봉별 venue를 식별할 수 있는가?
4. 상위 데이터 권리자는 누구이며 토스증권이 저장·파생·재생·표시 권리를 이용자에게 허여할 수 있는가?
5. `timestamp`가 봉 시작시각이라는 기술 계약 외에 봉 확정시점과 사후 정정 규칙은 무엇인가?
6. `volume`은 어느 시장과 세션의 거래량을 포함하는가?
7. 전체 최대 5 TPS이면서 실제 응답 한도의 25% 이하로 5거래일 폴링하는 비교시험을 허용하는가?
8. 공급자명을 포함한 비가격 비교 결과와 향후 공개 1분 차트를 각각 허용하는가?

venue, 세션, 확정시점, 정정 및 거래량 의미가 답변되지 않으면 권리가 허용돼도 관측 의미는 `UNKNOWN`이며 비교 점수에 넣지 않는다.

공식 서면 문의는 [토스증권 고객센터](https://home.tossinvest.com/ko/faq)에 안내된 `support@tossinvest.com`을 우선 사용한다. Open API 제품 담당, 준법 또는 시세권리 담당 부서의 승인된 답변으로 이관해 달라고 요청한다. 전화·채팅 답변은 이메일이나 공식 티켓으로 재확인한다.

토스 캔들에 NXT 데이터가 포함된다면 [NEXTRADE Market Data Portal](https://portal.nextrade.co.kr/mdclient/home.do)의 계약 문의 경로에서도 동일 범위를 확인한다. KRX 데이터라면 [KRX 계약 안내](https://openapi.krx.co.kr/contents/OPP/DATA/OPPDATA003.jsp)에 따라 코스콤 계약 필요 여부를 확인한다.

## 8. 증거 보존

### 8.1 비공개 증거 메타데이터

```text
evidenceId
provider
right                         # storage, benchmark, replay, ci, internalDisplay, externalDistribution
decision                      # UNKNOWN, ALLOWED, DENIED
scopeVersion
scopeSha256
requestChannel
requestSubject
sentAt
ticketId
replyAt
responderOrganization
responderName
responderTeam
responderRole
authorityConfirmed
governingDocumentTitle
governingDocumentVersion
governingClause
officialUrl
finalUrl
retrievedAt
contentType
etag
lastModified
documentSha256
allowedEntity
allowedAccountType
allowedApiIds
allowedDataTypes
allowedUses
retentionDays
storageRegion
internalUserLimit
externalAudience
requiredDelay
requiredAttribution
upstreamLicensors
requiredContracts
fees
validFrom
validUntil
revocationConditions
nextReviewAt
evidenceFileSha256
internalApprover
decisionReason
```

전체 헤더가 포함된 이메일, 공식 티켓, PDF와 계약서는 접근 제한된 별도 증거 저장소에 보관하고 파일의 SHA-256만 관측 실행에 연결한다. Git 저장소에는 회신 본문, 계약 본문, 증거 파일, 회신자 개인정보를 넣지 않는다.

공식 문서는 redirect 이후 최종 URL, 조회 시각, 응답 헤더, 원본 bytes를 함께 보관한다. 동적 페이지는 화면 캡처만 남기지 않고 해당 시점의 원본 HTML·공식 asset 또는 PDF와 checksum을 보관한다.

### 8.2 canonical 권리 bundle

관측 run의 단일 `rights_evidence_id`와 `rights_evidence_sha256`은 한 문서의 hash가 아니라 다음 내용을 포함한 비공개 canonical bundle을 가리켜야 한다.

- 여섯 권리 각각의 결정, 적용 주체·계정·API·데이터·용도·환경·기간
- 결정별 공식 회신 또는 계약 파일의 SHA-256과 답변 권한 확인 결과
- KRX·코스콤 및 필요한 경우 NXT 상위 계약의 상태·유효기간·파일 SHA-256
- 조건부 허용을 실행 명세로 바꾼 값과 그 충족 증거
- bundle 생성 시각, 내부 승인자, 다음 재검토 시각

key 정렬, UTF-8, LF, 정수·boolean의 고정 표현을 사용하는 canonical serialization의 SHA-256만 run에 넣는다. 동일 bundle은 항상 같은 bytes와 hash를 만들어야 하고, 여섯 enum은 bundle의 결정과 정확히 같아야 한다.

현재 관측 foundation은 bundle ID·checksum과 enum을 저장하지만 bundle 내부를 조회해 항목별 scope·유효기간을 검증하는 registry는 아직 없다. 현재 활성화 정책은 `origin=PROVIDER`를 거절하고, JDBC 저장소는 provider 활성화와 REST poll·tick·candle·fault append 및 완료 전환을 거절한다. 활성화 전 clock 증거와 `INVALID`·cleanup은 허용한다. 이 봉인은 관측 저장소 범위이며 현재 실제 network adapter가 없다는 별도 경계와 함께만 유효하다. 따라서 실제 공급자 adapter를 붙이기 전에 별도 V5에서 승인된 비공개 bundle을 읽고 hash·여섯 결정·상위 계약·유효기간을 원자적으로 검증하는 Gate 0 registry를 구현하고, provider 연결과 canonical `minute_candles` 저장·보정도 verified lease에 묶어야 한다. 단순히 임의의 64자리 hash와 `ALLOWED` enum을 넣어 run을 활성화해서는 안 되며, 실제 network 호출을 `SYNTHETIC`으로 표시해서도 안 된다.

### 8.3 fail-closed 판정

| 상태 | 기록 |
| --- | --- |
| 무응답, 구두 답변, 일반 FAQ 안내 | `UNKNOWN` |
| “가능할 것”, “개발용 가능”, “가공 가능”처럼 범위가 모호함 | `UNKNOWN` |
| 조건부 허용이지만 조건을 실행 명세에 고정하지 못함 | `UNKNOWN` |
| 공급자는 허용했지만 KRX·NXT 계약 필요 여부가 불명확함 | 관련 권리 `UNKNOWN` |
| 필요한 거래소 계약이 아직 미체결임 | 관련 권리 `DENIED` |
| 정규화 저장은 허용하고 원본 저장은 금지함 | 원본 미저장 명세를 고정한 경우에만 `storage=ALLOWED` |
| 실제 데이터 CI가 금지되거나 사용하지 않음 | `ci=DENIED` |
| 외부 차트가 금지됨 | `externalDistribution=DENIED`, 운영 후보 제외 |
| 비가격 비교 결과 공개가 불명확함 | 외부 공개 중지 |
| 회신·계약이 만료되거나 공식 문서가 변경됨 | 새 실행은 `UNKNOWN`에서 재판정 |

문서나 회신이 단순히 “금지라고 쓰지 않았다”는 이유로 `ALLOWED`를 부여하지 않는다. `ALLOWED`에는 정확한 주체, 계정, API, 데이터, 환경, 기간과 행위가 모두 포함되어야 한다.

## 9. 비밀정보·시세값 금지

공식 문의서, GitHub, PR, CI, k6 결과와 기술 블로그에는 다음을 넣지 않는다.

- App Key, App Secret, Client ID, Client Secret, Bearer token
- 계좌번호, 계좌 sequence, 사용자 식별자, 주민등록번호
- 실제 API 원본 응답과 실제 OHLCV·개별 체결 가격·수량
- 내부 evidence manifest, row checksum, cursor 또는 1분 시계열에서 계산한 hash
- 실제 시세 차트나 운영 화면 스크린샷
- 계약서·공식 회신 본문과 회신자 개인정보
- 운영 IP, 내부 저장소 주소 등 공격에 이용될 수 있는 값

외부 공개가 서면 허용된 경우에도 공급자명, 시험 종목·기간 범위, 가격을 복원할 수 없는 일별 지연 백분위, 누락·중복·오류율, 복구시간과 시험 방법만 공개한다. 원시 시세나 1분별 측정값의 hash를 공개 근거로 대신 사용하지 않는다.

## 10. 실행 전 체크리스트

- [ ] 발송 전 사용자 입력값이 모두 채워졌다.
- [ ] KIS와 토스증권이 6개 권리를 항목별로 서면 회신했다.
- [ ] 회신자의 조직·역할과 답변 권한이 확인됐다.
- [ ] KRX·코스콤 및 필요한 경우 NXT 계약 범위가 확인됐다.
- [ ] 조건부 허용 조건이 실행 명세와 보존 기한에 고정됐다.
- [ ] 6개 권리 판단에 `UNKNOWN`이 없다.
- [ ] `storage=ALLOWED`, `benchmark=ALLOWED`다.
- [ ] 재생을 사용할 실행이면 `replay=ALLOWED`다.
- [ ] 공개 차트 후보로 평가하려면 Q6-A 범위의 새 bundle에서 `externalDistribution=ALLOWED`다.
- [ ] 공급자별 venue·세션·봉 시각·확정·정정·거래량 의미가 확정됐다.
- [ ] 공식 문서·회신·계약의 최종 URL, 조회시각, 유효기간과 SHA-256을 보존했다.
- [ ] V5 registry가 canonical bundle의 ID/hash, 여섯 결정, 실행 scope·유효기간과 상위 계약을 원자적으로 검증하며 provider permit과 canonical write를 fail-closed로 제어한다.
- [ ] 실제 키와 시세값이 Git·CI·문의 증거에 포함되지 않았다.

하나라도 충족하지 못하면 실데이터 수집을 시작하지 않고 합성 fixture 단계에 머문다.
