package dev.curiobot

import android.app.Application
import dev.curiobot.log.Prefs
import dev.curiobot.log.Telemetry

class CuriobotApp : Application() {
    /** One creature per app process, shared by the main screen and the dev panel. */
    val game: Game by lazy { Game(this) }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Telemetry.init(this)
    }
}
