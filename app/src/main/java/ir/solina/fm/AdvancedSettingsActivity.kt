package ir.solina.fm

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AdvancedSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("solina_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "تنظیمات تکمیلی"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(action("تنظیم پوشهٔ آغازین") { editHomeFolder() })
        root.addView(action(if (prefs.getBoolean("dynamic_colors", false)) "غیرفعال‌کردن رنگ‌های پویا" else "استفاده از رنگ‌های سیستم (Dynamic Color)") { toggleDynamicColors() })
        root.addView(action("تنظیم یا تغییر رمز قفل برنامه") { setAppPin() })
        root.addView(action(if (prefs.getBoolean("biometric_lock_enabled", false)) "غیرفعال‌کردن بازگشایی زیستی" else "فعال‌کردن اثرانگشت/قفل دستگاه") { toggleBiometric() })
        root.addView(action("غیرفعال‌کردن قفل برنامه") { disableAppLock() })
        root.addView(action("ساخت میان‌بر پوشهٔ فعلی روی صفحهٔ اصلی") { pinShortcut() })
        root.addView(action("فایل‌های اخیر") { startActivity(Intent(this, RecentFilesActivity::class.java)) })
        setContentView(root)
    }

    private fun action(label: String, callback: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { callback() }
    }

    private fun toggleDynamicColors() {
        val enabled = !prefs.getBoolean("dynamic_colors", false)
        prefs.edit().putBoolean("dynamic_colors", enabled).apply()
        Toast.makeText(
            this,
            if (enabled) "رنگ‌های سیستم ذخیره شد؛ برنامه را دوباره اجرا کن تا در همهٔ صفحه‌ها اعمال شود"
            else "رنگ برند ذخیره شد؛ برنامه را دوباره اجرا کن تا تغییر کامل اعمال شود",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun editHomeFolder() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/Documents"
            setSingleLine(true)
            setText(prefs.getString("home_folder", ""))
        }
        MaterialAlertDialogBuilder(this).setTitle("پوشهٔ آغازین دلخواه").setMessage(
            "مسیر یک پوشهٔ موجود را وارد کن. برای بازگشت به حافظهٔ پیش‌فرض، کادر را خالی بگذار."
        ).setView(input).setPositiveButton("ذخیره") { _, _ ->
            val path = input.text?.toString()?.trim().orEmpty()
            if (path.isEmpty()) {
                prefs.edit().remove("home_folder").apply()
                Toast.makeText(this, "پوشهٔ آغازین پیش‌فرض شد", Toast.LENGTH_SHORT).show()
            } else {
                val folder = File(path)
                if (folder.exists() && folder.isDirectory && folder.canRead()) {
                    prefs.edit().putString("home_folder", folder.absolutePath).apply()
                    Toast.makeText(this, "پوشهٔ آغازین ذخیره شد؛ از اجرای بعدی اعمال می‌شود", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, "مسیر معتبر و قابل‌خواندن نیست", Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("لغو", null).show()
    }

    private fun toggleBiometric() {
        if (prefs.getBoolean("biometric_lock_enabled", false)) {
            prefs.edit().putBoolean("biometric_lock_enabled", false).apply()
            Toast.makeText(this, "بازگشایی زیستی غیرفعال شد", Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.getBoolean("app_lock_enabled", false)) {
            Toast.makeText(this, "ابتدا رمز قفل برنامه را تنظیم کن", Toast.LENGTH_LONG).show()
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "اثر انگشت یا قفل صفحهٔ دستگاه آماده نیست؛ ابتدا در تنظیمات Android آن را فعال کن", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putBoolean("biometric_lock_enabled", true).apply()
        Toast.makeText(this, "بازگشایی با اثر انگشت یا قفل دستگاه فعال شد", Toast.LENGTH_LONG).show()
    }

    private fun setAppPin() {
        val input = EditText(this).apply {
            hint = "رمز حداقل ۴ رقمی"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this).setTitle("قفل برنامه").setMessage(
            "رمز فقط به‌صورت هش در تنظیمات خصوصی برنامه ذخیره می‌شود."
        ).setView(input).setPositiveButton("فعال‌کردن") { _, _ ->
            val pin = input.text?.toString().orEmpty()
            if (pin.length < 4) {
                Toast.makeText(this, "رمز باید حداقل ۴ رقم باشد", Toast.LENGTH_LONG).show()
            } else {
                val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val hash = pinHash(pin, salt)
                prefs.edit().putString("app_lock_salt", salt.joinToString("") { "%02x".format(it) })
                    .putString("app_lock_hash", hash)
                    .putBoolean("app_lock_enabled", true).apply()
                Toast.makeText(this, "قفل برنامه فعال شد؛ در اجرای بعدی رمز درخواست می‌شود", Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("لغو", null).show()
    }

    private fun disableAppLock() {
        if (!prefs.getBoolean("app_lock_enabled", false)) {
            Toast.makeText(this, "قفل برنامه فعال نیست", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "رمز فعلی"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this).setTitle("غیرفعال‌کردن قفل").setView(input)
            .setPositiveButton("غیرفعال‌کردن") { _, _ ->
                if (verifyPin(input.text?.toString().orEmpty())) {
                    prefs.edit().remove("app_lock_salt").remove("app_lock_hash")
                        .putBoolean("app_lock_enabled", false).apply()
                    Toast.makeText(this, "قفل برنامه غیرفعال شد", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "رمز نادرست است", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("لغو", null).show()
    }

    private fun pinHash(pin: String, salt: ByteArray): String =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
            .joinToString("") { "%02x".format(it) }

    private fun verifyPin(pin: String): Boolean {
        val saltHex = prefs.getString("app_lock_salt", null) ?: return false
        val expected = prefs.getString("app_lock_hash", null) ?: return false
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(expected.toByteArray(), pinHash(pin, salt).toByteArray())
    }

    private fun pinShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "میان‌بر قابل‌نصب فقط در Android 8 و بالاتر پشتیبانی می‌شود", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) {
            Toast.makeText(this, "لانچر دستگاه ساخت میان‌بر را پشتیبانی نمی‌کند", Toast.LENGTH_LONG).show()
            return
        }
        val path = intent.getStringExtra("current_path") ?: prefs.getString("home_folder", null)
        val intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        if (!path.isNullOrBlank()) intent.putExtra("start_path", path)
        val shortcut = ShortcutInfo.Builder(this, "solina-folder-shortcut")
            .setShortLabel("SolinaFM")
            .setLongLabel("بازکردن SolinaFM")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        manager.requestPinShortcut(shortcut, null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
