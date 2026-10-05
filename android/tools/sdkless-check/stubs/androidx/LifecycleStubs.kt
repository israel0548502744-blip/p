@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope

abstract class ViewModel
open class AndroidViewModel(private val application: android.app.Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : android.app.Application> getApplication(): T = application as T
}
val ViewModel.viewModelScope: CoroutineScope get() = error("stub")
