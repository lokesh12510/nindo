package app.nindo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/*
 * Phase 2C: Sensei's voice call, behind the voice test screen for now.
 *
 *   mic -> Silero VAD (end of speech) -> Moonshine (speech to text) -> Gemma 4 E2B (streamed, GPU)
 *       -> a voice (Piper or the phone's own), starting at the first clause while Gemma keeps writing
 *
 * Half-duplex: the mic is ignored while Sensei speaks, so it never hears itself.
 * Everything runs on the phone; nothing is sent anywhere.
 */

// ===================================================================== brain

/** Gemma 4 through LiteRT-LM. Load once; keep the conversation for context between turns. */
class SenseiBrainLlm(private val ctx: Context) {
    private var engine: Engine? = null
    private var convo: Conversation? = null
    var gpu = false; private set
    var boost = false; private set
    var modelName = ""; private set

    val loaded get() = engine != null

    /**
     * Loads the model (slow: call off the main thread). [boost] turns on speculative decoding;
     * if the model or phone can't do it, it loads again without. Returns the load time in ms.
     */
    @OptIn(ExperimentalApi::class)
    fun load(modelPath: String, useGpu: Boolean, wantBoost: Boolean, system: String): Long {
        close()
        ExperimentalFlags.enableBenchmark = true
        val t = SystemClock.elapsedRealtime()
        fun make(spec: Boolean): Engine = Engine(EngineConfig(
            modelPath = modelPath,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            cacheDir = ctx.cacheDir.absolutePath,
            speculativeDecodingConfig = SpeculativeDecodingConfig(if (spec) SpeculativeDecodingMode.ADAPTIVE else SpeculativeDecodingMode.OFF),
        )).also { it.initialize() }
        val e = if (wantBoost) runCatching { make(true) }.getOrNull()?.also { boost = true } ?: make(false).also { boost = false }
            else make(false).also { boost = false }
        engine = e; gpu = useGpu; modelName = modelPath.substringAfterLast('/')
        newConversation(system)
        return SystemClock.elapsedRealtime() - t
    }

    /** A fresh conversation. Its instructions are read straight away, so the first reply isn't slower. */
    fun newConversation(system: String, temperature: Double = 1.0) {
        runCatching { convo?.close() }
        // Gemma's recommended sampling for talk (livelier, less repetitive); low temperature for notes.
        convo = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = temperature),
            prefillPrefaceOnInit = true,
        ))
    }

    /** Streams a reply; [onText] gets each new piece. Returns tokens per second (0 if unknown). */
    @OptIn(ExperimentalApi::class)
    suspend fun reply(text: String, onText: (String) -> Unit): Double {
        val c = convo ?: error("Model not loaded")
        val sb = StringBuilder()
        c.sendMessageAsync(Message.user(text)).collect { m ->
            val piece = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (piece.isEmpty()) return@collect
            // Some versions send the whole reply so far, others only the new part.
            val delta = if (sb.isNotEmpty() && piece.startsWith(sb)) piece.substring(sb.length) else piece
            if (delta.isNotEmpty()) { sb.append(delta); onText(delta) }
        }
        return runCatching { c.getBenchmarkInfo().lastDecodeTokensPerSecond }.getOrDefault(0.0)
    }

    fun stop() { runCatching { convo?.cancelProcess() } }

    fun close() {
        runCatching { convo?.close() }; convo = null
        runCatching { engine?.close() }; engine = null
    }
}

// ====================================================================== ears

/** Mic + voice detection + speech recognition. Calls back with each finished sentence. */
class SenseiEars(vadModel: String, sttDir: String, val silenceS: Float) {
    private val vad = Vad(null, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = vadModel, threshold = 0.5f, minSilenceDuration = silenceS,
            minSpeechDuration = 0.25f, windowSize = WINDOW, maxSpeechDuration = 20f),
        sampleRate = RATE, numThreads = 1, provider = "cpu"))
    private val stt = OfflineRecognizer(null, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(preprocessor = "$sttDir/preprocess.onnx", encoder = "$sttDir/encode.int8.onnx",
                uncachedDecoder = "$sttDir/uncached_decode.int8.onnx", cachedDecoder = "$sttDir/cached_decode.int8.onnx"),
            tokens = "$sttDir/tokens.txt", numThreads = 2, provider = "cpu")))

    private val running = AtomicBoolean(false)
    /** While true, audio is thrown away (Sensei is speaking). */
    val muted = AtomicBoolean(true)
    /** Audio before this time is thrown away: the room still rings for a moment after Sensei stops. */
    @Volatile private var dropUntil = 0L
    /** Whether the phone's echo canceller is running on the mic. */
    val echoCancel = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val hearing = MutableStateFlow(false)
    private var thread: Thread? = null

    /** [onHeard] (text, speech length ms, recognition time ms) runs on the mic thread. */
    @SuppressLint("MissingPermission")
    fun start(onHeard: (String, Long, Long) -> Unit, onError: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WINDOW * 8))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { onError("Couldn't open the microphone"); running.set(false); return@Thread }
            val buf = ShortArray(WINDOW)
            val f = FloatArray(WINDOW)
            // The phone's own echo canceller removes Sensei's voice from the mic (it works because the
            // voice plays as call audio, see CallAudio). Noise suppression helps in a noisy room.
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            echoCancel.value = aec?.enabled == true
            rec.startRecording()
            try {
                while (running.get()) {
                    val n = rec.read(buf, 0, WINDOW)
                    if (n <= 0) continue
                    if (muted.get() || SystemClock.elapsedRealtime() < dropUntil) { vad.reset(); hearing.value = false; level.value = 0f; continue }
                    var peak = 0f
                    for (i in 0 until n) { f[i] = buf[i] / 32768f; peak = maxOf(peak, kotlin.math.abs(f[i])) }
                    level.value = peak
                    vad.acceptWaveform(if (n == WINDOW) f else f.copyOf(n))
                    hearing.value = vad.isSpeechDetected()
                    while (!vad.empty()) {
                        val seg = vad.front(); vad.pop()
                        val t = SystemClock.elapsedRealtime()
                        val s = stt.createStream()
                        s.acceptWaveform(seg.samples, RATE)
                        stt.decode(s)
                        val text = stt.getResult(s).text.trim()
                        s.release()
                        if (text.isNotEmpty()) onHeard(text, seg.samples.size * 1000L / RATE, SystemClock.elapsedRealtime() - t)
                    }
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
                runCatching { aec?.release() }; runCatching { ns?.release() }
                hearing.value = false; level.value = 0f
            }
        }.apply { name = "sensei-ears"; start() }
    }

    fun stop() { running.set(false); thread?.join(1500); thread = null }
    fun release() { stop(); runCatching { vad.release() }; runCatching { stt.release() } }

    /** Mic off: Sensei's turn. */
    fun mute() { muted.set(true) }

    /** Mic back on after [tailMs] more of ignored audio. */
    fun unmute(tailMs: Long) { dropUntil = SystemClock.elapsedRealtime() + tailMs; muted.set(false) }

    companion object {
        const val RATE = 16000
        const val WINDOW = 512
    }
}

