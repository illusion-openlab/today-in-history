package tech.illusion.todayinhistory.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.todayinhistory.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
