@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.StateFlow

@Composable fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = error("stub")
