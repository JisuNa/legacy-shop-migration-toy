# ADR-001. 결제 원장 3층 구조와 부분취소 비례 배분

- 상태: 승인 (2026-11-07)
- 관련 지점: F2 부분취소 배분 오류, F3 취소 모델 혼재, F5 결제수단 열 나열, F8 포인트 잔액 컬럼, F9 멱등성 부재

## 맥락

레거시 `TB_PAY`는 한 행에 카드·가상계좌·내부 포인트·제휴 포인트(MILEAGE)·쿠폰 열을 모두 가진 열 나열 구조(wide table)다. 시드 데이터가 보여 주는 결함은 세 가지다.

| 증거 | 내용 |
|---|---|
| `ORD20260301005` 7번 행 | 카드 79,000 + 포인트 7,000 결제에서 부분취소 35,833을 전액 카드로 환불. 포인트 몫 2,917이 카드 현금으로 나감(`PNT_AMT` NULL) |
| 4번 행 vs 7번 행 | 4번은 승인 행을 `'C'`로 덮어씀(원 승인 기록 소실), 7번은 취소 행을 새로 추가. 같은 테이블에 두 모델이 공존 |
| `PG_APPROVE_NO` | UNIQUE 없음. 재시도가 이중 승인 행이 됨 |

하나의 결제에 PG·제휴 포인트·내부 포인트·쿠폰이 함께 쓰이므로 수단별 취소와 감사 추적이 가능해야 한다.

## 고려한 대안

| 안 | 요약 |
|---|---|
| (a) 레거시 유지 | wide 행 하나, 취소는 상태 플립 또는 행 추가 |
| (b) 3층 원장 + 포인트 우선 환불 | 취소 시 포인트를 먼저 전액 돌려주고 나머지를 PG에서 환불 |
| (c) 3층 원장 + UNKNOWN을 원장 행에 기록 | 타임아웃 상태를 `payment_transaction.status`로 관리 |
| (d) 3층 원장 + 배분 함수 공용 라이브러리 | 주문(할인 배분)과 결제(수단 배분)가 같은 코드 사용 |
| **(e) 3층 원장 + 요청 테이블 분리 + 비례 배분 (채택)** | 아래 |

## 선택과 근거

**구조**: 원장 3층(`payment_master` / `payment_detail` / `payment_transaction`) + 요청 테이블(`payment_request`). 이하 "3층"은 원장 3층을 가리킨다.

| 테이블 | 역할 | 변경 가능 여부 |
|---|---|---|
| `payment_master` | 주문 1건의 결제. 주문금액·할인·결제금액·상태 | 상태만 갱신 |
| `payment_detail` | 수단별 승인분(PG / POINT / MILEAGE). 승인액·취소누계 | 취소누계만 조건부 UPDATE |
| `payment_transaction` | 승인·취소 거래 1건당 1행. **INSERT-only**, `created_at`만 | 불변 |
| `payment_request` | 멱등키의 주인. REQUESTED → UNKNOWN → DONE/FAILED | 요청 상태 전이 |
| `point_account` / `point_transaction` | 내부 포인트 잔액 캐시 + INSERT-only 원장 | 원장 불변 |

원장(`payment_transaction`)에는 상태가 없고, 요청 상태 전이는 `payment_request`에만 있다.

