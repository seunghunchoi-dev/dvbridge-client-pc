// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * DVBridge 서버(USRP 포트)에 붙는 클라이언트 쪽 프로토콜 (PC·안드로이드 공통, 규격: docs/PROTOCOL.md)
 *  - 패킷: 헤더 32바이트 빅엔디안 "USRP" seq memory keyup talkgroup type mpxid reserved + 음성 160샘플(8kHz, 리틀엔디안)
 *  - type 칸을 빅엔디안으로 보내므로 서버가 INFO(JSON)·MSG 알림도 보내 줌. 반대 순서 패킷은 무시
 *  - 접속: TEXT "REG:DVSWITCH" → "REG:OK", 5초마다 PING, 15초 응답 없으면 NO_REPLY(계속 다시 시도)
 */
class UsrpClient(private val l: Listener) {
    enum class State { OFF, RESOLVING, WAITING, ONLINE, NO_REPLY }

    data class CallInfo(
        val src: Int, val rptr: Long, val dst: Int, val slot: Int, val priv: Boolean, val call: String,
        val name: String = "", val loc: String = "", val alias: String = "",   // 서버가 알려 준 이름·지역, 상대방 이름표(Talker Alias)
        val notice: Boolean = false,                                          // 브릿지 공지 (name = 공지 제목)
        val source: String = "", val resume: Boolean = false,                 // 공지 종류 tts/live/schedule, 이어서
        val intercom: Boolean = false,                                        // 내부 채널 (BM으로 안 나감, name = 말한 사용자 이름)
        val echo: Boolean = false,                                            // 서버 내부 에코 시험 응답 (BM 안 거침)
    )

    interface Listener {
        fun onState(s: State)
        fun onRxVoice(pcm: ShortArray)
        fun onRxEnd()
        fun onCallInfo(c: CallInfo)
        fun onCallDetail(c: CallInfo)
        fun onInfo(tg: Int, priv: Boolean, ts: Int)
        fun onIntercomTg(tg: Int)
        fun onEchoTg(tg: Int)
        fun onMessage(text: String)
        fun onLog(text: String)
    }

    companion object {
        const val T_VOICE = 0
        const val T_DTMF = 1
        const val T_TEXT = 2
        const val T_PING = 3
        const val T_TLV = 4
        const val SAMPLES = 160
        private const val TLV_REMOTE_CMD = 5
        private const val TLV_SET_INFO = 8
        private const val NUL = 0.toChar()
    }

