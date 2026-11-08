package com.shop.payment.domain

object Money {
    /** 최대잔여법: 내림 후 남은 1원들을 나머지가 큰 순(동률이면 앞 인덱스)으로 배분. 합계는 항상 total. */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0) { "total must be >= 0" }
        require(weights.isNotEmpty() && weights.all { it >= 0 }) { "weights must be non-empty and non-negative" }
        val sum = weights.sum()
        require(sum > 0) { "weights sum must be > 0" }
        val result = weights.map { Math.multiplyExact(total, it) / sum }.toMutableList()
        val left = (total - result.sum()).toInt()
        weights.indices.sortedByDescending { Math.multiplyExact(total, weights[it]) % sum }
            .take(left).forEach { result[it]++ }
        return result
    }
}
