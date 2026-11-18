package com.akki.photostudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF10111A)
private val Panel = Color(0xFF1B1D2A)
private val Accent = Color(0xFF9C7BFF)
private val Muted = Color(0xFF9B9CAE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
                StudioScreen(
                    onLoad = { uri, done -> removeBackground(uri, done) },
                    onExport = { bitmap -> savePng(bitmap) }
                )
            }
        }
    }

    private fun removeBackground(uri: Uri, done: (Bitmap?) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                        ?: return@runCatching null
                    val image = InputImage.fromBitmap(source, 0)
                    val options = SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .enableRawSizeMask()
                        .build()
                    val segmenter = Segmentation.getClient(options)
                    try {
                        val mask = segmenter.process(image).await()
                        val buffer = mask.buffer
                        val width = mask.width()
                        val height = mask.height()
                        buffer.rewind()
                        val alpha = IntArray(width * height)
                        for (i in alpha.indices) {
                            val a = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
                            alpha[i] = (a shl 24) or 0x00FFFFFF
                        }
                        val maskBitmap = Bitmap.createBitmap(alpha, width, height, Bitmap.Config.ARGB_8888)
                        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(output)
                        canvas.drawBitmap(source, 0f, 0f, null)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        }
                        canvas.drawBitmap(maskBitmap, null, Rect(0, 0, source.width, source.height), paint)
                        paint.xfermode = null
                        maskBitmap.recycle()
                        output
                    } finally {
                        segmenter.close()
                    }
                }.getOrNull()
            }
            done(result)
        }
    }

    private fun savePng(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AkkiStudio_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Akki Photo Studio")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Composable
private fun StudioScreen(
    onLoad: (Uri, (Bitmap?) -> Unit) -> Unit,
    onExport: (Bitmap) -> Unit
) {
    var edited by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a photo to begin editing") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            status = "Removing background on your device…"
            onLoad(uri) { bitmap ->
                edited = bitmap
                busy = false
                status = if (bitmap != null) "Background removed · Ready to export" else "Could not process this photo. Try a clear portrait."
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Accent.copy(alpha = .18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AKKI", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 18.sp)
                    Text("PHOTO STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Icon(Icons.Default.MoreHoriz, null, tint = Color.White)
            }
            Spacer(Modifier.height(26.dp))
            Text("Create something", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("beautiful.", color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(26.dp))
                    .background(Panel, RoundedCornerShape(26.dp))
                    .clickable { picker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                val image = edited
                if (image != null) {
                    Image(image.asImageBitmap(), null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = Accent, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Tap to add your photo", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Portraits work best for AI background tools", color = Muted, fontSize = 12.sp)
                    }
                }
                if (busy) CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(status, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Text("QUICK TOOLS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolTile("Remove BG", Icons.Default.AutoFixHigh, Modifier.weight(1f)) { picker.launch("image/*") }
                ToolTile("Background Blur", Icons.Default.BlurOn, Modifier.weight(1f)) { status = "Background blur is planned for the next implementation phase." }
                ToolTile("Replace BG", Icons.Default.Wallpaper, Modifier.weight(1f)) { status = "Background replacement is planned for the next implementation phase." }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(7.dp)); Text("Open photo")
                }
                Button(onClick = { edited?.let(onExport) }, enabled = edited != null && !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(7.dp)); Text("Export PNG")
                }
            }
        }
    }
}

@Composable
private fun ToolTile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(Panel, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 15.dp, horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
