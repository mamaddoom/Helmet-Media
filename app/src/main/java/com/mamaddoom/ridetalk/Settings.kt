package com.mamaddoom.ridetalk

import android.content.Context
import android.net.Uri
import android.os.Build

/** Persistent user preferences. Plain SharedPreferences; reads are cheap and cached. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("ridetalk", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * Updating over an older build kept preferences whose meaning has since
     * changed (old "preset" numbers, the old call-mode default, ...), so an
     * update behaved differently from a fresh install. When the stored schema
     * is older than [SCHEMA], everything except the rider's own data (name,
     * group code, playlist, look and language) goes back to today's defaults.
     */
    private fun migrate() {
        if (p.getInt("schema", 0) >= SCHEMA) return
        val keep = setOf("name", "group", "playlist", "trackIndex", "shuffle", "theme", "lang", "onboarded")
        val e = p.edit()
        for (k in p.all.keys) if (k !in keep && !k.startsWith("srv_")) e.remove(k)
        e.putInt("schema", SCHEMA)
        e.commit()
    }

    // ------------------------------------------------------------- look & language

    /** First-launch choices made (theme and language). */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** true = blue theme, false = pink. */
    var blueTheme: Boolean
        get() = p.getString("theme", "pink") == "blue"
        set(v) = p.edit().putString("theme", if (v) "blue" else "pink").apply()

    /** "fa" or "en"; empty = follow the phone. */
    var language: String
        get() = p.getString("lang", "").orEmpty()
        // Written at once: the activity restarts right after to apply it.
        set(v) {
            p.edit().putString("lang", v).commit()
        }

    /** Online mode: connect through a public relay server instead of the hotspot. */
    var online: Boolean
        get() = p.getBoolean("online", false)
        set(v) = p.edit().putBoolean("online", v).apply()

    /** Pinned relay server id; empty = automatic (best first, then fall back). */
    var onlineServer: String
        get() = p.getString("onlineServer", "").orEmpty()
        set(v) = p.edit().putString("onlineServer", v).apply()

    /** Privacy mode (online): steady same-size encrypted traffic, so nobody can tell when you talk. */
    var privacyMode: Boolean
        get() = p.getBoolean("privacyMode", false)
        set(v) = p.edit().putBoolean("privacyMode", v).apply()

    /** Hide my IP (online): reach the relay through a SOCKS proxy such as Tor (Orbot). */
    var hideIp: Boolean
        get() = p.getBoolean("hideIp", false)
        set(v) = p.edit().putBoolean("hideIp", v).apply()

    /** host:port of that proxy; Orbot's default is 127.0.0.1:9050. */
    var proxyAddress: String
        get() = p.getString("proxyAddress", "127.0.0.1:9050").orEmpty()
        set(v) = p.edit().putString("proxyAddress", v.trim()).apply()

    fun proxy(): java.net.Proxy? {
        if (!hideIp) return null
        val host = proxyAddress.substringBeforeLast(':').ifEmpty { "127.0.0.1" }
        val port = proxyAddress.substringAfterLast(':').toIntOrNull() ?: 9050
        return java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
    }

    /** A login typed on this phone for a server that needs one: port, user, password, TLS. */
    class ServerLogin(val port: Int, val user: String, val password: String, val tls: Boolean)

    fun serverLogin(id: String): ServerLogin? {
        val host = if (id == com.mamaddoom.ridetalk.net.RelayServers.CUSTOM_ID) p.getString("srv_${id}_host", "").orEmpty() else "x"
        val pass = p.getString("srv_${id}_pass", "").orEmpty()
        val user = p.getString("srv_${id}_user", "").orEmpty()
        if (host.isEmpty() || (pass.isEmpty() && user.isEmpty() && id != com.mamaddoom.ridetalk.net.RelayServers.CUSTOM_ID)) return null
        return ServerLogin(p.getInt("srv_${id}_port", 1883), user, pass, p.getBoolean("srv_${id}_tls", false))
    }

    fun setServerLogin(id: String, host: String?, name: String?, l: ServerLogin?) {
        val e = p.edit()
        if (l == null) {
            for (k in listOf("host", "name", "port", "user", "pass", "tls")) e.remove("srv_${id}_$k")
        } else {
            host?.let { e.putString("srv_${id}_host", it.trim()) }
            name?.let { e.putString("srv_${id}_name", it.trim()) }
            e.putInt("srv_${id}_port", l.port).putString("srv_${id}_user", l.user.trim())
                .putString("srv_${id}_pass", l.password).putBoolean("srv_${id}_tls", l.tls)
        }
        e.apply()
    }

    fun customServerHost() = p.getString("srv_custom_host", "").orEmpty()
    fun customServerName() = p.getString("srv_custom_name", "").orEmpty()

    /**
     * Every relay this phone can use, in automatic order: the public ones (best
     * first), then the Iranian ones with a login, then your own server if set up.
     */
    fun relayServers(): List<com.mamaddoom.ridetalk.net.RelayServers.Server> {
        val rs = com.mamaddoom.ridetalk.net.RelayServers
        val out = ArrayList(rs.ALL)
        for (s in rs.IRAN) serverLogin(s.id)?.let { out += s.withLogin(it.port, it.user.ifEmpty { s.user.orEmpty() }, it.password, it.tls) }
        serverLogin(rs.CUSTOM_ID)?.let {
            out += com.mamaddoom.ridetalk.net.RelayServers.Server(
                rs.CUSTOM_ID, customServerName().ifEmpty { customServerHost() }, customServerHost(), it.port, 0,
            )
                .withLogin(it.port, it.user, it.password, it.tls)
        }
        return out
    }

    /** Spoken announcements ("music mode", "Sara joined") instead of beeps, when the phone can speak. */
    var voicePrompts: Boolean
        get() = p.getBoolean("voicePrompts", true)
        set(v) = p.edit().putBoolean("voicePrompts", v).apply()

    var name: String
        get() = p.getString("name", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        set(v) = p.edit().putString("name", v.trim().take(24)).apply()

    /** true = push-to-talk, false = open mic with voice activity detection. */
    var ptt: Boolean
        get() = p.getBoolean("ptt", false)
        set(v) = p.edit().putBoolean("ptt", v).apply()

    /** VOX sensitivity 0..100; higher opens the mic on quieter speech. */
    var voxSensitivity: Int
        get() = p.getInt("vox", 55)
        set(v) = p.edit().putInt("vox", v).apply()

    /** Mic gain in percent (50..300). */
    var micGain: Int
        get() = p.getInt("micGain", 100)
        set(v) = p.edit().putInt("micGain", v).apply()

    /** Voice volume in percent (0..200). */
    var voiceVolume: Int
        get() = p.getInt("voiceVol", 100)
        set(v) = p.edit().putInt("voiceVol", v).apply()

    /** Music volume in percent (0..100). */
    var musicVolume: Int
        get() = p.getInt("musicVol", 85)
        set(v) = p.edit().putInt("musicVol", v).apply()

    var ducking: Boolean
        get() = p.getBoolean("duck", true)
        set(v) = p.edit().putBoolean("duck", v).apply()

    /**
     * true = call mode: headset mic always open over SCO (music at call quality).
     * false = music-quality mode: A2DP stereo, the mic opens on tap (default).
     */
    var headsetMic: Boolean
        get() = p.getBoolean("sco", false)
        set(v) = p.edit().putBoolean("sco", v).apply()

    /** Noise reduction strength 0..100. */
    var noiseReduction: Int
        get() = p.getInt("nr", 60)
        set(v) = p.edit().putInt("nr", v).apply()

    /** Short tones in the headset when a rider joins or leaves. */
    var cues: Boolean
        get() = p.getBoolean("cues", true)
        set(v) = p.edit().putBoolean("cues", v).apply()

    /** Optional group code; empty = open group, otherwise packets are encrypted with it. */
    var groupCode: String
        get() = p.getString("group", "").orEmpty()
        set(v) = p.edit().putString("group", v.trim().take(32)).apply()

    /** Hold the mic down while others talk, to stop speaker/mic feedback loops. */
    var antiFeedback: Boolean
        get() = p.getBoolean("antiFeedback", true)
        set(v) = p.edit().putBoolean("antiFeedback", v).apply()

    /** Keep the screen on while the driving screen is open. */
    var driveKeepScreenOn: Boolean
        get() = p.getBoolean("driveScreenOn", false)
        set(v) = p.edit().putBoolean("driveScreenOn", v).apply()

    /** Noise suppression on the voices you hear (cleans the other rider's wind). */
    var rxDenoise: Boolean
        get() = p.getBoolean("rxDenoise", true)
        set(v) = p.edit().putBoolean("rxDenoise", v).apply()

    /** Turn incoming voices down while you talk, so your own voice can't echo back. */
    var echoGuard: Boolean
        get() = p.getBoolean("echoGuard", true)
        set(v) = p.edit().putBoolean("echoGuard", v).apply()

    /** Standby while alone, auto-off after 30 minutes alone. */
    var batterySaver: Boolean
        get() = p.getBoolean("batterySaver", true)
        set(v) = p.edit().putBoolean("batterySaver", v).apply()

    /** Extra music delay on this phone (0..400 ms) to line up two different headsets. */
    var syncDelayMs: Int
        get() = p.getInt("syncDelay", 0)
        set(v) = p.edit().putInt("syncDelay", v).apply()

    var playlist: List<Uri>
        get() = p.getString("playlist", "").orEmpty().split('\n').filter { it.isNotEmpty() }.map(Uri::parse)
        set(v) = p.edit().putString("playlist", v.joinToString("\n")).apply()

    var trackIndex: Int
        get() = p.getInt("trackIndex", 0)
        set(v) = p.edit().putInt("trackIndex", v).apply()

    var shuffle: Boolean
        get() = p.getBoolean("shuffle", false)
        set(v) = p.edit().putBoolean("shuffle", v).apply()

    // ------------------------------------------------------------- hands-free controls

    fun gesture(t: Controls.Trigger): Controls.Action =
        p.getString("g_${t.name}", null)
            ?.let { n -> Controls.Action.values().firstOrNull { it.name == n } }
            ?: t.default

    fun setGesture(t: Controls.Trigger, a: Controls.Action) =
        p.edit().putString("g_${t.name}", a.name).apply()

    fun applyPreset(preset: Controls.Preset) {
        val e = p.edit()
        for ((t, a) in preset.map) e.putString("g_${t.name}", a.name)
        e.apply()
    }

    /** The preset the current mapping matches, or null when customised. */
    val gesturePreset: Controls.Preset?
        get() = Controls.Preset.values().firstOrNull { pr -> pr.map.all { (t, a) -> gesture(t) == a } }

    /** True when any phone volume-key combination does something. */
    val volumeGestures: Boolean
        get() = Controls.Trigger.values().any { it.isVolume && gesture(it) != Controls.Action.NONE }

    // ------------------------------------------------------------- trip setup

    /**
     * What this phone does. With two phones (one earbud from each headset):
     * MUSIC plays only music in full stereo quality; TALK carries the
     * intercom (headset mic + the other riders' voices) and never music.
     */
    enum class Role { NORMAL, TALK, MUSIC }

    /** Two-phone setup chosen on the trip setup card (older "preset 3" means the same). */
    var twoPhones: Boolean
        get() = p.getBoolean("twoPhones", p.getInt("preset", 1) == 3)
        set(v) = p.edit().putBoolean("twoPhones", v).apply()

    /** Two-phone setup: true on the talk phone, false on the music phone. */
    var isTalkPhone: Boolean
        get() = p.getBoolean("twoPhoneMic", false)
        set(v) = p.edit().putBoolean("twoPhoneMic", v).apply()

    val role: Role
        get() = when {
            !twoPhones -> Role.NORMAL
            isTalkPhone -> Role.TALK
            else -> Role.MUSIC
        }

    /** One earbud in the ear: fold stereo into both channels so nothing is lost. */
    val monoOutput: Boolean
        get() = role == Role.MUSIC

    companion object {
        /** Bump when a stored preference changes meaning; older stores are reset on update. */
        const val SCHEMA = 3
    }
}
