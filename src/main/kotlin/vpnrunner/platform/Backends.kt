package vpnrunner.platform

import vpnrunner.core.Abroad
import vpnrunner.core.Fs
import vpnrunner.core.SingBoxConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Что нужно для запуска openconnect. */
data class CiscoLaunch(
    val openconnect: String,
    val args: List<String>,          // без пути к бинарнику и без --script
    val wrapperScript: File,
    /** Строки, которые уйдут в stdin: пароль и код. Вызывается в последний момент — код TOTP свежий. */
    val stdinProvider: () -> String,
)

/**
 * Режим «из-за рубежа»: sing-box-«цепочка» (прокси к российскому серверу, см. SingBoxConfig.buildChain)
 * запускается до openconnect, а openconnect — только после beforeCisco (проверка, что выход — в России).
 */
data class ChainLaunch(
    /** null — без kill switch. */
    val killSwitch: KillSwitch?,
)

/**
 * Kill switch (macOS, pf): наружу можно только через туннели (sing-box, Cisco) и к российским серверам.
 * Упал российский VPN — интернета нет. Обычный DNS (порт 53) мимо туннелей тоже закрыт.
 */
data class KillSwitch(
    val ruIps: List<String>,
    /** Пропускать соединения процессов root (это sing-box): нужно, если часть трафика идёт напрямую. */
    val allowRoot: Boolean,
)

interface VpnBackend {
    val runDir: File
    val ciscoStateFile: File get() = File(runDir, "cisco.state")
    val ciscoLog: File get() = File(runDir, "openconnect.log")
    val singboxLog: File get() = File(runDir, "sing-box.log")
    val singboxConfig: File get() = File(runDir, "config.json")
    val chainConfig: File get() = File(runDir, "chain.json")
    val chainLog: File get() = File(runDir, "chain.log")

    /** Готовит папку запуска. */
    fun prepare()
    /**
     * Запускает openconnect (если cisco != null) и ждёт команды на sing-box.
     * chain != null: сначала цепочку (и kill switch), затем beforeCisco, и только потом openconnect.
     */
    fun start(cisco: CiscoLaunch?, singbox: String, chain: ChainLaunch? = null, beforeCisco: () -> Unit = {})
    /** Конфиг sing-box записан — запускать. */
    fun startSingBox()
    /** Перечитать конфиг sing-box (после изменения правил), не трогая Cisco. */
    fun reloadSingBox()
    fun ciscoAlive(): Boolean
    fun singboxAlive(): Boolean
    fun chainAlive(): Boolean
    /** Останавливает всё: сначала sing-box, потом Cisco, потом цепочку и kill switch. */
    fun stop()
    /** Что-то осталось запущенным с прошлого раза. */
    fun leftovers(): Boolean = ciscoAlive() || singboxAlive() || chainAlive()
    /** Kill switch остался включён, хотя ничего не запущено (сбой) — интернета нет. */
    fun killSwitchStuck(): Boolean = false
    /** Снять оставшийся kill switch. */
    fun releaseKillSwitch() {}
    fun wrapperName(): String
    fun wrapperContent(orig: String, subnets: List<CiscoWrapper.Cidr>, domains: List<String>): String
}

// =============================================================================================
// macOS: openconnect и sing-box нужны права root. Программа один раз за запуск спрашивает
// пароль администратора (стандартный диалог macOS) и запускает скрипт-супервизор от root.
// Дальше общение через файлы в папке запуска: singbox.ready → запустить sing-box, stop → остановить.
// =============================================================================================
class MacBackend(override val runDir: File) : VpnBackend {
    private val fifo get() = File(runDir, "openconnect.stdin")
    private val runner get() = File(runDir, "supervisor.sh")

    override fun wrapperName() = "cisco-wrapper.sh"
    override fun wrapperContent(orig: String, subnets: List<CiscoWrapper.Cidr>, domains: List<String>) =
        CiscoWrapper.shell(orig, ciscoStateFile.path, subnets, domains)

    override fun prepare() {
        runDir.mkdirs()
        Fs.restrictToOwner(runDir)
        listOf("cisco.state", "singbox.ready", "stop", "reload", "stopped", "openconnect.pid", "sing-box.pid", "openconnect.stdin", "route-fix.log",
            "chain.pid", "chain.ok", "killswitch.tables", "killswitch.rules", "killswitch.conf")
            .forEach { File(runDir, it).delete() }
    }

