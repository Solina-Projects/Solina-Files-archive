package ir.solina.fm

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class RecycleBinActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        root.addView(TextView(this).apply { text = "سطل بازیافت"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "فایل‌ها تا زمان حذف دائمی یا بازیابی، با تغییر نام مخفی در محل اصلی نگه داشته می‌شوند."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(10))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "بستن"; setOnClickListener { finish() } })
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val entries = TrashStore.entries(this).asReversed()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "سطل بازیافت خالی است."
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, dp(28))
            })
            return
        }
        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@RecycleBinActivity, R.drawable.item_bg)
            }
            card.addView(TextView(this).apply { text = entry.original.name; textSize = 16f })
            card.addView(TextView(this).apply {
                text = entry.original.parent ?: entry.original.absolutePath
                textSize = 11f
                setTextIsSelectable(true)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this@RecycleBinActivity).apply {
                text = "بازیابی"
                isAllCaps = false
                setOnClickListener {
                    if (entry.original.exists()) {
                        Toast.makeText(this@RecycleBinActivity, "فایلی با این نام در محل اصلی وجود دارد؛ ابتدا آن را جابه‌جا یا تغییر نام بده.", Toast.LENGTH_LONG).show()
                    } else if (TrashStore.restore(this@RecycleBinActivity, entry)) {
                        Toast.makeText(this@RecycleBinActivity, "فایل بازیابی شد", Toast.LENGTH_SHORT).show()
                        render()
                    } else Toast.makeText(this@RecycleBinActivity, "بازیابی ناموفق بود", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this@RecycleBinActivity).apply {
                text = "حذف دائمی"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@RecycleBinActivity).setTitle("حذف دائمی")
                        .setMessage("«${entry.original.name}» برای همیشه حذف شود؟ این کار قابل بازگشت نیست.")
                        .setPositiveButton("حذف دائمی") { _, _ ->
                            if (TrashStore.deletePermanently(this@RecycleBinActivity, entry)) render()
                            else Toast.makeText(this@RecycleBinActivity, "حذف ناموفق بود", Toast.LENGTH_SHORT).show()
                        }.setNegativeButton("لغو", null).show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
