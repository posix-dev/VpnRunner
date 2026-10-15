package vpnrunner.platform

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

interface VpnBackend {
    val runDir: File
    val ciscoStateFile: File get() = File(runDir, "cisco.state")
    val ciscoLog: File get() = File(runDir, "openconnect.log")
    val singboxLog: File get() = File(runDir, "sing-box.log")
    val singboxConfig: File get() = File(runDir, "config.json")

    /** Готовит папку запуска. */
    fun prepare()
    /** Запускает openconnect (если cisco != null) и ждёт команды на sing-box. */
    fun start(cisco: CiscoLaunch?, singbox: String)
    /** Конфиг sing-box записан — запускать. */
    fun startSingBox()
    /** Перечитать конфиг sing-box (после изменения правил), не трогая Cisco. */
    fun reloadSingBox()
    fun ciscoAlive(): Boolean
    fun singboxAlive(): Boolean
    /** Останавливает всё: сначала sing-box, потом Cisco. */
    fun stop()
    /** Что-то осталось запущенным с прошлого раза. */
    fun leftovers(): Boolean = ciscoAlive() || singboxAlive()
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
        listOf("cisco.state", "singbox.ready", "stop", "reload", "stopped", "openconnect.pid", "sing-box.pid", "openconnect.stdin", "route-fix.log")
            .forEach { File(runDir, it).delete() }
    }

    override fun start(cisco: CiscoLaunch?, singbox: String) {
        val q = Platform::shQuote
        val script = buildString {
            appendLine("#!/bin/sh")
            appendLine("# Сгенерировано VpnRunner. Выполняется от root.")
            appendLine("umask 022")
            appendLine("RUN=${q(runDir.path)}")
            appendLine("cd \"\$RUN\" || exit 1")
            appendLine("OC_PID=\"\"; SB_PID=\"\"")
            appendDnsRepair(this)
            CiscoWrapper.appendTakeRoutes(this)
            CiscoWrapper.appendResolverFunctions(this)
            appendPublishDns(this)
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
            appendLine("  repair_dns")
            appendLine("  rm -f openconnect.pid sing-box.pid singbox.ready stop \"\$RUN/openconnect.stdin\"")
            appendLine("  touch stopped")
            appendLine("  exit 0")
            appendLine("}")
            appendLine("trap cleanup TERM INT HUP")
            appendLine("repair_dns")
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
        runner.writeText(script)
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
}

// =============================================================================================
// Windows: программа сама запущена от администратора, процессы — дочерние.
// =============================================================================================
class WindowsBackend(override val runDir: File) : VpnBackend {
    private var oc: Process? = null
    private var sb: Process? = null
    private var singboxPath: String = ""

    override fun wrapperName() = "cisco-wrapper.js"
    override fun wrapperContent(orig: String, subnets: List<CiscoWrapper.Cidr>, domains: List<String>) =
        CiscoWrapper.jscript(orig, ciscoStateFile.path, subnets, domains, SingBoxConfig.TUN_DNS)

    override fun prepare() {
        check(Platform.isWindowsAdmin()) { "Программа должна быть запущена от имени администратора" }
        runDir.mkdirs()
        ciscoStateFile.delete()
    }

    override fun start(cisco: CiscoLaunch?, singbox: String) {
        singboxPath = singbox
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

    override fun stop() {
        sb?.let { p -> p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() }
        sb = null
        oc?.let { p -> p.destroy(); if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly() }
        oc = null
        ciscoStateFile.delete()
    }
}
