// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import kotlin.concurrent.timerTask

/** 화면 상태 */
data class RadioState(
    val conn: UsrpClient.State = UsrpClient.State.OFF,
    val tg: Int = 0, val priv: Boolean = false, val ts: Int = 0,
    val rx: Boolean = false, val rxSince: Long = 0,
    val peer: UsrpClient.CallInfo? = null,          // 상대방 정보 (마지막 수신)
    val recent: List<String> = emptyList(),         // 최근 수신 (새 것이 앞)
    val tx: Boolean = false, val txSince: Long = 0,
    val message: String = "",
    val micLevel: Float = 0f,
    val intercomTg: Int = 0,                        // 서버 설정 내부 채널 번호 (INFO로 받음, 0 = 없음·꺼짐)
    val log: List<String> = emptyList(),
)

fun nowMs() = System.nanoTime() / 1_000_000

/**
 * 클라이언트 본체 (PC·안드로이드 공통): 서버 연결·송수신·상대방 정보·공지·내부 채널·내 정보·송신 시간 제한.
 * 화면은 [state]를 보고 함수로 요청. 안드로이드는 포그라운드 서비스가, PC는 프로그램이 이 객체를 붙잡고 있음.
 */
class Radio(private val store: KeyValueStore, private val platform: PlatformInfo, private val audio: AudioBackend) : UsrpClient.Listener {
    companion object { const val TOT_SECONDS = 180 }

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state
    private val client = UsrpClient(this)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val timer = Timer("radio-tot", true)

    /** 상태가 바뀔 때 (안드로이드 알림 갱신용) */
    var onChanged: (() -> Unit)? = null

    init {
        audio.volume = store.getInt("volume", 100) / 100f
        audio.micGain = store.getInt("mic_gain", 100) / 100f
        timer.schedule(timerTask {
            val s = _state.value
            if (s.tx && nowMs() - s.txSince > TOT_SECONDS * 1000L) {
                setPtt(false)
                _state.update { it.copy(message = "송신이 ${TOT_SECONDS}초를 넘어 자동으로 멈췄습니다") }
            }
        }, 500, 500)
    }

    val connState get() = client.state

    /** 설정에 저장된 내 정보 (장비명이 비었으면 기기 기본값) */
    fun myInfo() = buildJsonObject {
        put("call", store.getString("callsign", "").trim().uppercase())
        put("dmr_id", store.getString("dmr_id", "").toLongOrNull() ?: 0L)
        put("name", store.getString("name", "").trim())
        put("device", store.getString("device", "").trim().ifEmpty { platform.defaultDevice })
        put("app", platform.appLabel)
    }

    fun connect(host: String, port: Int) {
        audio.startPlayback()
        _state.update { it.copy(message = "", tg = 0) }
        client.setMyInfo(myInfo())
        client.connect(host, port)
    }

    fun disconnect() {
        setPtt(false)
        client.disconnect()
        audio.stopPlayback()
        _state.update { it.copy(conn = UsrpClient.State.OFF, rx = false, tx = false, tg = 0, ts = 0, intercomTg = 0) }  // 끊으면 지난 대상 지움
        onChanged?.invoke()
    }

    /** 반환: 실패 이유 (성공이면 null). canRecord = 마이크 권한 (안드로이드) */
    fun setPtt(on: Boolean, canRecord: Boolean = true): String? {
        val s = _state.value
        if (on == s.tx) return null
        if (on) {
            if (client.state != UsrpClient.State.ONLINE) return "서버에 연결되지 않아 말할 수 없습니다"
            if (!canRecord) return "마이크 권한이 없습니다"
            client.setPtt(true)
            if (!audio.startCapture({ client.sendVoice(it) }, { lv -> _state.update { it.copy(micLevel = lv) } })) {
                client.setPtt(false)
                return "마이크를 열 수 없습니다"
            }
            _state.update { it.copy(tx = true, txSince = nowMs(), message = "") }
        } else {
            audio.stopCapture()
            client.setPtt(false)
            _state.update { it.copy(tx = false, micLevel = 0f) }
        }
        onChanged?.invoke()
        return null
    }

    fun changeTarget(dtmf: String): String? {
        if (client.state != UsrpClient.State.ONLINE) return "서버에 연결된 뒤에 바꿀 수 있습니다"
        if (_state.value.tx) return "말하는 중에는 바꿀 수 없습니다"
        client.sendTarget(dtmf)
        return null
    }

    fun changeSlot(ts: Int): String? {
        if (client.state != UsrpClient.State.ONLINE) return "서버에 연결된 뒤에 바꿀 수 있습니다"
        client.sendSlot(ts)
        log("타임슬롯 $ts 요청")
        return null
    }

