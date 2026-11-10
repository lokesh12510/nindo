package app.nindo

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Ready-made categories of websites and apps. Each has a small built-in list; some can
 * download a large community-maintained list (StevenBlack/hosts) for much wider coverage.
 */
object Blocklists {

    data class Category(
        val id: String,
        val name: String,
        val description: String,
        val domains: Set<String>,
        val apps: Set<String> = emptySet(),
        /** Also match apps whose Play category is "Game". */
        val playGames: Boolean = false,
        val remoteUrl: String? = null,
    )

    private const val SB = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates"

    val CATEGORIES: List<Category> = listOf(
        Category(
            "social", "Social media", "Instagram, Facebook, X, Snapchat, ShareChat, Reddit…",
            setOf(
                "facebook.com", "fb.com", "fbcdn.net", "messenger.com", "instagram.com", "cdninstagram.com", "threads.net",
                "twitter.com", "x.com", "twimg.com", "t.co", "snapchat.com", "sc-cdn.net", "tiktok.com", "tiktokcdn.com",
                "tiktokv.com", "musical.ly", "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "pinterest.com",
                "pinimg.com", "tumblr.com", "linkedin.com", "licdn.com", "sharechat.com", "mojapp.in", "myjosh.in",
                "roposo.com", "chingari.io", "koo.in", "quora.com", "discord.com", "discord.gg", "discordapp.com",
                "telegram.org", "t.me", "vk.com", "weibo.com", "bsky.app", "mastodon.social", "lemon8-app.com", "bereal.com",
            ),
            apps = Apps.SOCIAL + setOf("com.discord", "org.telegram.messenger", "com.quora.android", "com.bereal.ft"),
            remoteUrl = "$SB/social-only/hosts",
        ),
        Category(
            "video", "Video & short video", "YouTube, Netflix, Prime Video, Hotstar, Twitch…",
            setOf(
                "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "youtube-nocookie.com", "netflix.com", "nflxvideo.net",
                "nflximg.net", "primevideo.com", "hotstar.com", "jiocinema.com", "jiohotstar.com", "zee5.com", "sonyliv.com",
                "mxplayer.in", "voot.com", "altbalaji.com", "erosnow.com", "sunnxt.com", "aha.video", "hoichoi.tv",
                "twitch.tv", "ttvnw.net", "kick.com", "dailymotion.com", "vimeo.com", "hulu.com", "disneyplus.com", "max.com",
                "crunchyroll.com", "bilibili.com", "rumble.com",
            ),
            apps = setOf(
                "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
                "com.jio.media.ondemand", "com.graymatrix.did", "com.sonyliv", "com.mxtech.videoplayer.ad", "tv.twitch.android.app",
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "in.mohalla.video", "com.eterno.shortvideos",
            ),
        ),
        Category(
            "games", "Games", "Gaming sites, plus every app listed as a game on your phone",
            setOf(
                "steampowered.com", "steamcommunity.com", "epicgames.com", "roblox.com", "rbxcdn.com", "miniclip.com", "poki.com",
                "crazygames.com", "chess.com", "lichess.org", "ea.com", "battle.net", "playstation.com", "xbox.com", "pubg.com",
                "battlegroundsmobileindia.com", "freefire.garena.com", "garena.com", "supercell.com", "king.com", "itch.io",
                "y8.com", "friv.com", "kongregate.com", "armorgames.com", "coolmathgames.com",
            ),
            apps = setOf("com.pubg.imobile", "com.dts.freefireth", "com.dts.freefiremax", "com.supercell.clashofclans",
                "com.supercell.clashroyale", "com.supercell.brawlstars", "com.king.candycrushsaga", "com.roblox.client",
                "com.activision.callofduty.shooter", "com.mojang.minecraftpe", "com.ludo.king", "com.miniclip.eightballpool"),
            playGames = true,
        ),
        Category(
            "gambling", "Betting & gambling", "Casinos, betting, real-money games",
            setOf(
                "bet365.com", "betway.com", "1xbet.com", "parimatch.com", "parimatch.in", "stake.com", "dafabet.com", "fun88.com",
                "22bet.com", "pokerstars.com", "pokerstars.in", "betfair.com", "williamhill.com", "unibet.com", "888casino.com",
                "888poker.com", "draftkings.com", "fanduel.com", "bovada.lv", "rummycircle.com", "junglee.com", "jungleerummy.com",
                "a23.com", "mpl.live", "dream11.com", "my11circle.com", "winzogames.com", "pocket52.com", "adda52.com",
                "pokerbaazi.com", "spartanpoker.com", "khelplayrummy.com", "rummyculture.com", "4rabet.com", "mostbet.com",
                "melbet.com", "batery.in", "lottoland.com", "betmgm.com", "casumo.com", "leovegas.com", "roobet.com",
            ),
            apps = setOf("com.dream11sportsguru", "com.app.dream11Pro", "com.mpl.androidapp", "com.games24x7.rummycircle.rummy",
                "com.jungleerummy.playcash", "com.ace2three.app", "com.winzo.gold", "com.my11circle.android"),
            remoteUrl = "$SB/gambling-only/hosts",
        ),
        Category(
            "adult", "Adult content", "Porn and explicit sites. Download the full list for wide coverage.",
            setOf(
                "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com", "onlyfans.com",
                "chaturbate.com", "stripchat.com", "spankbang.com", "eporner.com", "tube8.com", "brazzers.com", "livejasmin.com",
                "bongacams.com", "cam4.com", "porn.com", "beeg.com", "motherless.com", "fapello.com", "hqporner.com", "txxx.com",
                "porntrex.com", "thumbzilla.com", "sex.com", "fansly.com", "manyvids.com", "camsoda.com", "myfreecams.com",
                "rule34.xxx", "nhentai.net", "hanime.tv", "e-hentai.org",
            ),
            remoteUrl = "$SB/porn-only/hosts",
        ),
        Category(
            "shopping", "Shopping", "Amazon, Flipkart, Myntra, Meesho, Ajio…",
            setOf(
                "amazon.in", "amazon.com", "flipkart.com", "myntra.com", "meesho.com", "ajio.com", "nykaa.com", "snapdeal.com",
                "tatacliq.com", "shopsy.in", "jiomart.com", "firstcry.com", "lenskart.com", "bewakoof.com", "ebay.com",
                "aliexpress.com", "temu.com", "shein.com", "etsy.com", "walmart.com", "croma.com", "reliancedigital.in",
            ),
            apps = setOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.flipkart.android",
                "com.myntra.android", "com.meesho.supply", "com.ril.ajio", "com.fsn.nykaa", "com.snapdeal.main",
                "com.flipkart.shopsy", "com.einnovation.temu", "com.zzkko"),
        ),
        Category(
            "news", "News", "News sites and apps",
            setOf(
                "timesofindia.indiatimes.com", "indiatimes.com", "ndtv.com", "hindustantimes.com", "indianexpress.com",
                "thehindu.com", "news18.com", "indiatoday.in", "aajtak.in", "zeenews.india.com", "republicworld.com",
                "moneycontrol.com", "livemint.com", "economictimes.indiatimes.com", "inshorts.com", "dailyhunt.in",
                "bbc.com", "bbc.co.uk", "cnn.com", "nytimes.com", "theguardian.com", "reuters.com", "foxnews.com",
                "news.google.com", "news.yahoo.com", "dinamalar.com", "dailythanthi.com", "manoramaonline.com",
            ),
            apps = setOf("com.nis.app", "com.eterno", "com.toi.reader.activities", "com.ndtv.india", "com.htmedia.mint",
                "com.google.android.apps.magazines", "bbc.mobile.news.ww", "com.indiatoday"),
        ),
        Category(
            "dating", "Dating", "Tinder, Bumble, Hinge, Aisle…",
            setOf(
                "tinder.com", "gotinder.com", "bumble.com", "hinge.co", "okcupid.com", "match.com", "aisle.co", "trulymadly.com",
                "quackquack.in", "happn.com", "badoo.com", "grindr.com", "plentyoffish.com", "pof.com", "coffeemeetsbagel.com",
            ),
            apps = setOf("com.tinder", "com.bumble.app", "co.hinge.app", "com.okcupid.okcupid", "com.aisle.app",
                "com.trulymadly.android.app", "com.quackquack", "com.ftw_and_co.happn", "com.badoo.mobile", "com.grindrapp.android"),
        ),
    )

    private val byId = CATEGORIES.associateBy { it.id }
    fun category(id: String) = byId[id]

    /** Downloaded domains per category. */
    private val downloaded = ConcurrentHashMap<String, Set<String>>()
    private var dir: File? = null
    /** Package -> is a game, cached. */
    private val gameApps = ConcurrentHashMap<String, Boolean>()

    fun init(filesDir: File) {
        dir = File(filesDir, "lists").also { it.mkdirs() }
        CATEGORIES.forEach { c -> load(c.id) }
    }

    private fun file(id: String) = dir?.let { File(it, "$id.txt") }

    private fun load(id: String) {
        val f = file(id) ?: return
        if (f.exists()) downloaded[id] = f.readLines().filter { it.isNotBlank() }.toHashSet()
    }

    fun downloadedCount(id: String) = downloaded[id]?.size ?: 0
    fun lastUpdated(id: String) = file(id)?.takeIf { it.exists() }?.lastModified() ?: 0L
    fun totalCount(id: String) = (category(id)?.domains?.size ?: 0) + downloadedCount(id)

    /** True if [host] or any parent domain is in the category. */
    fun containsHost(id: String, host: String): Boolean {
        val c = byId[id] ?: return false
        val extra = downloaded[id]
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            if (h in c.domains || (extra != null && h in extra)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // stop at the registrable part
            h = h.substring(dot + 1)
        }
    }

    fun containsApp(id: String, pkg: String, isGame: (String) -> Boolean = { false }): Boolean {
        val c = byId[id] ?: return false
        if (pkg in c.apps) return true
        return c.playGames && gameApps.getOrPut(pkg) { isGame(pkg) }
    }

    /** Parses a hosts file ("0.0.0.0 example.com") into domains. */
    fun parseHosts(text: String): Set<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.startsWith("0.0.0.0 ") || it.startsWith("127.0.0.1 ") }
        .map { it.substringAfter(' ').trim().lowercase() }
        .filter { it.contains('.') && it != "0.0.0.0" && it != "localhost" && !it.endsWith(".localdomain") }
        .toHashSet()

    /** Downloads the full list for a category (blocking; call off the main thread). Returns the count. */
    fun download(id: String): Int {
        val url = byId[id]?.remoteUrl ?: return 0
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val set = parseHosts(conn.inputStream.bufferedReader().readText())
            if (set.isEmpty()) throw IllegalStateException("Empty list")
            file(id)?.writeText(set.joinToString("\n"))
            downloaded[id] = set
            return set.size
        } finally {
            conn.disconnect()
        }
    }

    fun removeDownload(id: String) { file(id)?.delete(); downloaded.remove(id) }
}
