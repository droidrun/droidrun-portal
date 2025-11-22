package com.droidrun.portal.api

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.core.JsonBuilders
import com.droidrun.portal.core.StateRepository
import org.json.JSONArray
import org.json.JSONObject

class ApiHandler(
    private val stateRepo: StateRepository,
    private val getKeyboardIME: () -> DroidrunKeyboardIME?,
    private val getPackageManager: () -> PackageManager,
    private val appVersionProvider: () -> String
) {
    // Queries
    fun ping() = ApiResponse.Success("pong")

    fun getTree(): ApiResponse {
        val elements = stateRepo.getVisibleElements()
        val json = elements.map { JsonBuilders.elementNodeToJson(it) }
        return ApiResponse.Success(JSONArray(json).toString())
    }

    fun getTreeFull(filter: Boolean): ApiResponse {
        val tree = stateRepo.getFullTree(filter)
            ?: return ApiResponse.Error("No active window or root filtered out")
        return ApiResponse.Success(tree.toString())
    }

    fun getPhoneState(): ApiResponse {
        val state = stateRepo.getPhoneState()
        return ApiResponse.Success(JsonBuilders.phoneStateToJson(state).toString())
    }

    fun getState(): ApiResponse {
        val elements = stateRepo.getVisibleElements()
        val treeJson = elements.map { JsonBuilders.elementNodeToJson(it) }
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())

        val combined = JSONObject().apply {
            put("a11y_tree", JSONArray(treeJson))
            put("phone_state", phoneStateJson)
        }
        return ApiResponse.Success(combined.toString())
    }

    fun getStateFull(filter: Boolean): ApiResponse {
        val tree = stateRepo.getFullTree(filter)
            ?: return ApiResponse.Error("No active window or root filtered out")
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())
        val deviceContext = stateRepo.getDeviceContext()

        val combined = JSONObject().apply {
            put("a11y_tree", tree)
            put("phone_state", phoneStateJson)
            put("device_context", deviceContext)
        }
        return ApiResponse.Success(combined.toString())
    }

    fun getVersion() = ApiResponse.Success(appVersionProvider())


    fun getPackages(): ApiResponse {
        return try {
            val pm = getPackageManager()
            val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }

            val resolvedApps: List<android.content.pm.ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val arr = JSONArray()

            for (resolveInfo in resolvedApps) {
                val pkgInfo = try {
                    pm.getPackageInfo(resolveInfo.activityInfo.packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    continue
                }

                val appInfo = resolveInfo.activityInfo.applicationInfo
                val obj = JSONObject()

                obj.put("packageName", pkgInfo.packageName)
                obj.put("label", resolveInfo.loadLabel(pm).toString())
                obj.put("versionName", pkgInfo.versionName ?: JSONObject.NULL)

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                obj.put("versionCode", versionCode)

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                obj.put("isSystemApp", isSystem)

                arr.put(obj)
            }

            val root = JSONObject()
            root.put("status", "success")
            root.put("count", arr.length())
            root.put("packages", arr)
            
            ApiResponse.Raw(root)

        } catch (e: Exception) {
            ApiResponse.Error("Failed to enumerate launchable apps: ${e.message}")
        }
    }

    // Keyboard actions
    fun keyboardInput(base64Text: String, clear: Boolean): ApiResponse {
        val ime = getKeyboardIME() ?: return ApiResponse.Error("IME not active")
        return if (ime.inputB64Text(base64Text, clear)) {
            ApiResponse.Success("input done (clear=$clear)")
        } else {
            ApiResponse.Error("input failed")
        }
    }

    fun keyboardClear(): ApiResponse {
        val ime = getKeyboardIME() ?: return ApiResponse.Error("DroidrunKeyboardIME not active or available")
        
        if (!ime.hasInputConnection()) {
            return ApiResponse.Error("No input connection available - keyboard may not be focused on an input field")
        }

        return if (ime.clearText()) {
            ApiResponse.Success("Text cleared via keyboard")
        } else {
            ApiResponse.Error("Failed to clear text via keyboard")
        }
    }
    
    fun keyboardKey(keyCode: Int): ApiResponse {
        val ime = getKeyboardIME() ?: return ApiResponse.Error("DroidrunKeyboardIME not active or available")
        
        if (!ime.hasInputConnection()) {
            return ApiResponse.Error("No input connection available - keyboard may not be focused on an input field")
        }

        return if (ime.sendKeyEventDirect(keyCode)) {
            ApiResponse.Success("Key event sent via keyboard - code: $keyCode")
        } else {
            ApiResponse.Error("Failed to send key event via keyboard")
        }
    }

    // Overlay
    fun setOverlayOffset(offset: Int): ApiResponse {
        return if (stateRepo.setOverlayOffset(offset)) {
            ApiResponse.Success("Overlay offset updated to $offset")
        } else {
            ApiResponse.Error("Failed to update overlay offset")
        }
    }

    fun setOverlayVisible(visible: Boolean): ApiResponse {
        return if (stateRepo.setOverlayVisible(visible)) {
            ApiResponse.Success("Overlay visibility set to $visible")
        } else {
            ApiResponse.Error("Failed to set overlay visibility")
        }
    }

    fun setSocketPort(port: Int): ApiResponse {
        return if (stateRepo.updateSocketServerPort(port)) {
            ApiResponse.Success("Socket server port updated to $port")
        } else {
            ApiResponse.Error("Failed to update socket server port to $port (bind failed or invalid)")
        }
    }

    fun getScreenshot(hideOverlay: Boolean): ApiResponse {
        return try {
            val future = stateRepo.takeScreenshot(hideOverlay)
            // Wait up to 5 seconds
            val result = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            
            if (result.startsWith("error:")) {
                ApiResponse.Error(result.substring(7))
            } else {
                ApiResponse.Success(result)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            ApiResponse.Error("Screenshot timeout - operation took too long")
        } catch (e: Exception) {
            ApiResponse.Error("Failed to get screenshot: ${e.message}")
        }
    }
}
