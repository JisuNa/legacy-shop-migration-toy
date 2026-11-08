# ADR-003. 재고 차감 시점과 방식: 주문 시 동기 선점, 확정·해제는 이벤트

- 상태: 승인 (2026-11-07)
- 관련 지점: F6 재고 경합·파생 상태 불일치, 배경 "세일·오픈 시점에 동일 상품으로 주문이 몰립니다"

재고는 이 제출물에서 **설계로만** 다룬다. 코드는 결제 서비스만 포함한다.

## 맥락

레거시는 `TB_GOODS.STOCK_QTY` 가변 컬럼 하나로 재고를 관리하고 선점 개념이 없다. 가상계좌 대기 중인 `ORD20260301003`(G004 ×2)이 재고를 잡았는지 스키마로는 알 수 없다. `SALE_STAT '02'`(품절)은 `STOCK_QTY = 0`의 중복 파생 상태라 어긋날 수 있다. 신규에서는 재고가 상품 서비스 DB에 있고 주문은 별도 서비스다. 정해야 할 것은 언제, 어떤 호출로 재고를 잡는가다.

## 고려한 대안

| 안 | 요약 |
|---|---|
| (a) 교과서형 코레오그래피 saga | 주문을 PENDING으로 저장 → outbox 이벤트 → 상품이 선점 → `StockReserved`/`StockRejected` 이벤트로 확정 |
| (b) 결제 완료 시 차감 | 선점 없음. 결제 성공 이벤트를 받아 그때 차감 |
| (c) 상품 행 `SELECT ... FOR UPDATE` | 주문마다 상품 행을 잠그고 차감 |
| (d) 낙관적 락(version 컬럼) | 충돌 시 재시도 |
| **(e) 동기 선점 + 비동기 확정·해제 (채택)** | 아래 |

## 선택과 근거

1. **주문 생성 시 상품 서비스에 동기 HTTP로 선점한다.** 재고는 `stock(qty, reserved_qty)`이고 판매가능 = qty − reserved_qty다. 조건부 UPDATE `UPDATE stock SET reserved_qty = reserved_qty + ? WHERE goods_id = ? AND qty - reserved_qty >= ?`가 1행이어야 성공이고, 0행이면 즉시 품절 응답이다. 요청에는 주문번호를 멱등키로 보내 재시도가 이중 선점이 되지 않게 한다(`stock_reservation.order_no UNIQUE`).
2. **확정과 해제는 outbox 이벤트다.** 결제 완료 → 확정, 취소·결제 실패·가상계좌 만료(`VBANK_DT`) → 해제. at-least-once이므로 수신 측은 선점 상태 전이(RESERVED → CONFIRMED/RELEASED)로 중복을 흡수한다.
3. **근거는 운영 사례다.** [배민 선물하기 재고] "구매가 정상적으로 이루어지지 않았는데 재고사용량이 증가되어 있으면 안되니까요 … 구매가 일어나는 API와 동기 방식으로 얽혀서 진행". 해제만 비동기다. "구매가 취소되거나, 구매시 API 호출이 실패했을 경우 … 재고사용량을 차감시키라는 이벤트를 비동기방식으로 발행". 원칙은 "절대 초과하여 상품이 판매되면 안되어야 합니다".
4. **outbox의 자리는 결정 이후의 전파다.** [배민 카프카]는 이미 확정된 상태를 흘려보내는 데 outbox를 쓴다. 선점처럼 사용자가 결과를 기다리는 판단에는 쓰지 않는다.
5. **품절은 저장하지 않고 도출한다.** 판매가능(qty − reserved_qty)이 0이면 품절이다. 레거시 `SALE_STAT '02'`처럼 파생값을 따로 들고 있으면 어긋난다.

## 버린 이유와 trade-off

| 버린 안 | 이유 |
|---|---|
| (a) 코레오그래피 saga | 사용자는 "접수됨"만 받고 품절은 나중에 안다. 세일 오픈처럼 대부분이 실패하는 상황에서는 거절·보상 이벤트가 폭주한다. [AWS Saga] "Compensatory transactions can add latency to the overall response time". [Azure Saga] "Compensating transactions might not always succeed". [SK Devocean] "비동기 방식은 실시간성, 동시성이 요구되는 경우 사용할 수 없습니다" |
| (b) 결제 완료 시 차감 | 결제 화면에서 품절이 나고, 결제 완료와 차감 사이에 오버셀 창이 생긴다 |
| (c) FOR UPDATE | 조건부 UPDATE도 같은 배타 잠금을 잡는다 [MySQL 잠금 읽기]. 차이는 잠금 보유 구간이다. 조건부 UPDATE는 문장 하나, FOR UPDATE는 조회→앱 로직→갱신 왕복 동안 잡아 인기 상품 행의 처리량이 이 구간 길이에 묶인다. [PostgreSQL 잠금] "can increase the likelihood of deadlocks" |
| (d) 낙관적 락 | 동일 상품 폭주에서는 거의 모든 요청이 충돌해 재시도 폭풍이 된다 |

받아들인 비용

- 주문 생성 경로에 동기 호출이 하나 생긴다. 상품 서비스 장애가 주문 생성 실패로 이어지므로 타임아웃과 실패 응답을 명확히 둔다.
- 선점 후 결제하지 않은 주문은 만료 전까지 재고를 점유한다. 가상계좌는 입금 기한까지 점유를 유지한다.
- 조건부 UPDATE도 결국 DB 행 하나다. 처리량이 부족해지면 Redis 원자 연산을 입장 제한으로 앞에 둔다. [Redis Lua] "Redis guarantees the script's atomic execution." [배민 선물하기 재고]는 Redis로 경합을 풀고 RDB `stock_history`로 유실을 막았다. 이때 분산락을 쓰면 [컬리 분산락] "락의 해제가 트랜잭션 커밋보다 먼저 이뤄지면 데이터 정합성이 깨질 수 있습니다"를 지켜야 한다.

## 출처

URL은 접속 확인했다(2026-11-07).

| 표기 | URL |
|---|---|
| 배민 선물하기 재고 | https://techblog.woowahan.com/2709/ |
| 배민 카프카 | https://techblog.woowahan.com/17386/ |
| AWS Saga | https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/saga-choreography.html |
| Azure Saga | https://learn.microsoft.com/en-us/azure/architecture/patterns/saga |
| SK Devocean | https://devocean.sk.com/blog/techBoardDetail.do?ID=165445 |
| MySQL 잠금 읽기 | https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html |
| PostgreSQL 잠금 | https://www.postgresql.org/docs/current/explicit-locking.html |
| Redis Lua | https://redis.io/docs/latest/develop/programmability/eval-intro/ |
| 컬리 분산락 | https://helloworld.kurly.com/blog/distributed-redisson-lock/ |
