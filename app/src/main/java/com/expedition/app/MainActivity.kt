package com.expedition.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.expedition.app.ui.theme.ExpeditionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpeditionTheme {
                ExpeditionApp()
            }
        }
    }
}
