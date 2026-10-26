package bo.app.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** Captura con CameraX cuando el escáner de ML Kit no está disponible; el recorte se hace con ManualCropCanvas. */
@Composable
fun CameraCaptureScreen(onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onClose()
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val capture = remember {
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            }
            val previewView = remember { PreviewView(context) }
            var shooting by remember { mutableStateOf(false) }

            DisposableEffect(owner) {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }, ContextCompat.getMainExecutor(context))
                onDispose { runCatching { future.get().unbindAll() } }
            }

            AndroidView({ previewView }, Modifier.fillMaxSize())

            Surface(
                onClick = {
                    if (shooting) return@Surface
                    shooting = true
                    val file = File(context.cacheDir, "captura_${System.currentTimeMillis()}.jpg")
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                shooting = false
                                onCaptured(file)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                shooting = false
                            }
                        },
                    )
                },
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 32.dp)
                    .size(76.dp)
                    .border(4.dp, Color.Gray, CircleShape),
            ) {
                if (shooting) Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            Text("Se necesita permiso de cámara", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        IconButton(onClick = onClose, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
            Icon(Icons.Default.Close, "Cerrar", tint = Color.White)
        }
    }
}
