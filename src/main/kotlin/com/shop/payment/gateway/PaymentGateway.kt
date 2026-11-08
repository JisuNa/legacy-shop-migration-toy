package com.shop.payment.gateway

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GatewayApproval(val provider: String, val externalKey: String, val amount: Long)
data class GatewayCancel(val externalTransactionKey: String, val amount: Long)

class GatewayTimeoutException(provider: String) : RuntimeException("$provider 응답 없음(타임아웃)")

/** 외부 결제수단(PG, 제휴 마일리지). 같은 idempotencyKey는 같은 저장 결과를 돌려준다(토스 멱등키 규약). */
interface PaymentGateway {
    fun approve(provider: String, orderNo: String, amount: Long, idempotencyKey: String): GatewayApproval
    fun cancel(provider: String, externalKey: String, amount: Long, idempotencyKey: String): GatewayCancel
}

// PG 샌드박스 대신 인메모리 가짜. 실제 연동 시 provider별 RestClient 구현으로 교체.
@Component
class FakePaymentGateway : PaymentGateway {
    enum class Fault { NONE, APPROVED_BUT_LOST, NOT_RECORDED }

    @Volatile
    var nextFault = Fault.NONE

    private val approvals = ConcurrentHashMap<String, GatewayApproval>()
    private val cancels = ConcurrentHashMap<String, GatewayCancel>()

    val approvalCount: Int get() = approvals.size
    val cancelCount: Int get() = cancels.size

    override fun approve(provider: String, orderNo: String, amount: Long, idempotencyKey: String): GatewayApproval =
        call(provider, approvals, idempotencyKey) { GatewayApproval(provider, "$provider-${UUID.randomUUID()}", amount) }

    override fun cancel(provider: String, externalKey: String, amount: Long, idempotencyKey: String): GatewayCancel =
        call(provider, cancels, idempotencyKey) { GatewayCancel("$provider-CX-${UUID.randomUUID()}", amount) }

    fun reset() {
        approvals.clear()
        cancels.clear()
        nextFault = Fault.NONE
    }

    private fun <T : Any> call(provider: String, store: ConcurrentHashMap<String, T>, key: String, create: () -> T): T {
        val fault = takeFault()
        if (fault == Fault.NOT_RECORDED) throw GatewayTimeoutException(provider)
        val result = store.computeIfAbsent(key) { create() }
        if (fault == Fault.APPROVED_BUT_LOST) throw GatewayTimeoutException(provider)
        return result
    }

    @Synchronized
    private fun takeFault(): Fault = nextFault.also { nextFault = Fault.NONE }
}
