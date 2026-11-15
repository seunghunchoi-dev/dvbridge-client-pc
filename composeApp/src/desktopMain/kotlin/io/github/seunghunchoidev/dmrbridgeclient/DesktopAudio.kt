// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dmrbridgeclient

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PC 마이크·스피커 (Java Sound) ⇄ 8kHz 16bit 160샘플
 *  - 장치: "@communications" = Windows 통신 장치(처음 값, 헤드셋 등), "" = Windows 기본 장치, 그 밖 = 장치 이름
 *  - 48kHz로 열고 표본율 변환은 직접 (Resampler)
 *  - 재생: 20ms마다 소리 또는 무음을 계속 써서 지연을 일정하게, 처음 60ms 모은 뒤 재생, 1초 넘게 밀리면 버림
 */
class DesktopAudio(private val store: KeyValueStore) : AudioBackend {
    companion object {
        const val COMM = "@communications"
        private const val RATE = 8000
        private const val FRAME = 160
        private const val DEV_RATE = 48000f
        private val FMT = AudioFormat(DEV_RATE, 16, 1, true, false)
    }

    @Volatile override var volume = 1.0f
    @Volatile override var micGain = 1.0f
    var outSel: String = store.getString("audio_output", COMM); private set
    var inSel: String = store.getString("audio_input", COMM); private set
    @Volatile var currentOut = ""; private set
    @Volatile var currentIn = ""; private set

    fun outputs(): List<String> = mixers(SourceDataLine::class.java)
    fun inputs(): List<String> = mixers(TargetDataLine::class.java)

    private fun mixers(c: Class<*>): List<String> = AudioSystem.getMixerInfo().filter { mi ->
        !mi.name.startsWith("Port") && try { AudioSystem.getMixer(mi).isLineSupported(DataLine.Info(c, FMT)) } catch (_: Exception) { false }
    }.map { it.name }.distinct()

    /** 고른 값을 실제 장치(믹서)로. 없으면 null = Java 기본 장치 */
    private fun resolve(sel: String, input: Boolean): Mixer.Info? {
        val names = AudioSystem.getMixerInfo().filter { !it.name.startsWith("Port") }
        val want = when (sel) {
            COMM -> WinAudio.defaultName(input, 2)
            "" -> WinAudio.defaultName(input, 0)
            else -> sel
        } ?: return null
        // Java Sound 장치 이름은 31자에서 잘릴 수 있어 앞부분으로도 맞춤
        return names.firstOrNull { it.name == want } ?: names.firstOrNull { want.startsWith(it.name) && it.name.length >= 8 }
            ?: names.firstOrNull { it.name.startsWith(want) }
    }

    fun commName(input: Boolean) = WinAudio.defaultName(input, 2) ?: "-"
    fun defaultName(input: Boolean) = WinAudio.defaultName(input, 0) ?: "-"

    fun select(output: Boolean, sel: String) {
        if (output) { outSel = sel; store.put("audio_output", sel); if (playLine != null) { stopPlayback(); startPlayback() } }
        else { inSel = sel; store.put("audio_input", sel) }
    }

    // ---------------- 재생 ----------------
    private val playQ = LinkedBlockingQueue<ShortArray>()
    @Volatile private var playing = false
    @Volatile private var flush = false
    private var playLine: SourceDataLine? = null
    private var playThread: Thread? = null

    @Synchronized override fun startPlayback() {
        if (playLine != null) return
        val mi = resolve(outSel, false)
        val line = try {
            (if (mi != null) AudioSystem.getMixer(mi).getLine(DataLine.Info(SourceDataLine::class.java, FMT))
             else AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, FMT))) as SourceDataLine
        } catch (e: Exception) { null } ?: return
        line.open(FMT, (DEV_RATE * 2 * 0.12f).toInt())          // 120ms 버퍼
        line.start()
        currentOut = mi?.name ?: "Java 기본 장치"
        playLine = line
        playing = true
        playThread = Thread({ playLoop(line) }, "audio-play").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stopPlayback() {
        playing = false
        playThread?.join(500)
        playThread = null
        playLine?.run { try { stop(); close() } catch (_: Exception) {} }
        playLine = null
        playQ.clear()
    }

    override fun playFrame(pcm: ShortArray) {
        playQ.offer(pcm)
        while (playQ.size > 50) playQ.poll()
    }

    override fun playEnd() { flush = true }

    private fun playLoop(line: SourceDataLine) {
        val up = Resampler(RATE, DEV_RATE.toInt())
        val out = ArrayList<Float>(1200)
        val silence = ByteArray(960 * 2)
        val fin = FloatArray(FRAME)
        var active = false
        while (playing) {
            if (!active && (playQ.size >= 3 || (flush && playQ.isNotEmpty()))) active = true
            val f = if (active) playQ.poll(20, TimeUnit.MILLISECONDS) else null
            if (f == null) {
                if (active) { active = false; flush = false }
                if (line.available() >= silence.size) line.write(silence, 0, silence.size) else Thread.sleep(5)
                continue
            }
            val v = volume
            for (i in 0 until FRAME) fin[i] = f[i] / 32768f * v
            out.clear()
            up.process(fin, FRAME, out)
            val b = ByteArray(out.size * 2)
            for (i in out.indices) {
                val s = (out[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                b[i * 2] = (s and 0xFF).toByte(); b[i * 2 + 1] = (s shr 8).toByte()
            }
            line.write(b, 0, b.size)
        }
    }

    // ---------------- 녹음 (말하기 중에만) ----------------
    @Volatile private var recording = false
    private var recThread: Thread? = null

    override fun startCapture(onFrame: (ShortArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
        if (recording) return true
        val mi = resolve(inSel, true)
        val line = try {
            (if (mi != null) AudioSystem.getMixer(mi).getLine(DataLine.Info(TargetDataLine::class.java, FMT))
             else AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, FMT))) as TargetDataLine
        } catch (e: Exception) { null } ?: return false
        try { line.open(FMT, (DEV_RATE * 2 * 0.1f).toInt()) } catch (_: Exception) { return false }
        currentIn = mi?.name ?: "Java 기본 장치"
        line.start()
        recording = true
        recThread = Thread({ recLoop(line, onFrame, onLevel) }, "audio-rec").apply { isDaemon = true; start() }
        return true
    }

    override fun stopCapture() {
        recording = false
        recThread?.join(500)
        recThread = null
    }

    private fun recLoop(line: TargetDataLine, onFrame: (ShortArray) -> Unit, onLevel: (Float) -> Unit) {
        val down = Resampler(DEV_RATE.toInt(), RATE)
        val raw = ByteArray(960 * 2)
        val fin = FloatArray(960)
        val acc = ArrayList<Float>(FRAME * 4)
        var peak = 0f
        var nf = 0
        try {
            while (recording) {
                val n = line.read(raw, 0, raw.size) / 2
                if (n <= 0) continue
                val g = micGain
                for (i in 0 until n) fin[i] = ((raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)).toShort() / 32768f * g
                down.process(fin, n, acc)
                while (acc.size >= FRAME) {
                    val pcm = ShortArray(FRAME)
                    for (i in 0 until FRAME) {
                        val v = acc[i].coerceIn(-1f, 1f)
                        peak = maxOf(peak, abs(v))
                        pcm[i] = (v * 32767f).roundToInt().toShort()
                    }
                    acc.subList(0, FRAME).clear()
                    onFrame(pcm)
                    if (++nf >= 3) { onLevel(peak); peak = 0f; nf = 0 }
                }
            }
        } finally {
            try { line.stop(); line.close() } catch (_: Exception) {}
            onLevel(0f)
        }
    }
}
