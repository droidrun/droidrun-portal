package com.mobilerun.portal.sms

/**
 * SMS processing state, named to match the numbers-api / SMS Gate contract.
 * Reported back to the cloud verbatim via PATCH .../sms/status.
 */
enum class SmsState(val api: String) {
    PENDING("Pending"),
    PROCESSED("Processed"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    FAILED("Failed"),
}

/** An outbound send task pulled from numbers-api. */
data class PendingSend(
    val sendTaskId: String,
    val text: String,
    val phoneNumbers: List<String>,
    val simNumber: Int?,
    val withDeliveryReport: Boolean,
    val validUntilMs: Long?,
)

/** A received SMS captured on the device, queued for upload. */
data class InboundSms(
    val localId: String,
    val sender: String,
    val recipient: String?,
    val body: String,
    val simNumber: Int?,
    val subscriptionId: Int?,
    val receivedAtMs: Long,
)
