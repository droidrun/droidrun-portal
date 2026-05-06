package com.mobilerun.portal.triggers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject

class TriggerSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        TriggerRuntime.initialize(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val messageBody = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val address = messages.firstOrNull()?.originatingAddress.orEmpty()

        val payload = JSONObject().apply {
            put("phone_number", address)
            put("message", messageBody)
            resolveContactName(context, address)?.let { put("contact_name", it) }
        }

        EventHub.emit(
            PortalEvent(
                type = EventType.SMS_RECEIVED,
                payload = payload,
            ),
        )
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumber)
            .build()
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )
        cursor.use {
            if (it != null && it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }
}
