package com.polybot.btc5m.bot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The signing key at rest.
 *
 * It used to be sealed under a PIN the user typed on every launch. Without that
 * PIN there is no secret coming from the user at all, so the sealing key has to
 * live somewhere the app can reach unaided — and the only such place worth
 * using is the Android Keystore, where the key material is held by the system
 * (in a secure element where the device has one) and never enters this process.
 *
 * What that does and does not buy is worth being precise about. An attacker
 * with the file system — a backup, a stolen disk image, another app — gets
 * ciphertext they cannot open, because the sealing key is not in the file. An
 * attacker holding the phone unlocked gets the key, because the app will happily
 * decrypt for whoever is holding it. That is the trade a PIN was paying for.
 */
object KeyVault {

    private const val ALIAS = "polybot.vault.v1"
    private const val PREFS = "polybot_vault"
    private const val KEY_CIPHERTEXT = "ciphertext"
    private const val KEY_IV = "iv"
    private const val GCM_TAG_BITS = 128

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        // A dedicated security chip where the phone has one; plenty of devices
        // do not, and asking for it there throws rather than degrading.
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (e: Exception) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    fun store(context: Context, privateKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(privateKey.toByteArray(Charsets.UTF_8))

        prefs(context).edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Null when there is nothing stored, or when what is stored can no longer be
     * opened — the keystore entry is dropped if the user adds a screen lock on
     * some devices, and a reinstall always loses it. Either way the honest
     * answer is that the key is gone and has to be entered again.
     */
    fun load(context: Context): String? {
        val prefs = prefs(context)
        val sealed = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing stored under the alias; the ciphertext is gone regardless.
        }
    }
}
