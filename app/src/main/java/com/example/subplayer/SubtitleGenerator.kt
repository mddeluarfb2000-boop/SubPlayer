package com.example.subplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Seg(val startMs: Long, val endMs: Long, val text: String)

/**
 * Pipeline: video -> 10 min audio chunks (AAC, small) -> Groq Whisper large-v3 /translations
 * (German speech -> English text with timestamps) -> one .srt file saved on the phone.
 * After that, playback is 100% offline.
 */
@UnstableApi
object SubtitleGenerator {
    private const val CHUNK_MS = 10 * 60 * 1000L
    private const val URL = "https://api.groq.com/openai/v1/audio/translations"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun srtFile(ctx: Context, uri: Uri): File {
        val dir = File(ctx.filesDir, "subs").apply { mkdirs() }
        val name = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$name.srt")
    }

    suspend fun generate(
        ctx: Context, uri: Uri, apiKey: String, out: File, onStatus: (String) -> Unit
    ) {
        val durationMs = withContext(Dispatchers.IO) { durationOf(ctx, uri) }
        val chunks = ((durationMs + CHUNK_MS - 1) / CHUNK_MS).toInt()
        val all = mutableListOf<Seg>()
        for (i in 0 until chunks) {
            val start = i * CHUNK_MS
            val end = minOf(start + CHUNK_MS, durationMs)
            val audio = File(ctx.cacheDir, "chunk_$i.m4a").also { it.delete() }
            onStatus("Subtitles ${i + 1}/$chunks: extracting audio...")
            extractAudio(ctx, uri, start, end, audio)
            onStatus("Subtitles ${i + 1}/$chunks: translating...")
            all += translate(audio, apiKey, start)
            audio.delete()
            out.writeText(toSrt(all)) // partial result saved after every chunk
        }
        if (all.isEmpty()) error("No speech found")
    }

    private fun durationOf(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                ?: error("Cannot read video duration")
        } finally {
            r.release()
        }
    }

    // Transformer must run on a thread with a Looper -> main thread.
    private suspend fun extractAudio(ctx: Context, uri: Uri, startMs: Long, endMs: Long, out: File) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val item = MediaItem.Builder().setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs).setEndPositionMs(endMs).build()
                    ).build()
                val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
                val encoder = DefaultEncoderFactory.Builder(ctx)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(48_000).build()
                    ).build()
                val t = Transformer.Builder(ctx)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(
                            composition: Composition, result: ExportResult, e: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(
                                Exception("Audio extraction failed (${e.errorCodeName}). " +
                                    "Audio codec may be unsupported on this phone.")
                            )
                        }
                    }).build()
                cont.invokeOnCancellation { t.cancel() }
                t.start(edited, out.absolutePath)
            }
        }

    private suspend fun translate(file: File, key: String, offsetMs: Long): List<Seg> =
        withContext(Dispatchers.IO) {
            repeat(8) {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "chunk.m4a", file.asRequestBody("audio/mp4".toMediaType()))
                    .addFormDataPart("model", "whisper-large-v3") // only this one supports translation
                    .addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("temperature", "0")
                    .build()
                val req = Request.Builder().url(URL)
                    .header("Authorization", "Bearer $key").post(body).build()
                val (code, retry, text) = client.newCall(req).execute().use {
                    Triple(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                }
                when (code) {
                    200 -> return@withContext parse(text, offsetMs)
                    429 -> {
                        val wait = retry?.toDoubleOrNull() ?: 30.0
                        delay((wait * 1000).toLong() + 1000)
                    }
                    401 -> error("Invalid API key")
                    else -> error("API error $code: ${text.take(150)}")
                }
            }
            error("Rate limit: please try again later")
        }

    private fun parse(json: String, offsetMs: Long): List<Seg> {
        val arr = JSONObject(json).optJSONArray("segments") ?: return emptyList()
        val list = mutableListOf<Seg>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val text = s.optString("text").trim()
            if (text.isEmpty() || s.optDouble("no_speech_prob", 0.0) > 0.8) continue
            list += Seg(
                offsetMs + (s.getDouble("start") * 1000).toLong(),
                offsetMs + (s.getDouble("end") * 1000).toLong(),
                text
            )
        }
        return list
    }

    private fun toSrt(segs: List<Seg>): String = buildString {
        segs.forEachIndexed { i, s ->
            append(i + 1).append('\n')
            append(ts(s.startMs)).append(" --> ").append(ts(s.endMs)).append('\n')
            append(s.text).append("\n\n")
        }
    }

    private fun ts(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60; val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }
}
