package com.shop.payment.application

import com.shop.payment.client.OrderClient
import com.shop.payment.domain.*
import com.shop.payment.gateway.GatewayTimeoutException
import com.shop.payment.gateway.PaymentGateway
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class MeansCommand(val type: MeansType, val provider: String?, val amount: Long)
data class ApproveCommand(val orderNo: String, val memberId: Long, val couponDiscount: Long, val means: List<MeansCommand>)

private val PROVIDER = Regex("^[A-Z0-9_]{1,32}$")

@Service
class ApprovePaymentService(
    private val requests: PaymentRequests,
    private val orderClient: OrderClient,
    private val masters: PaymentMasterRepository,
    private val transactions: PaymentTransactionRepository,
    private val outbox: OutboxRepository,
    private val points: PointLedger,
    private val gateway: PaymentGateway,
    private val json: ObjectMapper,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)

    fun approve(key: String, cmd: ApproveCommand): Outcome {
        val body = json.writeValueAsString(cmd)
        val (request, created) = requests.findOrCreate(key, RequestType.APPROVE, null, body)
        if (!created) {
            replayOrNull(request, key, RequestType.APPROVE, null, body)?.let { return it }
            if (!requests.claimUnknown(request.id!!)) throw RequestInFlightException(key)
        }
        val requestId = request.id!!
        return try {
            val order = orderClient.fetch(cmd.orderNo)
            validate(cmd, order.memberId, order.orderAmount, order.discountAmount)
            val response = tx.execute { execute(key, requestId, cmd, order.orderAmount, order.discountAmount) }!!
            Outcome(200, json.writeValueAsString(response))
        } catch (e: PaymentRejectedException) {
            failed(requests, json, requestId, e)
        } catch (e: DataIntegrityViolationException) {
            // order_no UNIQUE: 같은 주문이 다른 키로 동시에 들어온 경우. 외부 호출 전에 걸리므로 고아 승인이 없다
            failed(requests, json, requestId, PaymentRejectedException("이미 결제된 주문: ${cmd.orderNo}"))
        } catch (e: GatewayTimeoutException) {
            unknown(requests, json, requestId, key)
        } catch (e: RuntimeException) {
            // 주문 서비스 장애 등 예상 밖 오류: 같은 키로 재시도할 수 있게 UNKNOWN으로 남긴다
            requests.complete(requestId, RequestStatus.UNKNOWN, 500, unknownBody(json, key))
            throw e
        }
    }

    private fun validate(cmd: ApproveCommand, memberId: Long, orderAmount: Long, discount: Long) {
        fun reject(msg: String): Nothing = throw PaymentRejectedException(msg)
        if (memberId != cmd.memberId) reject("주문 회원 불일치")
        if (discount != cmd.couponDiscount) reject("쿠폰 할인 불일치: 주문 $discount, 요청 ${cmd.couponDiscount}")
        val sum = cmd.means.sumOf { it.amount }
        if (sum != orderAmount - discount) reject("결제수단 합계 불일치: 합계 $sum, 결제할 금액 ${orderAmount - discount}")
        if (cmd.means.any { it.amount <= 0 }) reject("결제 금액은 0보다 커야 합니다")
        if (cmd.means.any { it.type != MeansType.POINT && it.provider.isNullOrBlank() }) reject("외부 결제수단은 provider가 필요합니다")
        // provider는 게이트웨이 멱등키의 구성요소라 구분자(:)를 허용하면 다른 요청과 키가 겹칠 수 있다
        if (cmd.means.any { it.provider != null && !PROVIDER.matches(it.provider) }) reject("provider는 영문 대문자·숫자·_만 허용합니다")
        // 같은 수단·제휴사가 두 번 오면 게이트웨이 멱등키("$key:$type:$provider")가 겹친다
        if (cmd.means.map { it.type to it.provider }.toSet().size != cmd.means.size) reject("결제수단 중복")
    }

    /**
     * 순서가 곧 안전장치다.
     * 1) 결제 행을 먼저 INSERT해 주문을 선점한다(order_no UNIQUE). 중복 주문은 여기서 끝난다.
     * 2) 내부 포인트를 먼저 차감한다. 잔액 부족이면 외부 승인을 한 번도 호출하지 않고 롤백된다.
     * 3) 마지막에 PG·제휴사를 호출한다. 여기서 실패하면 되돌릴 외부 승인은 이미 받은 것뿐이고, 타임아웃은 UNKNOWN으로 남아 같은 키로 수렴한다.
     */
    private fun execute(key: String, requestId: Long, cmd: ApproveCommand, orderAmount: Long, discount: Long): PaymentResponse {
        if (masters.findByOrderNo(cmd.orderNo) != null) throw PaymentRejectedException("이미 결제된 주문: ${cmd.orderNo}")
        val paymentNo = Ids.next("PAY")
        val master = PaymentMaster(paymentNo, cmd.orderNo, cmd.memberId, orderAmount, discount, orderAmount - discount)
        cmd.means.forEach { m ->
            master.details += PaymentDetail(master, m.type, m.provider.takeIf { m.type != MeansType.POINT }, m.amount, null)
        }
        masters.saveAndFlush(master)

        val (internal, external) = master.details.partition { it.meansType == MeansType.POINT }
        internal.forEach { points.use(cmd.memberId, it.approvedAmount, "$key:POINT", paymentNo) }
        external.forEach {
            it.externalKey = gateway.approve(it.provider!!, cmd.orderNo, it.approvedAmount, "$key:${it.meansType}:${it.provider}").externalKey
        }
        masters.saveAndFlush(master)

        master.details.forEach {
            transactions.save(PaymentTransaction(it.id!!, requestId, TransactionType.APPROVE, it.approvedAmount, it.externalKey))
        }
        val response = PaymentResponse.of(master)
        val body = json.writeValueAsString(response)
        // outbox 행만 쓴다. relay는 이 제출물에 없고, 운영에서는 Debezium Outbox Event Router가 발행한다.
        outbox.save(Outbox(UUID.randomUUID().toString(), "PaymentApproved", paymentNo, body))
        requests.complete(requestId, RequestStatus.DONE, 200, body)
        return response
    }
}

