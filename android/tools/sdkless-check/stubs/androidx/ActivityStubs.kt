@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity

open class ComponentActivity : android.app.Activity()
fun ComponentActivity.enableEdgeToEdge() {}
inline fun <reified VM : androidx.lifecycle.ViewModel> ComponentActivity.viewModels(): Lazy<VM> = error("stub")
