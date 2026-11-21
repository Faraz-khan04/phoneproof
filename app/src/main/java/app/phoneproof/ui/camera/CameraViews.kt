package app.phoneproof.ui.camera

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Live camera preview + a frame analyzer. Binds to the screen's lifecycle and unbinds on leave.
 * Requires CAMERA permission to have been granted already.
 */
@Composable
fun CameraPreview(
    front: Boolean,
    modifier: Modifier = Modifier,
    analyzer: ((ImageProxy) -> Unit)? = null,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentAnalyzer = rememberUpdatedState(analyzer)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(front) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get().also { provider = it }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(executor) { img ->
                        try { currentAnalyzer.value?.invoke(img) } finally { img.close() }
                    }
                }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { provider?.unbindAll() } }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Mean brightness (0–255) of the Y plane, sampled sparsely. */
fun meanLuma(img: ImageProxy): Int {
    val plane = img.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    var sum = 0L; var n = 0
    var y = 0
    while (y < img.height) {
        var x = 0
        while (x < img.width) {
            val i = y * rowStride + x
            if (i < buf.limit()) { sum += (buf.get(i).toInt() and 0xFF); n++ }
            x += 16
        }
        y += 16
    }
    return if (n == 0) 0 else (sum / n).toInt()
}

/**
 * QR scanner on the back camera. Calls [onCode] for every distinct QR text seen
 * (multi-frame codes arrive as many calls). ML Kit model is bundled in the app.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val latest = rememberUpdatedState(onCode)
    val seen = remember { HashSet<String>() }
    val main = ContextCompat.getMainExecutor(LocalContext.current)
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    CameraPreview(front = false, modifier = modifier) { proxy ->
        val media = proxy.image ?: return@CameraPreview
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        // Block this analyzer thread until ML Kit finishes, so the proxy stays valid.
        val task = scanner.process(input)
        runCatching { com.google.android.gms.tasks.Tasks.await(task) }.getOrNull()?.forEach { b ->
            val text = b.rawValue ?: return@forEach
            if (seen.add(text)) main.execute { latest.value(text) }
        }
    }
}
