@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.android.gms.tasks

abstract class Task<T> {
    abstract val isSuccessful: Boolean
    abstract val result: T
    abstract fun addOnCompleteListener(l: OnCompleteListener<T>): Task<T>
}
fun interface OnCompleteListener<T> { fun onComplete(task: Task<T>) }
