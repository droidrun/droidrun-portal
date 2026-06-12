package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** SIM selection + inbound-SIM extraction, ported from the OSS app's SubscriptionsHelper. */
object SimHelper {

    private fun hasPhoneState(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun subscriptionManager(context: Context): SubscriptionManager? =
        ContextCompat.getSystemService(context, SubscriptionManager::class.java)

    /** Resolve the SmsManager for a 1-based simNumber (null → OS default). */
    @Suppress("DEPRECATION")
    fun getSmsManager(context: Context, simNumber: Int?): SmsManager {
        if (simNumber == null || !hasPhoneState(context)) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
        }
        val slot = simNumber - 1
        val sub = subscriptionManager(context)?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot }
            ?: throw IllegalStateException("SIM $simNumber not found")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(sub.subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(sub.subscriptionId)
        }
    }

    /** Read the subscriptionId an inbound SMS arrived on, if available. */
    fun extractSubscriptionId(context: Context, intent: Intent): Int? {
        val direct = intent.getIntExtra("subscription", -1).takeIf { it >= 0 }
            ?: intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        if (direct != null) return direct
        if (!hasPhoneState(context)) return null
        val slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1).takeIf { it >= 0 } ?: return null
        return subscriptionManager(context)?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot }?.subscriptionId
    }

    /** 1-based simNumber for a subscriptionId, for inbound attribution. */
    fun simNumberForSubscription(context: Context, subscriptionId: Int?): Int? {
        if (subscriptionId == null || !hasPhoneState(context)) return null
        val slot = subscriptionManager(context)?.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subscriptionId }?.simSlotIndex ?: return null
        return slot + 1
    }
}
