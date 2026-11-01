package com.mamaddoom.ridetalk.net

/**
 * Free public relay servers (MQTT brokers) for online mode, best first.
 * No account, TLS with a normal certificate; they only ever see packets that
 * are already end-to-end encrypted with the group code.
 *
 * [score] (0..100) comes from probing them from GitHub's servers (connect
 * time, round-trip time, loss at intercom packet rates); see RelayProbeTest.
 * Every phone uses the same order, so "automatic" lands the whole group on
 * the same server.
 */
object RelayServers {
    class Server(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val score: Int,
        val user: String? = null,
        val password: String? = null,
        /** False for servers that only offer plain TCP; the audio is end-to-end encrypted anyway. */
        val tls: Boolean = true,
        /** Needs the user's own login (typed on the phone, stored only there). */
        val needsLogin: Boolean = false,
        /** Shown with the Iranian flag. */
        val iran: Boolean = false,
    ) {
        fun withLogin(port: Int, user: String, password: String, tls: Boolean) =
            Server(id, name, host, port, score, user.ifEmpty { null }, password.ifEmpty { null }, tls, needsLogin, iran)
    }

    // Probed from GitHub's servers, three runs on 2026-10-30 and 11-01
    // (150 packets of 300 bytes at 50/s and 25/s; connect, round trip, loss):
    //   shiftr.io ~0.3-0.7 s, ~92-130 ms, 0% loss, always connected      -> 85
    //   TBMQ      ~0.4-0.5 s, ~123 ms,    0% loss                         -> 82
    //   HiveMQ    ~0.4-1.3 s, ~90-120 ms, 0% loss, 2 of 6 connects timed out -> 80
    // Left out: EMQX (60-80% loss: rate-limited at voice packet rates), Eclipse and
    // MQTT.Cool (unreachable), Mosquitto (refused the TLS session / self-signed CA),
    // flespi (needs an account), HiveMQ Dashboard alias (certificate name mismatch).
    val ALL: List<Server> = listOf(
        Server("shiftr", "shiftr.io", "public.cloud.shiftr.io", 8883, score = 85, user = "public", password = "public"),
        Server("tbmq", "TBMQ", "demo.tbmq.io", 8883, score = 82, user = "demo"),
        Server("hivemq", "HiveMQ", "broker.hivemq.com", 8883, score = 80),
    ).sortedByDescending { it.score }

    /**
     * Iranian server: the project's free Mosquitto on Runflare, which needs its
     * login typed on the phone. Not scored: it can't be measured from outside
     * Iran. Tried after the public ones in automatic mode, once the login is set.
     */
    val IRAN: List<Server> = listOf(
        Server("runflare", "Runflare", "mqtt-slw-mqtt.runflare.cloud", 1883, score = 0, user = "mqtt", tls = false, needsLogin = true, iran = true),
    )

    /** Id of the user's own server, set up on the phone. */
    const val CUSTOM_ID = "custom"

    /** Also probed in CI, to replace a weak entry above if one of these does better. */
    val CANDIDATES: List<Server> = ALL + listOf(
        Server("emqx", "EMQX", "broker.emqx.io", 8883, score = 0),
        Server("eclipse", "Eclipse", "mqtt.eclipseprojects.io", 8883, score = 0),
        Server("mosquitto", "Mosquitto", "test.mosquitto.org", 8886, score = 0),
        Server("mqttcool", "MQTT.Cool", "broker.mqtt.cool", 8883, score = 0),
        Server("hivemq-dash", "HiveMQ Dashboard", "broker.mqttdashboard.com", 8883, score = 0),
        Server("mosquitto-8883", "Mosquitto (8883)", "test.mosquitto.org", 8883, score = 0),
        Server("emqx-cn", "EMQX (alt)", "broker-cn.emqx.io", 8883, score = 0),
        Server("flespi", "flespi", "mqtt.flespi.io", 8883, score = 0),
    )

    fun byId(id: String) = (ALL + IRAN).firstOrNull { it.id == id }
}
