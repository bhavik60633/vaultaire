package dev.vaultaire.scan

import android.content.Context
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.Preview as PreviewUseCase

/**
 * The phone's camera and ML Kit's text recognition, with the model inside the app (ADR-009).
 *
 * Only the preview and the frame-analysis use cases are bound, never photo or video capture.
 * Each frame is closed as soon as its text is read, and only the lines of text leave this class.
 * The preview is drawn in a TextureView, inside the window, so the window's screenshot blocking
 * covers it too.
 */
class MlKitCardCamera(private val context: Context) : CardCamera {

    private var onText: ((List<String>) -> Unit)? = null
    private var provider: ProcessCameraProvider? = null
    private var recognizer: TextRecognizer? = null
    private var executor: ExecutorService? = null

    @Composable
    override fun Preview(modifier: Modifier) {
        val owner = LocalLifecycleOwner.current
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    bind(owner, this)
                }
            },
            modifier = modifier,
        )
    }

    override fun start(onText: (List<String>) -> Unit) {
        this.onText = onText
    }

    override fun stop() {
        onText = null
        provider?.unbindAll()
        provider = null
        recognizer?.close()
        recognizer = null
        executor?.shutdown()
        executor = null
    }

    @OptIn(ExperimentalGetImage::class)
    private fun bind(owner: LifecycleOwner, view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameras = future.get()
            if (onText == null) return@addListener // stopped before the camera was ready
            val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val worker = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val preview = PreviewUseCase.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(worker) { frame ->
                val image = frame.image
                if (image == null) {
                    frame.close()
                    return@setAnalyzer
                }
                reader.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnSuccessListener(main) { text ->
                        onText?.invoke(text.textBlocks.flatMap { block -> block.lines.map { it.text } })
                    }
                    .addOnCompleteListener { frame.close() }
            }
            cameras.unbindAll()
            cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            provider = cameras
            recognizer = reader
            executor = worker
        }, ContextCompat.getMainExecutor(context))
    }
}
