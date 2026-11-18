package com.smileclinic.radiograph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.*

// ============================================================================
// 1. النماذج والمنطق الحسابي (Models & Core Math Logic)
// ============================================================================

data class Point2D(val x: Float, val y: Float) {
    fun distanceTo(other: Point2D): Float = hypot(other.x - x, other.y - y)
}

data class Scale(
    val sx: Float,
    val sy: Float,
    val source: String
)

enum class MeasureMode {
    MEASURE,
    CALIBRATE
}

data class LoadedRadiograph(
    val bitmap: Bitmap,
    val scale: Scale?
)

object RadiographMath {
    fun lengthPx(pts: List<Point2D>): Float {
        if (pts.size < 2) return 0f
        var sum = 0f
        for (i in 0 until pts.size - 1) {
            sum += pts[i].distanceTo(pts[i + 1])
        }
        return sum
    }

    fun lengthMm(pts: List<Point2D>, scale: Scale?): Float? {
        if (scale == null || pts.size < 2) return null
        var sum = 0f
        for (i in 0 until pts.size - 1) {
            val dx = (pts[i + 1].x - pts[i].x) * scale.sx
            val dy = (pts[i + 1].y - pts[i].y) * scale.sy
            sum += hypot(dx, dy)
        }
        return sum
    }

    fun formatLength(pts: List<Point2D>, scale: Scale?): String {
        val mm = lengthMm(pts, scale)
        return if (mm != null) {
            String.format(Locale.US, "%.1f mm", mm)
        } else {
            String.format(Locale.US, "%.0f px", lengthPx(pts))
        }
    }
}

// ============================================================================
// 2. قارئ الصور وملفات DICOM (Image & Lightweight DICOM Loader)
// ============================================================================

object RadiographLoader {

