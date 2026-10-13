package ru.vorontsov.coach

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

/** Съёмка только живой камерой (без галереи). Возвращает путь к JPEG во внутреннем хранилище. */
class CameraCaptureActivity : ComponentActivity() {
    class Contract : ActivityResultContract<String, String?>() {
        override fun createIntent(c: android.content.Context, input: String) = Intent(c, CameraCaptureActivity::class.java).putExtra("hint", input)
        override fun parseResult(code: Int, i: Intent?) = if (code == Activity.RESULT_OK) i?.getStringExtra("path") else null
    }
    private var capture: ImageCapture? = null
    private var granted by mutableStateOf(false)
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) finish() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) perm.launch(Manifest.permission.CAMERA)
        val hint = intent.getStringExtra("hint") ?: ""
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) AndroidView({ ctx -> PreviewView(ctx).also { pv ->
                val f = ProcessCameraProvider.getInstance(ctx)
                f.addListener({
                    val p = f.get(); val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                    capture = ImageCapture.Builder().setTargetResolution(android.util.Size(1280, 960)).build()
                    p.unbindAll()
                    val sel = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    runCatching { p.bindToLifecycle(this@CameraCaptureActivity, sel, prev, capture) }
                }, ContextCompat.getMainExecutor(ctx))
            } }, Modifier.fillMaxSize())
            Text("$hint\nТолько снимок с камеры — галерея недоступна.", Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xAA000000)).systemBarsPadding().padding(16.dp), color = Color.White)
            var busy by remember { mutableStateOf(false) }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ finish() }) { Text("Отмена", color = Color.White) }
                FilledIconButton({ if (!busy) { busy = true; shoot { busy = false } } }, Modifier.size(84.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)) {
                    if (busy) CircularProgressIndicator(Modifier.size(32.dp)) else Icon(Icons.Default.Camera, null, Modifier.size(44.dp), tint = Color.Black) }
            }
        } } }
    }
    private fun shoot(done: () -> Unit) {
        val cap = capture ?: return done()
        val f = File(File(filesDir, "photos").apply { mkdirs() }, "c_${System.currentTimeMillis()}.jpg")
        cap.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) { done(); setResult(Activity.RESULT_OK, Intent().putExtra("path", f.absolutePath)); finish() }
            override fun onError(e: ImageCaptureException) { done(); android.widget.Toast.makeText(this@CameraCaptureActivity, "Ошибка камеры: ${e.message}", 1).show() }
        })
    }
}
