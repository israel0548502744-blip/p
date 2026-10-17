@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

abstract class ActivityResultLauncher<I> { abstract fun launch(input: I) }
class PickVisualMediaRequest
fun PickVisualMediaRequest(mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo): PickVisualMediaRequest = error("stub")
class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
class IntentSenderRequest private constructor() {
    class Builder(sender: android.content.IntentSender) { fun build(): IntentSenderRequest = error("stub") }
}
