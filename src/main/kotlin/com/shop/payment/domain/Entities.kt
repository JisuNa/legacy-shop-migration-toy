package com.shop.payment.domain

import jakarta.persistence.*
import java.time.Instant

// SQLite TEXT 컬럼에 ISO-8601 UTC 문자열로 저장
@Converter(autoApply = true)
class InstantToIsoString : AttributeConverter<Instant, String> {
    override fun convertToDatabaseColumn(attribute: Instant?): String? = attribute?.toString()
    override fun convertToEntityAttribute(dbData: String?): Instant? = dbData?.let(Instant::parse)
}

@MappedSuperclass
abstract class Timestamped {
    @Column(updatable = false)
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
class PaymentRequest(
    val idempotencyKey: String,
    @Enumerated(EnumType.STRING) val requestType: RequestType,
    val paymentNo: String?,
    val requestBody: String,
) : Timestamped() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    var status: RequestStatus = RequestStatus.REQUESTED
    var responseStatus: Int? = null
    var responseBody: String? = null
}

@Entity
class PaymentMaster(
    val paymentNo: String,
    val orderNo: String,
    val memberId: Long,
    val orderAmount: Long,
    val discountAmount: Long,
    val payAmount: Long,
) : Timestamped() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.APPROVED

    @OneToMany(mappedBy = "master", cascade = [CascadeType.ALL], fetch = FetchType.EAGER)
    @OrderBy("id")
    val details: MutableList<PaymentDetail> = mutableListOf()

    val canceledAmount: Long get() = details.sumOf { it.canceledAmount }
    val remainingAmount: Long get() = payAmount - canceledAmount
}

@Entity
class PaymentDetail(
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "payment_master_id")
    val master: PaymentMaster,
    @Enumerated(EnumType.STRING) val meansType: MeansType,
    val provider: String?,
    val approvedAmount: Long,
    var externalKey: String?,
) : Timestamped() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var canceledAmount: Long = 0

    val remainingAmount: Long get() = approvedAmount - canceledAmount
}

@Entity
class PaymentTransaction(
    val paymentDetailId: Long,
    val paymentRequestId: Long,
    @Enumerated(EnumType.STRING) val transactionType: TransactionType,
    val amount: Long,
    val externalTransactionKey: String?,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    val createdAt: Instant = Instant.now()
}

@Entity
class PointAccount(
    @Id val memberId: Long,
    var balance: Long,
) : Timestamped()

@Entity
class PointTransaction(
    val memberId: Long,
    val amount: Long,
    val idempotencyKey: String,
    val paymentNo: String?,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    val createdAt: Instant = Instant.now()
}

@Entity
class Outbox(
    val eventId: String,
    val eventType: String,
    val aggregateNo: String,
    val payload: String,
) : Timestamped() {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var status: String = "NEW"
}
