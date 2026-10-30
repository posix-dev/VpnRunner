import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

// Байткод Java 17: запускается и на обычных JDK 17+ (встроенному браузеру нужна не JetBrains-сборка, см. BrowserEngine)
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }

// Встроенному браузеру (вкладка «Браузер») нужна JetBrains Runtime 17 с JCEF — на ней и запускаем программу.
// Ищется в ~/Library/Java/JavaVirtualMachines/jbrsdk_jcef-17*, либо путь задаётся: ./gradlew run -Pjbr.jcef.home=…/Contents/Home
// Не нашлась — программа работает на обычной Java, вкладка «Браузер» подскажет, что установить.
val jbrJcefHome: String? = (findProperty("jbr.jcef.home") as String?)
    ?: File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()
        ?.filter { it.name.startsWith("jbrsdk_jcef-17") }?.maxByOrNull { it.name }
        ?.resolve("Contents/Home")?.takeIf { it.isDirectory }?.path

group = "vpnrunner"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jogamp.org/deployment/maven") // gluegen/jogl для встроенного браузера (KCEF)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Встроенный браузер (вкладка «Браузер»): Chromium через KCEF, движок скачивается при первом открытии
    implementation("io.github.kevinnzou:compose-webview-multiplatform-desktop:2.0.3")
    // Новее, чем тянет библиотека: у этой версии известна совместимая сборка движка (см. BrowserEngine)
    implementation("dev.datlag:kcef:2025.03.23")
}

compose.desktop {
    application {
        mainClass = "vpnrunner.MainKt"
        jbrJcefHome?.let { javaHome = it }
        // Chromium (KCEF) встраивается в окно через внутренние классы AWT
        jvmArgs += listOf("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED", "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            jvmArgs += listOf("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "VpnRunner"
            packageVersion = "1.0.0"
            description = "Cisco + VPN по подписке с раздельным туннелированием"
            // Все модули JDK — чтобы HTTPS (подписки) точно работал в собранном приложении
            includeAllModules = true
            macOS {
                bundleID = "ru.pavel.vpnrunner"
            }
            windows {
                menuGroup = "VpnRunner"
                perUserInstall = false
                // Программе нужны права администратора: Windows спросит их при запуске ярлыка.
                upgradeUuid = "6b1f3c2e-8f6e-4f0a-9d7a-3c1b2a9e5d41"
            }
        }
    }
}
