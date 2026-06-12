package com.socatv.nova

import android.app.Application
import android.util.Log
import com.socatv.nova.data.db.NovaDatabase
import com.socatv.nova.utils.AppSecurity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.SecurityWorker
import com.socatv.nova.utils.UpdateCheckWorker

class NovaApp : Application() {

    companion object {
        lateinit var instance: NovaApp
            private set
        const val TAG = "SocaTvNova"
    }

    lateinit var database: NovaDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        AppSecurity.verify(this)
        instance = this
        Log.d(TAG, "NovaApp starting up")
        Prefs.init(this)
        database = NovaDatabase.getInstance(this)
        SecurityWorker.schedule(this)
        UpdateCheckWorker.schedule(this)
    }
}
