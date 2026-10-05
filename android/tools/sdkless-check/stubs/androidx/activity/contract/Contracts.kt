@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest

abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    open class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
    }
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
