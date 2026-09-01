package com.turningturtle.dtmonitor

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyStore

object SecretStore {
    private const val PREFS = "dt_monitor_secrets"
    private const val KEY_NAME = "dt_monitor_webhook_key"
    private const val VALUE = "webhook"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): javax.crypto.SecretKey {
        val store = keyStore()
        val existing = store.getKey(KEY_NAME, null)
        if (existing is javax.crypto.SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_NAME,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun put(context: Context, value: String) {
        if (value.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VALUE, packed).apply()
    }

    fun get(context: Context): String {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null) ?: return ""
        return try {
            val parts = packed.split(":", limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}
