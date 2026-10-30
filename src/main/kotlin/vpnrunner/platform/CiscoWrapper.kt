package vpnrunner.platform

/**
 * Обёртка над штатным vpnc-script из openconnect.
 *  1. Запоминает, какие маршруты прислал сервер Cisco.
 *  2. Если пользователь задал свои рабочие подсети — подменяет CISCO_SPLIT_INC на них,
 *     чтобы через Cisco шли только они (а не весь интернет).
 *  3. Не даёт vpnc-script трогать DNS системы: иначе все имена уходят в корпоративный DNS (он не отдаёт
 *     адреса многих сайтов и видит, куда вы ходите), а при другом VPN (Happ) DNS ещё и попадает в его туннель.
 *     Вместо этого рабочие домены прописываются в /etc/resolver/<домен> прямо на DNS Cisco — это работает
 *     при любом другом VPN, — а основной DNS системы ставит супервизор: DNS sing-box на его же интерфейсе.
 *  4. Вызывает штатный скрипт, который настраивает интерфейс и маршруты.
 *  5. Если рабочую подсеть уже занял маршрут другого VPN (Happ и т.п. отправляют 10.0.0.0/8 мимо себя
 *     через Wi-Fi), vpnc-script не может добавить свой — тогда маршрут переводится на Cisco,
 *     а после отключения возвращается как был.
 *  6. После подключения пишет файл состояния (интерфейс, DNS, маршруты, домены) — его читает программа.
 */
object CiscoWrapper {

    data class Cidr(val addr: String, val maskLen: Int) {
        val mask: String get() {
            val m = if (maskLen == 0) 0L else (0xFFFFFFFFL shl (32 - maskLen)) and 0xFFFFFFFFL
            return listOf(24, 16, 8, 0).joinToString(".") { ((m shr it) and 0xFF).toString() }
        }
    }

    fun parseCidrs(list: List<String>): List<Cidr> = list.mapNotNull { raw ->
        val s = raw.trim()
        if (s.isEmpty() || s.contains(':')) return@mapNotNull null // IPv6 пока не трогаем
        val addr = s.substringBefore('/')
        val len = s.substringAfter('/', "32").toIntOrNull() ?: return@mapNotNull null
        if (len !in 0..32 || addr.split('.').size != 4 || addr.split('.').any { it.toIntOrNull() !in 0..255 }) null
        else Cidr(addr, len)
    }

