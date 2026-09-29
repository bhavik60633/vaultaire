// Phase-0 spike: THROWAWAY CODE. Not product code; see spikes/phase0/README.md.
package dev.vaultaire.spike

/** ADR-001 criterion 3: the same library reached through plain JNI instead of JNA. */
object Jni {
    init {
        System.loadLibrary("spike_core")
    }

    external fun noop(x: Int): Int
}
