package com.droidrun.portal.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

fun EditText.addWhitespaceStrippingWatcher() {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val original = s?.toString() ?: return
            val cleaned = original.replace("\\s+".toRegex(), "")
            if (original != cleaned) {
                removeTextChangedListener(this)
                setText(cleaned)
                setSelection(cleaned.length)
                addTextChangedListener(this)
            }
        }
    })
}
