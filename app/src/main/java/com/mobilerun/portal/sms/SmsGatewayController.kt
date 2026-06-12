package com.mobilerun.portal.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mobilerun.portal.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Opt-in coordinator for the SMS gateway: registers the device with numbers-api,
 * runs a periodic sync (drain inbound → upload, pull outbound → send, report
 * status), and registers/unregisters the inbound SMS receiver. No coroutines /
 * WorkManager — a single-thread scheduled executor, matching the portal's style.
 */
object SmsGatewayController {
    private const val TAG = "SmsGateway"
    private const val SYNC_INTERVAL_SEC = 15L

    private var appContext: Context? = null
    private var executor: ScheduledExecutorService? = null
    private var inboundReceiver: SmsInboundReceiver? = null

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) enable(context) else disable(context)
    }

    @Synchronized
    fun enable(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        ConfigManager.getInstance(ctx).setSmsGatewayEnabledWithNotification(true)
        registerInboundReceiver(ctx)
        startExecutor(ctx)
        poke(ctx) // immediate first cycle: register + initial pull/drain
    }

    @Synchronized
    fun disable(context: Context) {
        val ctx = context.applicationContext
        ConfigManager.getInstance(ctx).setSmsGatewayEnabledWithNotification(false)
        inboundReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        inboundReceiver = null
        executor?.shutdownNow()
        executor = null
    }

    /** Restart on boot/app-foreground if the feature was left enabled. */
    @Synchronized
    fun reconcile(context: Context) {
        if (ConfigManager.getInstance(context).smsGatewayEnabled) enable(context)
    }

    /** Trigger an immediate sync cycle (e.g. after an inbound SMS). */
    fun poke(context: Context) {
        appContext = context.applicationContext
        executor?.execute { runCycle() }
    }

    @Synchronized
    private fun startExecutor(ctx: Context) {
        if (executor != null) return
        registerDevice(ctx)
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ runCycle() }, 0, SYNC_INTERVAL_SEC, TimeUnit.SECONDS)
        }
    }

    private fun registerInboundReceiver(ctx: Context) {
        if (inboundReceiver != null) return
        val receiver = SmsInboundReceiver()
        ctx.registerReceiver(receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        inboundReceiver = receiver
    }

    private fun registerDevice(ctx: Context) {
        runCatching {
            val config = ConfigManager.getInstance(ctx)
            SmsApiClient(ctx).registerDevice(config.deviceID, android.os.Build.MODEL ?: "android", buildSimCards(ctx))
        }.onFailure { Log.w(TAG, "registerDevice failed", it) }
    }

    private fun runCycle() {
        val ctx = appContext ?: return
        val config = ConfigManager.getInstance(ctx)
        if (!config.smsGatewayEnabled) return

        val deviceId = config.deviceID
        val store = SmsStore.getInstance(ctx)
        val client = SmsApiClient(ctx)

        runCatching {
            client.ping(deviceId, capabilityState(ctx))

            // Inbox: upload received SMS, ack only after the cloud persists them.
            for (sms in store.unackedInbound()) {
                if (client.postInbound(deviceId, sms)) store.markInboundAcked(sms.localId)
            }

            // Outbox: pull queued sends and dispatch (dedup-safe in the engine).
            for (send in client.getPending(deviceId)) {
                SmsEngine.send(ctx, store, send)
            }

            // Report state changes; mark reported on success.
            val unreported = store.unreportedOutbound()
            if (unreported.isNotEmpty() && client.reportStatus(deviceId, unreported)) {
                unreported.forEach { store.markReported(it.sendTaskId) }
            }
        }.onFailure { Log.w(TAG, "sync cycle failed", it) }
    }

    private fun capabilityState(ctx: Context): String {
        val send = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val receive = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        return if (send && receive) "ready" else "permission_revoked"
    }

    private fun buildSimCards(ctx: Context): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return arr
        }
        val sm = ContextCompat.getSystemService(ctx, SubscriptionManager::class.java) ?: return arr
        @Suppress("MissingPermission")
        sm.activeSubscriptionInfoList?.forEach { info ->
            arr.put(
                JSONObject()
                    .put("slotIndex", info.simSlotIndex)
                    .put("simNumber", info.simSlotIndex + 1)
                    .put("subscriptionId", info.subscriptionId)
                    .put("carrierName", info.carrierName?.toString())
                    .apply { info.number?.takeIf { it.isNotBlank() }?.let { put("phoneNumber", it) } },
            )
        }
        return arr
    }
}
