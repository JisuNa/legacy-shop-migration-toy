package com.shop.payment.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PaymentRequestRepository : JpaRepository<PaymentRequest, Long> {
    fun findByIdempotencyKey(key: String): PaymentRequest?

    /** UNKNOWN 요청의 재실행권을 한 요청만 가져간다. 1이면 선점 성공 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PaymentRequest r set r.status = :to, r.updatedAt = :now where r.id = :id and r.status = :from")
    fun transition(@Param("id") id: Long, @Param("from") from: RequestStatus, @Param("to") to: RequestStatus, @Param("now") now: Instant): Int
}

interface PaymentMasterRepository : JpaRepository<PaymentMaster, Long> {
    fun findByPaymentNo(paymentNo: String): PaymentMaster?
    fun findByOrderNo(orderNo: String): PaymentMaster?
}

interface PaymentDetailRepository : JpaRepository<PaymentDetail, Long> {
    // 취소 누계가 승인액을 넘으면 0건 갱신 (동시 취소 방어)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update PaymentDetail d set d.canceledAmount = d.canceledAmount + :amount, d.updatedAt = :now " +
            "where d.id = :id and d.canceledAmount + :amount <= d.approvedAmount"
    )
    fun addCanceled(@Param("id") id: Long, @Param("amount") amount: Long, @Param("now") now: Instant): Int
}

interface PaymentTransactionRepository : JpaRepository<PaymentTransaction, Long> {
    fun findAllByPaymentDetailIdIn(ids: Collection<Long>): List<PaymentTransaction>
}

interface PointAccountRepository : JpaRepository<PointAccount, Long> {
    @Modifying
    @Query(
        "update PointAccount p set p.balance = p.balance - :amount, p.updatedAt = :now " +
            "where p.memberId = :memberId and p.balance >= :amount"
    )
    fun deduct(@Param("memberId") memberId: Long, @Param("amount") amount: Long, @Param("now") now: Instant): Int

    @Modifying
    @Query("update PointAccount p set p.balance = p.balance + :amount, p.updatedAt = :now where p.memberId = :memberId")
    fun refund(@Param("memberId") memberId: Long, @Param("amount") amount: Long, @Param("now") now: Instant): Int
}

interface PointTransactionRepository : JpaRepository<PointTransaction, Long> {
    fun findAllByMemberId(memberId: Long): List<PointTransaction>
    fun findByIdempotencyKey(key: String): PointTransaction?
}

interface OutboxRepository : JpaRepository<Outbox, Long> {
    fun findAllByAggregateNo(aggregateNo: String): List<Outbox>
}