    fun refreshMyInfo() = client.setMyInfo(myInfo())
    fun setVolume(v: Float) { audio.volume = v }
    fun setMicGain(g: Float) { audio.micGain = g }
    fun playTestTone() {
        // 스피커 확인: 1초 동안 0.25초씩 끊어지는 1kHz (8kHz 프레임으로 재생 경로에 그대로 넣음)
        audio.startPlayback()
        for (f in 0 until 50) {
            val on = (f / 12) % 2 == 0
            audio.playFrame(ShortArray(160) { i -> if (on) (9000 * kotlin.math.sin(2 * Math.PI * 1000 * (f * 160 + i) / 8000.0)).toInt().toShort() else 0 })
        }
        audio.playEnd()
    }

    private fun log(text: String) {
        val line = timeFmt.format(Date()) + " " + text
        println("DMRClient: $line")      // 안드로이드 logcat(System.out) / PC 콘솔에도
        _state.update { it.copy(log = (it.log + line).takeLast(100)) }
    }

    // ---------------- UsrpClient.Listener (수신 스레드) ----------------

    override fun onState(s: UsrpClient.State) {
        _state.update { it.copy(conn = s) }
        if (s != UsrpClient.State.ONLINE && _state.value.tx) setPtt(false)
        onChanged?.invoke()
    }

    override fun onRxVoice(pcm: ShortArray) {
        if (!_state.value.rx) {
            _state.update { it.copy(rx = true, rxSince = nowMs()) }
            onChanged?.invoke()
        }
        audio.playFrame(pcm)
    }

    override fun onRxEnd() {
        audio.playEnd()
        _state.update { it.copy(rx = false) }
        onChanged?.invoke()
    }

    // 상대방 정보: 기본(SET_INFO) → 자세한 정보(이름·지역·이름표·공지·내부 채널) 순서로 채워짐. 새 통화 시작마다 지움
    override fun onCallInfo(c: UsrpClient.CallInfo) {
        val who = c.call.ifEmpty { c.src.toString() }
        val line = "${timeFmt.format(Date())}  $who → ${if (c.priv) "" else "TG "}${c.dst}"
        _state.update { it.copy(peer = c, recent = (listOf(line) + it.recent).take(30)) }
        log("수신: $who (${c.src}) → ${if (c.priv) "" else "TG "}${c.dst}, 슬롯 ${c.slot}")
    }

    override fun onCallDetail(c: UsrpClient.CallInfo) {
        _state.update {
            val old = it.peer?.takeIf { p -> p.src == c.src } ?: c
            val merged = old.copy(
                call = c.call.ifEmpty { old.call }, name = c.name.ifEmpty { old.name },
                loc = c.loc.ifEmpty { old.loc }, alias = c.alias.ifEmpty { old.alias },
                notice = old.notice || c.notice, source = c.source.ifEmpty { old.source },
                resume = old.resume || c.resume, intercom = old.intercom || c.intercom)
            var recent = it.recent
            if (recent.isNotEmpty()) {
                if (c.alias.isNotEmpty() && old.alias != c.alias) recent = listOf(recent[0] + "  「${c.alias}」") + recent.drop(1)
                if (c.intercom && !old.intercom) recent = listOf(recent[0] + "  🔁 내부 채널") + recent.drop(1)
                if (c.notice && !old.notice)
                    recent = listOf("${timeFmt.format(Date())}  📢 브릿지 공지${if (c.resume) " (이어서)" else ""}  ${c.name}") + recent.drop(1)
            }
            it.copy(peer = merged, recent = recent)
        }
        if (c.alias.isNotEmpty()) log("상대방 이름표: ${c.alias}")
    }

    override fun onInfo(tg: Int, priv: Boolean, ts: Int) {
        _state.update { it.copy(tg = tg, priv = priv, ts = ts) }
        onChanged?.invoke()
    }

    override fun onIntercomTg(tg: Int) { _state.update { it.copy(intercomTg = tg) } }

    override fun onMessage(text: String) {
        _state.update { it.copy(message = "서버 알림: $text") }
        log("서버 알림: $text")
    }

    override fun onLog(text: String) = log(text)
}

/** 서버와 같은 규칙의 미리 보기 (실제 값은 서버가 만듦: Session::make_location / make_talker_alias) */
object NamePreview {
    private fun ascii(s: String) = s.filter { it.code in 0x20..0x7E }.trim().replace(Regex(" +"), " ")

    /** BM 기기 위치 칸(20자) */
    fun location(device: String): String {
        val d = ascii(device)
        var t = if (d.isEmpty()) "DVBridge" else "DVBridge $d"
        if (t.length > 20 && d.startsWith("Galaxy ", ignoreCase = true)) t = "DVBridge " + d.substring(7)
        return t.take(20)
    }

    /** 이름표(Talker Alias, 31자) */
    fun alias(call: String, device: String): String {
        val c = ascii(call.uppercase())
        val d = ascii(device)
        val head = if (c.isEmpty()) "" else "$c "
        var t = head + "DVBridge" + if (d.isEmpty()) "" else " $d"
        if (t.length > 31 && d.startsWith("Galaxy ", ignoreCase = true)) t = head + "DVBridge " + d.substring(7)
        return t.take(31)
    }
}
