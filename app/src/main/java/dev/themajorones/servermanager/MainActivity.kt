package dev.themajorones.servermanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.themajorones.servermanager.ui.ServerManagerNavHost
import dev.themajorones.servermanager.ui.theme.ServerManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ServerManagerApp
        setContent {
            ServerManagerTheme {
                ServerManagerNavHost(app.container)
            }
        }
    }
}
