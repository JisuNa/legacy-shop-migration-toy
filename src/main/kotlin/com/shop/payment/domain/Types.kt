package com.shop.payment.domain

import java.util.concurrent.ThreadLocalRandom

enum class MeansType { PG, POINT, MILEAGE }
enum class PaymentStatus { APPROVED, PARTIALLY_CANCELED, CANCELED }
enum class RequestType { APPROVE, CANCEL }
enum class RequestStatus { REQUESTED, UNKNOWN, DONE, FAILED }
enum class TransactionType { APPROVE, CANCEL }

object Ids {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun next(prefix: String): String {
        val rnd = ThreadLocalRandom.current()
        val suffix = (1..6).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
        return "$prefix-${System.currentTimeMillis().toString(36).uppercase()}-$suffix"
    }
}
