package com.mobilerun.portal.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the sent/delivered PendingIntent broadcasts fired by SmsManager.
 * Declared statically in the manifest (explicit-component intents only).
 */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmsEngine.processResult(context.applicationContext, intent, resultCode)
    }
}
