package com.mobilerun.portal.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import com.mobilerun.portal.triggers.TriggerRuntime
import org.json.JSONObject

class MobilerunNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MobilerunNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        TriggerRuntime.initialize(this)
        Log.i(TAG, "Notification Listener Connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            // 1. Extract Data safely
            val extras = sbn.notification.extras
            val title = extras.getString("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val packageName = sbn.packageName

            // 2. Create our clean Payload object
            val payload = JSONObject().apply {
                put("package", packageName)
                put("title", title)
                put("text", text)
                put("id", sbn.id)
                put("tag", sbn.tag ?: "")
                put("is_ongoing", sbn.isOngoing)
                put("post_time", sbn.postTime)
                put("key", sbn.key)
            }

            // 3. Wrap it in our Event Model
            val legacyEvent = PortalEvent(type = EventType.NOTIFICATION, payload = payload)
            val event = PortalEvent(type = EventType.NOTIFICATION_POSTED, payload = payload)

            // 4. Send to the Hub
            EventHub.emit(legacyEvent)
            EventHub.emit(event)
            
            Log.v(TAG, "Emitted notification from $packageName")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
         if (sbn == null) return
         
         try {
            val payload = JSONObject().apply {
                put("package", sbn.packageName)
                put("id", sbn.id)
                put("key", sbn.key)
                put("removed", true)
            }
            
            val legacyEvent = PortalEvent(type = EventType.NOTIFICATION, payload = payload)
            val event = PortalEvent(type = EventType.NOTIFICATION_REMOVED, payload = payload)

            EventHub.emit(legacyEvent)
            EventHub.emit(event)
         } catch (e: Exception) {
             Log.e(TAG, "Error processing notification removal", e)
         }
    }
}
