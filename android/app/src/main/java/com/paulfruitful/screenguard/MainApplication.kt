package com.paulfruitful.screenguard

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.paulfruitful.screenguard.bridge.ScreenGuardPackage
import com.paulfruitful.screenguard.classifier.DetectionEngine
import com.paulfruitful.screenguard.storage.LocalStorage

class MainApplication : Application(), ReactApplication {

    override val reactHost: ReactHost by lazy {
        getDefaultReactHost(
            context = applicationContext,
            packageList = PackageList(this).packages.apply {
                add(ScreenGuardPackage())
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        LocalStorage.init(this)
        DetectionEngine.init(this)
        loadReactNative(this)
    }
}
