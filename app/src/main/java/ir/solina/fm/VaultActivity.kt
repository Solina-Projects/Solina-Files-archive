package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.Gravity
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted private vault. Files are AES-GCM encrypted and the vault screen is gated by system biometrics/device credential. */
class VaultActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val vaultDir by lazy { File(filesDir, "encrypted_vault").apply { mkdirs() } }
    private val prefs by lazy { getSharedPreferences("solina_vault", MODE_PRIVATE) }
    private val keyAlias = "solina_vault_aes_key"
    private var unlocked = false
    private var pendingRestore: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "خزانهٔ رمزگذاری‌شده"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply { text = "فایل‌ها با AES-GCM رمزگذاری می‌شوند و ورود به خزانه با قفل دستگاه/اثر انگشت محافظت می‌شود."; textSize = 13f; setPadding(0, dp(8), 0, dp(12)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this).apply { text = "افزودن فایل"; setOnClickListener { if (unlocked) pickFile() else authenticate() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(MaterialButton(this).apply { text = "قفل"; setOnClickListener { unlocked = false; authenticate() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        authenticate()
    }

    private fun authenticate() {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "برای استفاده از خزانه، ابتدا قفل صفحه یا اثر انگشت دستگاه را فعال کن.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                try {
                    ensureKey()
                } catch (_: Exception) {
                    // Do not silently generate a replacement key when encrypted data already exists.
                    Toast.makeText(
                        this@VaultActivity,
                        "کلید خزانه در دسترس نیست؛ کلید جدید ساخته نشد تا فایل‌های موجود بی‌دلیل غیرقابل‌بازیابی نشوند.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                renderFiles()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                unlocked = false
                Toast.makeText(this@VaultActivity, "خزانه قفل ماند: $errString", Toast.LENGTH_SHORT).show()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("بازکردن خزانهٔ Solina")
            .setSubtitle("تأیید هویت برای دسترسی به فایل‌های رمزگذاری‌شده")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun ensureKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        if (vaultDir.listFiles()?.any { it.isFile && it.extension == "svault" } == true) {
            throw IllegalStateException("کلید رمزگذاری قبلی پیدا نشد؛ ساخت کلید جدید داده‌های موجود را بازیابی نمی‌کند")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 501)
    }

    @Deprecated("Legacy callback retained for compatibility with minSdk 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 502 && resultCode == RESULT_OK && data?.data != null) {
            val file = pendingRestore
            pendingRestore = null
            if (unlocked && file != null) decryptToUri(file, data.data!!)
            else authenticate()
            return
        }
        if (requestCode != 501 || resultCode != RESULT_OK || data?.data == null) return
        if (!unlocked) { authenticate(); return }
        var partialTarget: File? = null
        try {
            val uri = data.data!!
            val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "file-${System.currentTimeMillis()}"
            val target = uniqueVaultFile(name)
            partialTarget = target
            val key = ensureKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val sourceStream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("فایل انتخاب‌شده قابل خواندن نیست")
            sourceStream.use { input ->
                FileOutputStream(target).use { output ->
                    output.write(byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()))
                    output.write(iv.size)
                    output.write(iv)
                    javax.crypto.CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted) }
                }
            }
            prefs.edit().putString(target.name, name).apply()
            partialTarget = null
            renderFiles()
            toast("فایل رمزگذاری و به خزانه اضافه شد")
        } catch (_: Exception) {
            partialTarget?.delete()
            toast("رمزگذاری فایل ناموفق بود؛ فایل ناقص از خزانه پاک‌سازی شد")
        }
    }

    private fun uniqueVaultFile(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "file" }
        var file = File(vaultDir, safe + ".svault")
        var i = 1
        while (file.exists()) file = File(vaultDir, "${safe}_$i.svault").also { i++ }
        return file
    }

    private fun renderFiles() {
        list.removeAllViews()
        if (!unlocked) {
            list.addView(TextView(this).apply { text = "خزانه قفل است."; setPadding(dp(8), dp(20), dp(8), dp(20)) })
            return
        }
        val files = vaultDir.listFiles()?.filter { it.isFile && it.extension == "svault" }.orEmpty()
        if (files.isEmpty()) list.addView(TextView(this).apply { text = "هنوز فایلی در خزانه نیست."; setPadding(8, 20, 8, 20) })
        files.forEach { file ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
            row.addView(TextView(this).apply {
                text = prefs.getString(file.name, file.nameWithoutExtension) + "\n" + android.text.format.Formatter.formatFileSize(this@VaultActivity, file.length())
                textSize = 15f
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(MaterialButton(this@VaultActivity).apply { text = "بازیابی"; setOnClickListener { if (unlocked) decryptToExport(file) } }, LinearLayout.LayoutParams(0, -2, 1f))
            buttons.addView(MaterialButton(this@VaultActivity).apply { text = "حذف"; setOnClickListener {
                MaterialAlertDialogBuilder(this@VaultActivity).setMessage("فایل رمزگذاری‌شده حذف شود؟")
                    .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (deleted) {
                            prefs.edit().remove(file.name).apply()
                            deleteVaultKeyIfUnused()
                            renderFiles()
                        } else {
                            toast("حذف فایل از خزانه انجام نشد")
                        }
                    }.show()
            } }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(buttons)
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(ContextCompat.getColor(this@VaultActivity, R.color.solina_outline), 90)); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) })
        }
    }

    private fun decryptToExport(file: File) {
        if (!unlocked) { authenticate(); return }
        pendingRestore = file
        val name = prefs.getString(file.name, file.nameWithoutExtension) ?: file.nameWithoutExtension
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }, 502)
    }

    private fun decryptToUri(file: File, uri: android.net.Uri) {
        var completed = false
        try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                var magicRead = 0
                while (magicRead < magic.size) {
                    val count = input.read(magic, magicRead, magic.size - magicRead)
                    if (count <= 0) throw IllegalStateException("سرآیند فایل ناقص است")
                    magicRead += count
                }
                if (String(magic, Charsets.US_ASCII) != "SVL1") {
                    throw IllegalStateException("ساختار فایل خزانه معتبر نیست")
                }
                val ivLength = input.read()
                if (ivLength !in 12..16) throw IllegalStateException("سرآیند رمزگذاری نامعتبر است")
                val iv = ByteArray(ivLength)
                var offset = 0
                while (offset < ivLength) {
                    val count = input.read(iv, offset, ivLength - offset)
                    if (count <= 0) throw IllegalStateException("فایل ناقص است")
                    offset += count
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, ensureKey(), GCMParameterSpec(128, iv))
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("مقصد برای نوشتن باز نشد")
                javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                    output.use { out -> decrypted.copyTo(out) }
                }
            }
            completed = true
            toast("فایل با موفقیت رمزگشایی و در مقصد انتخابی ذخیره شد")
        } catch (_: Exception) {
            if (!completed) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            toast("رمزگشایی ناموفق بود؛ فایل خروجی ناقص پاک‌سازی شد، اگر ارائه‌دهندهٔ مقصد حذف را پشتیبانی کند")
        }
    }

    private fun deleteVaultKeyIfUnused() {
        val remainingFiles = vaultDir.listFiles() ?: return
        if (remainingFiles.any { it.isFile && it.extension == "svault" }) return
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // Keeping an unused keystore entry is safer than interrupting vault cleanup.
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
