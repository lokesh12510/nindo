package app.nindo

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A local, DNS-only VPN. Only DNS traffic enters it (all other traffic goes out
 * normally), so it costs almost no battery or speed. Blocked names get "doesn't exist";
 * the rest are forwarded to the chosen upstream resolver. Works for every app and browser.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "app.nindo.STOP_VPN"
        private const val VPN_ADDR = "10.111.222.2"
        private const val DNS_ADDR = "10.111.222.1"
        val running = MutableStateFlow(false)

        /** Upstream resolvers for the Standard and Malware levels. */
        val UPSTREAMS = listOf(
            listOf("1.1.1.1", "8.8.8.8"),   // standard
            listOf("1.1.1.2", "1.0.0.2"),   // + malware (Cloudflare for Families)
        )
        val LEVEL_NAMES = listOf("Standard", "Block malware", "Block malware + adult")

        /** Family-safe resolvers for the adult level: they block adult sites by category, millions of them. */
        data class FamilyDns(val name: String, val servers: List<String>, val blurb: String)
        val FAMILY = listOf(
            FamilyDns("Cloudflare Families", listOf("1.1.1.3", "1.0.0.3"), "Adult + malware. Fast."),
            FamilyDns("CleanBrowsing Adult", listOf("185.228.168.10", "185.228.169.11"), "Adult + malware + phishing. Reddit etc. still work."),
            FamilyDns("CleanBrowsing Family", listOf("185.228.168.168", "185.228.169.168"),
                "Strictest: adult, proxies and VPNs, mixed-content sites; forces SafeSearch and YouTube restricted mode."),
            FamilyDns("AdGuard Family", listOf("94.140.14.15", "94.140.15.16"), "Adult + ads and trackers; forces SafeSearch."),
            FamilyDns("OpenDNS FamilyShield", listOf("208.67.222.123", "208.67.220.123"), "Adult content."),
        )

        /**
         * Encrypted-DNS endpoints browsers use to skip the phone's DNS. Blocking them while adult
         * filtering is on makes Chrome, Brave, Firefox and others fall back to the filtered DNS.
         */
        private val DOH_HOSTS = setOf(
            "dns.google", "dns.google.com", "dns64.dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
            "chrome.cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com", "security.cloudflare-dns.com",
            "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net", "doh.opendns.com", "dns.opendns.com",
            "doh.familyshield.opendns.com", "dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com",
            "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.dns.sb", "dns.sb", "doh.mullvad.net", "dns.mullvad.net",
            "dns.controld.com", "freedns.controld.com", "doh.xfinity.com", "dns.brave.com", "doh.libredns.gr",
            "use-application-dns.net", // Firefox's "is DoH allowed here?" canary: no answer = DoH off
        )

        fun isDohHost(domain: String): Boolean {
            val d = domain.lowercase().trimEnd('.')
            return DOH_HOSTS.any { d == it || d.endsWith(".$it") }
        }

        /** Adult filtering is on: chosen level, or automatically because an Adult content rule is on. */
        fun adultOn(store: Store = GuardApp.store): Boolean =
            store.dnsLevel >= 2 || (store.familyAuto && store.rules().any { it.enabled && it.target == Target.CATEGORY && it.value == "adult" })

        fun upstreams(store: Store = GuardApp.store): List<String> =
            if (adultOn(store)) FAMILY.getOrElse(store.familyDns) { FAMILY[0] }.servers
            else UPSTREAMS.getOrElse(store.dnsLevel) { UPSTREAMS[0] }

        /** Android's Private DNS set to a specific provider sends lookups around the VPN filter. */
        fun privateDnsBypasses(ctx: Context): Boolean =
            runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull() == "hostname"

        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var workers = Executors.newFixedThreadPool(8)
    private var timer: ScheduledExecutorService? = null
    private val lastBlockEvent = ConcurrentHashMap<String, Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (GuardApp.store.strict) return START_STICKY // can't be stopped during strict mode
            GuardApp.store.vpnWanted = false
            Partner.alert(this, "vpn_off", "The Nindō VPN filter was turned off in the app.")
            stopVpn(); stopSelf()
            return START_NOT_STICKY
        }
        startVpn() // also the path for Android's Always-on VPN
        return START_STICKY
    }

    @Synchronized
    private fun startVpn() {
        if (tun != null) return
        val pfd = runCatching {
            Builder()
                .setSession("Nindō DNS filter")
                .addAddress(VPN_ADDR, 32)
                .addRoute(DNS_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .setBlocking(true)
                .setMtu(1500)
                .also { b -> runCatching { b.addDisallowedApplication(packageName) } }
                .establish()
        }.getOrNull() ?: run { running.value = false; stopSelf(); return }

        tun = pfd
        GuardApp.store.vpnWanted = true
        running.value = true
        if (workers.isShutdown) workers = Executors.newFixedThreadPool(8)
        timer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ runCatching { GuardApp.db.flushDns() } }, 15, 15, TimeUnit.SECONDS)
        }
        reader = Thread({ readLoop(pfd) }, "dns-vpn").apply { start() }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) continue
                val q = DnsPacket.parse(buf, n) ?: continue
                workers.execute { handle(q, output) }
            }
        } catch (_: Exception) {
            // tun closed: VPN stopped
        }
    }

    private fun handle(q: DnsPacket.Query, out: FileOutputStream) {
        val domain = q.name.trimEnd('.')
        val verdict = runCatching {
            Engine.checkDomain(domain)
                ?: if (adultOn() && isDohHost(domain)) Engine.Verdict(null, "Encrypted DNS blocked so the adult filter can't be skipped") else null
        }.getOrNull()
        val reply: ByteArray? = if (verdict != null) {
            GuardApp.db.countDns(domain, blocked = true)
            logBlock(domain, verdict.reason)
            DnsPacket.wrap(q, DnsPacket.blockedReply(q))
        } else {
            GuardApp.db.countDns(domain, blocked = false)
            forward(q)
        }
        if (reply != null) runCatching { synchronized(out) { out.write(reply) } }
    }

    private fun forward(q: DnsPacket.Query): ByteArray? {
        val servers = upstreams()
        for (server in servers) {
            try {
                DatagramSocket().use { s ->
                    protect(s) // keep our own lookups out of the VPN
                    s.soTimeout = 4000
                    s.send(DatagramPacket(q.dns, q.dns.size, InetAddress.getByName(server), 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    return DnsPacket.wrap(q, rb, rp.length)
                }
            } catch (_: Exception) { /* try the next server */ }
        }
        return null
    }

    /** One timeline entry per domain per minute; apps retry lookups a lot. */
    private fun logBlock(domain: String, reason: String) {
        val now = System.currentTimeMillis()
        val last = lastBlockEvent[domain] ?: 0
        if (now - last < 60_000) return
        lastBlockEvent[domain] = now
        GuardApp.db.event(Kind.DNS_BLOCK, null, domain, reason)
    }

    @Synchronized
    private fun stopVpn() {
        reader?.interrupt(); reader = null
        runCatching { tun?.close() }; tun = null
        timer?.shutdownNow(); timer = null
        runCatching { GuardApp.db.flushDns() }
        running.value = false
    }

    override fun onRevoke() {
        Partner.alert(this, "vpn_off", "The Nindō VPN filter was turned off (another VPN or system settings).")
        // Android can't stop this, but the pause screen can meet it straight away.
        Urges.request(this, Urges.Request(Trigger.VPN_OFF, "VPN filter", extraLabel = "Open Nindō to turn it back on", onExtra = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }))
        GuardApp.store.vpnWanted = false
        stopVpn(); stopSelf()
    }

    override fun onDestroy() {
        stopVpn(); workers.shutdownNow(); super.onDestroy()
    }
}