    /** POSIX sh — для macOS. */
    fun shell(origScript: String, stateFile: String, subnets: List<Cidr>, splitDomains: List<String>): String = buildString {
        val q = Platform::shQuote
        appendLine("#!/bin/sh")
        appendLine("# Сгенерировано VpnRunner")
        appendLine("ORIG=${q(origScript)}")
        appendLine("STATE=${q(stateFile)}")
        appendLine()
        appendLine("ORIG_ROUTES=\"\"")
        appendLine("ORIG_N=\${CISCO_SPLIT_INC:-0}")
        appendLine("i=0")
        appendLine("while [ \"\$i\" -lt \"\$ORIG_N\" ]; do")
        appendLine("  eval a=\\\$CISCO_SPLIT_INC_\${i}_ADDR")
        appendLine("  eval l=\\\$CISCO_SPLIT_INC_\${i}_MASKLEN")
        appendLine("  ORIG_ROUTES=\"\$ORIG_ROUTES \$a/\$l\"")
        appendLine("  [ \"\$l\" = \"0\" ] && HAS_DEFAULT=1")
        appendLine("  i=\$((i+1))")
        appendLine("done")
        appendLine("if [ \"\$ORIG_N\" -eq 0 ] || [ -n \"\$HAS_DEFAULT\" ]; then FULL=1; else FULL=0; fi")
        appendLine()
        if (subnets.isNotEmpty()) {
            appendLine("# Свои рабочие подсети вместо присланных сервером")
            appendLine("i=0")
            appendLine("while [ \"\$i\" -lt \"\$ORIG_N\" ]; do")
            appendLine("  unset CISCO_SPLIT_INC_\${i}_ADDR CISCO_SPLIT_INC_\${i}_MASK CISCO_SPLIT_INC_\${i}_MASKLEN CISCO_SPLIT_INC_\${i}_PROTOCOL CISCO_SPLIT_INC_\${i}_SPORT CISCO_SPLIT_INC_\${i}_DPORT")
            appendLine("  i=\$((i+1))")
            appendLine("done")
            appendLine("export CISCO_SPLIT_INC=${subnets.size}")
            subnets.forEachIndexed { i, c ->
                appendLine("export CISCO_SPLIT_INC_${i}_ADDR=${c.addr} CISCO_SPLIT_INC_${i}_MASK=${c.mask} CISCO_SPLIT_INC_${i}_MASKLEN=${c.maskLen} CISCO_SPLIT_INC_${i}_PROTOCOL=0 CISCO_SPLIT_INC_${i}_SPORT=0 CISCO_SPLIT_INC_${i}_DPORT=0")
            }
            appendLine("ROUTES=${q(subnets.joinToString(" ") { "${it.addr}/${it.maskLen}" })}")
        } else {
            appendLine("ROUTES=\"\$ORIG_ROUTES\"")
        }
        if (splitDomains.isNotEmpty()) {
            // Свои рабочие домены — в дополнение к присланным сервером
            appendLine("export CISCO_SPLIT_DNS=\"\${CISCO_SPLIT_DNS:+\$CISCO_SPLIT_DNS,}\"${q(splitDomains.joinToString(","))}")
        }
        // Через прокси (из-за рубежа) шлюз — 127.0.0.1: при отключении vpnc-script удалил бы маршрут к нему
        appendLine("case \"\$VPNGATEWAY\" in 127.*|::1) [ \"\$reason\" = disconnect ] && unset VPNGATEWAY ;; esac")
        appendLine("REAL_DNS=\"\$INTERNAL_IP4_DNS\"")
        appendLine("WORK_DOMAINS=\"\$CISCO_DEF_DOMAIN \$(echo \"\$CISCO_SPLIT_DNS\" | tr ',' ' ')\"")
        appendLine("unset INTERNAL_IP4_DNS")
        appendResolverFunctions(this)
        appendLine()
        appendLine("DIR=\$(dirname \"\$STATE\")")
        appendTakeRoutes(this)
        appendDropDeadRoutes(this)
        // Перед (пере)подключением: маршрут к серверу через шлюз прежней сети не даёт поставить новый
        appendLine("case \"\$reason\" in connect|attempt-reconnect) drop_dead_routes \"\$DIR\" ;; esac")
        appendLine("\"\$ORIG\" \"\$@\"")
        appendLine("RC=\$?")
        // При переподключении openconnect заново ставит маршруты, а другой VPN мог за это время вернуть свои.
        appendLine("if [ \"\$reason\" = \"reconnect\" ]; then")
        appendLine("  take_routes \"\$TUNDEV\" \"\$ROUTES\" \"\$DIR\"")
        appendLine("  write_resolvers \"\$WORK_DOMAINS\" \"\$REAL_DNS\"")
        appendLine("fi")
        appendLine("if [ \"\$reason\" = \"connect\" ]; then")
        appendLine("  rm -f \"\$DIR/routes-taken\"")
        appendLine("  take_routes \"\$TUNDEV\" \"\$ROUTES\" \"\$DIR\"")
        appendLine("  write_resolvers \"\$WORK_DOMAINS\" \"\$REAL_DNS\"")
        appendLine("  # Запомнить DNS Cisco: если отключение пройдёт нештатно, супервизор по этому списку уберёт его из настроек сети")
        appendLine("  echo \"\$REAL_DNS\" > \"\$(dirname \"\$STATE\")/cisco-dns.known\"")
        appendLine("  {")
        appendLine("    echo \"TUNDEV=\$TUNDEV\"")
        appendLine("    echo \"DNS=\$REAL_DNS\"")
        appendLine("    echo \"DOMAINS=\$WORK_DOMAINS\"")
        appendLine("    echo \"ROUTES=\$ROUTES\"")
        appendLine("    echo \"SERVER_FULL_TUNNEL=\$FULL\"")
        appendLine("  } > \"\$STATE.tmp\" && chmod 644 \"\$STATE.tmp\" && mv -f \"\$STATE.tmp\" \"\$STATE\"")
        appendLine("fi")
        appendLine("if [ \"\$reason\" = \"disconnect\" ]; then")
        appendLine("  rm -f \"\$STATE\"")
        appendLine("  remove_resolvers")
        appendLine("  # Вернуть маршруты, которые забрали у другого VPN при подключении")
        appendLine("  [ -f \"\$DIR/routes-taken\" ] && while read -r r gw; do")
        appendLine("    route -n delete -net \"\$r\" >/dev/null 2>&1")
        appendLine("    [ -n \"\$gw\" ] && route -n add -net \"\$r\" \"\$gw\" >/dev/null 2>&1")
        appendLine("  done < \"\$DIR/routes-taken\"")
        appendLine("  rm -f \"\$DIR/routes-taken\"")
        // vpnc-script удаляет маршрут к серверу через шлюз текущей сети; если сеть сменилась — не удаляет
        appendLine("  drop_dead_routes \"\$DIR\"")
        appendLine("fi")
        appendLine("exit \$RC")
    }

