# ADR-002. 레거시 DB와 신규 DB의 분리와 공존 방식

- 상태: 승인 (2026-11-07)
- 관련 지점: 요구사항 2 (공존 기간 데이터), F1 주문·결제 상태 불일치, F10 위생

## 맥락

서비스는 멈출 수 없고, 전환 기간 동안 레거시 통합 DB와 신규 시스템(상품+재고 / 주문 / 결제 서비스, 각자 DB)이 공존한다. 레거시 화면과 배치는 `TB_ORDER`·`TB_PAY` 등을 계속 읽는다. 정해야 할 것은 두 가지다. 어느 쪽이 원천(truth)인가, 그리고 반대쪽을 어떻게 맞추는가.

이 제출물에서 공존은 **설계 문서로만** 다룬다. 코드와 테스트는 요구사항 3(결제 서비스)만 포함한다.

## 고려한 대안

| 안 | 요약 |
|---|---|
| (a) 레거시 원천 + 신규 섀도우 | 레거시가 정답, 신규는 따라 쓰며 주기적 보정 배치로 맞춘 뒤 교체 |
| (b) 애플리케이션 이중 쓰기 | 요청 하나가 레거시 DB와 신규 DB에 동시에 쓴다 |
| (c) 일괄 전환(dump/restore) | 점검 시간에 데이터를 옮기고 한 번에 전환 |
| (d) 레거시 동기화 전담 서비스 + 코드 이관 | 네 번째 서비스가 모든 TB_* 쓰기와 데이터 이관을 맡는다 |
| **(e) 주문 단위 라우팅 + outbox 동기화 (채택)** | 아래 |

## 선택과 근거

1. **분기점은 주문 생성 하나.** 주문 서비스 설정 `migration.new-order-ratio`가 신규로 보낼 비율을 정한다. 0이면 새 주문은 전부 레거시로 간다. 비율 0은 신규 유입 차단이고, 이미 신규가 원천인 주문은 신규가 끝까지 처리한다. [AWS Strangler] "Requires a rollback plan for each refactored service".
2. **신규가 만든 주문은 신규 DB가 원천.** 결제·취소·조회 모두 신규 서비스가 처리한다. 레거시가 만든 주문은 레거시가 끝까지 처리하고, 신규 서비스는 읽기 전용으로만 접근한다. 신규 원천 주문은 레거시 쓰기 경로(화면·CS 도구의 취소 등)를 차단한다(주문번호 형식으로 판별). 한 주문에 원천이 둘인 순간이 없다.
3. **레거시 테이블 동기화는 outbox로.** 신규 DB 저장과 outbox 행 기록이 같은 트랜잭션이고, outbox → (운영: Debezium → Kafka) → 레거시 테이블 쓰기 소비자로 전달한다. [Debezium outbox] "we cannot have one shared transaction that would span the service's database as well as Apache Kafka." [배민 회원 이벤트]도 같은 패턴을 쓴다.
4. **레거시 테이블마다 쓰는 서비스는 하나.**

| 레거시 테이블 | 쓰는 서비스 | 규칙 |
|---|---|---|
| TB_ORDER, TB_ORDER_ITEM, TB_CART | 주문 | 동기화 행. ORD_STAT은 `20`/`90`만 사용, 부분취소는 `20` 유지 |
| TB_PAY | 결제 | 동기화 행. 부분취소는 레거시 7번 행처럼 취소 행 추가 |
| TB_COUPON | 주문 | 원천. `USE_YN='N'` 조건부 UPDATE로 사용, 전체취소 시 복원 |
| TB_MEMBER.POINT_AMT | 결제 | 원천. `POINT_AMT>=?` 조건부 UPDATE로 차감, 취소 시 복원 |
| TB_GOODS | 상품 | 원천. `STOCK_QTY>=?` 조건부 UPDATE로 선점·해제 |

   날짜는 `YYYYMMDDHHMISS` 문자열로 변환한다. 레거시에 새 코드값을 넣지 않는 이유는 화면 수정이 필요해지면 "무중단"이 깨지기 때문이다.
