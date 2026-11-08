package com.shop.payment.api

import com.shop.payment.application.*
import com.shop.payment.domain.MeansType
import com.shop.payment.domain.PaymentMasterRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class MeansRequest(val type: MeansType, val provider: String?, @field:Positive val amount: Long)

data class ApproveRequest(
    @field:NotBlank val orderNo: String,
    val memberId: Long,
    @field:PositiveOrZero val couponDiscount: Long = 0,
    @field:NotEmpty @field:Valid val means: List<MeansRequest>,
)

data class CancelRequest(@field:Positive val cancelAmount: Long, val reason: String? = null)

@RestController
@RequestMapping("/payments")
class PaymentController(
    private val approveService: ApprovePaymentService,
    private val cancelService: CancelPaymentService,
    private val masters: PaymentMasterRepository,
) {
    @PostMapping
    fun approve(@RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody req: ApproveRequest) =
        approveService.approve(
            key,
            ApproveCommand(req.orderNo, req.memberId, req.couponDiscount, req.means.map { MeansCommand(it.type, it.provider, it.amount) }),
        ).toResponse()

    @PostMapping("/{paymentNo}/cancels")
    fun cancel(
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable paymentNo: String,
        @Valid @RequestBody req: CancelRequest,
    ) = cancelService.cancel(key, paymentNo, CancelCommand(req.cancelAmount, req.reason)).toResponse()

    @GetMapping("/{paymentNo}")
    fun get(@PathVariable paymentNo: String): PaymentResponse =
        masters.findByPaymentNo(paymentNo)?.let(PaymentResponse::of) ?: throw PaymentNotFoundException(paymentNo)

    private fun Outcome.toResponse(): ResponseEntity<String> =
        ResponseEntity.status(httpStatus).contentType(MediaType.APPLICATION_JSON).body(body)
}

@RestControllerAdvice
class PaymentExceptionHandler {
    @ExceptionHandler
    fun notFound(e: PaymentNotFoundException) = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler
    fun inFlight(e: RequestInFlightException) = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler
    fun keyReuse(e: IdempotencyKeyReuseException) = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.message)
}
