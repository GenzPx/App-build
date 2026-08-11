package com.monitorcheck

import android.app.Application
import com.monitorcheck.logs.CrashReporter

/**
 * Application entry point.
 *
 * Deliberately minimal: the only global setup is the local crash reporter. There is
 * no analytics SDK, no network client, and no background work started here.
 */
class MonitoredCheckApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Captures uncaught exceptions to app-private storage, then delegates to the
        // platform handler. Nothing is uploaded.
        CrashReporter(this).install()
    }
}
