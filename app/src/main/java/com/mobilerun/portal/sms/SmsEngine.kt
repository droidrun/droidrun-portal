package com.mobilerun.portal.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log

/**
 * Sends SMS via SmsManager and decodes sent/delivered results — the device
 * telephony mechanics, ported from the OSS SMS Gate app. State is persisted in
 * [SmsStore]; the sync worker reports it to numbers-api.
 */
object SmsEngine {
    private const val TAG = "SmsEngine"
    const val ACTION_SENT = "com.mobilerun.portal.SMS_SENT"
    const val ACTION_DELIVERED = "com.mobilerun.portal.SMS_DELIVERED"

    /** Send one task's recipients. Dedups on sendTaskId — a sent task is never re-sent. */
    fun send(context: Context, store: SmsStore, send: PendingSend) {
        if (send.validUntilMs != null && send.validUntilMs < System.currentTimeMillis()) {
            store.insertOutboundIfAbsent(send, send.phoneNumbers.firstOrNull() ?: "")
            store.setOutboundState(send.sendTaskId, SmsState.FAILED, "TTL expired")
            return
        }
        for (phone in send.phoneNumbers) {
            val fresh = store.insertOutboundIfAbsent(send, phone)
            val current = store.outboundState(send.sendTaskId)
            // Already past PENDING (claimed/sent) → don't re-dispatch to the carrier.
            if (!fresh && current != null && current != SmsState.PENDING.api) continue

            try {
                dispatch(context, store, send, phone)
                store.setOutboundState(send.sendTaskId, SmsState.PROCESSED)
            } catch (e: Exception) {
                Log.e(TAG, "send failed for ${send.sendTaskId}", e)
                store.setOutboundState(send.sendTaskId, SmsState.FAILED, e.message)
            }
        }
    }

    private fun dispatch(context: Context, store: SmsStore, send: PendingSend, phone: String) {
        val smsManager = SimHelper.getSmsManager(context, send.simNumber)
        val key = Uri.parse("${send.sendTaskId}|$phone")
        val sentPi = broadcast(context, ACTION_SENT, key)
        val deliveredPi = if (send.withDeliveryReport) broadcast(context, ACTION_DELIVERED, key) else null

        val parts = smsManager.divideMessage(send.text)
        store.setPartsCount(send.sendTaskId, parts.size)
        if (parts.size > 1) {
            // Same PendingIntent per part (the OSS app does the same); Android fires it once per part.
            val sentList = ArrayList<PendingIntent>(parts.size).apply { repeat(parts.size) { add(sentPi) } }
            val deliveredList = deliveredPi?.let { pi ->
                ArrayList<PendingIntent>(parts.size).apply { repeat(parts.size) { add(pi) } }
            }
            smsManager.sendMultipartTextMessage(phone, null, parts, sentList, deliveredList)
        } else {
            smsManager.sendTextMessage(phone, null, send.text, sentPi, deliveredPi)
        }
    }

    private fun broadcast(context: Context, action: String, data: Uri): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(action, data, context, SmsResultReceiver::class.java),
            PendingIntent.FLAG_MUTABLE,
        )

    /** Decode an ACTION_SENT / ACTION_DELIVERED broadcast and persist the new state. */
    fun processResult(context: Context, intent: Intent, resultCode: Int) {
        val sendTaskId = intent.dataString?.substringBefore('|') ?: return
        val store = SmsStore.getInstance(context)
        when (intent.action) {
            ACTION_SENT -> if (resultCode == Activity.RESULT_OK) {
                store.setOutboundState(sendTaskId, SmsState.SENT)
            } else {
                store.setOutboundState(sendTaskId, SmsState.FAILED, "send result $resultCode")
            }

            ACTION_DELIVERED -> {
                if (resultCode != Activity.RESULT_OK) {
                    store.setOutboundState(sendTaskId, SmsState.FAILED, "delivery result $resultCode")
                    return
                }
                val pdu = intent.getByteArrayExtra("pdu") ?: return
                @Suppress("DEPRECATION")
                val status = SmsMessage.createFromPdu(pdu)?.status ?: return
                when {
                    status.toUInt() < 0x20u -> store.setOutboundState(sendTaskId, SmsState.DELIVERED)
                    status.toUInt() < 0x40u -> Unit // 0x20–0x3F: SC still trying, ignore
                    else -> store.setOutboundState(sendTaskId, SmsState.FAILED, "delivery status $status")
                }
            }
        }
    }
}
