package com.shop.payment.application

import com.shop.payment.domain.MeansType
import com.shop.payment.domain.PaymentMaster
import com.shop.payment.domain.PaymentStatus

data class MeansResponse(val type: MeansType, val provider: String?, val approvedAmount: Long, val canceledAmount: Long)

data class PaymentResponse(
    val status: PaymentStatus,
    val paymentNo: String,
    val orderNo: String,
    val memberId: Long,
    val orderAmount: Long,
    val discountAmount: Long,
    val payAmount: Long,
    val canceledAmount: Long,
    val remainingAmount: Long,
    val means: List<MeansResponse>,
) {
    companion object {
        fun of(master: PaymentMaster) = PaymentResponse(
            master.status, master.paymentNo, master.orderNo, master.memberId, master.orderAmount,
            master.discountAmount, master.payAmount, master.canceledAmount, master.remainingAmount,
            master.details.map { MeansResponse(it.meansType, it.provider, it.approvedAmount, it.canceledAmount) },
        )
    }
}