    /**
     * Функция take_routes TUNDEV "ROUTES" DIR: каждую рабочую подсеть, которая идёт не через Cisco,
     * переводит на Cisco. Чужой маршрут ровно на эту подсеть (Happ ставит 10.0.0.0/8 через Wi-Fi) заменяется
     * и записывается в DIR/routes-taken, чтобы при отключении вернуть его; более широкий (172.16/12 при
     * нашей 172.16.8.0/24) не трогаем — узкий маршрут Cisco и так победит. Нужны права root.
     * Используется обёрткой (при подключении) и супервизором (периодически, если другой VPN вернёт своё).
     */
    fun appendTakeRoutes(sb: StringBuilder) = with(sb) {
        appendLine("take_routes() {")
        appendLine("  tr_dev=\"\$1\"; tr_dir=\"\$3\"")
        appendLine("  [ -n \"\$tr_dev\" ] || return 0")
        appendLine("  for r in \$2; do")
        appendLine("    case \"\$r\" in */0) continue ;; esac")
        appendLine("    got=\$(route -n get \"\${r%/*}\" 2>/dev/null)")
        appendLine("    via=\$(echo \"\$got\" | awk '/interface:/ { print \$2 }')")
        appendLine("    [ \"\$via\" = \"\$tr_dev\" ] && continue")
        appendLine("    gw=\$(echo \"\$got\" | awk '/gateway:/ { print \$2 }')")
        appendLine("    if [ \"\$(echo \"\$got\" | awk '/destination:/ { print \$2 }')\" = \"\${r%/*}\" ]; then")
        appendLine("      route -n delete -net \"\$r\" >/dev/null 2>&1")
        appendLine("    else gw=\"\"; fi")
        appendLine("    if route -n add -net \"\$r\" -interface \"\$tr_dev\" >/dev/null 2>&1; then")
        appendLine("      echo \"\$r \$gw\" >> \"\$tr_dir/routes-taken\"")
        appendLine("      echo \"\$(date '+%F %T') маршрут \${r} шёл через \${via:-?}, переведён на Cisco (\${tr_dev})\" >> \"\$tr_dir/route-fix.log\"")
        appendLine("    fi")
        appendLine("  done")
        appendLine("}")
    }

