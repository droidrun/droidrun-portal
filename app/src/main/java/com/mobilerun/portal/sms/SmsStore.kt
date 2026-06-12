package com.mobilerun.portal.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Device-local durable store for the SMS engine — the reliability backbone.
 *
 * Outbound rows are written BEFORE calling SmsManager and deduped on
 * sendTaskId so a task is never sent twice even if re-pulled. Sent/delivered
 * receivers recover state from here, not memory. Inbound rows are persisted
 * synchronously on receipt, then drained to numbers-api by the sync worker.
 *
 * Plain SQLite (no Room/coroutines) to match the portal's minimal stack.
 */
class SmsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "mobilerun_sms.db"
        private const val DB_VERSION = 1

        @Volatile
        private var INSTANCE: SmsStore? = null

        fun getInstance(context: Context): SmsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmsStore(context).also { INSTANCE = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE outbound (
                sendTaskId TEXT PRIMARY KEY,
                phoneNumber TEXT NOT NULL,
                body TEXT NOT NULL,
                simNumber INTEGER,
                withDeliveryReport INTEGER NOT NULL DEFAULT 0,
                partsCount INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                error TEXT,
                reported INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE inbound (
                localId TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                recipient TEXT,
                body TEXT NOT NULL,
                simNumber INTEGER,
                subscriptionId INTEGER,
                receivedAt INTEGER NOT NULL,
                acked INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only; future migrations go here.
    }

    // ── Outbound ─────────────────────────────────────────────────────────

    /** Insert a task if new. Returns false when the task already exists (dedup). */
    fun insertOutboundIfAbsent(send: PendingSend, phoneNumber: String): Boolean {
        val values = ContentValues().apply {
            put("sendTaskId", send.sendTaskId)
            put("phoneNumber", phoneNumber)
            put("body", send.text)
            send.simNumber?.let { put("simNumber", it) }
            put("withDeliveryReport", if (send.withDeliveryReport) 1 else 0)
            put("state", SmsState.PENDING.api)
            put("reported", 0)
            put("updatedAt", System.currentTimeMillis())
        }
        // CONFLICT_IGNORE → returns -1 when the row already exists.
        return writableDatabase.insertWithOnConflict("outbound", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
    }

    fun setPartsCount(sendTaskId: String, parts: Int) =
        updateOutbound(sendTaskId) { put("partsCount", parts) }

    /** Advance state, marking it unreported so the worker pushes it to the cloud. */
    fun setOutboundState(sendTaskId: String, state: SmsState, error: String? = null) {
        updateOutbound(sendTaskId) {
            put("state", state.api)
            put("error", error)
            put("reported", 0)
        }
    }

    fun markReported(sendTaskId: String) = updateOutbound(sendTaskId) { put("reported", 1) }

    fun outboundState(sendTaskId: String): String? =
        readableDatabase.query("outbound", arrayOf("state"), "sendTaskId = ?", arrayOf(sendTaskId), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    /** Rows whose state changed since the last successful report. */
    fun unreportedOutbound(): List<OutboundRow> =
        readableDatabase.query("outbound", null, "reported = 0", null, null, null, "updatedAt ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(OutboundRow.from(c))
            }
        }

    private inline fun updateOutbound(sendTaskId: String, block: ContentValues.() -> Unit) {
        val values = ContentValues().apply { put("updatedAt", System.currentTimeMillis()); block() }
        writableDatabase.update("outbound", values, "sendTaskId = ?", arrayOf(sendTaskId))
    }

    // ── Inbound ──────────────────────────────────────────────────────────

    fun insertInbound(sms: InboundSms): Boolean {
        val values = ContentValues().apply {
            put("localId", sms.localId)
            put("sender", sms.sender)
            put("recipient", sms.recipient)
            put("body", sms.body)
            sms.simNumber?.let { put("simNumber", it) }
            sms.subscriptionId?.let { put("subscriptionId", it) }
            put("receivedAt", sms.receivedAtMs)
            put("acked", 0)
        }
        return writableDatabase.insertWithOnConflict("inbound", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
    }

    fun unackedInbound(): List<InboundSms> =
        readableDatabase.query("inbound", null, "acked = 0", null, null, null, "receivedAt ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        InboundSms(
                            localId = c.getString(c.getColumnIndexOrThrow("localId")),
                            sender = c.getString(c.getColumnIndexOrThrow("sender")),
                            recipient = c.getStringOrNull("recipient"),
                            body = c.getString(c.getColumnIndexOrThrow("body")),
                            simNumber = c.getIntOrNull("simNumber"),
                            subscriptionId = c.getIntOrNull("subscriptionId"),
                            receivedAtMs = c.getLong(c.getColumnIndexOrThrow("receivedAt")),
                        ),
                    )
                }
            }
        }

    fun markInboundAcked(localId: String) {
        val values = ContentValues().apply { put("acked", 1) }
        writableDatabase.update("inbound", values, "localId = ?", arrayOf(localId))
    }

    data class OutboundRow(
        val sendTaskId: String,
        val phoneNumber: String,
        val state: String,
        val error: String?,
    ) {
        companion object {
            fun from(c: android.database.Cursor) = OutboundRow(
                sendTaskId = c.getString(c.getColumnIndexOrThrow("sendTaskId")),
                phoneNumber = c.getString(c.getColumnIndexOrThrow("phoneNumber")),
                state = c.getString(c.getColumnIndexOrThrow("state")),
                error = c.getStringOrNull("error"),
            )
        }
    }
}

private fun android.database.Cursor.getStringOrNull(col: String): String? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getString(i)
}

private fun android.database.Cursor.getIntOrNull(col: String): Int? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getInt(i)
}
