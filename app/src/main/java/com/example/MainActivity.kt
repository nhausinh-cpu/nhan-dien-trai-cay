package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

sealed interface AppState {
    object RequestPermission : AppState
    object PermissionDenied : AppState
    object CameraPreview : AppState
    data class Analyzing(val bitmap: Bitmap) : AppState
    data class Result(val bitmap: Bitmap, val text: String, val usedKeyLabel: String? = null) : AppState
}

class MainActivity : ComponentActivity() {

    private var ttsManager: TtsManager? = null
    private val isSpeaking = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize TextToSpeech engine
        ttsManager = TtsManager(
            context = this,
            onSpeakingStateChanged = { speaking ->
                isSpeaking.value = speaking
            },
            onMessage = { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        )

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent
                ) { innerPadding ->
                    FruitAppScreen(
                        modifier = Modifier.padding(innerPadding),
                        isSpeaking = isSpeaking.value,
                        onSpeak = { text -> ttsManager?.speak(text) },
                        onStopSpeaking = { ttsManager?.stop() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FruitAppScreen(
    modifier: Modifier = Modifier,
    isSpeaking: Boolean,
    onSpeak: (String) -> Unit,
    onStopSpeaking: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var appState by remember { mutableStateOf<AppState>(AppState.RequestPermission) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }

    var zoomRatio by remember { mutableStateOf(1.0f) }
    var minZoom by remember { mutableStateOf(1.0f) }
    var maxZoom by remember { mutableStateOf(5.0f) }

    fun startAnalysis(bitmap: Bitmap) {
        onStopSpeaking()
        appState = AppState.Analyzing(bitmap)
        analysisJob?.cancel()
        analysisJob = coroutineScope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) { bitmap.toBase64() }
                val apiKeys = GeminiService.getActiveApiKeys(context)
                val response = GeminiService.analyzeFruitImage(
                    apiKeys = apiKeys,
                    base64Image = base64
                )
                appState = AppState.Result(bitmap, response.text, response.usedKeyLabel)
            } catch (e: Exception) {
                appState = AppState.Result(bitmap, "Lỗi khi nhận diện hình ảnh: ${e.localizedMessage}", null)
            }
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadAndResizeBitmap(context, uri, maxDimension = 800)
                }
                if (bitmap != null) {
                    startAnalysis(bitmap)
                } else {
                    Toast.makeText(context, "Không thể mở ảnh từ thư viện.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appState = AppState.CameraPreview
        } else {
            appState = AppState.PermissionDenied
        }
    }

    // Check camera permission
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            appState = AppState.CameraPreview
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKeys = getCustomApiKeys(context),
            onDismiss = { showApiKeyDialog = false },
            onSave = { newKeys ->
                saveCustomApiKeys(context, newKeys)
                Toast.makeText(context, "Đã lưu danh sách Gemini API Keys.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8F5E9), // Soft fresh light green
                        Color(0xFFF1F8E9)  // Soft warm pastel cream
                    )
                )
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App header with API Key dialog launcher
        AppHeader(
            onOpenApiKeyDialog = { showApiKeyDialog = true }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val state = appState) {
                is AppState.RequestPermission -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("loading_permission")
                    )
                }

                is AppState.PermissionDenied -> {
                    PermissionDeniedView(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onPickFromGallery = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }

                is AppState.CameraPreview -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp))
                            .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                    ) {
                        CameraPreviewView(
                            modifier = Modifier.fillMaxSize(),
                            imageCapture = imageCapture,
                            zoomRatio = zoomRatio,
                            onZoomRatioChanged = { zoomRatio = it },
                            onZoomRangeDetermined = { min, max ->
                                minZoom = min
                                maxZoom = max
                            }
                        )

                        // Visual scanning target/frame overlay
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .align(Alignment.Center)
                                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                        ) {
                            // Subtle text inside scanner frame
                            Text(
                                text = "Đặt trái cây tại đây",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            )
                        }

                        // Camera Zoom Slider Control Overlay
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 96.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Zoom: ${"%.1f".format(zoomRatio)}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Slider(
                                value = zoomRatio,
                                onValueChange = {
                                    zoomRatio = it
                                },
                                valueRange = if (maxZoom > minZoom) minZoom..maxZoom else 1.0f..5.0f,
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(32.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.5f)
                                )
                            )
                        }

                        // Bottom Actions: Gallery Picker & Shutter Buttons side by side
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Gallery button
                            Button(
                                onClick = {
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.95f),
                                    contentColor = Color(0xFF2E7D32)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .testTag("gallery_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = "Chọn ảnh từ thư viện",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Thư viện ảnh",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            // Capture Button
                            Button(
                                onClick = {
                                    takePicture(
                                        context = context,
                                        imageCapture = imageCapture,
                                        onSuccess = { resizedBitmap ->
                                            startAnalysis(resizedBitmap)
                                        },
                                        onError = { exception ->
                                            errorMessage = "Không thể chụp ảnh: ${exception.localizedMessage}"
                                            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .testTag("capture_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Chụp ảnh",
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Chụp ảnh",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                is AppState.Analyzing -> {
                    var elapsedSeconds by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(1000L)
                            elapsedSeconds++
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp))
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                    ) {
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "Ảnh chụp",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        // Loading overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.65f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 4.dp,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Đang nhận diện...",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Đang kết nối Gemini AI (${elapsedSeconds}s / tối đa 15s)",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                OutlinedButton(
                                    onClick = {
                                        analysisJob?.cancel()
                                        appState = AppState.CameraPreview
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hủy",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Hủy nhận diện", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                is AppState.Result -> {
                    val isError = state.text.startsWith("Lỗi", ignoreCase = true) ||
                                  state.text.contains("quá thời gian", ignoreCase = true) ||
                                  state.text.contains("hết thời gian", ignoreCase = true)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Display the captured image cropped
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(
                                    2.dp,
                                    if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    RoundedCornerShape(24.dp)
                                )
                                .shadow(6.dp, RoundedCornerShape(24.dp))
                        ) {
                            Image(
                                bitmap = state.bitmap.asImageBitmap(),
                                contentDescription = "Trái cây đã chụp",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isError) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(4.dp, RoundedCornerShape(16.dp))
                                    .testTag("error_card"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFF3E0)
                                )
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = "Lỗi",
                                                tint = Color(0xFFD32F2F),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Không nhận diện được",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFD32F2F)
                                            )
                                        }
                                        state.usedKeyLabel?.let { label ->
                                            Surface(
                                                color = Color(0xFFFFCC80),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFFE65100),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Divider(color = Color(0xFFD32F2F).copy(alpha = 0.2f))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = state.text,
                                        fontSize = 15.sp,
                                        color = Color(0xFF37474F),
                                        lineHeight = 22.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { startAnalysis(state.bitmap) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("retry_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Thử lại",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Thử lại", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        onStopSpeaking()
                                        appState = AppState.CameraPreview
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .testTag("retake_error_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Chụp lại",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Chụp lại", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (state.text.contains("khóa", ignoreCase = true) ||
                                state.text.contains("API", ignoreCase = true) ||
                                state.text.contains("403") || state.text.contains("400")
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))
                                TextButton(
                                    onClick = { showApiKeyDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cấu hình Gemini API Key...", fontWeight = FontWeight.Medium)
                                }
                            }
                        } else {
                            // Text-To-Speech Auto triggers once result text becomes ready
                            LaunchedEffect(state.text) {
                                // Strip symbols or clean the text slightly if needed for better speech flow
                                val cleanSpeechText = state.text
                                    .replace("*", "")
                                    .replace("#", "")
                                onSpeak(cleanSpeechText)
                            }

                        // Display Gemini Result Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(4.dp, RoundedCornerShape(16.dp))
                                .testTag("result_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Kết quả nhận diện",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    state.usedKeyLabel?.let { label ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                Spacer(modifier = Modifier.height(12.dp))

                                FormattedResultText(text = state.text)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Speech & Navigation Controls Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Speaker / TTS Readout Controller
                            if (isSpeaking) {
                                Button(
                                    onClick = { onStopSpeaking() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .shadow(3.dp, RoundedCornerShape(14.dp))
                                        .testTag("stop_speech_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Dừng đọc"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Dừng đọc", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val cleanSpeechText = state.text
                                            .replace("*", "")
                                            .replace("#", "")
                                        onSpeak(cleanSpeechText)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .shadow(3.dp, RoundedCornerShape(14.dp))
                                        .testTag("speak_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Đọc lại"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Đọc lại", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            // Retake Photo Button
                            Button(
                                onClick = {
                                    onStopSpeaking()
                                    appState = AppState.CameraPreview
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(52.dp)
                                    .shadow(4.dp, RoundedCornerShape(14.dp))
                                    .testTag("retake_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Chụp lại"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Chụp lại", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun AppHeader(onOpenApiKeyDialog: () -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nhận diện trái cây 🍎🍊",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Hướng ống kính về phía trái cây hoặc rau củ",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            IconButton(
                onClick = onOpenApiKeyDialog,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Cấu hình API Key",
                    tint = Color(0xFF2E7D32)
                )
            }
        }
    }
}

@Composable
fun PermissionDeniedView(
    onRequestPermission: () -> Unit,
    onPickFromGallery: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Cảnh báo quyền",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Cần quyền truy cập Camera",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Ứng dụng cần sử dụng camera để chụp ảnh trái cây trực tiếp. Bạn cũng có thể chọn ảnh có sẵn từ thư viện mà không cần cấp quyền camera.",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Cấp quyền truy cập", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onPickFromGallery,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chọn ảnh từ thư viện", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    imageCapture: ImageCapture,
    zoomRatio: Float,
    onZoomRatioChanged: (Float) -> Unit,
    onZoomRangeDetermined: (minZoom: Float, maxZoom: Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var activeCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    var minZoom by remember { mutableStateOf(1.0f) }
    var maxZoom by remember { mutableStateOf(5.0f) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                activeCameraProvider?.unbindAll()
            } catch (exc: Exception) {
                Log.e("CameraPreviewView", "Unbinding on dispose failed", exc)
            }
        }
    }

    DisposableEffect(camera) {
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            if (state != null) {
                minZoom = state.minZoomRatio
                maxZoom = minOf(state.maxZoomRatio, 5.0f)
                onZoomRangeDetermined(minZoom, maxZoom)
            }
        }
        camera?.cameraInfo?.zoomState?.observe(lifecycleOwner, observer)
        onDispose {
            camera?.cameraInfo?.zoomState?.removeObserver(observer)
        }
    }

    LaunchedEffect(zoomRatio, camera) {
        try {
            camera?.cameraControl?.setZoomRatio(zoomRatio)
        } catch (exc: Exception) {
            Log.e("CameraPreviewView", "Failed to set zoom ratio", exc)
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    activeCameraProvider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider.unbindAll()
                    val cameraInstance = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    camera = cameraInstance
                } catch (exc: Exception) {
                    Log.e("CameraPreviewView", "Binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
        modifier = modifier
            .pointerInput(zoomRatio, minZoom, maxZoom) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (zoomRatio * zoom).coerceIn(minZoom, maxZoom)
                    onZoomRatioChanged(newZoom)
                }
            }
    )
}

@Composable
fun FormattedResultText(text: String, modifier: Modifier = Modifier) {
    val lines = text.split("\n")
    Column(modifier = modifier.fillMaxWidth()) {
        lines.forEach { line ->
            if (line.isBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
            } else {
                // Remove some markdown symbols like stars or hashes
                val cleanedLine = line
                    .replace("**", "")
                    .replace("*", "")
                    .replace("#", "")
                    .trim()

                val boldIndex = cleanedLine.indexOf(":")
                if (boldIndex != -1 && boldIndex < 40) {
                    val key = cleanedLine.substring(0, boldIndex + 1)
                    val value = cleanedLine.substring(boldIndex + 1).trim()
                    
                    val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        append(key)
                        pop()
                        append(" ")
                        pushStyle(
                            androidx.compose.ui.text.SpanStyle(
                                fontWeight = FontWeight.Normal,
                                color = Color(0xFF333333)
                            )
                        )
                        append(value)
                        pop()
                    }
                    
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                } else {
                    Text(
                        text = cleanedLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF333333),
                        lineHeight = 24.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

fun takePicture(
    context: Context,
    imageCapture: ImageCapture,
    onSuccess: (Bitmap) -> Unit,
    onError: (Exception) -> Unit
) {
    val file = File(context.cacheDir, "captured_fruit_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val resizedBitmap = loadAndResizeBitmapFromFile(file.absolutePath, 800)
                    file.delete()
                    if (resizedBitmap == null) {
                        onError(Exception("Không thể giải mã hình ảnh vừa chụp."))
                        return
                    }
                    onSuccess(resizedBitmap)
                } catch (e: Exception) {
                    onError(e)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

fun loadAndResizeBitmapFromFile(filePath: String, maxDimension: Int = 800): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, boundsOptions)

        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while ((rawWidth / sampleSize) > maxDimension * 2 || (rawHeight / sampleSize) > maxDimension * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val sampledBitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null
        val orientedBitmap = rotateBitmapIfRequired(sampledBitmap, filePath)
        resizeBitmap(orientedBitmap, maxDimension)
    } catch (e: Exception) {
        Log.e("loadAndResizeFile", "Error loading file $filePath", e)
        null
    }
}

fun rotateBitmapIfRequired(bitmap: Bitmap, imagePath: String): Bitmap {
    return try {
        val exifInterface = android.media.ExifInterface(imagePath)
        val orientation = exifInterface.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
            else -> bitmap
        }
    } catch (e: Exception) {
        Log.e("RotateBitmap", "Failed to get EXIF orientation", e)
        bitmap
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun Bitmap.toBase64(): String {
    val outputStream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDimension && height <= maxDimension) return bitmap

    val ratio = width.toFloat() / height.toFloat()
    val newWidth: Int
    val newHeight: Int
    if (width > height) {
        newWidth = maxDimension
        newHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
    } else {
        newHeight = maxDimension
        newWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
    }
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

fun loadAndResizeBitmap(context: Context, uri: Uri, maxDimension: Int = 800): Bitmap? {
    return try {
        // Step 1: Decode bounds only
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while ((rawWidth / sampleSize) > maxDimension * 2 || (rawHeight / sampleSize) > maxDimension * 2) {
            sampleSize *= 2
        }

        // Step 2: Decode bitmap with sampleSize
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        // Step 3: Check EXIF orientation
        val orientedBitmap = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(sampledBitmap, 90f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(sampledBitmap, 180f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(sampledBitmap, 270f)
                    else -> sampledBitmap
                }
            } ?: sampledBitmap
        } catch (e: Exception) {
            sampledBitmap
        }

        // Step 4: Scale down precisely if needed
        resizeBitmap(orientedBitmap, maxDimension)
    } catch (e: Exception) {
        Log.e("loadAndResizeBitmap", "Failed to load and resize bitmap from uri: $uri", e)
        null
    }
}

fun getCustomApiKeys(context: Context): List<String> {
    val prefs = context.getSharedPreferences("fruit_app_prefs", Context.MODE_PRIVATE)
    
    // Auto-migration of old single custom key to Key 1
    val oldKey = prefs.getString("custom_gemini_api_key", "") ?: ""
    if (oldKey.isNotBlank()) {
        val slot1 = prefs.getString("custom_gemini_api_key_1", "") ?: ""
        if (slot1.isBlank()) {
            prefs.edit()
                .putString("custom_gemini_api_key_1", oldKey)
                .remove("custom_gemini_api_key")
                .apply()
        }
    }

    return List(5) { i ->
        prefs.getString("custom_gemini_api_key_${i + 1}", "") ?: ""
    }
}

fun saveCustomApiKeys(context: Context, keys: List<String>) {
    val prefs = context.getSharedPreferences("fruit_app_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    keys.forEachIndexed { index, key ->
        editor.putString("custom_gemini_api_key_${index + 1}", key.trim())
    }
    editor.apply()
}

@Composable
fun ApiKeyDialog(
    currentKeys: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var inputKeys by remember {
        mutableStateOf(
            currentKeys.map { if (it == "YOUR_GEMINI_API_KEY") "" else it }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Cấu hình Gemini API Keys", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Nhập tối đa 5 khóa API dự phòng. Hệ thống tự động chuyển khóa tiếp theo nếu khóa hiện tại hết hạn mức (429):",
                    fontSize = 14.sp,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(16.dp))

                for (i in 0 until 5) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = "Khóa API ${i + 1}${if (i == 0) " (Ưu tiên 1 - Cao nhất)" else ""}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (i == 0) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = inputKeys.getOrElse(i) { "" },
                            onValueChange = { newVal ->
                                val newList = inputKeys.toMutableList()
                                if (i < newList.size) {
                                    newList[i] = newVal
                                } else {
                                    newList.add(newVal)
                                }
                                inputKeys = newList
                            },
                            placeholder = { Text("AIzaSy... (Để trống nếu bỏ qua)") },
                            singleLine = true,
                            trailingIcon = {
                                if (inputKeys.getOrElse(i) { "" }.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            val newList = inputKeys.toMutableList()
                                            newList[i] = ""
                                            inputKeys = newList
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Xóa khóa này",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(inputKeys)
                    onDismiss()
                }
            ) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}
