package ir.solina.fm

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class SolinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("solina_preferences", MODE_PRIVATE)

        // Apply the saved appearance before any Activity is created.
        val mode = preferences.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)

        // Dynamic colors are opt-in so the app's brand palette remains the default.
        // Settings can persist "dynamic_colors" to enable Android's wallpaper palette.
        if (preferences.getBoolean("dynamic_colors", false)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
