package com.manhwalens.app.overlay

import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A minimal lifecycle/viewmodel/savedstate owner for overlay [ComposeView]s that are
 * attached to a WindowManager window from a Service (no Activity to host them).
 *
 * Required by Compose: without a ViewTreeLifecycleOwner the view throws
 * "ViewTreeLifecycleOwner not found" during attach, and the exception escapes
 * addView()'s try/catch because attach happens during traversal, not inline.
 *
 * Lifecycle: onCreate() (restores SavedState, moves to CREATED) then onResume()
 * (RESUMED) before addView(); onDestroy() after removeView() — which moves to
 * DESTROYED and clears the ViewModelStore. Never reuse a destroyed owner; a new
 * one is created every time an overlay is shown.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        // Must run before the view starts observing the registry.
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/** Wire the three ViewTree owners Compose looks up on a ComposeView. */
fun ComposeView.attachOverlayOwners(owner: OverlayLifecycleOwner) {
    setViewTreeLifecycleOwner(owner)
    setViewTreeViewModelStoreOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
}
