package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ZeroLogEngine
import com.example.ui.ChatViewModel
import com.example.ui.SimpleXAppContent
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Prevent screenshots, screen capture, and system Recents preview thumbnails
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    ZeroLogEngine.log("Security", "FLAG_SECURE screenshot & recents shield enabled.")

    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val model: ChatViewModel = viewModel()
        val lifecycleOwner = LocalLifecycleOwner.current

        DisposableEffect(lifecycleOwner, model) {
          val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations) {
              model.onAppBackgrounded()
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
          }
        }

        SimpleXAppContent(viewModel = model)
      }
    }
  }
}
