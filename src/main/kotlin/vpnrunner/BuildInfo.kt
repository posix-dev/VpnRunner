package vpnrunner

object BuildInfo {
    val version: String = System.getProperty("vpnrunner.version")
        ?: BuildInfo::class.java.`package`.implementationVersion
        ?: "dev"
}
