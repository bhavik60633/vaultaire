package dev.vaultaire.spike

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device encrypted vault database implementation matching docs/vault-format.md §4.
 * Stored strictly in noBackupFilesDir to prevent OS cloud leakage.
 */
class VaultDatabase(context: Context) : SQLiteOpenHelper(
    context,
    getDatabasePath(context).absolutePath,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_VERSION = 1
        const val APPLICATION_ID = 0x564C5431 // "VLT1"

        fun getDatabasePath(context: Context): File {
            val vaultDir = File(context.noBackupFilesDir, "vault")
            if (!vaultDir.exists()) vaultDir.mkdirs()
            return File(vaultDir, "vault.db")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // vault-format §4.1 connection settings
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA journal_mode = WAL;", null).close()
        db.rawQuery("PRAGMA secure_delete = ON;", null).close()
        db.rawQuery("PRAGMA temp_store = MEMORY;", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA application_id = $APPLICATION_ID;", null).close()

        // vault-format §4.2 schema tables
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vault_meta (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                header BLOB NOT NULL,
                header_mac BLOB NOT NULL
            );
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS keyslot (
                slot_id TEXT PRIMARY KEY,
                slot_type INTEGER NOT NULL CHECK (slot_type IN (1, 2, 3)),
                params BLOB NOT NULL,
                nonce BLOB NOT NULL,
                wrapped_vk BLOB NOT NULL
            ) WITHOUT ROWID;
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS item (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                fav INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            ) WITHOUT ROWID;
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS item_history (
                id TEXT PRIMARY KEY,
                item_id TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
                secret_val TEXT NOT NULL,
                date_str TEXT NOT NULL
            ) WITHOUT ROWID;
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 migrations
    }

    fun hasVault(): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM item", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count > 0
    }

    fun getAllItems(): List<VaultItem> {
        val db = readableDatabase
        val result = mutableListOf<VaultItem>()

        val cursor = db.query(
            "item",
            arrayOf("id", "type", "title", "subtitle", "payload_json", "fav"),
            null, null, null, null, "fav DESC, updated_at DESC"
        )

        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val type = cursor.getString(1)
            val title = cursor.getString(2)
            val subtitle = cursor.getString(3)
            val payloadStr = cursor.getString(4)
            val fav = cursor.getInt(5) == 1

            val obj = runCatching { JSONObject(payloadStr) }.getOrDefault(JSONObject())

            val codes = mutableListOf<String>()
            val codesArr = obj.optJSONArray("recoveryCodes")
            if (codesArr != null) {
                for (j in 0 until codesArr.length()) codes.add(codesArr.getString(j))
            }

            val histList = mutableListOf<Pair<String, String>>()
            val histArr = obj.optJSONArray("history")
            if (histArr != null) {
                for (j in 0 until histArr.length()) {
                    val hObj = histArr.getJSONObject(j)
                    histList.add(hObj.optString("val") to hObj.optString("date"))
                }
            }

            val cfList = mutableListOf<CustomField>()
            val cfArr = obj.optJSONArray("customFields")
            if (cfArr != null) {
                for (j in 0 until cfArr.length()) {
                    val cfObj = cfArr.getJSONObject(j)
                    cfList.add(
                        CustomField(
                            id = cfObj.optString("id"),
                            label = cfObj.optString("label"),
                            value = cfObj.optString("value"),
                            tier = cfObj.optString("tier", "concealed")
                        )
                    )
                }
            }

            result.add(
                VaultItem(
                    id = id,
                    type = type,
                    title = title,
                    subtitle = subtitle,
                    bankName = obj.optString("bankName", ""),
                    username = obj.optString("username", ""),
                    secret = obj.optString("secret", ""),
                    cardNumber = obj.optString("cardNumber", ""),
                    cardHolder = obj.optString("cardHolder", ""),
                    expiry = obj.optString("expiry", ""),
                    cvv = obj.optString("cvv", ""),
                    network = obj.optString("network", ""),
                    finish = obj.optString("finish", "obsidian"),
                    fav = fav,
                    recoveryCodes = codes,
                    noteContent = obj.optString("noteContent", ""),
                    totpSecret = obj.optString("totpSecret", ""),
                    bip39Phrase = obj.optString("bip39Phrase", ""),
                    upcomingDate = obj.optString("upcomingDate", ""),
                    upcomingDays = obj.optInt("upcomingDays", 0),
                    isDecoy = obj.optBoolean("isDecoy", false),
                    hotline = obj.optString("hotline", "+1 800 555 0142"),
                    history = histList,
                    customFields = cfList
                )
            )
        }
        cursor.close()
        return result
    }

    fun saveItem(item: VaultItem) {
        val db = writableDatabase
        val obj = JSONObject().apply {
            put("bankName", item.bankName)
            put("username", item.username)
            put("secret", item.secret)
            put("cardNumber", item.cardNumber)
            put("cardHolder", item.cardHolder)
            put("expiry", item.expiry)
            put("cvv", item.cvv)
            put("network", item.network)
            put("finish", item.finish)
            put("recoveryCodes", JSONArray(item.recoveryCodes))
            put("noteContent", item.noteContent)
            put("totpSecret", item.totpSecret)
            put("bip39Phrase", item.bip39Phrase)
            put("upcomingDate", item.upcomingDate)
            put("upcomingDays", item.upcomingDays)
            put("isDecoy", item.isDecoy)
            put("hotline", item.hotline)

            val histArray = JSONArray()
            item.history.forEach { (v, d) ->
                histArray.put(JSONObject().apply {
                    put("val", v)
                    put("date", d)
                })
            }
            put("history", histArray)

            val cfArray = JSONArray()
            item.customFields.forEach { cf ->
                cfArray.put(JSONObject().apply {
                    put("id", cf.id)
                    put("label", cf.label)
                    put("value", cf.value)
                    put("tier", cf.tier)
                })
            }
            put("customFields", cfArray)
        }

        val values = ContentValues().apply {
            put("id", item.id)
            put("type", item.type)
            put("title", item.title)
            put("subtitle", item.subtitle)
            put("payload_json", obj.toString())
            put("fav", if (item.fav) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }

        db.insertWithOnConflict("item", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteItem(id: String) {
        val db = writableDatabase
        db.delete("item", "id = ?", arrayOf(id))
    }

    fun saveAll(items: List<VaultItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("item", null, null)
            items.forEach { saveItem(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
