package vpnrunner.core

/**
 * Режим «из-за рубежа» (см. AbroadSettings):
 *  1. sing-box-«цепочка» — только локальный прокси 127.0.0.1:${SingBoxConfig.CHAIN_PORT} → российский сервер;
 *  2. openconnect подключается к Cisco только через этот прокси (--proxy, без DTLS) — напрямую он не может
 *     в принципе, так что сервер Cisco никогда не увидит зарубежный адрес, даже если российский VPN упал;
 *  3. основной sing-box (TUN) — как обычно, только вместо зарубежного VPN российский.
 */
object Abroad {

    private val RU_NAME = Regex("🇷🇺|\\bRU\\b|\\bMSK\\b|(?iu)russia|росси|москв|moscow|спб|петербург|petersburg")

    /** Похоже на российский сервер по названию. */
    fun looksRussian(server: Server) = RU_NAME.containsMatchIn(server.name)

    /** Российские серверы для запуска: выбранный вручную или все «российские» по названию. */
    fun ruServers(settings: AppSettings, servers: List<Server>): List<Server> {
        val id = settings.abroad.ruServer
        if (id != AppSettings.AUTO) servers.firstOrNull { it.id == id }?.let { return listOf(it) }
        return servers.filter { looksRussian(it) }
    }

    /** Что нужно основному sing-box в этом режиме. */
    data class Plan(
        /** Адреса российских серверов: мимо TUN (по ним идёт цепочка Cisco) и в разрешённые kill switch. */
        val ruIps: List<String>,
        /** Имя сервера → адреса: sing-box не спрашивает DNS про свои же серверы (DNS ходит через них). */
        val pinned: Map<String, List<String>>,
        val allViaRu: Boolean,
    )

    fun isIpv4(s: String) = s.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
}
