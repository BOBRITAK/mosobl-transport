package ru.podmoskovye.transport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ru.podmoskovye.transport.data.Prefs
import ru.podmoskovye.transport.ui.MainScreen
import ru.podmoskovye.transport.ui.Pal
import ru.podmoskovye.transport.ui.TransportTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            TransportTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Pal.Bg) {
                    MainScreen(applicationContext, prefs)
                }
            }
        }
    }
}