    /**
     * Функция drop_dead_routes DIR: убирает постоянные маршруты к отдельным адресам, чей шлюз больше
     * не в локальной сети. Их оставляет vpnc-script: при подключении он ведёт сервер Cisco через шлюз
     * текущей сети (дома 192.168.1.254), а при отключении в другой сети удаляет маршрут с новым шлюзом —
     * и промахивается. Потом подключение к этому серверу (в том числе после перенаправления, например
     * на vpn5.…) падает с «Can't assign requested address». Маршрут через достижимый шлюз не трогаем —
     * он рабочий, в том числе у другого VPN. Нужны права root.
     */
    fun appendDropDeadRoutes(sb: StringBuilder) = with(sb) {
        appendLine("drop_dead_routes() {")
        appendLine("  netstat -rn -f inet | awk '\$3 ~ /G/ && \$3 ~ /H/ && \$3 ~ /S/ && \$3 !~ /I/ && \$2 ~ /^[0-9.]+\$/ && \$4 !~ /^utun/ { print \$1, \$2, \$4 }' |")
        appendLine("  while read -r dst gw dev; do")
        appendLine("    got=\$(route -n get \"\$gw\" 2>/dev/null)")
        appendLine("    if [ \"\$(echo \"\$got\" | awk '/interface:/ { print \$2 }')\" = \"\$dev\" ] && ! echo \"\$got\" | grep -q 'flags:.*GATEWAY'; then continue; fi")
        appendLine("    route -n delete -host \"\$dst\" \"\$gw\" >/dev/null 2>&1 &&")
        appendLine("      echo \"\$(date '+%F %T') убран нерабочий маршрут к \${dst} через \${gw} (\${dev}) — шлюз сети, в которой Mac уже нет\" >> \"\$1/route-fix.log\"")
        appendLine("  done")
        appendLine("  return 0")
        appendLine("}")
    }

    /**
     * write_resolvers "ДОМЕНЫ" "DNS": для каждого рабочего домена файл /etc/resolver/<домен> с DNS Cisco —
     * macOS спрашивает про эти домены (и их поддомены) только эти серверы. Наши файлы помечены «# VpnRunner»;
     * чужие не трогаем. remove_resolvers убирает только наши. «local» пропускаем — это mDNS.
     */
    fun appendResolverFunctions(sb: StringBuilder) = with(sb) {
        appendLine("write_resolvers() {")
        appendLine("  [ -n \"\$2\" ] || return 0")
        appendLine("  mkdir -p /etc/resolver")
        appendLine("  for d in \$1; do")
        appendLine("    case \"\$d\" in local|*[!A-Za-z0-9.-]*|.*|*..*) continue ;; esac")
        appendLine("    f=\"/etc/resolver/\$d\"")
        appendLine("    [ -f \"\$f\" ] && ! grep -q '^# VpnRunner' \"\$f\" && continue")
        appendLine("    { echo '# VpnRunner'; for s in \$2; do echo \"nameserver \$s\"; done; } > \"\$f\"")
        appendLine("  done")
        appendLine("}")
        appendLine("remove_resolvers() {")
        appendLine("  for f in /etc/resolver/*; do")
        appendLine("    [ -f \"\$f\" ] && grep -q '^# VpnRunner' \"\$f\" && rm -f \"\$f\"")
        appendLine("  done")
        appendLine("  return 0")
        appendLine("}")
    }