    @Volatile var state = State.OFF; private set
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var server: InetAddress? = null
    private var port = 0
    private var thread: Thread? = null
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "usrp-tx").apply { isDaemon = true } }
    private var seq = 0
    @Volatile var ptt = false; private set
    @Volatile private var myInfo: JsonObject? = null
    @Volatile private var sendErrLogged = false

    private var lastReply = 0L
    private var lastPing = 0L
    private var lastReg = 0L
    private var lastRxVoice = 0L
    private var rxActive = false

    private fun now() = System.nanoTime() / 1_000_000

    private fun setState(s: State) {
        if (state == s) return
        state = s
        l.onState(s)
    }

    /** 내 정보 (콜사인·DMR ID·이름·장비명·앱) → 서버. 등록할 때마다 함께 보냄 */
    fun setMyInfo(o: JsonObject) {
        myInfo = o
        if (state == State.ONLINE) sendMyInfo()
    }

    private fun sendMyInfo() {
        val o = myInfo ?: return
        sendRaw(T_TEXT, false, ("CLIENT:$o$NUL").toByteArray(Charsets.UTF_8))
    }

    fun connect(host: String, port: Int) {
        disconnect()
        this.port = port
        running = true
        setState(State.RESOLVING)
        thread = Thread({ runLoop(host.trim()) }, "usrp-rx").apply { isDaemon = true; start() }
    }

    fun disconnect() {
        if (ptt) setPtt(false)
        running = false
        socket?.close()
        thread?.join(500)
        thread = null
        socket = null
        if (rxActive) { rxActive = false; l.onRxEnd() }
        setState(State.OFF)
    }

    private fun runLoop(host: String) {
        val addr = try { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } } catch (e: Exception) { null }
        if (addr == null) {
            l.onLog("서버 주소를 찾지 못함: $host")
            running = false
            setState(State.OFF)
            return
        }
        server = addr
        val s = try { DatagramSocket() } catch (e: Exception) {
            l.onLog("UDP 소켓 열기 실패: ${e.message}")
            running = false
            setState(State.OFF)
            return
        }
        s.soTimeout = 100
        socket = s
        l.onLog("서버 ${addr.hostAddress}:$port 에 접속 요청")
        setState(State.WAITING)
        lastReply = 0
        sendReg()
        lastPing = now()
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                if (pkt.port == port && pkt.address == addr) handle(buf, pkt.length)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!running) break
            }
            tick()
        }
    }

    private fun tick() {
        val t = now()
        if (rxActive && t - lastRxVoice > 600) { rxActive = false; l.onRxEnd() }
        val silent = lastReply == 0L || t - lastReply > 15000
        if (state == State.ONLINE && silent) {
            setState(State.NO_REPLY)
            l.onLog("서버 응답 없음 - 계속 다시 시도합니다")
        }
        if (state != State.ONLINE) {
            if (t - lastReg >= 5000) sendReg()
            if (state == State.WAITING && t - lastPing > 15000) setState(State.NO_REPLY)
        } else if (t - lastPing >= 5000) {
            sendRaw(T_PING, false, "PING$NUL".toByteArray(Charsets.ISO_8859_1))
            lastPing = t
        }
    }

    private fun sendReg() {
        sendRaw(T_TEXT, false, "REG:DVSWITCH$NUL".toByteArray(Charsets.ISO_8859_1))
        sendMyInfo()
        lastReg = now()
    }

    private fun handle(b: ByteArray, n: Int) {
        if (n < 32 || b[0] != 'U'.code.toByte() || b[1] != 'S'.code.toByte() || b[2] != 'R'.code.toByte() || b[3] != 'P'.code.toByte()) return
        lastReply = now()
        if (state != State.ONLINE) {
            setState(State.ONLINE)
            l.onLog("서버 연결됨")
        }
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.BIG_ENDIAN)
        val keyup = bb.getInt(12)
        val type = bb.getInt(20)
        if ((type ushr 16) != 0) return     // 반대 바이트 순서(DVSwitch Mobile용)는 무시
        when (type) {
            T_VOICE -> {
                if (keyup != 0 && n >= 32 + SAMPLES * 2) {
                    val pcm = ShortArray(SAMPLES)
                    ByteBuffer.wrap(b, 32, SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    rxActive = true
                    lastRxVoice = now()
                    l.onRxVoice(pcm)
                } else if (keyup == 0 && rxActive) {
                    rxActive = false
                    l.onRxEnd()
                }
            }
            T_TEXT -> handleText(b.copyOfRange(32, n))
        }
    }

    private fun cStr(b: ByteArray, from: Int = 0): String {
        var end = from
        while (end < b.size && b[end] != 0.toByte()) end++
        return String(b, from, end - from, Charsets.UTF_8).trim()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content else it.content } ?: ""
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun handleText(pl: ByteArray) {
        if (pl.isEmpty()) return
        if ((pl[0].toInt() and 0xFF) == TLV_SET_INFO) {
            // 수신 통화 정보: src(3) rptr(4) dst(3) slot(1) cc|priv(1) 콜사인\0
            if (pl.size < 14) return
            fun u(i: Int) = pl[2 + i].toInt() and 0xFF
            val src = (u(0) shl 16) or (u(1) shl 8) or u(2)
            val rptr = (u(3).toLong() shl 24) or (u(4).toLong() shl 16) or (u(5).toLong() shl 8) or u(6).toLong()
            val dst = (u(7) shl 16) or (u(8) shl 8) or u(9)
            l.onCallInfo(CallInfo(src, rptr, dst, u(10), (u(11) and 0x80) != 0, cStr(pl, 14)))
            return
        }
        val t = cStr(pl)
        when {
            t.startsWith("REG:OK") -> {}
            t.startsWith("INFO:CALL:{") -> try {
                val o = Json.parseToJsonElement(t.substring(10)).jsonObject
                l.onCallDetail(CallInfo(o.int("src"), 0, o.int("dst"), o.int("slot"), o.bool("priv"),
                    o.str("call"), o.str("name"), o.str("loc"), o.str("alias"), o.bool("notice"),
                    o.str("source"), o.bool("resume"), o.bool("intercom"), o.bool("echo")))
            } catch (_: Exception) {}
            t.startsWith("INFO:MSG:") -> l.onMessage(t.substring(9))
            t.startsWith("INFO:{") -> try {
                val o = Json.parseToJsonElement(t.substring(5)).jsonObject
                var tune = o.str("last_tune")
                val ts = (o["tlv"] as? JsonObject)?.int("ts") ?: 0
                val priv = tune.endsWith("#")
                if (priv) tune = tune.dropLast(1)
                l.onIntercomTg(o.int("intercom_tg"))    // 서버는 꺼져 있으면 키를 안 넣음 → 0 = 숨김
                l.onEchoTg(o.int("echo_tg"))            // 서버 내부 에코 번호 (없으면 0 = 숨김)
                val tg = tune.toIntOrNull() ?: 0
                if (tg > 0) l.onInfo(tg, priv, ts)
            } catch (_: Exception) {}
        }
    }

    /** PTT 끄면 종료 패킷(keyup=0)을 UDP 유실 대비 두 번 보냄 */
    fun setPtt(on: Boolean) {
        if (ptt == on) return
        ptt = on
        if (!on) {
            sendRaw(T_VOICE, false, ByteArray(0))
            sendRaw(T_VOICE, false, ByteArray(0))
        }
    }

    fun sendVoice(pcm: ShortArray) {
        if (!ptt) return
        val a = ByteBuffer.allocate(SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        a.asShortBuffer().put(pcm, 0, SAMPLES)
        sendRaw(T_VOICE, true, a.array())
    }

    /** "450", "9990#"(개인호출) — 서버의 휴대폰 번호 입력과 같은 규칙 */
    fun sendTarget(dtmf: String) {
        sendRaw(T_DTMF, false, (dtmf.trim() + NUL).toByteArray(Charsets.ISO_8859_1))
        l.onLog("통화 대상 변경 요청: ${dtmf.trim()}")
    }

    fun sendSlot(ts: Int) {
        val cmd = "txTs=$ts".toByteArray(Charsets.ISO_8859_1)
        val b = ByteArray(2 + cmd.size + 1)
        b[0] = TLV_REMOTE_CMD.toByte()
        b[1] = (cmd.size + 1).toByte()
        System.arraycopy(cmd, 0, b, 2, cmd.size)
        sendRaw(T_TLV, false, b)
    }

    private fun sendRaw(type: Int, keyup: Boolean, payload: ByteArray) {
        val s = socket ?: return
        val dst = server ?: return
        val d = ByteBuffer.allocate(32 + payload.size).order(ByteOrder.BIG_ENDIAN)
        d.put("USRP".toByteArray(Charsets.ISO_8859_1))
        synchronized(this) { d.putInt(4, seq++) }
        d.putInt(12, if (keyup) 1 else 0)
        d.putInt(20, type)
        d.position(32)
        d.put(payload)
        val bytes = d.array()
        // 전송 전용 스레드 (안드로이드는 메인 스레드 네트워크 금지, 순서 유지)
        sender.execute {
            try { s.send(DatagramPacket(bytes, bytes.size, dst, port)); sendErrLogged = false }
            catch (e: Exception) { if (!sendErrLogged) { sendErrLogged = true; l.onLog("전송 실패: $e") } }   // 같은 오류는 한 번만
        }
    }
}
