package app.nindo

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Sensei's on-device models: the Gemma 4 brain, the Moonshine speech recogniser and the Silero
 * voice detector. Downloaded once by Android's DownloadManager into the app's own storage (no
 * permission needed; survives leaving the app; resumes after a dropped connection). Never in the APK.
 */
object VoiceModels {
    data class ModelFile(val name: String, val url: String, val bytes: Long)
    data class Pack(val id: String, val title: String, val subdir: String, val files: List<ModelFile>) {
        val bytes get() = files.sumOf { it.bytes }
    }

    private fun hf(repo: String, file: String) = "https://huggingface.co/$repo/resolve/main/$file?download=true"

    val GEMMA_E4B = Pack("gemma-e4b", "Gemma 4 E4B", "llm", listOf(
        ModelFile("gemma-4-E4B-it.litertlm", hf("litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm"), 3_659_000_000)))
    val GEMMA_E2B = Pack("gemma-e2b", "Gemma 4 E2B", "llm", listOf(
        ModelFile("gemma-4-E2B-it.litertlm", hf("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"), 2_588_000_000)))

    private const val MOONSHINE = "csukuangfj/sherpa-onnx-moonshine-tiny-en-int8"
    val STT = Pack("moonshine-tiny", "Speech recognition", "stt/moonshine-tiny-en", listOf(
        ModelFile("preprocess.onnx", hf(MOONSHINE, "preprocess.onnx"), 6_800_000),
        ModelFile("encode.int8.onnx", hf(MOONSHINE, "encode.int8.onnx"), 18_200_000),
        ModelFile("uncached_decode.int8.onnx", hf(MOONSHINE, "uncached_decode.int8.onnx"), 53_200_000),
        ModelFile("cached_decode.int8.onnx", hf(MOONSHINE, "cached_decode.int8.onnx"), 45_300_000),
        ModelFile("tokens.txt", hf(MOONSHINE, "tokens.txt"), 437_000)))
    val VAD = Pack("silero-vad", "Voice detection", "vad", listOf(
        ModelFile("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", 644_000)))

    /** Piper's word-to-sound list (sherpa-onnx 2.x reads pronunciations from it). Shared by all Piper voices. */
    val LEXICON = Pack("lexicon-en-us", "Pronunciation list", "tts/lexicon", listOf(
        ModelFile("lexicon-en-us.txt", "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/lexicon-en-us.txt", 6_306_000)))

    /** A Piper voice: one ~63 MB model. [speed] evens out how fast the speakers talk. */
    data class PiperVoice(val id: String, val name: String, val accent: String, val repoName: String, val speed: Float) {
        val pack = Pack("piper-$id", "Voice: $name", "tts/$id", listOf(
            ModelFile("$repoName.onnx", hf("csukuangfj/vits-piper-$repoName", "$repoName.onnx"), 63_200_000),
            ModelFile("tokens.txt", hf("csukuangfj/vits-piper-$repoName", "tokens.txt"), 1_000)))
    }

    /** Male voices, all checked for clear speech. */
    val VOICES = listOf(
        PiperVoice("alan", "Alan", "British, soft", "en_GB-alan-medium", 1.12f),
        PiperVoice("southern", "Southern English", "British", "en_GB-southern_english_male-medium", 1.0f),
        PiperVoice("northern", "Northern English", "British, warm", "en_GB-northern_english_male-medium", 1.0f),
        PiperVoice("ryan", "Ryan", "American, warm", "en_US-ryan-medium", 0.97f),
        PiperVoice("joe", "Joe", "American", "en_US-joe-medium", 1.0f),
        PiperVoice("kusal", "Kusal", "American", "en_US-kusal-medium", 1.0f),
    )

    /** E4B was too slow on the Nord CE6 (6-9 s replies on GPU) and could freeze the phone; only E2B is offered. */
    val ALL = listOf(GEMMA_E2B, STT, VAD, LEXICON) + VOICES.map { it.pack }

