package com.mobilerun.portal.service

import com.mobilerun.portal.events.EventHub
import com.mobilerun.portal.events.model.PortalEvent

class ReverseDeviceEventRelay(
    private val senderProvider: () -> ((String) -> Boolean)?,
) {
    private val eventListener: (PortalEvent) -> Unit = event@{ event ->
        val sender = senderProvider() ?: return@event
        sender(event.toReverseNotificationJson())
    }

    fun start() {
        EventHub.subscribe(eventListener)
    }

    fun stop() {
        EventHub.unsubscribe(eventListener)
    }
}
