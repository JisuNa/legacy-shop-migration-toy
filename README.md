# 복합결제 원장과 부분취소 (payment-service)

장바구니 → 주문 → 결제 흐름 재설계 과제의 제출물입니다. 설계(요구사항 1·2)는 [DESIGN.md](DESIGN.md)에 있고, 이 저장소의 코드는 **요구사항 3(가장 위험한 지점의 실행 증명)**인 결제 서비스입니다.

## 1. 실행 방법

### 준비

| 항목 | 값 |
|---|---|
| JDK | 21 (`java -version`으로 확인) |
| 빌드 | Gradle wrapper 포함. 별도 설치 불필요 |
| 저장소 | SQLite. 별도 DB 설치 불필요 |
| 네트워크 | 첫 실행 시 의존성 다운로드 |

### 소스 받기

둘 중 하나를 고릅니다.

1. git checkout
```bash
git clone https://github.com/JisuNa/legacy-shop-migration-toy.git
cd legacy-shop-migration-toy
```

2. Release에서 소스 ZIP 다운로드 

_[Release 링크](https://github.com/JisuNa/legacy-shop-migration-toy/releases/tag/v0.0.1)_

```bash
unzip legacy-shop-migration-toy-0.0.1.zip
cd legacy-shop-migration-toy-0.0.1
chmod +x gradlew   # 압축 해제 도구에 따라 실행 권한이 빠질 수 있다
```

### 테스트 실행 (요구사항 3 증명)

```bash
./gradlew test          # macOS, Linux
gradlew.bat test        # Windows
```

| 확인 | 기대 결과 |
|---|---|
| 콘솔 | `BUILD SUCCESSFUL`, 테스트 19개 통과 |
| 결과 보고서 | `build/reports/tests/test/index.html` |

| 테스트 클래스 | 개수 | 검증 |
|---|---|---|
| `MoneyAllocateTest` | 5 | 부분취소 금액의 수단별 배분. 35,833원 → 카드 32,916 / 포인트 2,917 |
| `PaymentApiTest` | 14 | 복합 승인, 멱등 재요청, 부분취소, 환불 한도, 동시 취소, PG 타임아웃 후 재시도 |

테스트마다 임시 SQLite 파일을 새로 만들므로 반복 실행해도 결과가 같습니다. 시나리오별 상세와 대응하는 레거시 결함은 [DESIGN.md 4.4](DESIGN.md#44-테스트)에 있습니다.

## 2. 서버로 직접 실행 (선택)

결제 서비스는 승인 전에 주문 서비스에서 주문 금액을 조회합니다. 주문 서비스는 설계만 있으므로, 직접 호출해 보려면 주문 조회 응답을 흉내 내는 스텁을 먼저 띄웁니다.

```bash
# 터미널 1: 주문 서비스 스텁 (포트 8081, 모든 주문을 96,000원 / 쿠폰 10,000원 / 회원 1002로 응답)
python3 -c '
import http.server, json
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(s):
        b = json.dumps({"orderNo": s.path.rsplit("/",1)[-1], "memberId": 1002,
                        "orderAmount": 96000, "discountAmount": 10000}).encode()
        s.send_response(200); s.send_header("Content-Type","application/json"); s.end_headers(); s.wfile.write(b)
http.server.HTTPServer(("", 8081), H).serve_forever()'
```

```bash
# 터미널 2: 결제 서비스 (포트 8082, data/payment.db 생성)
./gradlew bootRun
```

레거시 `ORD20260301005` 사례(카드 79,000 + 포인트 7,000 결제 후 35,833원 부분취소)를 재현합니다.

```bash
# 1) 복합 승인. 응답의 paymentNo를 확인한다
curl -s -X POST localhost:8082/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: pay-1' \
  -d '{"orderNo":"ORD20260301005","memberId":1002,"couponDiscount":10000,
       "means":[{"type":"PG","provider":"TOSS","amount":79000},
                {"type":"POINT","amount":7000}]}'

# 2) 부분취소 35,833원 → 카드 32,916 / 포인트 2,917로 나뉜다
curl -s -X POST localhost:8082/payments/<paymentNo>/cancels \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: cancel-1' \
  -d '{"cancelAmount":35833}'

# 3) 조회
curl -s localhost:8082/payments/<paymentNo>
```

| API | 설명 |
|---|---|
| `POST /payments` | 복합 승인. `Idempotency-Key` 헤더 필수 |
| `POST /payments/{paymentNo}/cancels` | 부분·전체 취소. `Idempotency-Key` 헤더 필수 |
| `GET /payments/{paymentNo}` | 결제와 수단별 승인·취소 금액 조회 |

| 응답 코드 | 의미 |
|---|---|
| 200 | 처리 완료. 같은 키로 다시 요청하면 저장된 응답을 그대로 돌려준다 |
| 202 | PG 응답 없음(UNKNOWN). 같은 키로 재시도하면 이어서 처리된다 |
| 400 | `Idempotency-Key` 누락, 요청 본문 오류 |
| 404 | 결제 없음 |
| 409 | 같은 키 요청이 처리 중, 동시 취소 충돌 |
| 422 | 금액 불일치, 포인트 부족, 이미 결제된 주문, 취소 한도 초과, 같은 키에 다른 요청, 허용되지 않는 provider |

## 3. 프로젝트 구조

```
src/main/kotlin/.../payment/
  domain/        결제·수단·원장 엔티티, 금액 배분(Money.allocate)
  gateway/       PG·제휴사 게이트웨이와 테스트용 인메모리 구현
  client/        주문 서비스 조회
  application/   승인·취소 처리, 멱등 처리
  api/           REST API, 오류 응답
src/main/resources/db/migration/   스키마(V1), 포인트 초기 데이터(V2)
src/test/                          MoneyAllocateTest, PaymentApiTest
```

## 4. 문서

| 파일 | 내용 |
|---|---|
| [DESIGN.md](DESIGN.md) | 문제 정의, 전체 구조, 레거시·신규 DB 분리와 전환 단계, 세운 가정 |
| [adr/](adr/) | 핵심 설계 결정 3건: 결제 원장과 부분취소, 레거시·신규 DB 공존, 재고 선점 |
| [AI_REFLECTION.md](AI_REFLECTION.md) | AI에 맡긴 것, 직접 판단한 것, 거부·수정한 제안 |
| [prompts/used_prompts.md](prompts/used_prompts.md) | 사용한 프롬프트와 설계 인터뷰 기록 |
