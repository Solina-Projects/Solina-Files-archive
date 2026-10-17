package ir.solina.fm

import android.app.Application
import com.google.android.material.color.DynamicColors

class SolinaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