5. **전환 완료 조건은 병행 비교.** 두 시스템이 만든 데이터를 대조해 불일치가 0에 수렴할 때 비율을 100으로 올린다. [토스 정산 개편기] "두 시스템에서 만든 거래가 동일하면 신규 시스템으로 만들어진 데이터를 투입합니다." [배민 포인트 #2] "레거시 도메인과 신규 도메인을 비교 & 검증". [Azure Strangler] "Validate consistency between both databases before cutover."

6. **공유 자원은 전환 완료 전까지 레거시가 원천.** 포인트 잔액·재고·쿠폰은 주문 단위로 나눌 수 없다. 원천이 둘이면 레거시 주문과 신규 주문이 같은 포인트를 두 번 쓰거나 같은 재고를 두 번 판다. 그래서 신규 서비스가 레거시 테이블에 조건부 UPDATE로 직접 차감·복원하고, 신규 테이블은 캐시로 둔다. 레거시 코드를 고치지 않아도 되고, 롤백은 비율 0으로 끝나며, [배민 포인트 #2]·[토스 정산 개편기]가 쓴 "구 시스템이 정답지" 순서와 같다. 반대안(전환 1일차에 신규로 이관하고 레거시가 신규 API 호출)은 설계 문서와 개발자가 없는 레거시를 고쳐야 해서 버렸다.

## 버린 이유와 trade-off

| 버린 안 | 이유 |
|---|---|
| (a) 레거시 원천 + 섀도우 | [토스 레거시 원장]은 구원장을 정답지로 두고 5분 보정 배치를 돌렸다. 검증된 방식이지만 신규 원장이 권위를 얻는 시점이 늦고 보정 배치가 추가로 필요하다. 주문 단위 라우팅은 신규 주문에 대해 첫날부터 신규가 원천이다 |
| (b) 애플리케이션 이중 쓰기 | 한 트랜잭션으로 두 DB를 묶을 수 없어 한쪽만 성공하는 상태가 생긴다(Debezium 인용). [LINE 샤딩]은 쓰기를 두 번 해 사용자 응답에 영향이 생겨 이 안을 버렸다: "ダブルライト中は書き込みを2度行うので、ユーザレスポンスに影響が出る" |
| (c) 일괄 전환 | [LY Flava] "typically requires application downtime". 서비스는 멈출 수 없다 |
| (d) 전담 서비스 + 코드 이관 | 실행 프로세스가 하나 늘고 레거시 지식이 또 한 곳에 생긴다. 과제 범위에서는 문서로 충분하다고 판단했다 |

받아들인 비용

- 전환 기간 동안 원천이 주문번호 기준으로 둘로 갈린다. 회원별 주문 목록 같은 조회는 두 DB를 합쳐야 한다.
- 레거시 화면은 부분취소를 "ORD_STAT 20 + TB_PAY 취소 행"으로만 본다. 정확한 상태는 신규 DB에만 있다.
- 대사 배치가 필요하다. [토스 정산 용어] "정산 대사란 매출과 정산 금액을 비교하는 과정". outbox는 at-least-once라 레거시 쪽 수신은 event_id로 중복을 걸러야 한다.
- 신규 서비스가 전환 기간 동안 레거시 DB에 쓰기 경로(포인트·재고·쿠폰 조건부 UPDATE)를 가진다. 레거시 DB의 쓰기 경합이 신규 서비스의 지연으로 이어지고, 전환 완료 시 잔액 이관 작업이 한 번 필요하다(DESIGN A10).

## 출처

URL은 접속 확인했다(2026-11-07).

| 표기 | URL |
|---|---|
| AWS Strangler | https://docs.aws.amazon.com/prescriptive-guidance/latest/modernization-decomposing-monoliths/strangler-fig.html |
| Azure Strangler | https://learn.microsoft.com/en-us/azure/architecture/patterns/strangler-fig |
| Debezium outbox | https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/ |
| 배민 회원 이벤트 | https://techblog.woowahan.com/7835/ |
| 토스 정산 개편기 | https://toss.tech/article/payments-legacy-6 |
| 배민 포인트 #2 | https://techblog.woowahan.com/2588/ |
| 토스 레거시 원장 | https://toss.tech/article/payments-legacy-5 |
| LINE 샤딩 | https://engineering.linecorp.com/ja/blog/line-manga-database |
| LY Flava | https://techblog.lycorp.co.jp/en/techverse2026-231 |
| 토스 정산 용어 | https://docs.tosspayments.com/resources/glossary/settlement |
