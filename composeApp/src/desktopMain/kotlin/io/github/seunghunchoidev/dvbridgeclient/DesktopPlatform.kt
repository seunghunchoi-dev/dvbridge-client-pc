// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.Properties

object DesktopPlatform : PlatformInfo {
    override val appLabel = "DVBridge Client $APP_VERSION (Windows)"
    override val defaultDevice = "PC"
    override val isDesktop = true
}

/**
 * 설정 파일: %APPDATA%\DVBridgeClient\universal.properties
 * 처음이면 옛 이름(DVBridge 클라이언트, %APPDATA%\DVBridgeClient)의 설정을 옮겨 오고, 그것도 없으면 Qt판 client.ini에서 가져옴
 */
class DesktopStore : KeyValueStore {
    private val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
    private val dir = File(base, "DVBridgeClient")
    private val oldDir = File(base, "DVBridgeClient")      // 0.4까지의 이름
    private val file = File(dir, "universal.properties")
    private val p = Properties()

    init {
        val old = File(oldDir, "universal.properties")
        when {
            file.exists() -> file.inputStream().use { p.load(it) }
            old.exists() -> { old.inputStream().use { p.load(it) }; save() }
            else -> importQt()
        }
    }

    private fun importQt() {
        val ini = File(oldDir, "client.ini")   // Qt판은 옛 이름 폴더에 저장했음
        if (!ini.exists()) return
        var section = ""
        val map = mapOf("server/host" to "host", "server/port" to "port", "me/callsign" to "callsign", "me/dmr_id" to "dmr_id",
            "me/name" to "name", "me/device" to "device", "General/auto_connect" to "auto_connect")
        ini.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) section = line.substring(1, line.length - 1)
            else if ('=' in line) {
                val k = line.substringBefore('=').trim(); val v = line.substringAfter('=').trim()
                map["$section/$k"]?.let { if (v.isNotEmpty()) p.setProperty(it, v) }
            }
        }
        save()
    }

    @Synchronized private fun save() {
        dir.mkdirs()
        file.outputStream().use { p.store(it, "DVBridge Client (universal)") }
    }

    override fun getString(key: String, def: String): String = p.getProperty(key) ?: def
    override fun getInt(key: String, def: Int) = p.getProperty(key)?.toIntOrNull() ?: def
    override fun getFloat(key: String, def: Float) = p.getProperty(key)?.toFloatOrNull() ?: def
    override fun getBoolean(key: String, def: Boolean) = p.getProperty(key)?.toBooleanStrictOrNull() ?: def
    @Synchronized override fun put(key: String, value: Any) { p.setProperty(key, value.toString()); save() }
}

/**
 * Windows 기본 장치 이름 (Core Audio, JNA로 COM 직접 호출) — 음성 통화 프로그램처럼 '통신 장치'를 쓰기 위해
 *  IMMDeviceEnumerator::GetDefaultAudioEndpoint → IMMDevice::OpenPropertyStore → PKEY_Device_FriendlyName
 */
object WinAudio {
    private fun vcall(obj: Pointer, index: Int, vararg args: Any?): Int {
        val vt = obj.getPointer(0)
        val f = Function.getFunction(vt.getPointer(index.toLong() * Native.POINTER_SIZE))
        return f.invokeInt(arrayOf(obj, *args))
    }

    /** role: 0 = 일반(Console), 2 = 통신(Communications) */
    fun defaultName(input: Boolean, role: Int): String? = try {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        val en = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null,
            WTypes.CLSCTX_ALL, Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), en)
        if (hr.toInt() != 0) null else {
            val enumr = en.value
            val dev = PointerByReference()
            var name: String? = null
            if (vcall(enumr, 4, if (input) 1 else 0, role, dev) == 0) {          // GetDefaultAudioEndpoint
                val store = PointerByReference()
                if (vcall(dev.value, 4, 0, store) == 0) {                       // OpenPropertyStore(STGM_READ)
                    val key = Memory(20).apply {                                // PKEY_Device_FriendlyName {a45c254e-...}, 14
                        setInt(0, 0xa45c254e.toInt()); setShort(4, 0xdf1c.toShort()); setShort(6, 0x4efd.toShort())
                        write(8, byteArrayOf(0x80.toByte(), 0x20, 0x67, 0xd1.toByte(), 0x46, 0xa8.toByte(), 0x50, 0xe0.toByte()), 0, 8)
                        setInt(16, 14)
                    }
                    val pv = Memory(24).apply { clear() }
                    if (vcall(store.value, 5, key, pv) == 0 && pv.getShort(0).toInt() == 31) name = pv.getPointer(8)?.getWideString(0)
                    NativeLibrary.getInstance("ole32").getFunction("PropVariantClear").invokeInt(arrayOf(pv))
                    vcall(store.value, 2)
                }
                vcall(dev.value, 2)
            }
            vcall(enumr, 2)
            name
        }
    } catch (_: Throwable) { null }
}
