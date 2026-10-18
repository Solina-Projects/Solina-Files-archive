package ir.solina.fm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object VaultSecurityManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "SolinaVaultMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 120_000

    fun encrypt(data: ByteArray, customPassword: String? = null): ByteArray {
        val key: SecretKey = if (!customPassword.isNullOrEmpty()) {
            deriveKeyFromPassword(customPassword)
        } else {
            getOrCreateHardwareKey()
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun decrypt(encryptedWithIv: ByteArray, customPassword: String? = null): ByteArray {
        require(encryptedWithIv.size > GCM_IV_LENGTH) { "فایل نامعتبر یا فاسد شده است" }

        val iv = encryptedWithIv.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedData = encryptedWithIv.copyOfRange(GCM_IV_LENGTH, encryptedWithIv.size)

        val key: SecretKey = if (!customPassword.isNullOrEmpty()) {
            deriveKeyFromPassword(customPassword)
        } else {
            getOrCreateHardwareKey()
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(encryptedData)
    }

    private fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    private fun deriveKeyFromPassword(password: String): SecretKey {
        val salt = "SolinaVaultSalt2026".toByteArray(Charsets.UTF_8)
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretBytes = keyFactory.generateSecret(keySpec).encoded
        return SecretKeySpec(secretBytes, "AES")
    }
}