// ===================================================================== voice

/** Sensei's voice. Sentences queue up and play in order; [onAllDone] fires when the last one ends. */
interface SenseiMouth {
    var onAllDone: (() -> Unit)?
    /** Starts a new reply: the next audio sets [firstAudioAt]. */
    fun beginReply()
    fun firstAudioAt(): Long
    fun say(sentence: String)
    val speaking: Boolean
    fun stop()
    fun shutdown()
}

/**
 * A Piper voice through sherpa-onnx. One thread turns sentences into audio while another plays,
 * so the next sentence is ready by the time the first ends. Loudness is evened out across voices.
 */
class PiperMouth(model: String, tokens: String, lexicon: String, private val speed: Float) : SenseiMouth {
    private val tts = OfflineTts(null, OfflineTtsConfig(
        model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = model, lexicon = lexicon, tokens = tokens),
            numThreads = 2, provider = "cpu"),
        maxNumSentences = 1))
    private val rate = tts.sampleRate()
    private val track = AudioTrack.Builder()
        .setAudioAttributes(CallAudio.attributes)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), (rate / 4) * 4))
        .build()

    private class Chunk(val epoch: Int, val text: String)
    private class Audio(val epoch: Int, val samples: FloatArray)
    private val texts = LinkedBlockingQueue<Chunk>()
    private val audio = LinkedBlockingQueue<Audio>()
    private val epoch = AtomicInteger(0)
    private val pending = AtomicInteger(0)
    private val alive = AtomicBoolean(true)
    @Volatile private var firstAudioAt = 0L
    @Volatile override var onAllDone: (() -> Unit)? = null
    private var written = 0L

    private val synth = Thread {
        while (alive.get()) {
            val c = runCatching { texts.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (c.epoch != epoch.get()) continue
            val gen = runCatching { tts.generate(c.text, 0, speed) }.getOrNull()
            if (gen == null || gen.samples.isEmpty()) { finishOne(); continue }
            audio.put(Audio(c.epoch, even(gen.samples)))
        }
    }.apply { name = "sensei-voice-synth"; start() }

    private val player = Thread {
        track.play()
        while (alive.get()) {
            val a = runCatching { audio.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            if (a.epoch != epoch.get()) continue
            if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime()
            track.write(a.samples, 0, a.samples.size, AudioTrack.WRITE_BLOCKING)
            written += a.samples.size
            // Wait for this chunk to finish playing before counting it done.
            while (alive.get() && a.epoch == epoch.get() && texts.isEmpty() && audio.isEmpty() &&
                (track.playbackHeadPosition.toLong() and 0xffffffffL) < written) Thread.sleep(15)
            finishOne()
        }
    }.apply { name = "sensei-voice-play"; start() }

    private fun finishOne() { if (pending.decrementAndGet() <= 0) { pending.set(0); onAllDone?.invoke() } }

    /** Same loudness for every voice: about -20 dBFS RMS, never clipping. */
    private fun even(s: FloatArray): FloatArray {
        var sum = 0.0; for (x in s) sum += x * x
        val rms = sqrt(sum / s.size).toFloat()
        if (rms < 1e-5f) return s
        val g = (0.1f / rms).coerceAtMost(4f)
        return FloatArray(s.size) { (s[it] * g).coerceIn(-0.98f, 0.98f) }
    }

    /** Runs the model once so the first real sentence isn't slower. */
    fun warmUp() { runCatching { tts.generate("Hi.", 0, speed) } }

    override fun beginReply() { firstAudioAt = 0L }
    override fun firstAudioAt() = firstAudioAt
    override fun say(sentence: String) {
        val s = sentence.trim()
        if (s.isEmpty()) return
        pending.incrementAndGet()
        texts.put(Chunk(epoch.get(), s))
    }
    override val speaking get() = pending.get() > 0
    override fun stop() {
        epoch.incrementAndGet(); texts.clear(); audio.clear(); pending.set(0)
        runCatching { track.pause(); track.flush(); written = 0; track.play() }
    }
    override fun shutdown() {
        stop(); alive.set(false)
        synth.join(500); player.join(500)
        runCatching { track.release() }; runCatching { tts.release() }
    }
}

/** The phone's own text-to-speech. [voiceName] picks one of its voices; lower [pitch] sounds softer. */
class SystemMouth(ctx: Context, private val voiceName: String?, private val pitch: Float) : SenseiMouth {
    private val ready = CountDownLatch(1)
    private var ok = false
    private val pending = AtomicInteger(0)
    @Volatile private var firstAudioAt = 0L
    @Volatile override var onAllDone: (() -> Unit)? = null
    private val tts = TextToSpeech(ctx.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime() }
            override fun onDone(id: String?) { if (pending.decrementAndGet() <= 0) onAllDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (pending.decrementAndGet() <= 0) onAllDone?.invoke() }
        })
    }

    /** Off the main thread only. */
    fun awaitReady(): Boolean {
        ready.await(5, TimeUnit.SECONDS)
        if (ok) {
            val v = voiceName?.let { n -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == n } }
            if (v != null) tts.voice = v else {
                val india = Locale("en", "IN")
                tts.language = if (tts.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.US
            }
            tts.setPitch(pitch)
            tts.setSpeechRate(1.0f)
            tts.setAudioAttributes(CallAudio.attributes)
        }
        return ok
    }

    /** English voices on this phone, offline ones first: (name, label). */
    fun englishVoices(): List<Pair<String, String>> = runCatching { tts.voices }.getOrNull().orEmpty()
        .filter { it.locale.language == "en" }
        .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.country != "IN" }, { it.name }))
        .map { v ->
            val region = when (v.locale.country) { "IN" -> "Indian"; "GB" -> "British"; "US" -> "American"; "AU" -> "Australian"; else -> v.locale.displayCountry }
            v.name to "$region English · ${v.name.substringAfterLast("-x-").substringBefore("-")}" + if (v.isNetworkConnectionRequired) " (online)" else ""
        }

    override fun beginReply() { firstAudioAt = 0L }
    override fun firstAudioAt() = firstAudioAt
    override fun say(sentence: String) {
        val s = sentence.trim()
        if (s.isEmpty()) return
        pending.incrementAndGet()
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, "s" + SystemClock.elapsedRealtimeNanos())
    }
    override val speaking get() = pending.get() > 0
    override fun stop() { pending.set(0); tts.stop() }
    override fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}

