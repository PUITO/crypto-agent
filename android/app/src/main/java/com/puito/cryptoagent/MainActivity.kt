package com.puito.cryptoagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.puito.cryptoagent.ui.AppNav
import com.puito.cryptoagent.ui.theme.AgentTheme

class MainActivity : ComponentActivity() {
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AgentApp
        setContent {
            AgentTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppNav(app.repo)
                }
            }
        }
    }
}
