package com.deskweek.android.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API credentials are encrypted with a device-bound key; old plaintext is migrated on first read. */
class ApiKeyStorage(context: Context, prefsName: String = "deskweek_ai") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val alias = "deskweek.ai.credentials.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String {
        val encrypted = prefs.getString("apiKeyEncrypted", null)
        if (encrypted == null) {
            val old = prefs.getString("apiKey", "").orEmpty()
            if (old.isNotEmpty()) write(old)
            return old
        }
        val parts = encrypted.split(":")
        check(parts.size == 2) { "凭据无法恢复，请重新填写 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }
    fun write(value: String) {
        if (value.isEmpty()) {
            check(prefs.edit().remove("apiKey").remove("apiKeyEncrypted").commit()) { "密钥保存失败" }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        check(prefs.edit().putString("apiKeyEncrypted", encoded).remove("apiKey").commit()) { "密钥保存失败" }
    }
}