/** Splits what Gemma writes into pieces to speak. Plain Kotlin, unit-tested. */
object SpeechChunker {
    /**
     * Where to cut what Gemma has written so far (index after the cut), or 0 to keep waiting.
     * The first piece may end at a comma once it has about four words, so the voice starts sooner.
     */
    fun boundary(sb: CharSequence, firstPiece: Boolean): Int {
        var cut = 0
        var words = 0
        for (i in 0 until sb.length - 1) {
            val c = sb[i]
            if (c == ' ') words++
            val next = sb[i + 1].isWhitespace()
            if (!next) continue
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && i >= 8) cut = i + 1
            else if (firstPiece && cut == 0 && (c == ',' || c == ';' || c == ':' || c == '—') && words >= 4) return i + 1
        }
        return cut
    }
}

// ================================================================ call audio

/**
 * Makes the test call sound and behave like a phone call on speaker: Android's communication mode
 * turns on the phone's echo canceller for Sensei's voice, and the audio goes to the loudspeaker.
 */
object CallAudio {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private var savedMode = AudioManager.MODE_NORMAL
    private var active = false

    fun begin(ctx: Context) {
        if (active) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        savedMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
            }
        }
        active = true
    }

    /** Loudspeaker (default) or the earpiece, like holding a phone to your ear. */
    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                am.availableCommunicationDevices.firstOrNull { it.type == type }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = on
            }
        }
    }

    fun end(ctx: Context) {
        if (!active) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
        }
        runCatching { am.mode = savedMode }
        active = false
    }
}

/**
 * Last line of defence against Sensei answering itself: if what the mic heard is mostly words
 * Sensei just said, it's Sensei's own voice coming back, not you.
 */
object EchoGuard {
    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotEmpty() }

    /**
     * Echo = most of the heard three-word runs appear, in order, in what Sensei said. A normal answer
     * that reuses a couple of Sensei's words ("Python practice was good") doesn't match like that.
     */
    fun isEcho(heard: String, saidRecently: String): Boolean {
        val h = words(heard)
        if (h.size < 3) return false
        val said = words(saidRecently)
        if (said.size < 3) return false
        val runs = (0..said.size - 3).map { said.subList(it, it + 3) }.toSet()
        val mine = (0..h.size - 3).map { h.subList(it, it + 3) }
        return mine.count { it in runs }.toFloat() / mine.size >= 0.5f
    }
}

// ===================================================================== turns

