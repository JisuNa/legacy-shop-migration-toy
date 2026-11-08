package com.shop.payment

import com.shop.payment.domain.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class MoneyAllocateTest : FunSpec({
    test("합계 불변식: 무작위 1,000건") {
        val rnd = Random(42)
        repeat(1000) {
            val total = rnd.nextLong(0, 10_000_000)
            val weights = List(rnd.nextInt(1, 6)) { rnd.nextLong(0, 1_000_000) }.let { w ->
                if (w.sum() == 0L) w.mapIndexed { i, v -> if (i == 0) 1 else v } else w
            }
            val parts = Money.allocate(total, weights)
            parts.sum() shouldBe total
            parts.all { it >= 0 } shouldBe true
        }
    }

    test("35,833 사례: 카드 79,000 + 포인트 7,000 잔액 비율") {
        // 32,916.36 / 2,916.64 -> 나머지가 큰 포인트가 1원을 받는다
        Money.allocate(35833, listOf(79000, 7000)) shouldBe listOf(32916L, 2917L)
    }

    test("Shopify 500/3 사례") {
        Money.allocate(500, listOf(1, 1, 1)) shouldBe listOf(167L, 167L, 166L)
    }

    test("가중치 0은 0을 받는다") {
        Money.allocate(1000, listOf(0, 3, 0, 7)) shouldBe listOf(0L, 300L, 0L, 700L)
    }

    test("가중치가 모두 0이면 예외") {
        shouldThrow<IllegalArgumentException> { Money.allocate(100, listOf(0, 0)) }
    }
})