    override fun start(cisco: CiscoLaunch?, singbox: String, chain: ChainLaunch?, beforeCisco: () -> Unit) {
        chain?.killSwitch?.let { writeKillSwitch(it) }
        runner.writeText(supervisorScript(cisco, singbox, chain))
        runner.setExecutable(true, true)

        if (cisco != null) {
            val (code, out) = exec("/usr/bin/mkfifo", "-m", "600", fifo.path)
            check(code == 0) { "mkfifo: $out" }
        }

        val shellCmd = "/bin/sh ${Platform.shQuote(runner.path)} >/dev/null 2>&1 &"
        val apple = "do shell script \"${appleEscape(shellCmd)}\" with prompt \"VpnRunner подключает VPN\" with administrator privileges"
        val (code, out) = exec("/usr/bin/osascript", "-e", apple, timeoutSec = 300)
        if (code != 0) {
            fifo.delete()
            throw IllegalStateException(if (out.contains("-128")) "Отменено: не введён пароль администратора" else "Не удалось получить права администратора: $out")
        }

        if (chain != null) {
            try { beforeCisco() } catch (e: Exception) { stop(); throw e }
            File(runDir, "chain.ok").writeText("1")
        }

        if (cisco != null) {
            // Открытие FIFO на запись ждёт, пока openconnect откроет его на чтение.
            val payload = cisco.stdinProvider()
            val t = Thread {
                runCatching { FileOutputStream(fifo).use { it.write(payload.toByteArray()) } }
            }
            t.isDaemon = true
            t.start()
            t.join(20_000)
            if (t.isAlive) {
                t.interrupt()
                throw IllegalStateException("openconnect не запустился — см. журнал Cisco")
            }
        }
    }