- 쿠폰은 결제수단이 아니라 **주문 할인**이다. `payment_master.discount_amount`에만 남기고 수단 행을 만들지 않는다. 레거시 `DC_AMT = CPN_AMT` 의미를 그대로 잇는다.
- 취소는 승인 행을 고치지 않고 **새 행**을 넣는다. [토스 레거시 원장] "취소 시 데이터를 UPDATE 하던 구조를 INSERT-only 원칙으로 변경했습니다". [토스 취소] "각 취소 거래마다 거래를 구분하는 transactionKey를 가지고 있습니다".
- 타임아웃(UNKNOWN)은 원장이 아니라 `payment_request`에서 다룬다. 같은 토스 글의 "타임아웃된 거래건을 별도로 저장" 구조와 같고, [카카오페이 MSA] "알수없음으로 저장된 트랜잭션인지 확인하고 후처리를 다시 진행"과 같은 절차다.
- 포인트 잔액은 이관 기초잔액 + 원장의 합이다. [배민 포인트 #1] "모든 포인트 데이터는 Insert만 존재하는 도메인 모델로 구현". [Modern Treasury] "Mutating balances directly creates a system that is prone to errors."

**부분취소 배분**

- 취소 금액을 수단별 **남은 금액** 비율로 나누고, 반올림 잔차는 최대잔여법으로 배분해 합계가 항상 취소 금액과 같게 한다.
- 레거시 사례 35,833 → 카드 32,916 / 포인트 2,917. 레거시는 총액만 맞고 수단 배분이 틀렸음을 테스트로 보인다.
- 단순 반올림의 실패 사례: [Shopify 반올림] "$5.00 … becomes $1.67 per unit, totaling $5.01". 환불 상한: [Stripe 환불] "you can't refund a total greater than the original charge amount." 이 상한은 `payment_detail`의 CHECK와 조건부 UPDATE로 강제한다.

**멱등성**

- `payment_request.idempotency_key UNIQUE`가 1차 방어선이고, 외부 수단 호출에는 `키:수단` 형태의 멱등키를 함께 보낸다. UNKNOWN 상태에서 같은 키로 재시도하면 게이트웨이가 같은 결과를 돌려주므로 원장이 수렴한다. [토스 멱등키] "타임아웃 문제로 응답을 받지 못했을 때도 안전하게 같은 요청을 다시 보낼 수 있습니다". [Stripe 멱등].

## 버린 이유와 trade-off

| 버린 안 | 이유 |
|---|---|
| (a) 레거시 유지 | 수단 2개 이상·제휴사 2곳이면 표현 불가. 4번/7번 행처럼 취소 모델이 갈라지고 승인 기록이 사라진다 |
| (b) 포인트 우선 환불 | 원 결제 구성 비율대로 돌려주는 것이 PG 정산 대사와 포인트 현금화 방지에 맞다 |
| (c) UNKNOWN을 원장에 | 원장에 상태 컬럼이 생기면 INSERT-only가 깨진다. 요청 상태 전이는 `payment_request`에만 둔다 |
| (d) 배분 함수 공용화 | 플랫폼 라이브러리에 금액 계산이 들어가면 한 서비스의 반올림 정책 변경이 다른 서비스 배포를 강제한다. 20줄 함수는 서비스마다 둔다 |

받아들인 비용

- 테이블이 1개(TB_PAY)에서 7개(결제 4 + 포인트 2 + outbox 1)로 늘고 조회에 조인이 필요하다.
- 부분취소 시 쿠폰 복원 여부는 주문 서비스의 책임이다. 이 결제 서비스는 할인액을 입력으로만 받는다.
- 1원 잔차 규칙(최대잔여법, 동률이면 앞 수단)을 문서와 테스트에 명시해야 한다. 규칙 없이 구현체가 바뀌면 정산 대사에서 1원 차이가 난다.

## 출처

URL은 접속 확인했다(2026-11-07).

| 표기 | URL |
|---|---|
| 토스 레거시 원장 | https://toss.tech/article/payments-legacy-5 |
| 토스 취소 | https://docs.tosspayments.com/guides/v2/cancel-payment |
| 토스 멱등키 | https://docs.tosspayments.com/reference/using-api/idempotency-key |
| 카카오페이 MSA | https://tech.kakaopay.com/post/msa-transaction/ |
| 배민 포인트 #1 | https://techblog.woowahan.com/2587/ |
| Modern Treasury | https://www.moderntreasury.com/journal/accounting-for-developers-part-i |
| Shopify 반올림 | https://shopify.dev/changelog/posts/minor-rounding-change-for-custom-line-item-discounts-in-pos-115 |
| Stripe 환불 | https://docs.stripe.com/refunds |
| Stripe 멱등 | https://docs.stripe.com/api/idempotent_requests |
