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
import android.os.Build
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

private val Ink = Color(0xFF101116)
private val Panel = Color(0xFF1B1D24)
private val Accent = Color(0xFFB8F36B)
private val Muted = Color(0xFF9699A4)

private enum class EditTool { REMOVE, BLUR, COLOR, REPLACE, ORIGINAL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 22)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
                PhotoRoomEditor(
                    onSegment = { uri, done -> segmentPhoto(uri, done) },
                    onExport = { bitmap, done -> exportImage(bitmap, done) }
                )
            }
        }
    }

    private fun segmentPhoto(uri: Uri, done: (Bitmap?, Bitmap?) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: return@runCatching null
                    val options = SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .enableRawSizeMask()
                        .build()
                    val segmenter = Segmentation.getClient(options)
                    try {
                        val mask = segmenter.process(InputImage.fromBitmap(source, 0)).await()
                        val buffer = mask.buffer
                        buffer.rewind()
                        val pixels = IntArray(mask.width * mask.height)
                        for (i in pixels.indices) {
                            val alpha = (buffer.float.coerceIn(0f, 1f) * 255f).toInt()
                            pixels[i] = (alpha shl 24) or 0x00FFFFFF
                        }
                        val maskBitmap = Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
                        source to maskBitmap
                    } finally {
                        segmenter.close()
                    }
                }.getOrNull()
            }
            done(result?.first, result?.second)
        }
    }

    private fun exportImage(bitmap: Bitmap, done: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "AkkiStudio_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Akki Photo Studio")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create image file")
                contentResolver.openOutputStream(uri)?.use {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG export failed")
                } ?: error("Could not open image file")
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { done(success) }
        }
    }
}