/** 이미 있던 요청: 종류·대상 결제·본문 중 하나라도 다르면 거절, 끝난 건 저장된 응답 재생, 처리 중이면 409, UNKNOWN이면 null(이어서 재실행). */
internal fun replayOrNull(request: PaymentRequest, key: String, type: RequestType, paymentNo: String?, body: String): Outcome? {
    if (request.requestType != type || request.paymentNo != paymentNo || request.requestBody != body) {
        throw IdempotencyKeyReuseException(key)
    }
    return when (request.status) {
        RequestStatus.DONE, RequestStatus.FAILED -> Outcome(request.responseStatus!!, request.responseBody!!)
        RequestStatus.REQUESTED -> throw RequestInFlightException(key)
        RequestStatus.UNKNOWN -> null
    }
}

internal fun failed(requests: PaymentRequests, json: ObjectMapper, requestId: Long, e: PaymentRejectedException): Outcome {
    val body = json.writeValueAsString(mapOf("status" to "FAILED", "message" to e.message))
    requests.complete(requestId, RequestStatus.FAILED, e.httpStatus, body)
    return Outcome(e.httpStatus, body)
}

internal fun unknownBody(json: ObjectMapper, key: String): String = json.writeValueAsString(
    mapOf(
        "status" to "UNKNOWN",
        "idempotencyKey" to key,
        "message" to "처리 결과를 확정하지 못했습니다. 같은 Idempotency-Key로 재시도하면 이어서 처리됩니다",
    )
)

internal fun unknown(requests: PaymentRequests, json: ObjectMapper, requestId: Long, key: String): Outcome {
    val body = unknownBody(json, key)
    requests.complete(requestId, RequestStatus.UNKNOWN, 202, body)
    return Outcome(202, body)
}
