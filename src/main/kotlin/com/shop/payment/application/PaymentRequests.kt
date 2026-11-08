package com.shop.payment.application

import com.shop.payment.domain.PaymentRequest
import com.shop.payment.domain.PaymentRequestRepository
import com.shop.payment.domain.RequestStatus
import com.shop.payment.domain.RequestType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Service
class PaymentRequests(private val repo: PaymentRequestRepository, txManager: PlatformTransactionManager) {
    private val newTx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val tx = TransactionTemplate(txManager)

    /** 멱등키로 요청을 찾거나 새로 기록한다. second = 이번에 새로 만들었는지. */
    fun findOrCreate(key: String, type: RequestType, paymentNo: String?, body: String): Pair<PaymentRequest, Boolean> {
        repo.findByIdempotencyKey(key)?.let { return it to false }
        return try {
            newTx.execute { repo.saveAndFlush(PaymentRequest(key, type, paymentNo, body)) }!! to true
        } catch (e: DataIntegrityViolationException) {
            // 같은 키가 동시에 들어와 UNIQUE에 걸린 경우: 먼저 들어온 쪽을 따른다
            repo.findByIdempotencyKey(key)!! to false
        }
    }

    /** UNKNOWN 요청을 동시에 재시도하면 한 요청만 재실행하고 나머지는 처리 중(409)으로 돌려보낸다 */
    fun claimUnknown(id: Long): Boolean =
        newTx.execute { repo.transition(id, RequestStatus.UNKNOWN, RequestStatus.REQUESTED, java.time.Instant.now()) } == 1

    fun complete(id: Long, status: RequestStatus, httpStatus: Int, body: String) {
        tx.executeWithoutResult {
            repo.findById(id).orElseThrow().apply {
                if (this.status == RequestStatus.DONE) return@apply   // 확정된 결과는 덮어쓰지 않는다
                this.status = status
                responseStatus = httpStatus
                responseBody = body
            }
        }
    }
}
