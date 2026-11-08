package com.shop.payment.application

open class PaymentRejectedException(message: String, val httpStatus: Int = 422) : RuntimeException(message)

class InsufficientPointException(memberId: Long, amount: Long) :
    PaymentRejectedException("포인트 부족: 회원 $memberId, 요청 $amount")

class ConcurrentCancelException(paymentNo: String) :
    PaymentRejectedException("동시 취소 충돌: $paymentNo", 409)

class RequestInFlightException(key: String) : RuntimeException("같은 Idempotency-Key 요청이 처리 중입니다: $key")

class PaymentNotFoundException(paymentNo: String) : RuntimeException("결제 없음: $paymentNo")

data class Outcome(val httpStatus: Int, val body: String)

class IdempotencyKeyReuseException(key: String) :
    RuntimeException("같은 Idempotency-Key로 다른 요청 본문이 왔습니다: $key")