    /** True when the old E4B download is still taking space. */
    fun hasOldE4b(ctx: Context) = GEMMA_E4B.files.any { file(ctx, GEMMA_E4B, it).exists() }
    fun deleteOldE4b(ctx: Context) = delete(ctx, GEMMA_E4B)

    fun root(ctx: Context): File = ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")
    fun file(ctx: Context, p: Pack, f: ModelFile) = File(root(ctx), "${p.subdir}/${f.name}")
    fun path(ctx: Context, p: Pack, name: String) = File(root(ctx), "${p.subdir}/$name").absolutePath
    private fun marker(ctx: Context, p: Pack) = File(root(ctx), "${p.subdir}/.${p.id}.ready")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voice_models", Context.MODE_PRIVATE)

    /** Fully downloaded (a marker is written only once every file finished). */
    fun ready(ctx: Context, p: Pack): Boolean = marker(ctx, p).exists() && p.files.all { file(ctx, p, it).length() > 0 }

    enum class State { MISSING, DOWNLOADING, PAUSED, FAILED, READY }
    data class Progress(val state: State, val done: Long, val total: Long, val reason: String? = null) {
        val fraction get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    fun download(ctx: Context, p: Pack, overMobileData: Boolean) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        cancel(ctx, p)
        val ids = p.files.map { f ->
            file(ctx, p, f).delete()
            dm.enqueue(DownloadManager.Request(Uri.parse(f.url))
                .setTitle("Sensei: ${p.title}")
                .setDescription(f.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(overMobileData)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(ctx, "models", "${p.subdir}/${f.name}"))
        }
        prefs(ctx).edit().putString(p.id, ids.joinToString(",")).apply()
    }

    fun cancel(ctx: Context, p: Pack) {
        val ids = ids(ctx, p)
        if (ids.isNotEmpty()) runCatching { ctx.getSystemService(DownloadManager::class.java).remove(*ids) }
        prefs(ctx).edit().remove(p.id).apply()
    }

    /** Frees the space. */
    fun delete(ctx: Context, p: Pack) {
        cancel(ctx, p)
        marker(ctx, p).delete()
        p.files.forEach { file(ctx, p, it).delete() }
    }

    private fun ids(ctx: Context, p: Pack): LongArray =
        prefs(ctx).getString(p.id, null)?.split(",")?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)

    /** Current state; also writes the ready marker the moment every file has finished. */
    fun progress(ctx: Context, p: Pack): Progress {
        if (ready(ctx, p)) return Progress(State.READY, p.bytes, p.bytes)
        val ids = ids(ctx, p)
        if (ids.isEmpty()) return Progress(State.MISSING, 0, p.bytes)
        val dm = ctx.getSystemService(DownloadManager::class.java)
        var done = 0L; var total = 0L; var ok = 0; var failed: String? = null; var paused = false
        dm.query(DownloadManager.Query().setFilterById(*ids))?.use { c ->
            val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iDone = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            while (c.moveToNext()) {
                done += c.getLong(iDone).coerceAtLeast(0)
                total += c.getLong(iTotal).takeIf { it > 0 } ?: 0
                when (c.getInt(iStatus)) {
                    DownloadManager.STATUS_SUCCESSFUL -> ok++
                    DownloadManager.STATUS_FAILED -> failed = "Download failed (code ${c.getInt(iReason)})"
                    DownloadManager.STATUS_PAUSED -> paused = true
                }
            }
        }
        if (total < p.bytes / 2) total = p.bytes
        if (ok == ids.size) {
            marker(ctx, p).apply { parentFile?.mkdirs(); writeText("ok") }
            prefs(ctx).edit().remove(p.id).apply()
            return Progress(State.READY, p.bytes, p.bytes)
        }
        return when {
            failed != null -> Progress(State.FAILED, done, total, failed)
            paused -> Progress(State.PAUSED, done, total, "Waiting for Wi-Fi or network")
            else -> Progress(State.DOWNLOADING, done, total)
        }
    }

    fun freeBytes(ctx: Context): Long = runCatching { root(ctx).apply { mkdirs() }.usableSpace }.getOrDefault(0L)
}
