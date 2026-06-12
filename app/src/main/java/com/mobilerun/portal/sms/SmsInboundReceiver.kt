package com.mobilerun.portal.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Captures incoming SMS, persists it durably, and pokes the sync worker to
 * upload it to numbers-api. Registered dynamically by [SmsGatewayController]
 * while the feature is enabled (parallel to the trigger engine's receiver).
 */
class SmsInboundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val first = messages.first()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val sender = first.originatingAddress.orEmpty()
        val receivedAt = first.timestampMillis
        val subscriptionId = SimHelper.extractSubscriptionId(context, intent)
        val simNumber = SimHelper.simNumberForSubscription(context, subscriptionId)

        // Stable local id (dedups redelivered broadcasts) → also the cloud dedup key.
        val localId = "rx:" + (sender + "|" + receivedAt + "|" + body).hashCode().toUInt().toString(16)

        val stored = SmsStore.getInstance(context).insertInbound(
            InboundSms(
                localId = localId,
                sender = sender,
                recipient = null,
                body = body,
                simNumber = simNumber,
                subscriptionId = subscriptionId,
                receivedAtMs = receivedAt,
            ),
        )
        if (stored) SmsGatewayController.poke(context.applicationContext)
    }
}
