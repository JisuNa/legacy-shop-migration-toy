package com.shop.payment.application

import com.shop.payment.domain.PointAccountRepository
import com.shop.payment.domain.PointTransaction
import com.shop.payment.domain.PointTransactionRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** 호출자 트랜잭션에 참여한다. 잔액은 조건부 UPDATE, 이력은 INSERT-only 원장. */
@Service
class PointLedger(private val accounts: PointAccountRepository, private val transactions: PointTransactionRepository) {
    fun use(memberId: Long, amount: Long, key: String, paymentNo: String) {
        if (accounts.deduct(memberId, amount, Instant.now()) != 1) throw InsufficientPointException(memberId, amount)
        transactions.save(PointTransaction(memberId, -amount, key, paymentNo))
    }

    fun restore(memberId: Long, amount: Long, key: String, paymentNo: String) {
        check(accounts.refund(memberId, amount, Instant.now()) == 1) { "포인트 계정 없음: $memberId" }
        transactions.save(PointTransaction(memberId, amount, key, paymentNo))
    }
}
