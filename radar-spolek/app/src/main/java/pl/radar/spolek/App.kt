package pl.radar.spolek

import android.app.Application
import pl.radar.spolek.data.SettingsStore
import pl.radar.spolek.work.Notifications
import pl.radar.spolek.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        if (SettingsStore(this).load().weeklyEnabled) Scheduler.scheduleWeekly(this)
    }
}
