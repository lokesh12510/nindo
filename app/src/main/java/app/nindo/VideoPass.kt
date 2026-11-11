package app.nindo

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * The Sensei pass: one picked video, played inside Nindō with YouTube's own embedded player.
 * No home feed, no Shorts, links out are blocked, and there's a time allowance (the video's
 * length plus 3 minutes, or 25 minutes if unknown). Watching 70% or to the end counts as
 * watched, which ticks the "Watch my Sensei pick" habit and today's mission.
 *
 * Nindō's own traffic skips its VPN filter, so a blocked youtube.com doesn't stop this player.
 */
object VideoPass {
    const val DEFAULT_MS = 25 * 60_000L
    const val EXTRA_MS = 3 * 60_000L

    /** The player page. The IFrame API reports progress back through `Nindo`. */
    fun html(videoId: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player = new YT.Player('p', {width:'100%', height:'100%', videoId:'$videoId', host:'https://www.youtube-nocookie.com',
    playerVars:{autoplay:1, playsinline:1, rel:0, modestbranding:1, fs:0, iv_load_policy:3, disablekb:0},
    events:{
      onReady:function(e){ Nindo.ready(e.target.getDuration()); e.target.playVideo(); },
      onStateChange:function(e){ Nindo.state(e.data); },
      onError:function(e){ Nindo.error(e.data); }
    }});
}
setInterval(function(){ try { if (player && player.getCurrentTime) Nindo.progress(player.getCurrentTime(), player.getDuration()); } catch(x){} }, 2000);
</script></body></html>
""".trimIndent()

    /** YouTube's ids are 11 letters, digits, - and _. Anything else is never put into the page. */
    fun safeId(id: String?): String? = id?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPassDialog(pick: Pick, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val id = VideoPass.safeId(pick.videoId)
    var durationS by remember { mutableDoubleStateOf(0.0) }
    var positionS by remember { mutableDoubleStateOf(0.0) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(id == null) }
    var watched by remember { mutableStateOf(pick.watched) }
    val opened = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(opened) }
    val allowance = if (durationS > 0) (durationS * 1000).toLong() + VideoPass.EXTRA_MS else VideoPass.DEFAULT_MS
    val left = (allowance - (now - opened)).coerceAtLeast(0)
    val timeUp = left == 0L
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) { senseiWrite { markOpened(pick.id) } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    // Watched: 70% or the end.
    LaunchedEffect(positionS, ended) {
        if (!watched && (ended || (durationS > 0 && positionS >= durationS * 0.7))) { watched = true; senseiWrite { markWatched(pick.id) } }
    }
    LaunchedEffect(timeUp) { if (timeUp) web?.evaluateJavascript("try{player.pauseVideo()}catch(e){}", null) }
    DisposableEffect(Unit) { onDispose { web?.apply { stopLoading(); loadUrl("about:blank"); destroy() } } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                    Text(pick.title, Modifier.weight(1f), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(clock(left), Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp), color = if (left < 60_000) Brand.Amber else Color.White, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    if (!failed && id != null && !timeUp && !ended) AndroidView(factory = { c ->
                        WebView(c).apply {
                            web = this
                            settings.javaScriptEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.BLACK)
                            // Only the player itself may load; taps on the title or logo don't open YouTube.
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                            }
                            addJavascriptInterface(object {
                                @JavascriptInterface fun ready(d: Double) { post { durationS = d } }
                                @JavascriptInterface fun progress(p: Double, d: Double) { post { positionS = p; if (d > 0) durationS = d } }
                                @JavascriptInterface fun state(s: Int) { post { if (s == 0) ended = true } }
                                @JavascriptInterface fun error(code: Int) { post { failed = true } }
                            }, "Nindo")
                            loadDataWithBaseURL("https://www.youtube-nocookie.com/", VideoPass.html(id), "text/html", "utf-8", null)
                        }
                    }, modifier = Modifier.fillMaxSize())
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(when { ended || watched -> Icons.Rounded.CheckCircle; timeUp -> Icons.Rounded.HourglassBottom; else -> Icons.Rounded.ErrorOutline },
                            null, Modifier.size(40.dp), tint = if (ended || watched) Brand.Green else Color.White)
                        Spacer(Modifier.height(10.dp))
                        Text(when {
                            ended -> "Done. That's today's video."
                            timeUp -> "That's your time for this one."
                            else -> "This video can't play inside Nindō."
                        }, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                }
                Column(Modifier.padding(20.dp)) {
                    Text(pick.reason, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    if (watched) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.TaskAlt, null, Modifier.size(18.dp), tint = Brand.Green)
                        Spacer(Modifier.width(8.dp))
                        Text("Watched. Your Sensei pick habit is ticked.", color = Color.White, fontSize = 14.sp)
                    } else Text("No feed, no Shorts, just this one. Watch most of it to tick your habit.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    if (failed) Button({
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pick.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, shape = RoundedCornerShape(50)) { Text("Open in YouTube") }
                    if (ended || timeUp) OutlinedButton(onDismiss, shape = RoundedCornerShape(50)) { Text("Close", color = Color.White) }
                }
            }
        }
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
