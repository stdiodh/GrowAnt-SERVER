# 저장소 작업 지침

## 작업 시작 게이트

다음 절차는 선택 사항이 아닙니다.

1. 매 작업 시작 시 이전 대화나 요약에 의존하지 않고 루트 `README.md` 전체를 직접 읽습니다.
2. 첫 파일 수정 전에 현재 브랜치와 README의 브랜치·커밋·PR·CI·QA 규칙 중 이번 작업에 적용되는 내용을 확인합니다.
3. `README.md`가 없거나 읽을 수 없으면 작업을 중단하고 사용자에게 알립니다.
4. 요청이 README 정책과 충돌하면 조용히 우회하지 말고 충돌 내용을 설명한 뒤 명시적인 결정을 받습니다.
5. 완료를 보고할 때 변경 내용, 실행한 검증, 실행하지 못한 검증을 구분합니다.

## 저장소 규칙

- 브랜치, 커밋, PR, CI, QA 정책의 단일 기준은 `README.md`입니다.
- `origin/develop`이 없으면 다른 기준 브랜치를 임의로 사용하지 말고 README의 최초 설정이 필요하다고 알립니다.
- `main`이나 `develop`에 직접 commit 또는 push하지 않습니다. 최신 `develop`에서 `feature/*`를 만든 뒤 commit합니다.
- `main` PR은 `develop`에서만, `develop` PR은 `feature/*`에서만 만듭니다.
- 변경을 작게 유지하고 완료를 보고하기 전에 README에 정의된 관련 `*Test`와 `*IT` 검사를 실행합니다.

## Code Review Rules

### Authentication and user isolation

- 클라이언트가 보낸 사용자 식별자를 신뢰하거나 JWT 보호를 약화하거나, 인증된 사용자 범위 없이 account, portfolio, position, trade 데이터를 읽고 쓰는 변경을 지적합니다.
  안전한 경로: 검증된 JWT에서 `userId`를 얻고 repository와 service query에 사용자 범위를 유지합니다.

### Trading atomicity

- 현금, 포지션, 거래 내역을 하나의 transaction 밖에서 갱신하거나 동등한 동시성 보장 없이 사용자별 write lock을 제거하는 변경을 지적합니다.
  안전한 경로: 주문 상태 변경을 원자적으로 유지하고 동시 주문을 `*IT`로 검증합니다.

### API and database compatibility

- 공통 `ApiResponse` 계약을 version 없이 깨뜨리거나 이미 적용된 Flyway migration을 수정하는 변경을 지적합니다.
  안전한 경로: 기존 응답 field를 보존하거나 명시적인 호환 계획을 세우고, 기존 migration 대신 새 migration을 추가합니다.
