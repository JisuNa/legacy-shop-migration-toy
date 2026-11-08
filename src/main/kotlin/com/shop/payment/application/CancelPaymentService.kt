package com.shop.payment.application

import com.shop.payment.domain.*
import com.shop.payment.gateway.GatewayTimeoutException
import com.shop.payment.gateway.PaymentGateway
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class CancelCommand(val cancelAmount: Long, val reason: String?)

@Service
class CancelPaymentService(
    private val requests: PaymentRequests,
    private val masters: PaymentMasterRepository,
    private val details: PaymentDetailRepository,
    private val transactions: PaymentTransactionRepository,
    private val outbox: OutboxRepository,
    private val points: PointLedger,
    private val gateway: PaymentGateway,
    private val json: ObjectMapper,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)

    fun cancel(key: String, paymentNo: String, cmd: CancelCommand): Outcome {
        masters.findByPaymentNo(paymentNo) ?: throw PaymentNotFoundException(paymentNo)
        val body = json.writeValueAsString(cmd)
        val (request, created) = requests.findOrCreate(key, RequestType.CANCEL, paymentNo, body)
        if (!created) {
            replayOrNull(request, key, RequestType.CANCEL, paymentNo, body)?.let { return it }
            if (!requests.claimUnknown(request.id!!)) throw RequestInFlightException(key)
        }
        val requestId = request.id!!
        return try {
            Outcome(200, tx.execute { execute(key, requestId, paymentNo, cmd.cancelAmount) }!!)
        } catch (e: PaymentRejectedException) {
            failed(requests, json, requestId, e)
        } catch (e: GatewayTimeoutException) {
            unknown(requests, json, requestId, key)
        } catch (e: RuntimeException) {
            requests.complete(requestId, RequestStatus.UNKNOWN, 500, unknownBody(json, key))
            throw e
        }
    }

    private fun execute(key: String, requestId: Long, paymentNo: String, cancelAmount: Long): String {
        val master = masters.findByPaymentNo(paymentNo)!!
        val remaining = master.remainingAmount
        if (cancelAmount <= 0 || cancelAmount > remaining) {
            throw PaymentRejectedException("취소 가능 금액 초과: 요청 $cancelAmount, 남은 금액 $remaining")
        }
        val targets = master.details.toList()
        // 전체취소는 수단별 잔액 그대로, 부분취소는 잔액 비율로 배분
        val plan = if (cancelAmount == remaining) targets.map { it.remainingAmount }
        else Money.allocate(cancelAmount, targets.map { it.remainingAmount })
        for ((detail, amount) in targets.zip(plan)) {
            if (amount <= 0) continue
            if (details.addCanceled(detail.id!!, amount, Instant.now()) != 1) throw ConcurrentCancelException(paymentNo)
            val externalTx = if (detail.meansType == MeansType.POINT) {
                points.restore(master.memberId, amount, "$key:POINT", paymentNo)
                null
            } else {
                gateway.cancel(detail.provider!!, detail.externalKey!!, amount, "$key:${detail.meansType}:${detail.provider}").externalTransactionKey
            }
            transactions.save(PaymentTransaction(detail.id!!, requestId, TransactionType.CANCEL, amount, externalTx))
        }
        // 벌크 UPDATE가 영속성 컨텍스트를 비웠으므로 다시 읽는다
        val fresh = masters.findById(master.id!!).orElseThrow()
        fresh.status = if (fresh.remainingAmount == 0L) PaymentStatus.CANCELED else PaymentStatus.PARTIALLY_CANCELED
        masters.save(fresh)
        val body = json.writeValueAsString(PaymentResponse.of(fresh))
        outbox.save(Outbox(UUID.randomUUID().toString(), "PaymentCanceled", paymentNo, body))
        requests.complete(requestId, RequestStatus.DONE, 200, body)
        return body
    }
}
