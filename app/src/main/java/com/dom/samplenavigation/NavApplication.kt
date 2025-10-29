package com.dom.samplenavigation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.dom.samplenavigation.constant.PREF_NAME
import com.naver.maps.map.NaverMapSdk
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class NavApplication : Application() {
    // init
    init {
        instance = this
    }

    // companion object
    companion object {

        lateinit var instance: NavApplication

        lateinit var pref:
                SharedPreferences


        fun applicationContext(): Context {
            return instance.applicationContext
        }

        // initialize shared preferences
        fun initSharedPreferences() {
            pref = instance.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        }
    }

    // onCreate
    override fun onCreate() {
        super.onCreate()
        
        // Timber 초기화
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        initSharedPreferences()
        NaverMapSdk.getInstance(this).client =
            NaverMapSdk.NcpKeyClient(getString(R.string.naverMapClientId))
    }
}
