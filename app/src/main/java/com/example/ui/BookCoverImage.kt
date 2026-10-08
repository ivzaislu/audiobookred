package com.example.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.image.PosterImageCache
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Защитный слой для обложек. Producer должен присылать корректный cover_url,
 * но Android не должен превращать декоративную горизонтальную картинку в
 * «сплющенный постер», если плохой URL всё же прошёл upstream validation.
 */
internal fun isLikelyBookCoverSize(width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0) return true
    val ratio = width.toFloat() / height.toFloat()
    return ratio in 0.35f..1.10f
}

@Composable
internal fun BookCoverImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var imageLoader by remember(appContext) { mutableStateOf<ImageLoader?>(null) }
    val requestModel = remember(model) { PosterImageCache.requestUrl(model) }

    LaunchedEffect(appContext) {
        imageLoader = withContext(Dispatchers.IO) {
            PosterImageCache.imageLoader(appContext)
        }
    }
    var retryAttempt by remember(requestModel) { mutableStateOf(0) }
    var failedAttempt by remember(requestModel) { mutableStateOf<Int?>(null) }
    var permanentlyRejected by remember(requestModel) {
        mutableStateOf(requestModel.isNullOrBlank())
    }

    val imageRequest = remember(context, requestModel, retryAttempt) {
        requestModel?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                // Keep one cache identity across retry attempts. The request
                // object changes so Coil retries, while successful bytes still
                // reuse the same memory/disk entry.
                .memoryCacheKey(url)
                .diskCacheKey(url)
                .build()
        }
    }

    LaunchedEffect(requestModel, failedAttempt, retryAttempt) {
        val failed = failedAttempt ?: return@LaunchedEffect
        if (failed != retryAttempt || retryAttempt >= POSTER_MAX_RETRIES) {
            return@LaunchedEffect
        }
        delay(POSTER_RETRY_DELAY_MS * (retryAttempt + 1L))
        if (failedAttempt == failed) {
            retryAttempt += 1
            failedAttempt = null
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Keep the placeholder behind AsyncImage instead of removing AsyncImage
        // after one transient failure. FastPic can fail on the first request and
        // succeed immediately afterwards; latching a boolean rejected state made
        // that recovery invisible until the composable/model was recreated.
        Icon(
            imageVector = Icons.Default.MenuBook,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(0.34f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val readyImageLoader = imageLoader
        if (!permanentlyRejected && imageRequest != null && readyImageLoader != null) {
            AsyncImage(
                model = imageRequest,
                imageLoader = readyImageLoader,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = { state ->
                    val drawable = state.result.drawable
                    failedAttempt = null
                    if (!isLikelyBookCoverSize(drawable.intrinsicWidth, drawable.intrinsicHeight)) {
                        Log.w(
                            POSTER_LOG_TAG,
                            "Rejected cover dimensions ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}: $requestModel"
                        )
                        permanentlyRejected = true
                    } else {
                        Log.d(
                            POSTER_LOG_TAG,
                            "Cover rendered ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}, attempt=$retryAttempt: $requestModel"
                        )
                    }
                },
                onError = { state ->
                    Log.w(
                        POSTER_LOG_TAG,
                        "Cover load failed, attempt=$retryAttempt: $requestModel",
                        state.result.throwable
                    )
                    failedAttempt = retryAttempt
                }
            )
        }
    }
}

private const val POSTER_LOG_TAG = "AbredPoster"
private const val POSTER_MAX_RETRIES = 2
private const val POSTER_RETRY_DELAY_MS = 700L