    /** JScript для cscript — для Windows (openconnect вызывает vpnc-script-win.js через cscript). */
    fun jscript(origScript: String, stateFile: String, subnets: List<Cidr>, splitDomains: List<String>, tunDns: String): String = buildString {
        fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        appendLine("// Сгенерировано VpnRunner")
        appendLine("var sh = new ActiveXObject(\"WScript.Shell\");")
        appendLine("var env = sh.Environment(\"Process\");")
        appendLine("var ORIG = ${js(origScript)};")
        appendLine("var STATE = ${js(stateFile)};")
        appendLine("function get(n) { var v = env(n); return v ? String(v) : \"\"; }")
        appendLine("function del(n) { try { env.Remove(n); } catch (e) {} }")
        appendLine("var reason = get(\"reason\");")
        appendLine("var n = parseInt(get(\"CISCO_SPLIT_INC\") || \"0\", 10);")
        appendLine("var orig = [];")
        appendLine("for (var i = 0; i < n; i++) orig.push(get(\"CISCO_SPLIT_INC_\" + i + \"_ADDR\") + \"/\" + get(\"CISCO_SPLIT_INC_\" + i + \"_MASKLEN\"));")
        appendLine("var full = n == 0 ? \"1\" : \"0\";")
        appendLine("for (var i = 0; i < n; i++) if (get(\"CISCO_SPLIT_INC_\" + i + \"_MASKLEN\") == \"0\") full = \"1\";")
        appendLine("var routes = orig.join(\" \");")
        if (subnets.isNotEmpty()) {
            appendLine("var sfx = [\"ADDR\", \"MASK\", \"MASKLEN\", \"PROTOCOL\", \"SPORT\", \"DPORT\"];")
            appendLine("for (var i = 0; i < n; i++) for (var j = 0; j < sfx.length; j++) del(\"CISCO_SPLIT_INC_\" + i + \"_\" + sfx[j]);")
            appendLine("env(\"CISCO_SPLIT_INC\") = \"${subnets.size}\";")
            subnets.forEachIndexed { i, c ->
                appendLine("env(\"CISCO_SPLIT_INC_${i}_ADDR\") = \"${c.addr}\"; env(\"CISCO_SPLIT_INC_${i}_MASK\") = \"${c.mask}\"; env(\"CISCO_SPLIT_INC_${i}_MASKLEN\") = \"${c.maskLen}\";")
                appendLine("env(\"CISCO_SPLIT_INC_${i}_PROTOCOL\") = \"0\"; env(\"CISCO_SPLIT_INC_${i}_SPORT\") = \"0\"; env(\"CISCO_SPLIT_INC_${i}_DPORT\") = \"0\";")
            }
            appendLine("routes = ${js(subnets.joinToString(" ") { "${it.addr}/${it.maskLen}" })};")
        }
        if (splitDomains.isNotEmpty()) {
            appendLine("env(\"CISCO_SPLIT_DNS\") = ${js(splitDomains.joinToString(","))};")
        }
        appendLine("if (reason == \"disconnect\" && /^127\\./.test(get(\"VPNGATEWAY\"))) del(\"VPNGATEWAY\");")
        appendLine("var realDns = get(\"INTERNAL_IP4_DNS\");")
        appendLine("if (realDns) env(\"INTERNAL_IP4_DNS\") = ${js(tunDns)};")
        appendLine("var rc = sh.Run(\"cscript //nologo \\\"\" + ORIG + \"\\\"\", 0, true);")
        appendLine("var fso = new ActiveXObject(\"Scripting.FileSystemObject\");")
        appendLine("if (reason == \"connect\") {")
        appendLine("  var f = fso.CreateTextFile(STATE + \".tmp\", true);")
        appendLine("  f.WriteLine(\"TUNDEV=\" + get(\"TUNDEV\"));")
        appendLine("  f.WriteLine(\"DNS=\" + realDns);")
        appendLine("  f.WriteLine(\"DOMAINS=\" + (get(\"CISCO_DEF_DOMAIN\") + \" \" + get(\"CISCO_SPLIT_DNS\").replace(/,/g, \" \")));")
        appendLine("  f.WriteLine(\"ROUTES=\" + routes);")
        appendLine("  f.WriteLine(\"SERVER_FULL_TUNNEL=\" + full);")
        appendLine("  f.Close();")
        appendLine("  if (fso.FileExists(STATE)) fso.DeleteFile(STATE, true);")
        appendLine("  fso.MoveFile(STATE + \".tmp\", STATE);")
        appendLine("}")
        appendLine("if (reason == \"disconnect\" && fso.FileExists(STATE)) fso.DeleteFile(STATE, true);")
        appendLine("WScript.Quit(rc);")
    }
}
