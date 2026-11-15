// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dmrbridgeclient

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.io.RandomAccessFile
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** PC판 시작점: 공통 화면(App) + 스페이스바 말하기 + 소리 장치 설정 + 한 번에 하나만 실행 */
fun main(args: Array<String>) {
    // 한 번에 하나만 (같은 마이크를 두 프로그램이 잡지 않게)
    val lockFile = File(System.getProperty("java.io.tmpdir"), "DMRBridgeClientUniversal.lock")
    val lock = try { RandomAccessFile(lockFile, "rw").channel.tryLock() } catch (_: Exception) { null }
    if (lock == null) {
        JOptionPane.showMessageDialog(null, "이미 실행 중입니다.", "DMRBridge 클라이언트", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }

    val store = DesktopStore()
    val audio = DesktopAudio(store)
    val radio = Radio(store, DesktopPlatform, audio)
    lateinit var model: UiModel
    model = UiModel(store, radio, UiHooks(
        connect = { h, p -> radio.connect(h, p) },
        disconnect = { radio.disconnect() },
        pttKeyHint = "스페이스바",
        extraSettings = { p -> DeviceSettings(audio, store, p) },
    ))
    if (store.getBoolean("auto_connect", false) && store.getString("host", "").isNotBlank())
        radio.connect(store.getString("host", ""), store.getInt("port", 32001))
    // 자체 시험: --demo-toast → 알림 팝업 모양 확인 (주의 → 완료 순서)
    if ("--demo-toast" in args) Thread {
        Thread.sleep(2500); model.toast = "서버에 연결되지 않아 말할 수 없습니다"
        Thread.sleep(5500); model.toast = "내 정보를 저장했습니다"
    }.apply { isDaemon = true; start() }
    // 자체 시험: --ptt-test → 연결되면 3초 말하기 후 결과를 임시 폴더 dmrclient_ptt_test.txt에 씀 (PC 마이크 송신 확인용)
    if ("--ptt-test" in args) Thread {
        val out = File(System.getProperty("java.io.tmpdir"), "dmrclient_ptt_test.txt")
        var waited = 0
        while (radio.connState != UsrpClient.State.ONLINE && waited < 15000) { Thread.sleep(200); waited += 200 }
        val r1 = radio.setPtt(true)
        Thread.sleep(3000)
        val lv = radio.state.value.micLevel
        radio.setPtt(false)
        out.writeText("online=${radio.connState} ptt=${r1 ?: "ok"} mic=${audio.currentIn} spk=${audio.currentOut} level=$lv\n", Charsets.UTF_8)
    }.apply { isDaemon = true; start() }

    application {
        var spaceDown by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { radio.disconnect(); exitApplication() },
            title = "DMRBridge 클라이언트",
            icon = painterResource("app.png"),
            // PC 화면: 넓게 (왼쪽 말하기 | 오른쪽 탭). 좁히면 휴대폰처럼 한 칸 배치로 바뀜
            state = rememberWindowState(width = 1200.dp, height = 800.dp, position = WindowPosition.Aligned(Alignment.Center)),
            onPreviewKeyEvent = { e ->
                // 스페이스바 = 말하기 (글자 입력 칸에 커서가 있을 때는 제외, 좁은 화면은 말하기 탭에서만)
                if (e.key == Key.Spacebar && !model.typing && (model.wide || model.tab == 0)) {
                    if (e.type == KeyEventType.KeyDown && !spaceDown) { spaceDown = true; model.pttPress() }
                    else if (e.type == KeyEventType.KeyUp && spaceDown) { spaceDown = false; model.pttRelease() }
                    true
                } else false
            },
        ) {
            App(model, DesktopPlatform)
        }
    }
}

/** PC 전용 설정: 소리 장치 (처음 값 = Windows 통신 장치), 자동 연결 */
@Composable
private fun ColumnScope.DeviceSettings(audio: DesktopAudio, store: KeyValueStore, p: Palette) {
    var outSel by remember { mutableStateOf(audio.outSel) }
    var inSel by remember { mutableStateOf(audio.inSel) }
    var auto by remember { mutableStateOf(store.getBoolean("auto_connect", false)) }
    val outs = remember { audio.outputs() }
    val ins = remember { audio.inputs() }
    Card(p) {
        Title("소리 장치 (PC)", p)
        Text("스피커", fontSize = 18.sp, color = p.text, fontWeight = FontWeight.Bold)
        DeviceList(listOf(DesktopAudio.COMM to "Windows 통신 장치 — ${audio.commName(false)}", "" to "Windows 기본 장치 — ${audio.defaultName(false)}") +
            outs.map { it to it }, outSel, p) { outSel = it; audio.select(true, it) }
        Text("마이크", fontSize = 18.sp, color = p.text, fontWeight = FontWeight.Bold)
        DeviceList(listOf(DesktopAudio.COMM to "Windows 통신 장치 — ${audio.commName(true)}", "" to "Windows 기본 장치 — ${audio.defaultName(true)}") +
            ins.map { it to it }, inSel, p) { inSel = it; audio.select(false, it) }
        if (audio.currentOut.isNotEmpty()) Text("지금 스피커: ${audio.currentOut}", fontSize = 15.sp, color = p.sub)
        CheckRow("프로그램을 켜면 자동으로 연결", auto, p) { auto = it; store.put("auto_connect", it) }
    }
}

@Composable
private fun DeviceList(items: List<Pair<String, String>>, sel: String, p: Palette, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (v, label) ->
            val cur = v == sel
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cur) Blue else p.bg)
                .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cur) "● " else "○ ", fontSize = 17.sp, color = if (cur) Color.White else p.text)
                Text(label, fontSize = 17.sp, color = if (cur) Color.White else p.text)
            }
        }
    }
}
