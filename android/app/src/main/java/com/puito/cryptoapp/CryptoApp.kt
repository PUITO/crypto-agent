package com.puito.cryptoapp

import android.app.Application
import com.puito.cryptoapp.data.AppRepository

class CryptoApp : Application() {
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
    }
}
