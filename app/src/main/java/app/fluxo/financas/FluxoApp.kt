package app.fluxo.financas

import android.app.Application
import app.fluxo.financas.notify.Notifier

class FluxoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}
