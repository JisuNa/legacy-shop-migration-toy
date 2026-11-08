-- 결제 서비스 신규 스키마 (SQLite)
-- 날짜는 ISO 8601 UTC 문자열. 금액은 KRW 정수.

-- 승인/취소 "요청" 기록. 멱등키의 주인. 상태가 바뀌는 유일한 결제 테이블.
CREATE TABLE payment_request (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  idempotency_key  TEXT    NOT NULL UNIQUE,
  request_type     TEXT    NOT NULL,            -- APPROVE / CANCEL
  payment_no       TEXT,
  request_body     TEXT    NOT NULL,
  status           TEXT    NOT NULL,            -- REQUESTED / UNKNOWN / DONE / FAILED
  response_status  INTEGER,
  response_body    TEXT,
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL
);

-- 주문 1건의 결제
CREATE TABLE payment_master (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  payment_no       TEXT    NOT NULL UNIQUE,
  order_no         TEXT    NOT NULL UNIQUE,
  member_id        INTEGER NOT NULL,
  order_amount     INTEGER NOT NULL,
  discount_amount  INTEGER NOT NULL,            -- 쿠폰 등 주문 할인 (결제수단 아님)
  pay_amount       INTEGER NOT NULL,
  status           TEXT    NOT NULL,            -- APPROVED / PARTIALLY_CANCELED / CANCELED
  created_at       TEXT    NOT NULL,
  updated_at       TEXT    NOT NULL,
  CHECK (pay_amount = order_amount - discount_amount)
);

-- 결제수단별 승인분. 취소 누계는 승인액을 넘지 못한다.
CREATE TABLE payment_detail (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  payment_master_id  INTEGER NOT NULL REFERENCES payment_master(id),
  means_type         TEXT    NOT NULL,          -- PG / POINT / MILEAGE
  provider           TEXT,                      -- TOSS, OKCASHBAG ... (POINT는 NULL)
  approved_amount    INTEGER NOT NULL,
  canceled_amount    INTEGER NOT NULL DEFAULT 0,
  external_key       TEXT,                      -- PG paymentKey / 제휴사 승인번호
  created_at         TEXT    NOT NULL,
  updated_at         TEXT    NOT NULL,
  CHECK (approved_amount > 0 AND canceled_amount >= 0 AND canceled_amount <= approved_amount)
);

-- 원장: 승인·취소 거래 1건당 1행. INSERT-only. 취소는 새 행이지 승인 행의 수정이 아니다.
CREATE TABLE payment_transaction (
  id                        INTEGER PRIMARY KEY AUTOINCREMENT,
  payment_detail_id         INTEGER NOT NULL REFERENCES payment_detail(id),
  payment_request_id        INTEGER NOT NULL REFERENCES payment_request(id),
  transaction_type          TEXT    NOT NULL,   -- APPROVE / CANCEL
  amount                    INTEGER NOT NULL CHECK (amount > 0),
  external_transaction_key  TEXT,
  created_at                TEXT    NOT NULL
);

-- 내부 포인트 잔액(조회용 캐시)과 원장
CREATE TABLE point_account (
  member_id   INTEGER PRIMARY KEY,
  balance     INTEGER NOT NULL CHECK (balance >= 0),
  created_at  TEXT    NOT NULL,
  updated_at  TEXT    NOT NULL
);

CREATE TABLE point_transaction (                -- INSERT-only. 잔액 변동은 모두 여기 남는다(이관 기초잔액은 제외)
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  member_id        INTEGER NOT NULL REFERENCES point_account(member_id),
  amount           INTEGER NOT NULL,            -- 사용 음수 / 복원 양수
  idempotency_key  TEXT    NOT NULL UNIQUE,
  payment_no       TEXT,
  created_at       TEXT    NOT NULL
);

-- 이벤트 발행함. 운영에서는 Debezium Outbox Event Router가 읽는다.
CREATE TABLE outbox (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id      TEXT NOT NULL UNIQUE,
  event_type    TEXT NOT NULL,                  -- PaymentApproved / PaymentCanceled
  aggregate_no  TEXT NOT NULL,                  -- payment_no
  payload       TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'NEW',    -- NEW / SENT
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

CREATE INDEX idx_payment_detail_master ON payment_detail(payment_master_id);
CREATE INDEX idx_payment_transaction_detail ON payment_transaction(payment_detail_id);
CREATE INDEX idx_point_transaction_member ON point_transaction(member_id);
CREATE INDEX idx_outbox_status ON outbox(status, id);
