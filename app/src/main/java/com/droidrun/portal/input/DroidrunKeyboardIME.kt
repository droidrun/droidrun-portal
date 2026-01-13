package com.droidrun.portal.input

import com.droidrun.portal.R

import android.inputmethodservice.InputMethodService
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest

class DroidrunKeyboardIME : InputMethodService() {
    private val TAG = "DroidrunKeyboardIME"

    companion object {
        private var instance: DroidrunKeyboardIME? = null
        
        fun getInstance(): DroidrunKeyboardIME? = instance
        
        /**
         * Check if the DroidrunKeyboardIME is currently active and available
         */
        fun isAvailable(): Boolean = instance != null

        /**
         * Check if this IME is currently selected as the system default
         */
        fun isSelected(context: android.content.Context): Boolean {
            val currentId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val myId = android.content.ComponentName(context, DroidrunKeyboardIME::class.java).flattenToShortString()
            return currentId == myId
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "DroidrunKeyboardIME: onCreate() called")
    }


    /**
     * Direct method to input text from Base64 without using broadcasts
     */
    fun inputB64Text(base64Text: String, clear: Boolean = true): Boolean {
        return try {
            val decoded = Base64.decode(base64Text, Base64.DEFAULT)
            val text = String(decoded, Charsets.UTF_8)
            inputText(text, clear)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 for direct input", e)
            false
        }
    }
    
    fun inputText(text: String, clear: Boolean = true): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                if (clear) {
                    clearText()
                }
                ic.commitText(text, 1)
                Log.d(TAG, "Text input successful: $text (clear=$clear)")
                true
            } else {
                Log.w(TAG, "No input connection available for text input")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in text input", e)
            false
        }
    }
    

    /**
     * Direct method to clear text without using broadcasts
     */
    fun clearText(): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                val extractedText = ic.getExtractedText(ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    val curPos = extractedText.text
                    val beforePos = ic.getTextBeforeCursor(curPos.length, 0)
                    val afterPos = ic.getTextAfterCursor(curPos.length, 0)
                    ic.deleteSurroundingText(beforePos?.length ?: 0, afterPos?.length ?: 0)
                    Log.d(TAG, "Direct text clear successful")
                    true
                } else {
                    Log.w(TAG, "No extracted text available for clearing")
                    false
                }
            } else {
                Log.w(TAG, "No input connection available for direct clear")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct text clear", e)
            false
        }
    }

    /**
     * Direct method to send key events without using broadcasts
     */
    fun sendKeyEventDirect(keyCode: Int): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                Log.d(TAG, "Direct key event sent: $keyCode")
                true
            } else {
                Log.w(TAG, "No input connection available for direct key event")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending direct key event", e)
            false
        }
    }

    /**
     * Get current input connection status
     */
    fun hasInputConnection(): Boolean {
        return currentInputConnection != null
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView called")
        
        // Inflate the existing keyboard layout XML
        return layoutInflater.inflate(R.layout.keyboard_view, null)
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput called - restarting: $restarting")
    }

    override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        Log.d(TAG, "onStartInputView called - keyboard should be visible now")
    }

    override fun onDestroy() {
        Log.d(TAG, "DroidrunKeyboardIME: onDestroy() called")
        instance = null
        super.onDestroy()
    }
}