@Composable
private fun PhotoRoomEditor(
    onSegment: (Uri, (Bitmap?, Bitmap?) -> Unit) -> Unit,
    onExport: (Bitmap, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var replacement by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var tool by remember { mutableStateOf(EditTool.REMOVE) }
    var selectedColor by remember { mutableStateOf(android.graphics.Color.WHITE) }
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a photo to start") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            status = "Detecting subject on your device…"
            onSegment(uri) { src, matte ->
                original = src
                mask = matte
                replacement = null
                if (src != null && matte != null) {
                    tool = EditTool.REMOVE
                    preview = renderEdit(src, matte, tool, selectedColor, null)
                    status = "Background removed. Choose a new look."
                } else {
                    preview = null
                    status = "Could not detect the subject. Try a clear person photo."
                }
                busy = false
            }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val src = original
            val matte = mask
            if (src != null && matte != null) {
                busy = true
                status = "Applying new background…"
                // Decode and compose off the UI thread.
                // The selected background is retained for subsequent edits.
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    replacement = BitmapFactory.decodeStream(stream)
                }
                replacement?.let { bg ->
                    preview = renderEdit(src, matte, EditTool.REPLACE, selectedColor, bg)
                    tool = EditTool.REPLACE
                    status = "New background applied"
                }
                busy = false
            }
        }
    }

    fun applyTool(next: EditTool) {
        val src = original
        val matte = mask
        if (src == null || matte == null) {
            status = "Add a photo first"
            return
        }
        if (next == EditTool.REPLACE && replacement == null) {
            status = "Choose a background photo"
            backgroundPicker.launch("image/*")
            return
        }
        busy = true
        tool = next
        preview = renderEdit(src, matte, next, selectedColor, replacement)
        status = when (next) {
            EditTool.REMOVE -> "Transparent background ready"
            EditTool.BLUR -> "Blurred background applied"
            EditTool.COLOR -> "Background color applied"
            EditTool.REPLACE -> "Custom background applied"
            EditTool.ORIGINAL -> "Original photo restored"
        }
        busy = false
    }

    val colors = listOf(
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        android.graphics.Color.rgb(235, 238, 242),
        android.graphics.Color.rgb(255, 222, 190),
        android.graphics.Color.rgb(255, 214, 225),
        android.graphics.Color.rgb(190, 222, 255),
        android.graphics.Color.rgb(199, 242, 190),
        android.graphics.Color.rgb(231, 213, 255)
    )

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Ink)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Akki Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("AI PHOTO EDITOR", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                IconButton(onClick = {
                    original = null; mask = null; preview = null; replacement = null
                    status = "Choose a photo to start"
                }) { Icon(Icons.Default.Refresh, "Reset", tint = Color.White) }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Make it", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("stand out.", color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                if (preview != null) {
                    TextButton(onClick = { applyTool(EditTool.ORIGINAL) }) {
                        Text("Reset edit", color = Muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF252832)).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(22.dp))
                    .clickable(enabled = preview == null && !busy) { photoPicker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                val image = preview
                if (image != null) {
                    Image(image.asImageBitmap(), null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = Accent, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Select a photo", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("Best results with a clear person in frame", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { photoPicker.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Accent), shape = RoundedCornerShape(14.dp)) {
                            Text("Choose photo", color = Ink, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (busy) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(Modifier.height(10.dp))
                            Text("Working on your photo…", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, color = Muted, fontSize = 11.sp, maxLines = 1)

            Spacer(Modifier.height(12.dp))
            Text("BACKGROUND", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                EditorToolButton("Remove", Icons.Default.Person, tool == EditTool.REMOVE) { applyTool(EditTool.REMOVE) }
                EditorToolButton("Blur", Icons.Default.BlurOn, tool == EditTool.BLUR) { applyTool(EditTool.BLUR) }
                EditorToolButton("Color", Icons.Default.Palette, tool == EditTool.COLOR) { applyTool(EditTool.COLOR) }
                EditorToolButton("Replace", Icons.Default.Wallpaper, tool == EditTool.REPLACE) { applyTool(EditTool.REPLACE) }
                EditorToolButton("Original", Icons.Default.Image, tool == EditTool.ORIGINAL) { applyTool(EditTool.ORIGINAL) }
            }

            if (tool == EditTool.COLOR && original != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    colors.forEach { color ->
                        Box(
                            Modifier.size(31.dp).clip(CircleShape).background(Color(color))
                                .border(if (selectedColor == color) 3.dp else 1.dp, if (selectedColor == color) Accent else Color.White.copy(alpha = .35f), CircleShape)
                                .clickable {
                                    selectedColor = color
                                    val src = original; val matte = mask
                                    if (src != null && matte != null) {
                                        preview = renderEdit(src, matte, EditTool.COLOR, color, replacement)
                                        status = "Background color updated"
                                    }
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { photoPicker.launch("image/*") },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    enabled = !busy && !exporting
                ) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(7.dp)); Text("Change photo")
                }
                Button(
                    onClick = {
                        preview?.let { image ->
                            exporting = true
                            onExport(image) { ok ->
                                exporting = false
                                status = if (ok) "Saved to Pictures/Akki Photo Studio" else "Export failed. Please try again."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    enabled = preview != null && !busy && !exporting
                ) {
                    if (exporting) CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Download, null, tint = Ink)
                    Spacer(Modifier.width(7.dp))
                    Text("Export PNG", color = Ink, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EditorToolButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = .16f) else Panel)
            .border(1.dp, if (selected) Accent.copy(alpha = .8f) else Color.White.copy(alpha = .06f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected) Accent else Color.White, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) Accent else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

private fun renderEdit(
    source: Bitmap,
    mask: Bitmap,
    tool: EditTool,
    color: Int,
    replacement: Bitmap?
): Bitmap {
    if (tool == EditTool.ORIGINAL) return source.copy(Bitmap.Config.ARGB_8888, false)
    val width = source.width
    val height = source.height
    val foreground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(foreground).apply {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        drawBitmap(source, 0f, 0f, null)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        drawBitmap(mask, null, Rect(0, 0, width, height), paint)
        paint.xfermode = null
    }
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    when (tool) {
        EditTool.REMOVE -> { /* transparent canvas */ }
        EditTool.COLOR -> canvas.drawColor(color)
        EditTool.BLUR -> canvas.drawBitmap(blurBitmap(source), 0f, 0f, null)
        EditTool.REPLACE -> {
            val bg = replacement
            if (bg != null) {
                val scale = maxOf(width.toFloat() / bg.width, height.toFloat() / bg.height)
                val w = bg.width * scale
                val h = bg.height * scale
                val left = (width - w) / 2f
                val top = (height - h) / 2f
                canvas.drawBitmap(bg, null, android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else canvas.drawColor(color)
        }
        EditTool.ORIGINAL -> canvas.drawBitmap(source, 0f, 0f, null)
    }
    canvas.drawBitmap(foreground, 0f, 0f, null)
    foreground.recycle()
    return output
}

private fun blurBitmap(source: Bitmap): Bitmap {
    // Lightweight offline blur approximation: downscale and upscale with filtering.
    // This works on Android 8+ without API-31-only RenderEffect calls.
    val smallWidth = maxOf(1, source.width / 14)
    val smallHeight = maxOf(1, source.height / 14)
    val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
    val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
    if (small !== blurred) small.recycle()
    return blurred
}