    /** Скрипт-супервизор (выполняется от root). */
    internal fun supervisorScript(cisco: CiscoLaunch?, singbox: String, chain: ChainLaunch?): String {
        val q = Platform::shQuote
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# Сгенерировано VpnRunner. Выполняется от root.")
            appendLine("umask 022")
            appendLine("RUN=${q(runDir.path)}")
            appendLine("cd \"\$RUN\" || exit 1")
            appendLine("OC_PID=\"\"; SB_PID=\"\"; CH_PID=\"\"")
            appendKillSwitch(this)
            appendDnsRepair(this)
            CiscoWrapper.appendTakeRoutes(this)
            CiscoWrapper.appendDropDeadRoutes(this)
            CiscoWrapper.appendResolverFunctions(this)
            appendPublishDns(this)
            appendLine("start_chain() {")
            appendLine("  ${q(singbox)} run -c chain.json -D \"\$RUN\" >> chain.log 2>&1 &")
            appendLine("  CH_PID=\$!; echo \$CH_PID > chain.pid")
            appendLine("}")
            // Цепочка упала — openconnect без неё никуда не подключится (только через прокси), поднимаем заново.
            appendLine("chain_watch() {")
            appendLine("  [ -n \"\$CH_PID\" ] || return 0")
            appendLine("  kill -0 \"\$CH_PID\" 2>/dev/null && return 0")
            appendLine("  echo \"\$(date '+%F %T') российский VPN (цепочка) остановился — перезапускаю\" >> chain.log")
            appendLine("  sleep 1; start_chain")
            appendLine("}")
            appendLine("start_singbox() {")
            appendLine("  ${q(singbox)} run -c config.json -D \"\$RUN\" >> sing-box.log 2>&1 &")
            appendLine("  SB_PID=\$!; echo \$SB_PID > sing-box.pid")
            appendLine("}")
            appendLine("cleanup() {")
            appendLine("  [ -n \"\$SB_PID\" ] && kill -TERM \"\$SB_PID\" 2>/dev/null")
            appendLine("  for i in 1 2 3 4 5 6 7 8 9 10; do [ -n \"\$SB_PID\" ] && kill -0 \"\$SB_PID\" 2>/dev/null || break; sleep 0.5; done")
            appendLine("  [ -n \"\$SB_PID\" ] && kill -KILL \"\$SB_PID\" 2>/dev/null")
            appendLine("  [ -n \"\$OC_PID\" ] && kill -TERM \"\$OC_PID\" 2>/dev/null")
            appendLine("  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do [ -n \"\$OC_PID\" ] && kill -0 \"\$OC_PID\" 2>/dev/null || break; sleep 0.5; done")
            appendLine("  [ -n \"\$OC_PID\" ] && kill -KILL \"\$OC_PID\" 2>/dev/null")
            // Цепочку — после openconnect: через неё он прощается с сервером Cisco. Kill switch — последним.
            appendLine("  [ -n \"\$CH_PID\" ] && kill -TERM \"\$CH_PID\" 2>/dev/null")
            appendLine("  for i in 1 2 3 4 5 6; do [ -n \"\$CH_PID\" ] && kill -0 \"\$CH_PID\" 2>/dev/null || break; sleep 0.5; done")
            appendLine("  [ -n \"\$CH_PID\" ] && kill -KILL \"\$CH_PID\" 2>/dev/null")
            appendLine("  CH_PID=\"\"")
            appendLine("  ks_release")
            appendLine("  repair_dns")
            appendLine("  drop_dead_routes \"\$RUN\"")
            appendLine("  rm -f openconnect.pid sing-box.pid chain.pid chain.ok singbox.ready stop \"\$RUN/openconnect.stdin\"")
            appendLine("  touch stopped")
            appendLine("  exit 0")
            appendLine("}")
            appendLine("trap cleanup TERM INT HUP")
            appendLine("ks_release") // остался от сбоя
            appendLine("repair_dns")
            // Маршруты к серверам Cisco через шлюз прежней сети (остались от прошлых сеансов) — до запуска openconnect
            appendLine("drop_dead_routes \"\$RUN\"")
            if (chain != null) {
                if (chain.killSwitch != null) appendLine("ks_arm")
                appendLine(": > chain.log")
                appendLine("start_chain")
                appendLine("# ждём проверки, что выход — в России (её делает программа)")
                appendLine("while [ ! -f chain.ok ]; do")
                appendLine("  [ -f stop ] && cleanup")
                appendLine("  kill -0 \"\$CH_PID\" 2>/dev/null || cleanup")
                appendLine("  sleep 0.3")
                appendLine("done")
            }
            if (cisco != null) {
                // openconnect передаёт --script в /bin/sh -c, поэтому путь с пробелами нужно взять в кавычки
                val args = (listOf(cisco.openconnect, "--script=" + q(cisco.wrapperScript.path)) + cisco.args).joinToString(" ") { q(it) }
                appendLine("$args < \"\$RUN/openconnect.stdin\" > openconnect.log 2>&1 &")
                appendLine("OC_PID=\$!; echo \$OC_PID > openconnect.pid")
            }
            appendLine("# ждём команды запустить sing-box")
            appendLine("while [ ! -f singbox.ready ]; do")
            appendLine("  [ -f stop ] && cleanup")
            if (cisco != null) appendLine("  kill -0 \"\$OC_PID\" 2>/dev/null || cleanup")
            appendLine("  chain_watch; ks_check")
            appendLine("  sleep 0.3")
            appendLine("done")
            appendLine(": > sing-box.log")
            appendLine("start_singbox")
            appendLine("PUB_DEV=\"\"")
            // Раз в ~10 с: sing-box жив (его может убить система, например при сне), рабочие подсети
            // по-прежнему идут через Cisco (другой VPN мог вернуть свои маршруты).
            appendLine("N=0")
            appendLine("while [ ! -f stop ]; do")
            appendLine("  if [ -f reload ]; then rm -f reload; kill -HUP \"\$SB_PID\" 2>/dev/null; fi")
            appendLine("  [ -z \"\$PUB_DEV\" ] && publish_dns")
            appendLine("  chain_watch; ks_check")
            appendLine("  N=\$((N + 1))")
            appendLine("  if [ \$((N % 20)) -eq 0 ]; then")
            appendLine("    if ! kill -0 \"\$SB_PID\" 2>/dev/null; then")
            appendLine("      echo \"\$(date '+%F %T') sing-box неожиданно остановился — перезапускаю\" >> sing-box.log")
            appendLine("      start_singbox")
            appendLine("    fi")
            appendLine("    publish_dns")
            if (cisco != null) {
                appendLine("    [ -f cisco.state ] && take_routes \"\$(sed -n 's/^TUNDEV=//p' cisco.state)\" \"\$(sed -n 's/^ROUTES=//p' cisco.state)\" \"\$RUN\"")
            }
            appendLine("  fi")
            appendLine("  sleep 0.5")
            appendLine("done")
            appendLine("cleanup")
        }
    }

    /**
     * Функция repair_dns для супервизора. Если Cisco отключился нештатно (openconnect убит, программу закрыли
     * принудительно), vpnc-script не успевает вернуть DNS: в системе остаются записи от несуществующего
     * utun, а в настройках Wi-Fi — DNS Cisco, который без Cisco недоступен. Тогда не работает ни один сайт,
     * и даже сам Cisco не находит свой сервер. Вызывается перед подключением и после остановки.
     */
    private fun appendDnsRepair(sb: StringBuilder) = with(sb) {
        appendLine("repair_dns() {")
        // 1. Записи vpnc-script для utun, у которого уже нет этого адреса (туннель закрыт).
        appendLine("  for dev in \$(echo 'list State:/Network/Service/utun[0-9]+/(IPv4|DNS)' | scutil | sed -n 's#.*State:/Network/Service/\\(utun[0-9]*\\)/.*#\\1#p' | sort -u); do")
        appendLine("    addr=\$(echo \"show State:/Network/Service/\$dev/IPv4\" | scutil | awk '/Addresses/ { getline; print \$3; exit }')")
        appendLine("    if [ -z \"\$addr\" ] || ! ifconfig \"\$dev\" 2>/dev/null | grep -q \"inet \$addr \"; then")
        appendLine("      printf 'open\\nremove State:/Network/Service/%s/IPv4\\nremove State:/Network/Service/%s/DNS\\nclose\\n' \"\$dev\" \"\$dev\" | scutil")
        appendLine("      echo \"\$(date '+%F %T') убраны записи DNS закрытого туннеля \${dev}\" >> dns-repair.log")
        appendLine("    fi")
        appendLine("  done")
        // Наши /etc/resolver и служба DNS sing-box: после остановки или если остались после сбоя.
        appendLine("  remove_resolvers")
        appendLine("  unpublish_dns")
        // 2. DNS в настройках сети, который поставил vpnc-script прежних версий программы: адреса Cisco или sing-box и ничего другого.
        appendLine("  KNOWN=\" ${SingBoxConfig.TUN_DNS} \$(cat cisco-dns.known 2>/dev/null) \"")
        // Все службы, а не только та, что держит маршрут по умолчанию: его может держать как раз застрявший utun.
        appendLine("  networksetup -listallnetworkservices | sed '1d; s/^\\*//' | while IFS= read -r SVC; do")
        appendLine("    CUR=\$(networksetup -getdnsservers \"\$SVC\" 2>/dev/null | grep -E '^[0-9.]+\$')")
        appendLine("    [ -n \"\$CUR\" ] || continue")
        appendLine("    OURS=1")
        appendLine("    for d in \$CUR; do case \"\$KNOWN\" in *\" \$d \"*) ;; *) OURS=0 ;; esac; done")
        appendLine("    [ \"\$OURS\" = 1 ] || continue")
        appendLine("    networksetup -setdnsservers \"\$SVC\" Empty")
        appendLine("    echo \"\$(date '+%F %T') в «\${SVC}» сброшен DNS от Cisco: \$(echo \$CUR)\" >> dns-repair.log")
        appendLine("  done")
        appendLine("}")
    }

    /**
     * publish_dns: основной DNS системы — sing-box (${SingBoxConfig.TUN_DNS}) на его собственном интерфейсе.
     * macOS привязывает DNS службы к её интерфейсу: впиши его в чужую службу (Cisco, Happ) — запросы уйдут
     * в чужой туннель. Повторяется, если sing-box перезапустился на другом utun. Если основной службой
     * останется другой VPN (Happ), обычные имена будет резолвить он — рабочие домены всё равно идут
     * в DNS Cisco через /etc/resolver.
     */
    private fun appendPublishDns(sb: StringBuilder) = with(sb) {
        val key = "State:/Network/Service/VpnRunner"
        appendLine("publish_dns() {")
        appendLine("  dev=\$(ifconfig | awk '/^utun[0-9]+:/ { d = \$1 } /inet ${SingBoxConfig.TUN_ADDRESS.replace(".", "\\.")} / { sub(\":\", \"\", d); print d; exit }')")
        appendLine("  [ -n \"\$dev\" ] && [ \"\$dev\" != \"\$PUB_DEV\" ] || return 0")
        appendLine("  search=\$(sed -n 's/^DOMAINS=//p' cisco.state 2>/dev/null | awk '{ print \$1 }')")
        appendLine("  {")
        appendLine("    echo open; echo d.init; echo 'd.add ServerAddresses * ${SingBoxConfig.TUN_DNS}'")
        appendLine("    [ -n \"\$search\" ] && echo \"d.add SearchDomains * \$search\"")
        appendLine("    echo 'set $key/DNS'")
        appendLine("    echo d.init; echo 'd.add Addresses * ${SingBoxConfig.TUN_ADDRESS}'; echo 'd.add SubnetMasks * 255.255.255.255'")
        appendLine("    echo \"d.add InterfaceName \$dev\"; echo 'd.add OverridePrimary # 1'")
        appendLine("    echo 'set $key/IPv4'; echo close")
        appendLine("  } | scutil")
        appendLine("  PUB_DEV=\"\$dev\"")
        appendLine("}")
        appendLine("unpublish_dns() {")
        appendLine("  printf 'open\\nremove $key/DNS\\nremove $key/IPv4\\nclose\\n' | scutil")
        appendLine("  PUB_DEV=\"\"")
        appendLine("}")
    }

    override fun startSingBox() { File(runDir, "singbox.ready").writeText("1") }

    override fun reloadSingBox() { File(runDir, "reload").writeText("1") }

    private fun pidAlive(name: String): Boolean {
        val pid = File(runDir, name).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    override fun ciscoAlive() = pidAlive("openconnect.pid")
    override fun singboxAlive() = pidAlive("sing-box.pid")
    override fun chainAlive() = pidAlive("chain.pid")

    // ---- kill switch (pf) ----

    /**
     * Правила pf — в своём якоре внутри «com.apple» (он подключён в штатном /etc/pf.conf), сам /etc/pf.conf не меняем.
     * Таблицы и неизменная часть пишутся программой; проходы через туннели супервизор дописывает сам,
     * когда узнаёт их интерфейсы (sing-box, Cisco).
     */
    private fun writeKillSwitch(k: KillSwitch) {
        val ips = k.ruIps.filter { Abroad.isIpv4(it) }
        File(runDir, "killswitch.tables").writeText(buildString {
            appendLine("table <vr_ru> const { ${ips.joinToString(" ")} }")
            appendLine("table <vr_lan> const { 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 224.0.0.0/4 255.255.255.255 fe80::/10 ff00::/8 }")
        })
        File(runDir, "killswitch.rules").writeText(buildString {
            appendLine("pass out quick inet proto { tcp udp } to <vr_ru>")
            // Обычный DNS — только через туннели (выше): иначе имена сайтов увидит местный провайдер.
            appendLine("block drop out quick proto { tcp udp } to any port 53")
            appendLine("pass out quick to <vr_lan>")
            appendLine("pass out quick inet proto udp from any port 68 to any port 67")
            if (k.allowRoot) appendLine("pass out quick proto { tcp udp } user root")
            // Всё остальное, включая IPv6 (TUN sing-box только IPv4 — через IPv6 ушло бы мимо VPN).
            appendLine("block drop out quick all")
        })
    }

    private fun appendKillSwitch(sb: StringBuilder) = with(sb) {
        val anchor = KS_ANCHOR
        appendLine("KS_KEY=\"\"")
        appendLine("ks_apply() {")
        appendLine("  {")
        appendLine("    cat killswitch.tables")
        appendLine("    echo 'pass out quick on lo0 all'")
        appendLine("    for d in \"\$PUB_DEV\" \"\$1\"; do case \"\$d\" in utun[0-9]|utun[0-9][0-9]|utun[0-9][0-9][0-9]) echo \"pass out quick on \$d all\" ;; esac; done")
        appendLine("    cat killswitch.rules")
        appendLine("  } > killswitch.conf")
        appendLine("  pfctl -a $anchor -f killswitch.conf >> killswitch.log 2>&1")
        appendLine("}")
        appendLine("ks_arm() {")
        // В основных правилах должен быть якорь «com.apple» — без него наш якорь не проверяется
        appendLine("  pfctl -s rules 2>/dev/null | grep -q 'com.apple/\\*' || pfctl -f /etc/pf.conf >> killswitch.log 2>&1")
        appendLine("  pfctl -E 2>&1 | sed -n 's/^Token : *//p' > killswitch.token")
        appendLine("  touch killswitch.on")
        appendLine("  KS_KEY=\"|\"; ks_apply \"\"")
        appendLine("  echo \"\$(date '+%F %T') kill switch включён\" >> killswitch.log")
        appendLine("}")
        // Интерфейсы туннелей поменялись (поднялся sing-box или Cisco) — обновить проходы
        appendLine("ks_check() {")
        appendLine("  [ -f killswitch.on ] && [ -f killswitch.rules ] || return 0")
        appendLine("  cdev=\$(sed -n 's/^TUNDEV=//p' cisco.state 2>/dev/null)")
        appendLine("  [ \"\$PUB_DEV|\$cdev\" = \"\$KS_KEY\" ] && return 0")
        appendLine("  KS_KEY=\"\$PUB_DEV|\$cdev\"; ks_apply \"\$cdev\"")
        appendLine("}")
        appendLine("ks_release() {")
        appendLine("  [ -f killswitch.on ] || return 0")
        appendLine("  pfctl -a $anchor -F all >> killswitch.log 2>&1")
        appendLine("  t=\$(cat killswitch.token 2>/dev/null); [ -n \"\$t\" ] && pfctl -X \"\$t\" >> killswitch.log 2>&1")
        appendLine("  rm -f killswitch.on killswitch.token")
        appendLine("  echo \"\$(date '+%F %T') kill switch выключен\" >> killswitch.log")
        appendLine("}")
    }

    override fun killSwitchStuck() = File(runDir, "killswitch.on").exists() && !leftovers()

    override fun releaseKillSwitch() {
        val q = Platform::shQuote
        val cmd = "cd ${q(runDir.path)} && pfctl -a $KS_ANCHOR -F all; t=\$(cat killswitch.token 2>/dev/null); " +
            "[ -n \"\$t\" ] && pfctl -X \"\$t\"; rm -f killswitch.on killswitch.token; true"
        val apple = "do shell script \"${appleEscape(cmd)}\" with prompt \"VpnRunner снимает блокировку интернета\" with administrator privileges"
        val (code, out) = exec("/usr/bin/osascript", "-e", apple, timeoutSec = 120)
        check(code == 0) { if (out.contains("-128")) "Отменено: не введён пароль администратора" else "Не удалось снять блокировку: $out" }
    }

    override fun stop() {
        if (!File(runDir, "supervisor.sh").exists()) return
        File(runDir, "stop").writeText("1")
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (File(runDir, "stopped").exists() || (!ciscoAlive() && !singboxAlive() && !File(runDir, "openconnect.pid").exists())) break
            Thread.sleep(300)
        }
    }

    private fun appleEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val KS_ANCHOR = "com.apple/250.VpnRunner"
    }
}

