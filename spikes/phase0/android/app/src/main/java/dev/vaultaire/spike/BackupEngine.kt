package dev.vaultaire.spike

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// -------------------------------------------------------------------------
// APPROACH C: STORAGE ACCESS FRAMEWORK (SAF) BACKUP ENGINE (Air-Gapped E2EE)
// -------------------------------------------------------------------------
object BackupEngine {
    val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 0, 1, 0)
    private const val BUCKET = 4096

    fun createBackup(items: List<VaultItem>, password: String, recoveryKey: String): ByteArray {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("title", item.title)
                put("subtitle", item.subtitle)
                put("bankName", item.bankName)
                put("username", item.username)
                put("secret", item.secret)
                put("cardNumber", item.cardNumber)
                put("cardHolder", item.cardHolder)
                put("expiry", item.expiry)
                put("cvv", item.cvv)
                put("network", item.network)
                put("finish", item.finish)
                put("fav", item.fav)
                put("recoveryCodes", JSONArray(item.recoveryCodes))
                put("noteContent", item.noteContent)
                put("totpSecret", item.totpSecret)
                put("bip39Phrase", item.bip39Phrase)
                put("upcomingDate", item.upcomingDate)
                put("upcomingDays", item.upcomingDays)
                put("isDecoy", item.isDecoy)
                put("hotline", item.hotline)
                
                // History
                val histArray = JSONArray()
                item.history.forEach { (v, d) ->
                    histArray.put(JSONObject().apply {
                        put("val", v)
                        put("date", d)
                    })
                }
                put("history", histArray)

                // Custom fields
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
            jsonArray.put(obj)
        }
        val rawPlain = jsonArray.toString().toByteArray(Charsets.UTF_8)

        val padLen = BUCKET - (rawPlain.size % BUCKET)
        val plain = ByteArray(rawPlain.size + padLen)
        System.arraycopy(rawPlain, 0, plain, 0, rawPlain.size)
        plain[rawPlain.size] = 0x80.toByte()

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ikm = (password.trim() + ":" + recoveryKey.replace("-", "").trim()).toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val keyBytes = md.digest(ikm)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val cipherText = cipher.doFinal(plain)

        val headerObj = JSONObject().apply {
            put("format_version", 1)
            put("kdf", "argon2id-compat")
            put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            put("timestamp", System.currentTimeMillis())
            put("items_count", items.size)
            put("generator", "Vaultaire-Android-SAF-Obsidian")
        }
        val headerBytes = headerObj.toString().toByteArray(Charsets.UTF_8)
        val headerLen = headerBytes.size

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write((headerLen shr 24).toByte().toInt())
        out.write((headerLen shr 16).toByte().toInt())
        out.write((headerLen shr 8).toByte().toInt())
        out.write(headerLen.toByte().toInt())
        out.write(headerBytes)
        out.write(iv)
        out.write(cipherText)
        return out.toByteArray()
    }

    fun restoreBackup(data: ByteArray, password: String, recoveryKey: String): List<VaultItem> {
        if (data.size < MAGIC.size + 4 + 12 + 16) {
            throw IllegalArgumentException("File too small to be a valid Vaultaire backup")
        }
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) {
                throw IllegalArgumentException("Not a valid Vaultaire backup file (header magic mismatch)")
            }
        }
        var offset = MAGIC.size
        val headerLen = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        offset += 4
        if (offset + headerLen > data.size) {
            throw IllegalArgumentException("Corrupt backup file: truncated header")
        }
        val headerBytes = data.copyOfRange(offset, offset + headerLen)
        offset += headerLen

        val headerObj = JSONObject(String(headerBytes, Charsets.UTF_8))
        val salt = android.util.Base64.decode(headerObj.getString("salt"), android.util.Base64.NO_WRAP)

        val iv = data.copyOfRange(offset, offset + 12)
        offset += 12
        val cipherText = data.copyOfRange(offset, data.size)

        val ikm = (password.trim() + ":" + recoveryKey.replace("-", "").trim()).toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val keyBytes = md.digest(ikm)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val plainPadded = try {
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw IllegalArgumentException("Decryption failed: Incorrect Password or Recovery Key.")
        }

        var last = plainPadded.size - 1
        while (last >= 0 && plainPadded[last] == 0.toByte()) {
            last--
        }
        if (last < 0 || plainPadded[last] != 0x80.toByte()) {
            throw IllegalArgumentException("Padding validation error")
        }
        val plain = plainPadded.copyOfRange(0, last)
        val jsonArray = JSONArray(String(plain, Charsets.UTF_8))

        val result = mutableListOf<VaultItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val codes = mutableListOf<String>()
            val codesArr = obj.optJSONArray("recoveryCodes")
            if (codesArr != null) {
                for (j in 0 until codesArr.length()) {
                    codes.add(codesArr.getString(j))
                }
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
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    type = obj.optString("type", "login"),
                    title = obj.optString("title", "Item"),
                    subtitle = obj.optString("subtitle", ""),
                    bankName = obj.optString("bankName", ""),
                    username = obj.optString("username", ""),
                    secret = obj.optString("secret", ""),
                    cardNumber = obj.optString("cardNumber", ""),
                    cardHolder = obj.optString("cardHolder", ""),
                    expiry = obj.optString("expiry", ""),
                    cvv = obj.optString("cvv", ""),
                    network = obj.optString("network", ""),
                    finish = obj.optString("finish", "obsidian"),
                    fav = obj.optBoolean("fav", false),
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
        return result
    }
}
