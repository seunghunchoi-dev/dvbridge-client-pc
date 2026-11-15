// DVBridge 클라이언트 (PC, Windows) — MIT, Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

// 폴더 경로에 영문이 아닌 글자가 있으면 jlink(런타임 묶기)가 실패 → 설치 파일을 만들 때 영문 폴더로 빌드: -Pdmrc.buildDir=C:/dev/dmrc-build
val asciiBuildDir = providers.gradleProperty("dmrc.buildDir").orNull
asciiBuildDir?.let { layout.buildDirectory.set(file(it)) }
// jpackage는 인자 파일을 시스템 문자셋으로 읽음 → 아이콘을 영문 폴더에 복사해서 넘김
val winIcon: File = project.file("src/desktopMain/resources/app.ico").let { src ->
    asciiBuildDir?.let { File(it, "app.ico").also { f -> f.parentFile.mkdirs(); src.copyTo(f, overwrite = true) } } ?: src
}

kotlin {
    jvm("desktop") {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        // 안드로이드판(dvbridge-client-android)과 똑같은 공통 코드: 통신·변환·상태·화면
        val jvmShared by creating { dependsOn(commonMain) }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                // Windows '통신 장치' 이름 읽기 (JNA: Apache 2.0 / LGPL 2.1 이중 라이선스 → Apache 2.0으로 사용)
                implementation("net.java.dev.jna:jna-platform:5.15.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.seunghunchoidev.dvbridgeclient.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "DVBridgeClient"
            packageVersion = "0.5.2"
            description = "DVBridge Client"
            copyright = "Copyright (c) 2026 Choi Seunghun (6K2KZR), MIT License"
            vendor = "DVBridge"
            modules("java.naming", "jdk.unsupported")
            windows {
                iconFile.set(winIcon)
                menuGroup = "DVBridge"
                upgradeUuid = "6f3d2b8a-4c1e-4a7b-9d52-0e8a1c3f6b21"
            }
        }
    }
}
