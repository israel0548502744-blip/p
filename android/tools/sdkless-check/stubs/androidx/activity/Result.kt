@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

abstract class ActivityResultLauncher<I> { abstract fun launch(input: I) }
class PickVisualMediaRequest
fun PickVisualMediaRequest(mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo): PickVisualMediaRequest = error("stub")
