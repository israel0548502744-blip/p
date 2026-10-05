// Signature stubs for Android-only Jetpack APIs used by the app (compile-time only, never run).
@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable fun <T : View> AndroidView(factory: (Context) -> T, modifier: Modifier = Modifier, update: (T) -> Unit = {}) {}
