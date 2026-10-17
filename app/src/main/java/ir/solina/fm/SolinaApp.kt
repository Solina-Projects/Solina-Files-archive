package ir.solina.fm

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class SolinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved appearance before any Activity is created so every screen
        // and every dialog uses the same day/night configuration.
        val mode = getSharedPreferences("solina_preferences", MODE_PRIVATE)
            .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