    fun loadFromUri(context: Context, uri: Uri): LoadedRadiograph {
        val fileName = getFileName(context, uri).lowercase(Locale.US)
        val isDicom = fileName.endsWith(".dcm") || fileName.endsWith(".dicom") || checkDicomMagic(context, uri)

        return if (isDicom) {
            loadDicom(context, uri)
        } else {
            loadStandardImage(context, uri)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: name
            }
        }
        return name
    }

    private fun checkDicomMagic(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(132)
                val read = stream.read(header)
                read == 132 &&
                        header[128] == 'D'.code.toByte() &&
                        header[129] == 'I'.code.toByte() &&
                        header[130] == 'C'.code.toByte() &&
                        header[131] == 'M'.code.toByte()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun loadStandardImage(context: Context, uri: Uri): LoadedRadiograph {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            try {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalArgumentException("تعذّر قراءة ملف الصورة")

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val finalBitmap = if (!matrix.isIdentity) {
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
        return LoadedRadiograph(finalBitmap, null)
    }

    /**
     * قارئ DICOM مدمج وخفيف يستخرج أبعاد الصورة، PixelSpacing، وبيانات البكسل 8/16-bit
     * مع تطبيق تطبيع التباين (0.5% - 99.5%) ودعم MONOCHROME1.
     */
    private fun loadDicom(context: Context, uri: Uri): LoadedRadiograph {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("تعذّر قراءة ملف DICOM")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = if (bytes.size > 132 &&
            bytes[128] == 'D'.code.toByte() &&
            bytes[129] == 'I'.code.toByte() &&
            bytes[130] == 'C'.code.toByte() &&
            bytes[131] == 'M'.code.toByte()
        ) 132 else 0

        var rows = 0
        var cols = 0
        var bitsAllocated = 16
        var pixelRepresentation = 0 // 0 = unsigned, 1 = signed
        var samplesPerPixel = 1
        var photometric = "MONOCHROME2"
        var pixelSpacing: Pair<Float, Float>? = null
        var pixelDataOffset = -1
        var pixelDataLength = 0

        val explicitVRsWith4ByteLen = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UR", "UT", "UN")

        while (pos + 8 <= bytes.size) {
            val group = buf.getShort(pos).toInt() and 0xFFFF
            val element = buf.getShort(pos + 2).toInt() and 0xFFFF
            pos += 4

            // التحقق هل VR صريح (حرفان كبيران) أم ضمني
            val b0 = bytes[pos].toInt() and 0xFF
            val b1 = bytes[pos + 1].toInt() and 0xFF
            val isExplicitVR = (b0 in 65..90) && (b1 in 65..90)

            var vr = ""
            var length: Int

            if (isExplicitVR) {
                vr = "${b0.toChar()}${b1.toChar()}"
                if (vr in explicitVRsWith4ByteLen) {
                    if (pos + 8 > bytes.size) break
                    length = buf.getInt(pos + 4)
                    pos += 8
                } else {
                    length = buf.getShort(pos + 2).toInt() and 0xFFFF
                    pos += 4
                }
            } else {
                length = buf.getInt(pos)
                pos += 4
            }

            if (length < 0 || pos + length > bytes.size) {
                if (group == 0x7FE0 && element == 0x0010) {
                    throw IllegalArgumentException("ملفات DICOM المضغوطة (JPEG/RLE) غير مدعومة في القارئ المدمج")
                }
                break
            }

            when {
                group == 0x0028 && element == 0x0002 -> samplesPerPixel = buf.getShort(pos).toInt() and 0xFFFF
                group == 0x0028 && element == 0x0004 -> photometric = String(bytes, pos, length, Charsets.US_ASCII).trim()
                group == 0x0028 && element == 0x0010 -> rows = buf.getShort(pos).toInt() and 0xFFFF
                group == 0x0028 && element == 0x0011 -> cols = buf.getShort(pos).toInt() and 0xFFFF
                group == 0x0028 && element == 0x0100 -> bitsAllocated = buf.getShort(pos).toInt() and 0xFFFF
                group == 0x0028 && element == 0x0103 -> pixelRepresentation = buf.getShort(pos).toInt() and 0xFFFF
                group == 0x0028 && element == 0x0030 -> {
                    val str = String(bytes, pos, length, Charsets.US_ASCII).trim().trimEnd('\u0000')
                    val parts = str.split("\\\\")
                    if (parts.size >= 2) {
                        val rowSp = parts[0].trim().toFloatOrNull()
                        val colSp = parts[1].trim().toFloatOrNull()
                        if (rowSp != null && colSp != null && rowSp > 0f && colSp > 0f) {
                            pixelSpacing = Pair(colSp, rowSp) // (sx, sy)
                        }
                    }
                }
                group == 0x7FE0 && element == 0x0010 -> {
                    pixelDataOffset = pos
                    pixelDataLength = length
                    break
                }
            }
            pos += length
        }

        if (rows <= 0 || cols <= 0 || pixelDataOffset < 0) {
            throw IllegalArgumentException("لم يتم العثور على بيانات صورة صالحة داخل ملف DICOM")
        }
        if (samplesPerPixel != 1) {
            throw IllegalArgumentException("ملفات DICOM متعددة القنوات اللونية غير مدعومة")
        }

        val totalPixels = rows * cols
        val rawValues = FloatArray(totalPixels)

        if (bitsAllocated == 16 && pixelDataOffset + totalPixels * 2 <= bytes.size) {
            for (i in 0 until totalPixels) {
                val s = buf.getShort(pixelDataOffset + i * 2)
                rawValues[i] = if (pixelRepresentation == 1) s.toFloat() else (s.toInt() and 0xFFFF).toFloat()
            }
        } else if (bitsAllocated == 8 && pixelDataOffset + totalPixels <= bytes.size) {
            for (i in 0 until totalPixels) {
                val b = bytes[pixelDataOffset + i]
                rawValues[i] = if (pixelRepresentation == 1) b.toFloat() else (b.toInt() and 0xFF).toFloat()
            }
        } else {
            throw IllegalArgumentException("تنسيق البكسل ($bitsAllocated-bit) غير مدعوم")
        }

        // حساب النسب المئوية (0.5% و 99.5%) لضبط التباين تلقائياً
        val sorted = rawValues.copyOf()
        sorted.sort()
        val lo = sorted[(totalPixels * 0.005f).toInt().coerceIn(0, totalPixels - 1)]
        val hi = sorted[(totalPixels * 0.995f).toInt().coerceIn(0, totalPixels - 1)]
        val range = max(hi - lo, 1e-6f)
        val invert = photometric == "MONOCHROME1"

        val argbPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            var norm = ((rawValues[i] - lo) / range).coerceIn(0f, 1f)
            if (invert) norm = 1f - norm
            val g = (norm * 255f).toInt().coerceIn(0, 255)
            argbPixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }

        val bitmap = Bitmap.createBitmap(argbPixels, cols, rows, Bitmap.Config.ARGB_8888)
        val scale = pixelSpacing?.let { Scale(it.first, it.second, "DICOM PixelSpacing") }
        return LoadedRadiograph(bitmap, scale)
    }
}

// ============================================================================
// 3. التصدير (CSV & Annotated PNG Exporter)
// ============================================================================

object RadiographExporter {

    fun writeCsv(outputStream: OutputStream, paths: List<List<Point2D>>, scale: Scale?) {
        // كتابة UTF-8 BOM ليفتح بالعربية بشكل سليم في Excel
        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        val src = scale?.source ?: "غير معاير"
        writer.write("#,points,length_px,length_mm,scale_source\n")
        paths.forEachIndexed { idx, pts ->
            val pxStr = String.format(Locale.US, "%.1f", RadiographMath.lengthPx(pts))
            val mm = RadiographMath.lengthMm(pts, scale)
            val mmStr = if (mm != null) String.format(Locale.US, "%.2f", mm) else ""
            writer.write("${idx + 1},${pts.size},$pxStr,$mmStr,\"$src\"\n")
        }
        writer.flush()
    }

    fun writeAnnotatedPng(
        outputStream: OutputStream,
        sourceBitmap: Bitmap,
        paths: List<List<Point2D>>,
        scale: Scale?
    ) {
        val result = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val lw = max(3f, max(result.width, result.height) / 500f)

        val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0, 229, 255)
            strokeWidth = lw
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }

        val pointPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 214, 0)
            style = android.graphics.Paint.Style.FILL
        }

        val textSizePx = lw * 8f
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = textSizePx
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val strokeTextPaint = android.graphics.Paint(textPaint).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = lw * 1.2f
        }

        paths.forEachIndexed { idx, pts ->
            if (pts.size >= 2) {
                val androidPath = android.graphics.Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        lineTo(pts[i].x, pts[i].y)
                    }
                }
                canvas.drawPath(androidPath, linePaint)
            }

            pts.forEach { pt ->
                canvas.drawCircle(pt.x, pt.y, lw * 2.2f, pointPaint)
            }

            if (pts.isNotEmpty()) {
                val last = pts.last()
                val label = "#${idx + 1} ${RadiographMath.formatLength(pts, scale)}"
                val tx = (last.x + lw * 4f).coerceAtMost(result.width - textSizePx * 4f)
                val ty = (last.y - lw * 4f).coerceAtLeast(textSizePx)
                canvas.drawText(label, tx, ty, strokeTextPaint)
                canvas.drawText(label, tx, ty, textPaint)
            }
        }

        result.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.flush()
    }
}

