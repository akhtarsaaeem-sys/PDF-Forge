package com.pdforge.app.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app PDF viewer with a continuous vertical document layout.
 *
 * - Pages are stacked vertically (like a normal mobile PDF reader) instead of
 *   requiring next/previous taps.
 * - Each page is sized to fit the device width while preserving the PDF's
 *   aspect ratio.
 * - Pages are rendered lazily — only pages near the viewport are rasterized,
 *   and a small LRU cache (capped) prevents holding every page in memory.
 * - Pinch with two fingers to zoom, drag with two fingers to pan while zoomed,
 *   and tap the reset button (or double-tap) to return to fit-width.
 * - [input] may be a content:// URI, file:// URI, or raw filesystem path.
 */
@Composable
fun PdfViewer(
    input: String,
    modifier: Modifier = Modifier,
    onPageInfo: (current: Int, total: Int) -> Unit = { _, _ -> },
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var retryKey by remember { mutableIntStateOf(0) }
    var isOpening by remember { mutableStateOf(true) }
    var openError by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }

    val session = remember(input, retryKey) { PdfRendererSession(context, input) }

    // Release the renderer and its descriptor whenever the session is replaced
    // or the viewer leaves composition.
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session) {
        isOpening = true
        openError = null
        val ok = withContext(Dispatchers.IO) { session.open() }
        if (ok) {
            val count = session.pageCount
            pageCount = count
            if (count > 0) {
                onPageInfo(1, count)
            } else {
                openError = "This PDF appears to be encrypted or unsupported."
            }
        } else {
            openError = "The file could not be read. It may be damaged, encrypted, or no longer available."
        }
        isOpening = false
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isOpening -> {
                CircularProgressIndicator()
            }
            openError != null -> {
                ErrorContent(
                    message = "Unable to open PDF",
                    detail = openError!!,
                    onRetry = { retryKey++ },
                    onBack = onBack
                )
            }
            pageCount > 0 -> {
                DocumentContent(
                    session = session,
                    pageCount = pageCount,
                    onPageInfo = onPageInfo
                )
            }
            else -> {
                ErrorContent(
                    message = "Unable to open PDF",
                    detail = "The file could not be read. It may be damaged, encrypted, or no longer available.",
                    onRetry = { retryKey++ },
                    onBack = onBack
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    detail: String,
    onRetry: () -> Unit,
    onBack: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRetry) { Text("Retry") }
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun DocumentContent(
    session: PdfRendererSession,
    pageCount: Int,
    onPageInfo: (current: Int, total: Int) -> Unit
) {
    val lazyListState = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // Small LRU cache of rendered pages; evicted bitmaps are recycled so a large
    // document never keeps dozens of full-resolution pages in memory.
    val MAX_CACHE = 5
    val bitmapCache = remember(session) {
        object : LinkedHashMap<Int, Bitmap>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
                if (size > MAX_CACHE) {
                    eldest?.value?.let { if (!it.isRecycled) it.recycle() }
                    return true
                }
                return false
            }
        }
    }
    DisposableEffect(bitmapCache) {
        onDispose {
            bitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
            bitmapCache.clear()
        }
    }

    fun renderCached(index: Int): Bitmap? {
        synchronized(bitmapCache) {
            bitmapCache[index]?.let { return it }
        }
        val targetWidth = viewport.width.coerceAtLeast(1)
        val bitmap = session.renderPage(index, targetWidth)
        if (bitmap != null) {
            synchronized(bitmapCache) { bitmapCache[index] = bitmap }
        }
        return bitmap
    }

    // Report the currently visible page to the host (toolbar indicator).
    LaunchedEffect(lazyListState, pageCount) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .collect { index ->
                onPageInfo((index + 1).coerceIn(1, pageCount), pageCount)
            }
    }

    fun clampOffset(s: Float, o: Offset): Offset {
        if (viewport == IntSize.Zero) return o
        val maxX = (s - 1f) * viewport.width / 2f
        val maxY = (s - 1f) * viewport.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                clampOffset(newScale, offset + panChange)
                            } else {
                                Offset.Zero
                            }
                            event.changes.forEach { change -> change.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                // Double-tap toggles between fit-width and 2x zoom.
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(count = pageCount) { index ->
                PageItem(
                    index = index,
                    session = session,
                    render = { renderCached(index) }
                )
            }
        }

        // Zoom reset button (visible only while zoomed in).
        if (scale > 1f) {
            FloatingActionButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Reset zoom")
            }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    session: PdfRendererSession,
    render: () -> Bitmap?
) {
    val size = remember(index, session) { session.pageSize(index) }
    val aspect = if (size.first > 0 && size.second > 0) {
        size.first.toFloat() / size.second.toFloat()
    } else {
        1f
    }

    val bitmap by produceState<Bitmap?>(null, index, session) {
        value = withContext(Dispatchers.Default) { render() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .background(Color.White, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}