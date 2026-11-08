package com.shop.payment.client

import com.shop.payment.application.PaymentRejectedException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@ConfigurationProperties("order")
data class OrderProperties(val baseUrl: String)

data class OrderSnapshot(val orderNo: String, val memberId: Long, val orderAmount: Long, val discountAmount: Long)

class OrderNotFoundException(orderNo: String) : PaymentRejectedException("주문 없음: $orderNo")

@Component
class OrderClient(builder: RestClient.Builder, props: OrderProperties) {
    private val client = builder.baseUrl(props.baseUrl).build()

    fun fetch(orderNo: String): OrderSnapshot =
        client.get().uri("/orders/{orderNo}", orderNo).retrieve()
            .onStatus({ it == HttpStatus.NOT_FOUND }) { _, _ -> throw OrderNotFoundException(orderNo) }
            .body<OrderSnapshot>()!!
}
