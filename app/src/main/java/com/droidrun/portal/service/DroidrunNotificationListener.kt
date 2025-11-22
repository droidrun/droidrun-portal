package com.droidrun.portal.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.droidrun.portal.events.EventHub
import com.droidrun.portal.events.model.EventType
import com.droidrun.portal.events.model.PortalEvent
import org.json.JSONObject

class DroidrunNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "DroidrunNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
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
            val event = PortalEvent(
                type = EventType.NOTIFICATION,
                payload = payload
            )

            // 4. Send to the Hub
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
            
            // For now, we can reuse NOTIFICATION type or create a new one.
            // Let's send it as NOTIFICATION but with 'removed' flag for simplicity 
            // unless we want a specific event type.
            val event = PortalEvent(
                type = EventType.NOTIFICATION,
                payload = payload
            )
            
            EventHub.emit(event)
         } catch (e: Exception) {
             Log.e(TAG, "Error processing notification removal", e)
         }
    }
}
