import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

group = "vpnrunner"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "vpnrunner.MainKt"
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