/** One back-and-forth, with where the time went. All times in ms. */
data class VoiceTurn(
    val you: String,
    val sensei: String = "",
    val speechMs: Long = 0,
    /** Silence the voice detector waits before it decides you finished. */
    val endpointMs: Long = 0,
    val sttMs: Long = 0,
    /** From your text to Gemma's first word. */
    val firstTokenMs: Long = 0,
    /** From your text to the first piece Sensei starts saying. */
    val firstSentenceMs: Long = 0,
    /** From the moment you stopped talking to Sensei's voice starting. The number that matters. */
    val replyDelayMs: Long = 0,
    val tokensPerSec: Double = 0.0,
    val typed: Boolean = false,
    /** Sensei's hello when the call starts. */
    val greeting: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

/** Which voice Sensei speaks with: "piper:<id>" or "system:<voice name or empty>". */
object SenseiVoiceChoice {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voice_lab", 0)
    fun get(ctx: Context): String = prefs(ctx).getString("voice", null)
        ?: if (VoiceModels.ready(ctx, VoiceModels.LEXICON) && VoiceModels.ready(ctx, VoiceModels.VOICES.first().pack)) "piper:alan" else "system:"
    fun set(ctx: Context, v: String) = prefs(ctx).edit().putString("voice", v).apply()
    fun pitch(ctx: Context) = prefs(ctx).getFloat("pitch", 0.92f)
    fun setPitch(ctx: Context, p: Float) = prefs(ctx).edit().putFloat("pitch", p).apply()
    fun silence(ctx: Context) = prefs(ctx).getFloat("silence", 0.6f)
    fun setSilence(ctx: Context, s: Float) = prefs(ctx).edit().putFloat("silence", s).apply()
}

// ======================================================================= lab

/** The test call: owns brain, ears and voice, and the list of turns. One per app. */
object VoiceLab {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val thinking = MutableStateFlow(false)
    val turns = MutableStateFlow<List<VoiceTurn>>(emptyList())
    val loadMs = MutableStateFlow(0L)
    val sttLoadMs = MutableStateFlow(0L)
    val systemVoices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val voiceLabel = MutableStateFlow("")

    private var brain: SenseiBrainLlm? = null
    private var ears: SenseiEars? = null
    private var mouth: SenseiMouth? = null
    private var mouthKey = ""
    private var replyJob: Job? = null
    /** Sensei's hello, written in the background right after loading so the call opens instantly. */
    @Volatile private var readyGreeting: String? = null
    private var greetJob: Job? = null
    /** True from the moment you finish until Sensei has finished writing its reply. */
    @Volatile private var replying = false
    /** What Sensei said in the last two turns, to recognise its own voice in the mic. */
    @Volatile private var recentSaid = ""
    private var appCtx: Context? = null
    private const val TAIL_MS = 600L

    val brainLoaded get() = brain?.loaded == true
    val earsLoaded get() = ears != null
    fun level() = ears?.level
    fun hearing() = ears?.hearing
    fun backend() = if (brain?.gpu == true) "GPU" else "CPU"
    fun model() = brain?.modelName ?: ""

    fun loadBrain(ctx: Context, pack: VoiceModels.Pack, gpu: Boolean, boost: Boolean) {
        if (busy.value) return
        busy.value = true
        scope.launch { try { doLoadBrain(ctx, pack, gpu, boost) } finally { busy.value = false } }
    }

    fun loadEars(ctx: Context) {
        if (busy.value) return
        busy.value = true
        scope.launch { try { doLoadEars(ctx) } finally { busy.value = false } }
    }

    private fun doLoadBrain(ctx: Context, pack: VoiceModels.Pack, gpu: Boolean, boost: Boolean): Boolean {
        status.value = "Loading the brain on ${if (gpu) "GPU" else "CPU"}…"
        return try {
            val b = brain ?: SenseiBrainLlm(ctx.applicationContext).also { brain = it }
            val ms = b.load(VoiceModels.path(ctx, pack, pack.files.first().name), gpu, boost, systemPrompt())
            loadMs.value = ms
            ensureMouth(ctx)
            turns.value = emptyList()
            prepareGreeting()
            status.value = "Brain ready on ${if (gpu) "GPU" else "CPU"} in ${fmt(ms)}" +
                if (boost && !b.boost) " (speed boost not supported, loaded without)" else if (b.boost) " with speed boost" else ""
            true
        } catch (t: Throwable) {
            brain?.close()
            status.value = "Couldn't load the model: ${t.message ?: t.javaClass.simpleName}" + if (gpu) ". Try CPU." else ""
            false
        }
    }

    private fun doLoadEars(ctx: Context): Boolean {
        status.value = "Loading speech recognition…"
        return try {
            ears?.release(); ears = null; listening.value = false
            val t = SystemClock.elapsedRealtime()
            ears = SenseiEars(VoiceModels.path(ctx, VoiceModels.VAD, "silero_vad.onnx"),
                VoiceModels.path(ctx, VoiceModels.STT, "").trimEnd('/'), SenseiVoiceChoice.silence(ctx))
            sttLoadMs.value = SystemClock.elapsedRealtime() - t
            ensureMouth(ctx)
            status.value = "Speech ready in ${fmt(sttLoadMs.value)}"
            true
        } catch (t: Throwable) {
            status.value = "Couldn't load speech recognition: ${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    // ------------------------------------------------------------- calls

    enum class Phase { IDLE, CONNECTING, LIVE, SAVING }

    val phase = MutableStateFlow(Phase.IDLE)
    val callStartedAt = MutableStateFlow(0L)
    val userMuted = MutableStateFlow(false)
    val speakerOn = MutableStateFlow(true)
    /** Short line after a call, e.g. "Sensei noted 2 things and added 1 task". */
    val lastNotes = MutableStateFlow<String?>(null)
    @Volatile private var lastUsed = 0L
    @Volatile var labOpen = false
    private var warming: Job? = null
    private var idleWatch: Job? = null
    /** The conversation is new: no turns since the hello was prepared. */
    @Volatile private var fresh = false
    /** Why Sensei is calling you (set by the incoming-call screen before the call starts). */
    data class CallReason(val kind: ScheduledCall.Kind, val title: String)
    @Volatile var nextCallReason: CallReason? = null
    @Volatile private var reason: CallReason? = null

    /** This call's topics (see CallPlan) and how many times you've spoken. */
    @Volatile private var planTopics: List<String> = emptyList()
    @Volatile private var userTurns = 0
    /** Open tasks and habits when the call started, numbered for the notes ("you said 2 is done"). */
    @Volatile private var callTasks: List<SenseiTask> = emptyList()
    @Volatile private var callHabits: List<Habit> = emptyList()
    /** Sensei said [bye]: hang up once it finishes speaking. */
    @Volatile private var hangUpAfterSpeech = false

    /** Builds the call's topics from today's habits, streaks, tasks, interests and the last call. */
    private fun buildPlan() {
        val snap = runCatching { HabitEngine.lastOrLoad() }.getOrNull()
        val open = snap?.today?.filter { !it.done && !it.failed && it.habit.kind != HabitKind.AVOID && !it.habit.isAuto }.orEmpty()
        val all = snap?.let { s -> s.today.map { it.habit } + s.notToday }.orEmpty()
        val streaks = snap?.streaks.orEmpty().mapNotNull { (id, st) -> all.firstOrNull { it.id == id }?.let { it.name to st.current } }
            .filter { it.second >= 2 }.sortedByDescending { it.second }
        val db = GuardApp.sensei
        callTasks = runCatching { db.tasks().filter { !it.done } }.getOrDefault(emptyList())
        callHabits = open.map { it.habit }
        val lastFacts = runCatching { db.calls(1).firstOrNull()?.id?.let { id -> db.facts().filter { it.callId == id }.map { it.text } } }.getOrNull().orEmpty()
        planTopics = CallPlan.topics(CallPlan.Input(
            openHabits = callHabits.map { it.name }, streaks = streaks, openTasks = callTasks.map { it.title },
            interests = runCatching { db.interests().map { it.label } }.getOrDefault(emptyList()), fromLastCall = lastFacts))
        // A reminder call keeps the reminder as the first thing to check on.
        reason?.takeIf { it.kind == ScheduledCall.Kind.REMINDER || it.kind == ScheduledCall.Kind.SNOOZE }?.let { r ->
            planTopics = listOf("the reminder you called about (${r.title}): has it been done, or when will it be") + planTopics.take(4)
        }
        userTurns = 0
        hangUpAfterSpeech = false
    }

    /** Brain, speech recognition and voice are all downloaded. */
    fun modelsReady(ctx: Context) = VoiceModels.ready(ctx, VoiceModels.GEMMA_E2B) && VoiceModels.ready(ctx, VoiceModels.STT) &&
        VoiceModels.ready(ctx, VoiceModels.VAD)

    /**
     * Loads everything in the background (when the Sensei tab opens), so a call starts without the
     * 20-second wait. Frees the memory again after 10 idle minutes.
     */
    fun prewarm(ctx: Context): Job? {
        lastUsed = SystemClock.elapsedRealtime()
        if (!modelsReady(ctx)) return null
        if (brainLoaded && earsLoaded) return null
        warming?.let { if (it.isActive) return it }
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("voice_lab", 0)
        return scope.launch {
            busy.value = true
            try {
                if (!brainLoaded) doLoadBrain(app, VoiceModels.GEMMA_E2B, prefs.getBoolean("gpu", true), prefs.getBoolean("boost", true))
                if (!earsLoaded) doLoadEars(app)
                fresh = true
            } finally { busy.value = false }
        }.also { warming = it; watchIdle() }
    }

    private fun watchIdle() {
        if (idleWatch?.isActive == true) return
        idleWatch = scope.launch {
            while (true) {
                delay(60_000)
                val idle = SystemClock.elapsedRealtime() - lastUsed > 10 * 60_000L
                if (idle && phase.value == Phase.IDLE && !labOpen && !busy.value && brainLoaded) {
                    unload(); status.value = "Sensei went to sleep to free memory"
                }
            }
        }
    }

    /** Starts a real call: waits for loading if needed, opens a fresh conversation, and Sensei says hi. */
    fun beginCall(ctx: Context) {
        if (phase.value != Phase.IDLE) return
        lastUsed = SystemClock.elapsedRealtime()
        phase.value = Phase.CONNECTING
        userMuted.value = false; speakerOn.value = true; lastNotes.value = null
        val app = ctx.applicationContext
        scope.launch {
            (warming?.takeIf { it.isActive } ?: prewarm(app))?.join()
            if (phase.value != Phase.CONNECTING) return@launch // hung up while connecting
            if (!brainLoaded || !earsLoaded) { phase.value = Phase.IDLE; return@launch }
            reason = nextCallReason.also { nextCallReason = null }
            val opening = reason?.let { openingFor(it) }
            if (!fresh || turns.value.isNotEmpty() || opening != null) {
                interrupt(); turns.value = emptyList()
                brain?.newConversation(systemPrompt()); prepareGreeting(opening ?: GREETING)
            }
            fresh = false
            buildPlan()
            callStartedAt.value = System.currentTimeMillis()
            phase.value = Phase.LIVE
            SenseiCallService.start(app, incoming = reason != null)
            android.os.Handler(android.os.Looper.getMainLooper()).post { startCall(app) }
        }
    }

    fun toggleMute() {
        userMuted.value = !userMuted.value
        if (userMuted.value) ears?.mute() else maybeUnmute()
    }

    fun toggleSpeaker(ctx: Context) {
        speakerOn.value = !speakerOn.value
        CallAudio.setSpeaker(ctx.applicationContext, speakerOn.value)
    }

    /** Ends the call, then has Sensei write its notes and gets ready for the next call. */
    fun endCall(ctx: Context) {
        if (phase.value == Phase.CONNECTING) { phase.value = Phase.IDLE; return }
        if (phase.value != Phase.LIVE) return
        val started = callStartedAt.value
        stopListening(); interrupt()
        phase.value = Phase.SAVING
        SenseiCallService.stop(ctx.applicationContext)
        lastUsed = SystemClock.elapsedRealtime()
        scope.launch {
            val done = turns.value.filter { it.done && it.error == null }
            val lines = done.flatMap { t -> listOfNotNull(
                t.you.takeIf { !t.greeting && it.isNotBlank() }?.let { SenseiMemory.Line(false, it) },
                t.sensei.takeIf { it.isNotBlank() }?.let { SenseiMemory.Line(true, it) }) }
            try {
                if (lines.any { !it.sensei }) lastNotes.value = writeNotes(lines, started)
            } catch (t: Throwable) {
                lastNotes.value = "Couldn't write notes from this call: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                turns.value = emptyList()
                brain?.newConversation(systemPrompt()); prepareGreeting(); fresh = true
                phase.value = Phase.IDLE
            }
        }
    }

    /** Gemma reads the call (in a separate, low-temperature conversation) and the notes are saved. */
    private suspend fun writeNotes(lines: List<SenseiMemory.Line>, started: Long): String {
        val b = brain ?: return "Call saved"
        val db = GuardApp.sensei
        val transcript = SenseiMemory.transcript(lines)
        val tasksAtStart = callTasks
        val habitsAtStart = callHabits
        b.newConversation(SenseiMemory.notesPrompt(db.facts().map { it.text }, db.interests().map { it.label },
            tasksAtStart.map { it.title }, habitsAtStart.map { it.name }, CallTimes.nowText(System.currentTimeMillis())), temperature = 0.2)
        val sb = StringBuilder()
        b.reply("Here is the call:\n$transcript") { sb.append(it) }
        val notes = SenseiMemory.parse(sb.toString())
        val id = java.util.UUID.randomUUID().toString()
        db.saveCall(CallLog(id, started, System.currentTimeMillis(), notes?.summary.orEmpty(), transcript))
        if (notes == null) return "Call saved"
        val facts = notes.facts.count { db.addFact(it, id) }
        // Things you said you'd already done: tick them (numbers refer to the lists in the prompt).
        val doneTasks = notes.doneTasks.mapNotNull { tasksAtStart.getOrNull(it - 1) }.filter { !it.done }
        doneTasks.forEach { db.setTaskDone(it.id, true) }
        val doneHabits = notes.doneHabits.mapNotNull { habitsAtStart.getOrNull(it - 1) }
            .filter { it.kind != HabitKind.AVOID && !it.isAuto }
        doneHabits.forEach { h -> runCatching { GuardApp.habits.complete(h) } }
        val t = today()
        notes.tasks.forEach { (title, due) ->
            db.upsertTask(SenseiTask(title = title, due = when (due) { "today" -> t; "tomorrow" -> Days.shift(t, 1); else -> null },
                source = SenseiSource.CALL))
        }
        val known = db.interests().map { it.label.lowercase() }.toSet()
        val forgotten = db.forgotten()
        val newInterests = notes.interests.filter { it.lowercase() !in known && it.lowercase() !in forgotten }
        newInterests.forEach { db.upsertInterest(Interest(label = it.replaceFirstChar { c -> c.uppercase() }, source = SenseiSource.CALL)) }
        if (newInterests.isNotEmpty()) Thread { Feeds.afterInterestsChanged(GuardApp.app) }.start()
        // "Remind me at 7 to call mom" -> Sensei calls you at 7.
        val app = appCtx ?: GuardApp.app
        val now = System.currentTimeMillis()
        val calls = notes.reminders.mapNotNull { r -> CallTimes.at(now, r.minutes, r.day)?.let { at -> r.title to at } }
            .filter { (title, at) -> SenseiCalls.addReminder(app, title, at) }
        val ticked = doneTasks.size + doneHabits.size
        val parts = listOfNotNull(
            ticked.takeIf { it > 0 }?.let { "ticked off $it" },
            calls.firstOrNull()?.let { (title, at) -> "will call you ${CallTimes.whenLabel(now, at)} about $title" +
                if (calls.size > 1) " (+${calls.size - 1} more)" else "" },
            facts.takeIf { it > 0 }?.let { "remembered $it ${if (it == 1) "thing" else "things"}" },
            notes.tasks.size.takeIf { it > 0 }?.let { "added $it ${if (it == 1) "task" else "tasks"}" },
            newInterests.size.takeIf { it > 0 }?.let { "picked up ${newInterests.joinToString(", ")}" },
        )
        return if (parts.isEmpty()) "Call saved" else "Sensei " + parts.joinToString(", ").let {
            val i = it.lastIndexOf(", "); if (i > 0) it.substring(0, i) + " and " + it.substring(i + 2) else it }
    }

    /** Background threads only. Builds the chosen voice; Piper if downloaded, else the phone's voice. */
    private fun ensureMouth(ctx: Context): SenseiMouth? {
        val choice = SenseiVoiceChoice.get(ctx)
        val key = choice + "|" + SenseiVoiceChoice.pitch(ctx)
        mouth?.let { if (key == mouthKey) return it; it.shutdown(); mouth = null }
        val piper = choice.removePrefix("piper:").takeIf { choice.startsWith("piper:") }
            ?.let { id -> VoiceModels.VOICES.firstOrNull { it.id == id } }
            ?.takeIf { VoiceModels.ready(ctx, it.pack) && VoiceModels.ready(ctx, VoiceModels.LEXICON) }
        val m: SenseiMouth? = if (piper != null) runCatching {
            PiperMouth(VoiceModels.path(ctx, piper.pack, piper.pack.files[0].name), VoiceModels.path(ctx, piper.pack, "tokens.txt"),
                VoiceModels.path(ctx, VoiceModels.LEXICON, "lexicon-en-us.txt"), piper.speed).also { it.warmUp() }
        }.onFailure { status.value = "Couldn't load the voice: ${it.message}" }.getOrNull()
        else systemMouth(ctx, choice.removePrefix("system:").ifEmpty { null })
        voiceLabel.value = piper?.let { "${it.name} (${it.accent})" } ?: "Phone voice"
        m?.onAllDone = { maybeUnmute() }
        mouth = m; mouthKey = key
        return m
    }

    /** TTS is created on the main thread, then waited for here. */
    private fun systemMouth(ctx: Context, name: String?): SystemMouth? {
        val made = arrayOfNulls<SystemMouth>(1)
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            made[0] = runCatching { SystemMouth(ctx, name, SenseiVoiceChoice.pitch(ctx)) }.getOrNull(); latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        val m = made[0] ?: return null
        if (!m.awaitReady()) status.value = "The phone's text-to-speech isn't available"
        systemVoices.value = m.englishVoices()
        return m
    }

    /** Switches voice and plays a short sample with it. */
    fun preview(ctx: Context, choice: String) {
        SenseiVoiceChoice.set(ctx, choice)
        scope.launch {
            val m = ensureMouth(ctx) ?: return@launch
            m.stop(); m.beginReply()
            m.say("Hey, good to hear from you. You got your practice in today, and honestly, that's the part that counts.")
        }
    }

    /** Lists the phone's voices (loads its TTS once). */
    fun loadSystemVoices(ctx: Context) {
        if (systemVoices.value.isNotEmpty()) return
        scope.launch { systemMouth(ctx, null)?.shutdown() }
    }

    /** Starts the hands-free test call: Sensei says hello first, then listens. */
    fun startCall(ctx: Context) {
        val e = ears ?: return
        if (!brainLoaded) { status.value = "Load the brain first"; return }
        appCtx = ctx.applicationContext
        CallAudio.begin(ctx.applicationContext)
        e.mute()
        e.start(onHeard = { text, speechMs, sttMs -> onHeard(text, speechMs, sttMs) },
            onError = { status.value = it; listening.value = false })
        listening.value = true
        status.value = "On a call with Sensei"
        if (turns.value.isEmpty()) scope.launch {
            greetJob?.join()
            val g = readyGreeting
            readyGreeting = null
            if (g != null) speakPrepared(g) else onSaid(GREETING, 0, 0, typed = true, greeting = true)
        }
        else e.unmute(TAIL_MS)
    }

    fun stopListening() {
        ears?.stop(); listening.value = false
        appCtx?.let { CallAudio.end(it) }
    }

    fun echoCancel() = ears?.echoCancel

    /** Writes the hello now (Gemma is idle anyway), so the call's first words come without a wait. */
    private fun prepareGreeting(prompt: String = GREETING) {
        val b = brain ?: return
        readyGreeting = null
        greetJob = scope.launch {
            val sb = StringBuilder()
            runCatching { b.reply(prompt) { sb.append(it) } }
            readyGreeting = sb.toString().trim().ifEmpty { null }
        }
    }

    /** Speaks the prepared hello. Its delay is just the voice starting. */
    private fun speakPrepared(text: String) {
        val m = mouth
        ears?.mute()
        replying = true
        thinking.value = true
        val t0 = SystemClock.elapsedRealtime()
        val index = turns.value.size
        turns.value = turns.value + VoiceTurn(you = "", sensei = text, typed = true, greeting = true)
        try {
            m?.beginReply()
            val rest = StringBuilder(text)
            var cut = boundary(rest, firstPiece = true)
            while (cut > 0) { m?.say(clean(rest.substring(0, cut))); rest.delete(0, cut); cut = boundary(rest, firstPiece = false) }
            if (rest.isNotBlank()) m?.say(clean(rest.toString()))
            var waited = 0
            while (m != null && m.firstAudioAt() == 0L && waited < 5000) { Thread.sleep(20); waited += 20 }
            val audio = m?.firstAudioAt() ?: 0L
            update(index) { it.copy(replyDelayMs = if (audio > 0) audio - t0 else 0, done = true) }
            recentSaid = text
        } finally {
            replying = false
            thinking.value = false
            maybeUnmute()
        }
    }

    /** From the mic. Dropped if Sensei is still on its turn or if it's Sensei's own voice coming back. */
    private fun onHeard(text: String, speechMs: Long, sttMs: Long) {
        if (replying || thinking.value || mouth?.speaking == true) return
        if (EchoGuard.isEcho(text, recentSaid)) { status.value = "Ignored an echo of Sensei: \u201c$text\u201d"; return }
        onSaid(text, speechMs, sttMs, typed = false)
    }

    /** Mic back on only when the reply is fully written and fully spoken, plus a short tail for the room. */
    private fun maybeUnmute() {
        if (replying || mouth?.speaking == true) return
        if (hangUpAfterSpeech) {
            hangUpAfterSpeech = false
            scope.launch { delay(700); appCtx?.let { endCall(it) } }
            return
        }
        scope.launch {
            delay(50)
            if (!replying && mouth?.speaking != true && listening.value && !userMuted.value) ears?.unmute(TAIL_MS)
        }
    }

    /** Typed test: same path without the microphone. */
    fun send(text: String) { if (brainLoaded && text.isNotBlank()) onSaid(text.trim(), 0, 0, typed = true) }

    private fun onSaid(text: String, speechMs: Long, sttMs: Long, typed: Boolean, greeting: Boolean = false) {
        val b = brain ?: return
        val m = mouth
        if (thinking.value) return
        ears?.mute()
        replying = true
        thinking.value = true
        val endpointMs = if (typed) 0L else ((ears?.silenceS ?: 0.6f) * 1000).toLong()
        // Time zero: when you stopped talking (typed: when you pressed send).
        val t0 = SystemClock.elapsedRealtime() - sttMs - endpointMs
        val tText = SystemClock.elapsedRealtime()
        val index = turns.value.size
        turns.value = turns.value + VoiceTurn(you = if (greeting) "" else text, speechMs = speechMs, endpointMs = endpointMs,
            sttMs = sttMs, typed = typed, greeting = greeting)
        // A private note keeps the call moving through its topics and lets Sensei end it (see CallPlan).
        if (!greeting) userTurns++
        if (planTopics.isEmpty()) buildPlan()
        val note = if (greeting) null else CallPlan.note(userTurns, planTopics, text)
        val toBrain = if (note == null) text else "$text\n\n$note"
        replyJob = scope.launch {
            greetJob?.join()
            var first = 0L; var firstSpoken = 0L
            var bye = false
            val all = StringBuilder(); val pending = StringBuilder()
            m?.beginReply()
            try {
                val tps = b.reply(toBrain) { piece ->
                    if (first == 0L) first = SystemClock.elapsedRealtime() - tText
                    all.append(piece); pending.append(piece)
                    if (CallPlan.BYE.containsMatchIn(all)) bye = true
                    // Never speak or show the [bye] token; wait while a "[" might still become one.
                    val clean = CallPlan.BYE.replace(pending, "")
                    if (clean.length != pending.length) { pending.setLength(0); pending.append(clean) }
                    if (pending.lastIndexOf('[') > pending.lastIndexOf(']')) {
                        update(index) { it.copy(sensei = CallPlan.BYE.replace(all, "").trim(), firstTokenMs = first, firstSentenceMs = firstSpoken) }
                        return@reply
                    }
                    // The first piece goes out at the first clause; after that, whole sentences.
                    val cut = boundary(pending, firstPiece = firstSpoken == 0L)
                    if (cut > 0) {
                        val s = pending.substring(0, cut); pending.delete(0, cut)
                        if (firstSpoken == 0L) firstSpoken = SystemClock.elapsedRealtime() - tText
                        m?.say(clean(s))
                    }
                    update(index) { it.copy(sensei = CallPlan.BYE.replace(all, "").trim(), firstTokenMs = first, firstSentenceMs = firstSpoken) }
                }
                CallPlan.BYE.replace(pending, "").let { pending.setLength(0); pending.append(it.replace("[", "")) }
                if (pending.isNotBlank()) {
                    if (firstSpoken == 0L) firstSpoken = SystemClock.elapsedRealtime() - tText
                    m?.say(clean(pending.toString()))
                }
                var waited = 0
                while (m != null && m.firstAudioAt() == 0L && waited < 5000) { Thread.sleep(20); waited += 20 }
                val audio = m?.firstAudioAt() ?: 0L
                update(index) { it.copy(sensei = CallPlan.BYE.replace(all, "").trim(), firstTokenMs = first, firstSentenceMs = firstSpoken,
                    replyDelayMs = if (audio > 0) audio - t0 else 0, tokensPerSec = tps, done = true) }
                recentSaid = (turns.value.takeLast(2).joinToString(" ") { it.sensei })
                // Hang up after the goodbye, but only in a real call (not the test screen).
                if (bye && phase.value == Phase.LIVE) hangUpAfterSpeech = true
            } catch (t: Throwable) {
                update(index) { it.copy(error = t.message ?: t.javaClass.simpleName, done = true) }
            } finally {
                replying = false
                thinking.value = false
                maybeUnmute()
            }
        }
    }

    fun interrupt() { brain?.stop(); mouth?.stop(); replying = false; if (listening.value && !userMuted.value) ears?.unmute(TAIL_MS) }

    /** Fresh conversation with today's context. */
    fun reset() { interrupt(); brain?.newConversation(systemPrompt()); turns.value = emptyList(); prepareGreeting() }

    fun unload() {
        interrupt(); stopListening()
        recentSaid = ""
        brain?.close(); brain = null
        ears?.release(); ears = null
        mouth?.shutdown(); mouth = null; mouthKey = ""
        turns.value = emptyList(); status.value = "Unloaded"
    }

    private fun update(i: Int, f: (VoiceTurn) -> VoiceTurn) {
        val l = turns.value.toMutableList()
        if (i in l.indices) { l[i] = f(l[i]); turns.value = l }
    }

    internal fun boundary(sb: CharSequence, firstPiece: Boolean) = SpeechChunker.boundary(sb, firstPiece)

    /** Text for speaking: no markdown symbols or emoji. */
    private fun clean(s: String) = s.replace(Regex("[*_#`>]+"), "").replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}]"), "").trim()

    fun report(): String = buildString {
        appendLine("Nindō voice lab, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Model ${model()} on ${backend()}${if (brain?.boost == true) " + speed boost" else ""}, load ${fmt(loadMs.value)}; " +
            "speech load ${fmt(sttLoadMs.value)}; voice ${voiceLabel.value}; end of speech ${fmt(((ears?.silenceS ?: 0f) * 1000).toLong())}")
        turns.value.filter { it.done }.forEachIndexed { i, t ->
            appendLine("${i + 1}. ${if (t.greeting) "hello" else if (t.typed) "typed" else "spoke ${fmt(t.speechMs)}"} | stt ${fmt(t.sttMs)} | " +
                "first word ${fmt(t.firstTokenMs)} | first spoken ${fmt(t.firstSentenceMs)} | reply delay ${fmt(t.replyDelayMs)} | " +
                "${"%.1f".format(t.tokensPerSec)} tok/s" + (t.error?.let { " | error $it" } ?: ""))
        }
    }

    fun fmt(ms: Long) = if (ms <= 0) "-" else if (ms < 1000) "${ms} ms" else "%.1f s".format(ms / 1000.0)

    /** How Sensei opens a call it made to you. */
    private fun openingFor(r: CallReason): String? = when (r.kind) {
        ScheduledCall.Kind.REMINDER, ScheduledCall.Kind.SNOOZE ->
            "(You are calling them because they asked you to remind them: \"${r.title}\". The call just connected. In one or two short " +
                "lines, say hi and remind them, then ask if they've done it or when they will. Don't ask anything else yet.)"
        ScheduledCall.Kind.DAILY ->
            "(You are calling for your daily evening check-in, which they set up. The call just connected. Say hi in one short casual line " +
                "and ask how their day went.)"
        ScheduledCall.Kind.TEST -> "(This is a test call they asked for. Say hi in one short line and say the call works.)"
    }

    /** Hidden message that makes Sensei open the call. */
    private const val GREETING = "(The call just connected. Say hi first, in one short casual line, like a friend picking up the phone. " +
        "If something from today fits, mention it lightly. Don't ask more than one thing.)"

    /** Who Sensei is and how it talks, plus today's context from the app. */
    fun systemPrompt(): String {
        val snap = runCatching { HabitEngine.lastOrLoad() }.getOrNull()
        val pending = snap?.today?.filter { !it.done && !it.failed && it.habit.kind != HabitKind.AVOID }?.map { it.habit.name }.orEmpty()
        val done = snap?.today?.filter { it.done }?.map { it.habit.name }.orEmpty()
        val interests = runCatching { GuardApp.sensei.interests().map { it.label } }.getOrDefault(emptyList())
        val tasks = runCatching { GuardApp.sensei.tasks().filter { !it.done }.map { it.title } }.getOrDefault(emptyList())
        return buildString {
            appendLine("You are Sensei, the user's friend and coach in the Nindo app, on a relaxed phone call. " +
                "Nindo means 'the ninja way': keep going, don't give up.")
            appendLine()
            appendLine("How you talk:")
            appendLine("- Like a close, easy-going friend who happens to be a great coach: warm, a little playful, real.")
            appendLine("- This is spoken out loud. Usually one or two short sentences. No lists, no markdown, no emojis.")
            appendLine("- React first to what they just said: how it lands with you, a small opinion, a light joke, or a short bit of Sensei wisdom.")
            appendLine("- Don't interview them. Ask a question in at most every second reply, and never more than one at a time. " +
                "Often just respond and let them carry on.")
            appendLine("- Match their energy: hyped when they're hyped, gentle when they're tired. Use their own words back now and then.")
            appendLine("- Never say 'great question', 'I understand', 'that's valid', or repeat back a summary. No lectures; " +
                "give advice only if they ask or really need a nudge.")
            appendLine("- Bring up habits and goals only when it fits naturally, like a friend would, never as a checklist.")
            appendLine("- Be honest. If they're slacking, tease them kindly and nudge. Don't flatter.")
            appendLine("- Keep the call moving: after a couple of replies on one thing, move on to something else from their day. " +
                "Their habits, streaks and open tasks matter; bring them up naturally.")
            appendLine("- Notes in brackets that start with 'Private note' are from the app, not the user. Follow them and never read them out.")
            appendLine("- When the user wants to end the call, or says yes to wrapping up, say a short warm goodbye and end your reply with [bye].")
            appendLine("- You're a coach, not a therapist. If they sound in real distress or talk about harming themselves, " +
                "say gently that you're worried and suggest calling Tele-MANAS on 14416 or someone they trust.")
            appendLine()
            appendLine("The vibe, for example:")
            appendLine("User: Ugh, today was so long.")
            appendLine("Sensei: Oof, one of those. Did it at least end better than it started?")
            appendLine("User: Not really, I skipped my workout.")
            appendLine("Sensei: Ha, the couch won this round. Happens to every ninja, we take it back tomorrow.")
            appendLine("User: I did finish the Python loops video though.")
            appendLine("Sensei: Wait, that's actually big. Loops are where it starts clicking.")
            appendLine()
            appendLine("What you know about today (use lightly):")
            if (done.isNotEmpty()) appendLine("- Done today: ${done.joinToString(", ")}.")
            if (pending.isNotEmpty()) appendLine("- Still open today: ${pending.joinToString(", ")}.")
            if (tasks.isNotEmpty()) appendLine("- Open tasks: ${tasks.take(8).joinToString(", ")}.")
            if (interests.isNotEmpty()) appendLine("- Learning: ${interests.joinToString(", ")}.")
            snap?.let { appendLine("- XP ${it.xp}, rank ${Ranks.of(it.xp).name}.") }
            runCatching { append(SenseiMemory.forNextCall(GuardApp.sensei.facts(), GuardApp.sensei.calls(2))) }
        }
    }
}
