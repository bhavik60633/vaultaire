package dev.vaultaire.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Where the scanner's text comes from: the phone's camera with on-device text recognition
 * ([MlKitCardCamera]), or a test's frames. Only text leaves it, one frame's lines at a time;
 * no picture is kept or written anywhere (ADR-009).
 */
interface CardCamera {
    /** The live picture while the camera runs. */
    @Composable
    fun Preview(modifier: Modifier)

    /** Starts reading; each frame's recognised lines go to [onText], on the main thread. */
    fun start(onText: (List<String>) -> Unit)

    /** Stops the camera and forgets what it was given. */
    fun stop()
}

/** The camera for the scanner; null on a phone without one, and then there is no Scan button. */
val LocalCardCamera = staticCompositionLocalOf<CardCamera?> { null }
