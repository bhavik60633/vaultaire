package dev.vaultaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.vaultaire.scan.CardCamera

/**
 * Component 18 (milestone M5): the camera for the scanner tests. No picture; the test hands it
 * the lines of text the real camera's text recognition would have found in one frame.
 */
class FakeCamera : CardCamera {
    var starts = 0
        private set
    var running = false
        private set
    private var onText: ((List<String>) -> Unit)? = null

    @Composable
    override fun Preview(modifier: Modifier) {
        Box(modifier)
    }

    override fun start(onText: (List<String>) -> Unit) {
        starts++
        running = true
        this.onText = onText
    }

    override fun stop() {
        running = false
        onText = null
    }

    /** One frame in which the camera's text recognition found [lines]. */
    fun show(vararg lines: String) {
        onText?.invoke(lines.toList())
    }
}
