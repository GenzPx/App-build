package com.monitorcheck

import android.app.Application
import com.monitorcheck.logs.CrashReporter

class MonitoredCheckApp : Application() {

    override fun onCreate() {
        super.onCreate()

        CrashReporter(this).install()
    }
}
