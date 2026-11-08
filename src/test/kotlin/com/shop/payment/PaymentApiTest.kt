package com.shop.payment

import com.shop.payment.domain.*
import com.shop.payment.gateway.FakePaymentGateway
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentApiTest : FunSpec() {

    @LocalServerPort var port = 0
    @Autowired lateinit var gateway: FakePaymentGateway
    @Autowired lateinit var masters: PaymentMasterRepository
    @Autowired lateinit var transactions: PaymentTransactionRepository
    @Autowired lateinit var accounts: PointAccountRepository
    @Autowired lateinit var pointTransactions: PointTransactionRepository
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var json: ObjectMapper

    private val http by lazy {
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }) { _, _ -> }.build()
    }

    private fun post(path: String, key: String?, body: Any): ResponseEntity<String> =
        http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .apply { if (key != null) header("Idempotency-Key", key) }
            .body(body).retrieve().toEntity(String::class.java)

    private fun approve(key: String, orderNo: String, means: List<Map<String, Any>>, coupon: Long = 0) =
        post("/payments", key, mapOf("orderNo" to orderNo, "memberId" to 1002, "couponDiscount" to coupon, "means" to means))

    private fun cancel(key: String, paymentNo: String, amount: Long) =
        post("/payments/$paymentNo/cancels", key, mapOf("cancelAmount" to amount))

    private fun tree(r: ResponseEntity<String>): JsonNode = json.readTree(r.body)
    private fun key() = UUID.randomUUID().toString()
    private fun order(prefix: String) = "$prefix-${UUID.randomUUID()}"
    private fun balance(member: Long) = accounts.findById(member).get().balance
    private fun pg(provider: String, amount: Long) = mapOf("type" to "PG", "provider" to provider, "amount" to amount)
    private fun mileage(provider: String, amount: Long) = mapOf("type" to "MILEAGE", "provider" to provider, "amount" to amount)
    private fun point(amount: Long) = mapOf("type" to "POINT", "amount" to amount)
    private fun master(paymentNo: String) = masters.findByPaymentNo(paymentNo)!!
    private fun txs(paymentNo: String) = transactions.findAllByPaymentDetailIdIn(master(paymentNo).details.map { it.id!! })
    private fun canceledSum(paymentNo: String) =
        txs(paymentNo).filter { it.transactionType == TransactionType.CANCEL }.sumOf { it.amount }

    /** 96K 주문: 카드 79,000 + 포인트 7,000, 쿠폰 10,000 */
    private fun approve96k(): String {
        val r = approve(key(), order("ORD-96K"), listOf(pg("TOSS", 79000), point(7000)), coupon = 10000)
        r.statusCode.value() shouldBe 200
        return tree(r)["paymentNo"].asString()
    }

    init {
        extension(SpringExtension())

        test("복합 승인: PG + 포인트 + 마일리지") {
            val before = balance(1002)
            val r = approve(key(), order("ORD-36K"), listOf(pg("TOSS", 20000), point(6000), mileage("OKCASHBAG", 10000)))
            r.statusCode.value() shouldBe 200
            val paymentNo = tree(r)["paymentNo"].asString()

            val got = http.get().uri("/payments/$paymentNo").retrieve().toEntity(String::class.java)
            tree(got)["means"].size() shouldBe 3
            txs(paymentNo).filter { it.transactionType == TransactionType.APPROVE } shouldHaveSize 3
            balance(1002) shouldBe before - 6000
            outbox.findAllByAggregateNo(paymentNo).map { it.eventType } shouldBe listOf("PaymentApproved")
        }

        test("멱등: 같은 키 두 번이면 같은 응답, 부수효과 없음") {
            val k = key()
            val orderNo = order("ORD-36K")
            val means = listOf(pg("TOSS", 20000), point(6000), mileage("OKCASHBAG", 10000))
            val first = approve(k, orderNo, means)
            val paymentNo = tree(first)["paymentNo"].asString()
            val txCount = txs(paymentNo).size
            val approvals = gateway.approvalCount

            val second = approve(k, orderNo, means)
            second.statusCode.value() shouldBe 200
            second.body shouldBe first.body
            txs(paymentNo).size shouldBe txCount
            gateway.approvalCount shouldBe approvals

            // 같은 키에 다른 본문은 재생하지 않고 거절한다
            approve(k, orderNo, listOf(pg("TOSS", 36000))).statusCode.value() shouldBe 422
            gateway.approvalCount shouldBe approvals
        }

        test("35,833 부분취소: 잔액 비율 배분 + 포인트 복원") {
            val paymentNo = approve96k()
            cancel(key(), paymentNo, 35833).statusCode.value() shouldBe 200

            val m = master(paymentNo)
            m.details.first { it.meansType == MeansType.PG }.canceledAmount shouldBe 32916
            m.details.first { it.meansType == MeansType.POINT }.canceledAmount shouldBe 2917
            m.status shouldBe PaymentStatus.PARTIALLY_CANCELED
            pointTransactions.findAllByMemberId(1002).filter { it.paymentNo == paymentNo }.map { it.amount } shouldContain 2917L
            canceledSum(paymentNo) shouldBe 35833
        }

        test("환불 누계는 승인액을 넘지 못한다") {
            val paymentNo = approve96k()
            cancel(key(), paymentNo, 35833).statusCode.value() shouldBe 200
            cancel(key(), paymentNo, 60000).statusCode.value() shouldBe 422
            cancel(key(), paymentNo, 50167).statusCode.value() shouldBe 200
            master(paymentNo).status shouldBe PaymentStatus.CANCELED
            cancel(key(), paymentNo, 1).statusCode.value() shouldBe 422
            master(paymentNo).details.forEach { it.canceledAmount shouldBe it.approvedAmount }
        }

        test("거절 경로") {
            // 결제수단 합계 불일치 -> 422, 같은 키 재요청은 같은 422
            val k = key()
            val badOrder = order("ORD-36K")
            val bad = approve(k, badOrder, listOf(pg("TOSS", 1000)))
            bad.statusCode.value() shouldBe 422
            tree(bad)["status"].asString() shouldBe "FAILED"
            val replay = approve(k, badOrder, listOf(pg("TOSS", 1000)))
            replay.statusCode.value() shouldBe 422
            replay.body shouldBe bad.body

            // 포인트 부족: 회원 1003 잔액 0
            val approvals = gateway.approvalCount
            val noPoint = order("ORD-80K")
            post("/payments", key(), mapOf("orderNo" to noPoint, "memberId" to 1003, "means" to listOf(point(80000))))
                .statusCode.value() shouldBe 422
            masters.findByOrderNo(noPoint).shouldBeNull()
            gateway.approvalCount shouldBe approvals

            approve(key(), "ORD-MISSING", listOf(pg("TOSS", 1000))).statusCode.value() shouldBe 422
            cancel(key(), "PAY-NOPE", 1000).statusCode.value() shouldBe 404
            approve(key(), order("ORD-36K"), listOf(pg("TOSS", 36000))).statusCode.value() shouldBe 200
            post("/payments", null, mapOf("orderNo" to order("ORD-36K"), "memberId" to 1002, "means" to listOf(pg("TOSS", 36000))))
                .statusCode.value() shouldBe 400
        }

        test("외부 승인 전에 포인트를 먼저 차감한다: 포인트 부족이면 PG 호출 없이 거절") {
            val approvals = gateway.approvalCount
            val orderNo = order("ORD-80K")   // 회원 1003, 잔액 0
            val r = post("/payments", key(), mapOf("orderNo" to orderNo, "memberId" to 1003, "means" to listOf(pg("TOSS", 70000), point(10000))))
            r.statusCode.value() shouldBe 422
            masters.findByOrderNo(orderNo).shouldBeNull()
            gateway.approvalCount shouldBe approvals
        }

        test("같은 주문의 두 번째 승인은 PG 호출 전에 거절") {
            val orderNo = order("ORD-36K")
            approve(key(), orderNo, listOf(pg("TOSS", 36000))).statusCode.value() shouldBe 200
            val approvals = gateway.approvalCount
            val dup = approve(key(), orderNo, listOf(pg("TOSS", 36000)))
            dup.statusCode.value() shouldBe 422
            tree(dup)["message"].asString() shouldContain "이미 결제된 주문"
            gateway.approvalCount shouldBe approvals
        }

        test("PG와 제휴 포인트가 같은 제휴사여도 각각 승인된다") {
            val approvals = gateway.approvalCount
            val r = approve(key(), order("ORD-36K"), listOf(pg("TOSS", 26000), mileage("TOSS", 10000)))
            r.statusCode.value() shouldBe 200
            gateway.approvalCount shouldBe approvals + 2
        }

        test("provider에 구분자가 들어오면 PG 호출 없이 거절") {
            val approvals = gateway.approvalCount
            approve(key(), order("ORD-36K"), listOf(pg("X:PG:TOSS", 36000))).statusCode.value() shouldBe 422
            gateway.approvalCount shouldBe approvals
        }

        test("같은 멱등키를 다른 결제의 취소에 쓰면 거절") {
            val first = approve96k()
            val second = approve96k()
            val k = key()
            cancel(k, first, 1000).statusCode.value() shouldBe 200
            cancel(k, second, 1000).statusCode.value() shouldBe 422
            master(second).canceledAmount shouldBe 0
        }

        test("동시 전체취소: 하나만 성공") {
            val paymentNo = approve96k()
            val latch = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val futures = List(2) { pool.submit<Int> { latch.await(); cancel(key(), paymentNo, 86000).statusCode.value() } }
            latch.countDown()
            val statuses = futures.map { it.get() }.sorted()
            pool.shutdown()

            statuses[0] shouldBe 200
            (statuses[1] == 409 || statuses[1] == 422) shouldBe true
            master(paymentNo).canceledAmount shouldBe 86000
            canceledSum(paymentNo) shouldBe 86000
        }

        test("UNKNOWN 요청을 같은 키로 동시에 재시도해도 한 번만 처리") {
            val k = key()
            val orderNo = order("ORD-36K")
            val means = listOf(point(6000), pg("TOSS", 30000))
            val before = balance(1002)
            gateway.nextFault = FakePaymentGateway.Fault.NOT_RECORDED
            approve(k, orderNo, means).statusCode.value() shouldBe 202

            val approvals = gateway.approvalCount
            val latch = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            val futures = List(4) { pool.submit<Int> { latch.await(); approve(k, orderNo, means).statusCode.value() } }
            latch.countDown()
            val statuses = futures.map { it.get() }
            pool.shutdown()

            statuses.all { it == 200 || it == 409 } shouldBe true
            (statuses.count { it == 200 } >= 1) shouldBe true
            gateway.approvalCount shouldBe approvals + 1
            balance(1002) shouldBe before - 6000
            txs(masters.findByOrderNo(orderNo)!!.paymentNo).size shouldBe 2
        }

        for (fault in listOf(FakePaymentGateway.Fault.APPROVED_BUT_LOST, FakePaymentGateway.Fault.NOT_RECORDED)) {
            test("PG 타임아웃($fault): 202 UNKNOWN 후 같은 키 재시도로 완료") {
                val k = key()
                val orderNo = order("ORD-36K")
                // 포인트를 먼저 차감한 뒤 PG가 끊기도록 순서를 둔다
                val means = listOf(point(6000), pg("TOSS", 20000), mileage("OKCASHBAG", 10000))
                val before = balance(1002)
                val approvals = gateway.approvalCount

                gateway.nextFault = fault
                val first = approve(k, orderNo, means)
                first.statusCode.value() shouldBe 202
                tree(first)["status"].asString() shouldBe "UNKNOWN"
                masters.findByOrderNo(orderNo).shouldBeNull()
                balance(1002) shouldBe before

                approve(k, orderNo, means).statusCode.value() shouldBe 200
                pointTransactions.findByIdempotencyKey("$k:POINT")!!.amount shouldBe -6000
                balance(1002) shouldBe before - 6000
                gateway.approvalCount shouldBe approvals + 2
            }
        }
    }

    companion object {
        private val wireMock = WireMockServer(options().port(18089)).apply {
            start()
            stubFor(get(urlMatching("/orders/ORD-96K-.*")).willReturn(okJson("""{"orderNo":"ORD-96K","memberId":1002,"orderAmount":96000,"discountAmount":10000}""")))
            stubFor(get(urlMatching("/orders/ORD-36K-.*")).willReturn(okJson("""{"orderNo":"ORD-36K","memberId":1002,"orderAmount":36000,"discountAmount":0}""")))
            stubFor(get(urlMatching("/orders/ORD-80K-.*")).willReturn(okJson("""{"orderNo":"ORD-80K","memberId":1003,"orderAmount":80000,"discountAmount":0}""")))
            stubFor(get(urlEqualTo("/orders/ORD-MISSING")).willReturn(notFound()))
        }
    }
}
