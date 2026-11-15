// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

/** 설정 저장소 (안드로이드: SharedPreferences "settings" — 기존 앱 설정 그대로 / PC: 속성 파일) */
interface KeyValueStore {
    fun getString(key: String, def: String): String
    fun getInt(key: String, def: Int): Int
    fun getFloat(key: String, def: Float): Float
    fun getBoolean(key: String, def: Boolean): Boolean
    fun put(key: String, value: Any)
}

/** 마이크·스피커 ⇄ 8kHz 16bit 160샘플 프레임 */
interface AudioBackend {
    var volume: Float          // 0~2
    var micGain: Float         // 0.1~4
    fun startPlayback()
    fun stopPlayback()
    fun playFrame(pcm: ShortArray)
    fun playEnd()
    /** 말하기 중에만 마이크를 엶. 실패하면 false */
    fun startCapture(onFrame: (ShortArray) -> Unit, onLevel: (Float) -> Unit): Boolean
    fun stopCapture()
}

/** 기기별 정보 */
interface PlatformInfo {
    val appLabel: String       // 예: "DVBridge Client 0.3 (Android 15)"
    val defaultDevice: String  // 장비명 칸이 비었을 때 (안드로이드: 자동 감지 "Galaxy A17", PC: "PC")
    val isDesktop: Boolean
    val dataDir: java.io.File  // 주소록 같은 파일을 둘 곳 (안드로이드: 앱 전용 폴더, PC: %APPDATA%\\DVBridgeClient)
}

const val APP_VERSION = "0.5.2"
