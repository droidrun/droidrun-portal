package com.droidrun.portal.api

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.core.JsonBuilders
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.service.GestureController
import com.droidrun.portal.service.DroidrunAccessibilityService
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
                // Result is Base64 string from Service. 
                // decode it back to bytes to pass as Binary response.
                // In future, Service should return bytes directly to avoid this encode/decode cycle.
                val bytes = android.util.Base64.decode(result, android.util.Base64.DEFAULT)
                ApiResponse.Binary(bytes)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            ApiResponse.Error("Screenshot timeout - operation took too long")
        } catch (e: Exception) {
            ApiResponse.Error("Failed to get screenshot: ${e.message}")
        }
    }

    // New Gesture Actions
    fun performTap(x: Int, y: Int): ApiResponse {
        return if (GestureController.tap(x, y)) {
            ApiResponse.Success("Tap performed at ($x, $y)")
        } else {
            ApiResponse.Error("Failed to perform tap at ($x, $y)")
        }
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int): ApiResponse {
        return if (GestureController.swipe(startX, startY, endX, endY, duration)) {
            ApiResponse.Success("Swipe performed")
        } else {
            ApiResponse.Error("Failed to perform swipe")
        }
    }

    fun performGlobalAction(action: Int): ApiResponse {
        return if (GestureController.performGlobalAction(action)) {
            ApiResponse.Success("Global action $action performed")
        } else {
            ApiResponse.Error("Failed to perform global action $action")
        }
    }

    fun startApp(packageName: String, activityName: String? = null): ApiResponse {
        val service = DroidrunAccessibilityService.getInstance()
            ?: return ApiResponse.Error("Accessibility Service not available")

        // TODO check the problem with other apps
        val actualPackageName = if (packageName.equals("Settings", ignoreCase = true)) {
            "com.android.settings"
        } else {
            packageName
        }

        return try {
            val intent = if (!activityName.isNullOrEmpty() && activityName != "null") {
                Intent().apply {
                    setClassName(actualPackageName, if (activityName.startsWith(".")) actualPackageName + activityName else activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                service.packageManager.getLaunchIntentForPackage(actualPackageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent != null) {
                service.startActivity(intent)
                ApiResponse.Success("Started app $actualPackageName")
            } else {
                Log.e("ApiHandler", "Could not create intent for $actualPackageName - getLaunchIntentForPackage returned null. Trying fallback.")
                
                // Fallback for system apps like Settings that might need explicit component handling
                // or if visibility rules are strict.
                // generic MAIN/LAUNCHER intent for the package
                // TODO test with other apps
                try {
                    val fallbackIntent = Intent(Intent.ACTION_MAIN)
                    fallbackIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    fallbackIntent.setPackage(actualPackageName)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    if (fallbackIntent.resolveActivity(service.packageManager) != null) {
                         service.startActivity(fallbackIntent)
                         ApiResponse.Success("Started app $actualPackageName (fallback)")
                    } else {
                         ApiResponse.Error("Could not create intent for $actualPackageName")
                    }
                } catch (e2: Exception) {
                    Log.e("ApiHandler", "Fallback start failed", e2)
                    ApiResponse.Error("Could not create intent for $actualPackageName")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiHandler", "Error starting app", e)
            ApiResponse.Error("Error starting app: ${e.message}")
        }
    }
}