// =============================================================================================
// Windows: программа сама запущена от администратора, процессы — дочерние.
// =============================================================================================
class WindowsBackend(override val runDir: File) : VpnBackend {
    private var oc: Process? = null
    private var sb: Process? = null
    private var chain: Process? = null
    private var singboxPath: String = ""

    override fun wrapperName() = "cisco-wrapper.js"
    override fun wrapperContent(orig: String, subnets: List<CiscoWrapper.Cidr>, domains: List<String>) =
        CiscoWrapper.jscript(orig, ciscoStateFile.path, subnets, domains, SingBoxConfig.TUN_DNS)

    override fun prepare() {
        check(Platform.isWindowsAdmin()) { "Программа должна быть запущена от имени администратора" }
        runDir.mkdirs()
        ciscoStateFile.delete()
    }

    override fun start(cisco: CiscoLaunch?, singbox: String, chain: ChainLaunch?, beforeCisco: () -> Unit) {
        singboxPath = singbox
        if (chain != null) {
            this.chain = ProcessBuilder(singbox, "run", "-c", chainConfig.path, "-D", runDir.path)
                .directory(runDir).redirectErrorStream(true).redirectOutput(chainLog).start()
            try { beforeCisco() } catch (e: Exception) { stop(); throw e }
        }
        if (cisco == null) return
        val cmd = listOf(cisco.openconnect, "--script=cscript //nologo ${cisco.wrapperScript.path}") + cisco.args
        val p = ProcessBuilder(cmd)
            .directory(File(cisco.openconnect).parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ciscoLog)
            .start()
        oc = p
        p.outputStream.use { it.write(cisco.stdinProvider().toByteArray()) }
    }

    override fun startSingBox() {
        val p = ProcessBuilder(singboxPath, "run", "-c", singboxConfig.path, "-D", runDir.path)
            .directory(runDir)
            .redirectErrorStream(true)
            .redirectOutput(singboxLog)
            .start()
        sb = p
    }

    override fun reloadSingBox() {
        sb?.let { p -> p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() }
        startSingBox()
    }

    override fun ciscoAlive() = oc?.isAlive == true
    override fun singboxAlive() = sb?.isAlive == true
    override fun chainAlive() = chain?.isAlive == true

    override fun stop() {
        sb?.let { p -> p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() }
        sb = null
        oc?.let { p -> p.destroy(); if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly() }
        oc = null
        chain?.let { p -> p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() }
        chain = null
        ciscoStateFile.delete()
    }
}
