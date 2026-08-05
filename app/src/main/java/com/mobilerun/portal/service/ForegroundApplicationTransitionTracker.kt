package com.mobilerun.portal.service

import com.mobilerun.portal.events.model.EventType
import com.mobilerun.portal.events.model.PortalEvent
import org.json.JSONObject

/** Emits app transitions only when confirmed foreground application evidence advances. */
internal class ForegroundApplicationTransitionTracker(
    private val emit: (PortalEvent) -> Unit,
) {
    private var foregroundPackageName = ""

    fun advance(packageName: String?) {
        val nextPackage = packageName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        val previousPackage = foregroundPackageName
        if (nextPackage == previousPackage) return

        foregroundPackageName = nextPackage

        if (previousPackage.isNotEmpty()) {
            emit(
                PortalEvent(
                    type = EventType.APP_EXITED,
                    payload = JSONObject().apply {
                        put("package", previousPackage)
                        put("next_package", nextPackage)
                    },
                ),
            )
        }

        emit(
            PortalEvent(
                type = EventType.APP_ENTERED,
                payload = JSONObject().apply {
                    put("package", nextPackage)
                    if (previousPackage.isNotEmpty()) {
                        put("previous_package", previousPackage)
                    }
                },
            ),
        )
    }
}