// ============================================================================
// 4. واجهة المستخدم التفاعلية (Jetpack Compose Screen)
// ============================================================================

private val ColorDone = Color(0xFF00E5FF)
private val ColorActive = Color(0xFFFFD600)
private val ColorCalib = Color(0xFFFF4DFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadiographMeasureScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // حالة الصورة والمقياس
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember { mutableStateOf<Scale?>(null) }

    // حالة التكبير والتحريك
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // حالة القياسات والنقاط
    val paths = remember { mutableStateListOf<List<Point2D>>() }
    var current by remember { mutableStateOf<List<Point2D>>(emptyList()) }
    var calib by remember { mutableStateOf<List<Point2D>>(emptyList()) }
    var mode by remember { mutableStateOf(MeasureMode.MEASURE) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) } // -1 للقياس الجاري، >= 0 للمثبت

    // حالة السحب والعدسة المكبرة (Loupe)
    var draggingTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (pathIdx [-1=current], pointIdx)
    var activeTouchScreenPos by remember { mutableStateOf<Offset?>(null) }

    // الحوارات المنبثقة (Dialogs)
    var showCalibLengthDialog by remember { mutableStateOf(false) }
    var pendingCalibPx by remember { mutableFloatStateOf(0f) }
    var showManualScaleDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val hitRadiusPx = with(density) { 26.dp.toPx() }
    val minZoom = 0.05f
    val maxZoom = 16f

    // دوال التحويل بين إحداثيات الشاشة والصورة
    fun toScreen(p: Point2D): Offset = Offset(pan.x + p.x * zoom, pan.y + p.y * zoom)
    fun toImage(s: Offset): Point2D = Point2D((s.x - pan.x) / zoom, (s.y - pan.y) / zoom)
    fun isInsideImage(p: Point2D): Boolean {
        val bmp = bitmap ?: return false
        return p.x in 0f..bmp.width.toFloat() && p.y in 0f..bmp.height.toFloat()
    }

    fun fitImageToCanvas(bmp: Bitmap, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val z = min(size.width.toFloat() / bmp.width, size.height.toFloat() / bmp.height) * 0.95f
        zoom = z
        pan = Offset(
            (size.width - bmp.width * z) / 2f,
            (size.height - bmp.height * z) / 2f
        )
    }

    // مختار الملفات (فتح صورة / DICOM)
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val loaded = RadiographLoader.loadFromUri(context, uri)
                bitmap = loaded.bitmap
                imageBitmap = loaded.bitmap.asImageBitmap()
                scale = loaded.scale
                paths.clear()
                current = emptyList()
                calib = emptyList()
                mode = MeasureMode.MEASURE
                selectedIndex = null
                fitImageToCanvas(loaded.bitmap, canvasSize)
            } catch (e: Exception) {
                Toast.makeText(context, "تعذّر فتح الملف: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // تصدير CSV
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    RadiographExporter.writeCsv(out, paths.toList(), scale)
                }
                Toast.makeText(context, "تم حفظ ملف CSV بنجاح", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // تصدير صورة مُعلّمة PNG
    val exportImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? ->
        val bmp = bitmap ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    RadiographExporter.writeAnnotatedPng(out, bmp, paths.toList(), scale)
                }
                Toast.makeText(context, "تم حفظ الصورة المُعلّمة بنجاح", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "فشل حفظ الصورة: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // دوال الأوامر
    fun finishCurrent() {
        if (current.size >= 2) {
            paths.add(current)
        }
        current = emptyList()
        selectedIndex = null
    }

    fun undoPoint() {
        if (mode == MeasureMode.CALIBRATE && calib.isNotEmpty()) {
            calib = calib.dropLast(1)
        } else if (current.isNotEmpty()) {
            current = current.dropLast(1)
        }
    }

    fun deleteSelected() {
        val sel = selectedIndex ?: return
        if (sel == -1) {
            current = emptyList()
        } else if (sel in paths.indices) {
            paths.removeAt(sel)
        }
        selectedIndex = null
    }

    // إيماءات التكبير والتحريك بإصبعين (Pinch-to-Zoom & Pan)
    val transformState = rememberTransformableState { zoomChange, panChange, centroid ->
        if (bitmap == null) return@rememberTransformableState
        val newZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
        val factor = newZoom / zoom
        pan = Offset(
            x = centroid.x - (centroid.x - pan.x) * factor + panChange.x,
            y = centroid.y - (centroid.y - pan.y) * factor + panChange.y
        )
        zoom = newZoom
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Text(
                            "Smile Clinic - قياس الأشعة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = { openFileLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "فتح صورة")
                        }
                        IconButton(
                            onClick = {
                                if (paths.isEmpty()) {
                                    Toast.makeText(context, "ثبّت قياساً واحداً على الأقل", Toast.LENGTH_SHORT).show()
                                } else {
                                    exportCsvLauncher.launch("smile_clinic_measurements.csv")
                                }
                            },
                            enabled = bitmap != null
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = "تصدير CSV")
                        }
                        IconButton(
                            onClick = {
                                if (paths.isEmpty()) {
                                    Toast.makeText(context, "ثبّت قياساً واحداً على الأقل", Toast.LENGTH_SHORT).show()
                                } else {
                                    exportImageLauncher.launch("smile_clinic_annotated.png")
                                }
                            },
                            enabled = bitmap != null
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "تصدير صورة")
                        }
                    }
                )

                // شريط الأدوات السريع القابل للتمرير أفقياً
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { openFileLauncher.launch(arrayOf("*/*")) }
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("فتح صورة")
                    }

                    FilterChip(
                        selected = mode == MeasureMode.CALIBRATE,
                        onClick = {
                            if (bitmap != null) {
                                if (mode == MeasureMode.CALIBRATE) {
                                    mode = MeasureMode.MEASURE
                                    calib = emptyList()
                                } else {
                                    mode = MeasureMode.CALIBRATE
                                    calib = emptyList()
                                    Toast.makeText(
                                        context,
                                        "اضغط على طرفي جسم معروف الطول داخل الصورة",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = bitmap != null,
                        label = { Text("معايرة") },
                        leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )

                    OutlinedButton(
                        onClick = { showManualScaleDialog = true },
                        enabled = bitmap != null
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("مقياس يدوي")
                    }

                    Button(
                        onClick = { finishCurrent() },
                        enabled = current.size >= 2
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تثبيت")
                    }

                    OutlinedButton(
                        onClick = { undoPoint() },
                        enabled = (mode == MeasureMode.CALIBRATE && calib.isNotEmpty()) || current.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تراجع")
                    }

                    OutlinedButton(
                        onClick = { deleteSelected() },
                        enabled = selectedIndex != null
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("حذف")
                    }

                    OutlinedButton(
                        onClick = {
                            if (paths.isNotEmpty()) showClearConfirmDialog = true
                            else {
                                current = emptyList()
                                selectedIndex = null
                            }
                        },
                        enabled = paths.isNotEmpty() || current.isNotEmpty()
                    ) {
                        Text("مسح الكل")
                    }
                }
            }
        },
        bottomBar = {
            // شريط الحالة السفلي
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = when {
                        bitmap == null -> "افتح صورة بانوراما أو أشعة ذروية للبدء"
                        mode == MeasureMode.CALIBRATE -> "وضع المعايرة: حدّد طرفي الجسم المرجعي (${calib.size}/2)"
                        scale != null -> "${bitmap!!.width}×${bitmap!!.height} | ${scale!!.source} (${String.format(Locale.US, "%.4f", scale!!.sx)} mm/px)"
                        else -> "⚠ غير معاير (بالبكسل). استخدم «معايرة»"
                    }
                    val statusColor = when {
                        mode == MeasureMode.CALIBRATE -> Color(0xFFD81B60)
                        bitmap != null && scale == null -> Color(0xFFE65100)
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        modifier = Modifier.weight(1f)
                    )

                    if (current.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${RadiographMath.formatLength(current, scale)} (${current.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF121212))
                .onSizeChanged { newSize ->
                    val wasEmpty = canvasSize == IntSize.Zero
                    canvasSize = newSize
                    if (wasEmpty && bitmap != null) {
                        fitImageToCanvas(bitmap!!, newSize)
                    }
                }
                .transformable(state = transformState)
                // النقر لإضافة نقطة جديدة
                .pointerInput(bitmap, mode, zoom, pan) {
                    detectTapGestures { tapOffset ->
                        val bmp = bitmap ?: return@detectTapGestures
                        val imgPt = toImage(tapOffset)
                        if (!isInsideImage(imgPt)) return@detectTapGestures

                        if (mode == MeasureMode.CALIBRATE) {
                            val updated = calib.plus(imgPt)
                            if (updated.size == 2) {
                                calib = updated
                                pendingCalibPx = updated[0].distanceTo(updated[1])
                                showCalibLengthDialog = true
                            } else {
                                calib = updated
                            }
                        } else {
                            // التحقق إن كان النقر لتحديد مسار موجود
                            var hitPath: Int? = null
                            for (i in current.indices) {
                                if ((toScreen(current[i]) - tapOffset).getDistance() <= hitRadiusPx) {
                                    hitPath = -1
                                    break
                                }
                            }
                            if (hitPath == null) {
                                for (pIdx in paths.indices.reversed()) {
                                    if (paths[pIdx].any { (toScreen(it) - tapOffset).getDistance() <= hitRadiusPx }) {
                                        hitPath = pIdx
                                        break
                                    }
                                }
                            }

                            if (hitPath != null) {
                                selectedIndex = hitPath
                            } else {
                                current = current.plus(imgPt)
                                selectedIndex = -1
                            }
                        }
                    }
                }
                // السحب لتعديل موضع نقطة موجودة مع إظهار العدسة المكبرة
                .pointerInput(bitmap, mode, zoom, pan) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (bitmap == null || mode == MeasureMode.CALIBRATE) return@detectDragGestures
                            // البحث عن أقرب نقطة ضمن نطاق اللمس
                            var found: Pair<Int, Int>? = null
                            for (i in current.indices) {
                                if ((toScreen(current[i]) - startOffset).getDistance() <= hitRadiusPx) {
                                    found = Pair(-1, i)
                                    break
                                }
                            }
                            if (found == null) {
                                for (pIdx in paths.indices.reversed()) {
                                    val ptIdx = paths[pIdx].indexOfFirst {
                                        (toScreen(it) - startOffset).getDistance() <= hitRadiusPx
                                    }
                                    if (ptIdx >= 0) {
                                        found = Pair(pIdx, ptIdx)
                                        break
                                    }
                                }
                            }
                            if (found != null) {
                                draggingTarget = found
                                selectedIndex = found.first
                                activeTouchScreenPos = startOffset
                            }
                        },
                        onDrag = { change, _ ->
                            val target = draggingTarget ?: return@detectDragGestures
                            val bmp = bitmap ?: return@detectDragGestures
                            change.consume()
                            activeTouchScreenPos = change.position
                            val imgPt = toImage(change.position)
                            val clamped = Point2D(
                                imgPt.x.coerceIn(0f, bmp.width.toFloat()),
                                imgPt.y.coerceIn(0f, bmp.height.toFloat())
                            )
                            val (pIdx, ptIdx) = target
                            if (pIdx == -1 && ptIdx in current.indices) {
                                current = current.toMutableList().apply { set(ptIdx, clamped) }
                            } else if (pIdx in paths.indices && ptIdx in paths[pIdx].indices) {
                                paths[pIdx] = paths[pIdx].toMutableList().apply { set(ptIdx, clamped) }
                            }
                        },
                        onDragEnd = {
                            draggingTarget = null
                            activeTouchScreenPos = null
                        },
                        onDragCancel = {
                            draggingTarget = null
                            activeTouchScreenPos = null
                        }
                    )
                }
        ) {
            val imgBmp = imageBitmap
            if (imgBmp == null) {
                Text(
                    text = "افتح صورة بانوراما أو أشعة ذروية\n\n" +
                            "لمسة: إضافة نقطة  |  سحب نقطة: تعديل دقيق بالعدسة\n" +
                            "إصبعان: تكبير وتحريك",
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // رسم الصورة بالتحويلات الحالية (Pan & Zoom)
                    val dstOffset = IntOffset(pan.x.roundToInt(), pan.y.roundToInt())
                    val dstSize = IntSize(
                        (imgBmp.width * zoom).roundToInt(),
                        (imgBmp.height * zoom).roundToInt()
                    )
                    drawImage(
                        image = imgBmp,
                        dstOffset = dstOffset,
                        dstSize = dstSize
                    )

                    // رسم المسارات المثبتة
                    paths.forEachIndexed { idx, pts ->
                        drawMeasurementPath(
                            pts = pts,
                            color = ColorDone,
                            highlight = selectedIndex == idx,
                            scale = scale,
                            toScreen = ::toScreen,
                            textMeasurer = textMeasurer
                        )
                    }

                    // رسم القياس الجاري
                    if (current.isNotEmpty()) {
                        drawMeasurementPath(
                            pts = current,
                            color = ColorActive,
                            highlight = true,
                            scale = scale,
                            toScreen = ::toScreen,
                            textMeasurer = textMeasurer
                        )
                    }

                    // رسم نقاط المعايرة
                    if (calib.isNotEmpty()) {
                        val screenCalib = calib.map(::toScreen)
                        if (screenCalib.size == 2) {
                            drawLine(
                                color = ColorCalib,
                                start = screenCalib[0],
                                end = screenCalib[1],
                                strokeWidth = 5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        }
                        screenCalib.forEach { pt ->
                            drawCircle(color = ColorCalib, radius = 14f, center = pt, style = Stroke(width = 5f))
                        }
                    }

                    // رسم العدسة المكبرة (Loupe) أعلى الإصبع أثناء سحب النقطة
                    val touchPos = activeTouchScreenPos
                    if (draggingTarget != null && touchPos != null) {
                        val loupeRadius = 110f
                        val loupeCenter = Offset(
                            x = touchPos.x.coerceIn(loupeRadius + 16f, size.width - loupeRadius - 16f),
                            y = (touchPos.y - loupeRadius - 90f).coerceAtLeast(loupeRadius + 16f)
                        )
                        val imgFocus = toImage(touchPos)
                        val srcHalfSize = (loupeRadius / (zoom * 2.5f)).roundToInt().coerceAtLeast(10)
                        val srcX = (imgFocus.x.roundToInt() - srcHalfSize).coerceIn(0, max(0, imgBmp.width - srcHalfSize * 2))
                        val srcY = (imgFocus.y.roundToInt() - srcHalfSize).coerceIn(0, max(0, imgBmp.height - srcHalfSize * 2))
                        val srcW = min(srcHalfSize * 2, imgBmp.width - srcX)
                        val srcH = min(srcHalfSize * 2, imgBmp.height - srcY)

                        if (srcW > 0 && srcH > 0) {
                            val clipPath = Path().apply {
                                addOval(androidx.compose.ui.geometry.Rect(center = loupeCenter, radius = loupeRadius))
                            }
                            drawContext.canvas.save()
                            drawContext.canvas.clipPath(clipPath)
                            drawImage(
                                image = imgBmp,
                                srcOffset = IntOffset(srcX, srcY),
                                srcSize = IntSize(srcW, srcH),
                                dstOffset = IntOffset((loupeCenter.x - loupeRadius).roundToInt(), (loupeCenter.y - loupeRadius).roundToInt()),
                                dstSize = IntSize((loupeRadius * 2).roundToInt(), (loupeRadius * 2).roundToInt())
                            )
                            // علامة التقاطع داخل العدسة
                            drawLine(ColorActive, Offset(loupeCenter.x - 24f, loupeCenter.y), Offset(loupeCenter.x + 24f, loupeCenter.y), 3f)
                            drawLine(ColorActive, Offset(loupeCenter.x, loupeCenter.y - 24f), Offset(loupeCenter.x, loupeCenter.y + 24f), 3f)
                            drawContext.canvas.restore()

                            drawCircle(color = ColorActive, radius = loupeRadius, center = loupeCenter, style = Stroke(width = 6f))
                        }
                    }
                }

                // زر عائم لإعادة ملاءمة الصورة للشاشة
                SmallFloatingActionButton(
                    onClick = { bitmap?.let { fitImageToCanvas(it, canvasSize) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = "ملاءمة الشاشة")
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // الحوارات المنبثقة (Dialogs)
    // ------------------------------------------------------------------------

    if (showCalibLengthDialog) {
        var mmInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showCalibLengthDialog = false
                calib = emptyList()
                mode = MeasureMode.MEASURE
            },
            title = { Text("معايرة الطول المرجعي") },
            text = {
                Column {
                    Text("أدخل الطول الحقيقي بين النقطتين بالمليمتر (mm):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mmInput,
                        onValueChange = { mmInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("مثال: 5.0 أو 10.0") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mm = mmInput.toFloatOrNull()
                    if (mm != null && mm > 0.01f && pendingCalibPx > 1e-5f) {
                        val factor = mm / pendingCalibPx
                        scale = Scale(factor, factor, "معايرة بجسم مرجعي")
                    }
                    showCalibLengthDialog = false
                    calib = emptyList()
                    mode = MeasureMode.MEASURE
                }) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCalibLengthDialog = false
                    calib = emptyList()
                    mode = MeasureMode.MEASURE
                }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showManualScaleDialog) {
        var pitchInput by remember { mutableStateOf("0.095") }
        var magInput by remember { mutableStateOf("1.2") }
        AlertDialog(
            onDismissRequest = { showManualScaleDialog = false },
            title = { Text("إدخال مقياس يدوي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pitchInput,
                        onValueChange = { pitchInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("حجم بكسل الحساس (ملم) - مثل 0.095") }
                    )
                    OutlinedTextField(
                        value = magInput,
                        onValueChange = { magInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("معامل التكبير - مثل 1.2") }
                    )
                    Text(
                        "ملاحظة: التكبير في البانوراما تقريبي؛ المعايرة بجسم مرجعي أدق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = pitchInput.toFloatOrNull()
                    val m = magInput.toFloatOrNull()
                    if (p != null && p > 0f && m != null && m >= 1f) {
                        scale = Scale(p / m, p / m, "يدوي ($p مم ÷ $m)")
                    }
                    showManualScaleDialog = false
                }) {
                    Text("تطبيق")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualScaleDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("مسح الكل") },
            text = { Text("هل تريد حذف جميع القياسات المثبتة؟") },
            confirmButton = {
                TextButton(onClick = {
                    paths.clear()
                    current = emptyList()
                    selectedIndex = null
                    showClearConfirmDialog = false
                }) {
                    Text("حذف الكل", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

private fun DrawScope.drawMeasurementPath(
    pts: List<Point2D>,
    color: Color,
    highlight: Boolean,
    scale: Scale?,
    toScreen: (Point2D) -> Offset,
    textMeasurer: TextMeasurer
) {
    val screenPts = pts.map(toScreen)
    if (screenPts.size >= 2) {
        val path = Path().apply {
            moveTo(screenPts[0].x, screenPts[0].y)
            for (i in 1 until screenPts.size) {
                lineTo(screenPts[i].x, screenPts[i].y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = if (highlight) 8f else 5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        val label = RadiographMath.formatLength(pts, scale)
        val layoutResult = textMeasurer.measure(
            text = AnnotatedString(label),
            style = TextStyle(
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        )
        val last = screenPts.last()
        val boxTopLeft = Offset(last.x + 18f, last.y - layoutResult.size.height - 12f)
        val boxSize = Size(layoutResult.size.width + 16f, layoutResult.size.height + 8f)

        drawRect(color = Color.Black.copy(alpha = 0.85f), topLeft = boxTopLeft, size = boxSize)
        drawRect(color = color, topLeft = boxTopLeft, size = boxSize, style = Stroke(width = 2f))
        drawText(
            textLayoutResult = layoutResult,
            topLeft = Offset(boxTopLeft.x + 8f, boxTopLeft.y + 4f)
        )
    }

    val radius = if (highlight) 14f else 10f
    screenPts.forEachIndexed { idx, pt ->
        val fill = when (idx) {
            0 -> Color(0xFF00FF00)
            screenPts.lastIndex -> Color(0xFFFF3B30)
            else -> color
        }
        drawCircle(color = fill, radius = radius, center = pt)
        drawCircle(color = Color.White, radius = radius, center = pt, style = Stroke(width = 3f))
    }
}
