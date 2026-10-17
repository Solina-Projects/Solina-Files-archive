package ir.solina.fm

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class RecentFilesActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "فایل‌های اخیر"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = android.widget.ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()
        val files = RecentFilesStore.list(this)
        if (files.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "هنوز فایلی باز نشده است."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(32), dp(12), dp(32))
            })
            return
        }
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@RecentFilesActivity, R.drawable.item_bg)
            }
            val icon = ImageView(this).apply {
                FileTypeVisuals.applyTo(this, file)
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@RecentFilesActivity, R.drawable.icon_bg)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = null
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            val label = TextView(this).apply {
                text = file.name + "\n" + file.parent
                textSize = 14f
                maxLines = 2
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    if (!file.exists()) {
                        Toast.makeText(this@RecentFilesActivity, "فایل دیگر وجود ندارد", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(this@RecentFilesActivity, MainActivity::class.java)
                            .putExtra("open_recent_path", file.absolutePath))
                        finish()
                    }
                }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@RecentFilesActivity)
                        .setItems(arrayOf("بازکردن", "حذف از فهرست اخیر")) { _, which ->
                            if (which == 0 && file.exists()) {
                                startActivity(Intent(this@RecentFilesActivity, MainActivity::class.java)
                                    .putExtra("open_recent_path", file.absolutePath))
                                finish()
                            } else {
                                RecentFilesStore.remove(this@RecentFilesActivity, file)
                                render()
                            }
                        }.show()
                    true
                }
            }
            val open = MaterialButton(this).apply {
                text = "بازکردن"
                isAllCaps = false
                setOnClickListener { label.performClick() }
            }
            row.addView(label)
            row.addView(open)
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
