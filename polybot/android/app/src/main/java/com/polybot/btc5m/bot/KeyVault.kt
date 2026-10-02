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

    /*
      One sealing key, several sealed keys.

      The keystore entry is the app's, not a wallet's: it is what makes the
      ciphertext unreadable off the device, and a second entry would buy nothing
      a second slot in the same preferences file does not. So the alias stays
      one and the stored ciphertext is per slot — and slot zero keeps the
      unsuffixed names it was written under, so an upgrade still finds its key.
    */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cipherKey(slot: Int) = KEY_CIPHERTEXT + Wallets.suffix(slot)

    private fun ivKey(slot: Int) = KEY_IV + Wallets.suffix(slot)

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

    fun store(context: Context, privateKey: String, slot: Int = Wallets.current) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(privateKey.toByteArray(Charsets.UTF_8))

        prefs(context).edit()
            .putString(cipherKey(slot), Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(ivKey(slot), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Null when there is nothing stored, or when what is stored can no longer be
     * opened — the keystore entry is dropped if the user adds a screen lock on
     * some devices, and a reinstall always loses it. Either way the honest
     * answer is that the key is gone and has to be entered again.
     */
    fun load(context: Context, slot: Int = Wallets.current): String? {
        val prefs = prefs(context)
        val sealed = prefs.getString(cipherKey(slot), null) ?: return null
        val iv = prefs.getString(ivKey(slot), null) ?: return null

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

    /**
     * Forget one wallet's key.
     *
     * The keystore entry goes only when the last of them does: it is what seals
     * every slot, and deleting it to forget one would take the others with it.
     */
    fun clear(context: Context, slot: Int = Wallets.current) {
        prefs(context).edit()
            .remove(cipherKey(slot))
            .remove(ivKey(slot))
            .apply()
        val anyLeft = (0 until Wallets.SLOTS)
            .any { prefs(context).getString(cipherKey(it), null) != null }
        if (anyLeft) return
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing stored under the alias; the ciphertext is gone regardless.
        }
    }
}
