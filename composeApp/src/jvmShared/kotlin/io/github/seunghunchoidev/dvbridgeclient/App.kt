// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// 대비가 큰 색 (어르신도 잘 보이게): 글자는 검정/흰색, 상태는 진한 초록·빨강·주황, 말하기 버튼은 진한 파랑
val Red = Color(0xFFC62828)
val Green = Color(0xFF1B7A33)
val Amber = Color(0xFFB45F06)
val Blue = Color(0xFF1546A0)
val Gray = Color(0xFF6B6B6B)

data class Palette(val bg: Color, val card: Color, val text: Color, val sub: Color, val line: Color)
private val LightP = Palette(Color.White, Color(0xFFF1F3F6), Color(0xFF0A0A0A), Color(0xFF333333), Color(0xFFB8BEC8))
private val DarkP = Palette(Color.Black, Color(0xFF1B1D21), Color.White, Color(0xFFDDDDDD), Color(0xFF4A4F57))

/** 기기마다 다른 부분 */
class UiHooks(
    val connect: (host: String, port: Int) -> Unit,
    val disconnect: () -> Unit,
    val canRecord: () -> Boolean = { true },
    val pttKeyHint: String? = null,                          // PC: "스페이스바"
    val showVolumeKeyOption: Boolean = false,                // 안드로이드: 볼륨 아래 버튼으로 말하기
    val keepScreenOn: ((Boolean) -> Unit)? = null,           // 안드로이드: 화면 켜 둔 채로
    val extraSettings: @Composable ColumnScope.(Palette) -> Unit = {},   // PC: 소리 장치
)

/** 채널 슬롯 (무전기 채널 메모리처럼): 이름·번호·개인호출·타임슬롯 — 원하는 만큼 추가 */
@Serializable
data class ChannelSlot(val name: String, val tg: Int, val priv: Boolean, val ts: Int,
                       val group: String = "", val fav: Boolean = false)   // 그룹(선택)·즐겨찾기 ★

fun Modifier.typing(m: UiModel) = onFocusChanged { m.typing = it.isFocused }

/** 화면 상태 (기기의 키 입력 처리에서도 씀) */
class UiModel(private val store: KeyValueStore, val radio: Radio, val hooks: UiHooks) {
    var tab by mutableIntStateOf(if (store.getString("host", "").isBlank()) 3 else 0)   // 0 말하기 1 슬롯 2 채널 3 설정
    var typing by mutableStateOf(false)      // 글자 입력 칸에 커서가 있음 → 스페이스바를 말하기로 쓰지 않음
    var wide by mutableStateOf(false)        // 넓은 화면 배치 (PC·태블릿·폴더블): 같은 메뉴를 왼쪽 세로 줄로
    val slots = mutableStateListOf<ChannelSlot>().apply { addAll(loadSlots()) }
    var slotFilter by mutableStateOf("")     // 슬롯 걸러 보기: "" 전체, "★" 즐겨찾기, 그룹 이름
    var slotCompact by mutableStateOf(store.getBoolean("slot_compact", false))   // 간단히 보기 (한 줄)

    private fun loadSlots(): List<ChannelSlot> {
        val js = store.getString("slots", "")
        if (js.isNotBlank()) try { return Json { ignoreUnknownKeys = true }.decodeFromString<List<ChannelSlot>>(js) } catch (_: Exception) {}
        return listOf(ChannelSlot("한국", 450, false, 2), ChannelSlot("전세계", 91, false, 2), ChannelSlot("에코 시험", 9990, true, 2),
            ChannelSlot("한국 에코", 450997, true, 2), ChannelSlot("연결 해제", 4000, false, 2))
    }
    fun saveSlots() = store.put("slots", Json.encodeToString(slots.toList()))

    /** 슬롯 누르기: 타임슬롯이 다르면 먼저 바꾸고 통화 대상 변경 */
    fun applySlot(c: ChannelSlot) {
        val s = radio.state.value
        if (s.ts != 0 && s.ts != c.ts) radio.changeSlot(c.ts)?.let { toast = it; return }
        radio.changeTarget("${c.tg}" + if (c.priv) "#" else "")?.let { toast = it }
    }
    var toast by mutableStateOf("")
    var textScale by mutableFloatStateOf(store.getFloat("text_scale", 1.15f))   // 1.0 보통 / 1.15 크게 / 1.35 아주 크게
    var themeMode by mutableIntStateOf(store.getInt("theme_mode", 0))             // 0 자동 / 1 밝게 / 2 어둡게
    var toggleMode by mutableStateOf(store.getBoolean("toggle_ptt", false))
    var volumeKeyPtt by mutableStateOf(store.getBoolean("volume_key_ptt", false))

    fun pttPress() {
        val on = if (toggleMode) !radio.state.value.tx else true
        radio.setPtt(on, hooks.canRecord())?.let { toast = it }
    }

    fun pttRelease() {
        if (!toggleMode) radio.setPtt(false)
    }

    fun save(key: String, v: Any) = store.put(key, v)
    fun str(key: String, def: String = "") = store.getString(key, def)
    fun int(key: String, def: Int) = store.getInt(key, def)
    fun bool(key: String, def: Boolean) = store.getBoolean(key, def)
}

private fun targetText(s: RadioState) = when {
    s.tg <= 0 -> "아직 모름"
    s.intercomTg > 0 && s.tg == s.intercomTg -> "🔁 내부 ${s.tg}"
    else -> "${if (s.priv) "개인" else "TG"} ${s.tg}"
}

/** 지금 통화 대상과 같은 슬롯의 이름 (없으면 "") — 위쪽에 "한국 · TG 450"처럼 이름을 함께 보여 줌 */
private fun slotName(m: UiModel, s: RadioState) =
    m.slots.firstOrNull { it.tg == s.tg && it.priv == s.priv }?.name?.trim().orEmpty()

/** 화면 전체: 위쪽 상태 + 탭 3개 (말하기·채널·설정) */
@Composable
fun App(m: UiModel, platform: PlatformInfo) {
    val dark = when (m.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkP else LightP
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = Blue, onPrimary = Color.White, background = p.bg, surface = p.bg, onBackground = p.text, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.sub, outline = p.line, secondaryContainer = Blue, onSecondaryContainer = Color.White)
    val d = LocalDensity.current
    // 글자 크기: 기기 글꼴 크기 설정 위에 앱 배율을 곱함
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * m.textScale)) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = p.bg) { Screen(m, p, platform) }
        }
    }
}

private val TABS = listOf("🎙" to "말하기", "🗂" to "슬롯", "📻" to "채널", "⚙" to "설정")

@Composable
private fun Screen(m: UiModel, p: Palette, platform: PlatformInfo) {
    val s by m.radio.state.collectAsState()
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(250) } }
    LaunchedEffect(m.toast) { if (m.toast.isNotEmpty()) { delay(4000); m.toast = "" } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 넓은 배치(왼쪽 말하기 | 오른쪽 탭): PC 창, 태블릿, 폴더블 펼친 화면(Galaxy Z Fold 안쪽 ≈ 860dp)
        val wide = maxWidth >= 700.dp
        SideEffect { m.wide = wide }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = p.bg,
            topBar = { StatusBar(m, s, p) },
            bottomBar = {
                if (!wide) NavigationBar(containerColor = p.card, tonalElevation = 0.dp) {
                    TABS.forEachIndexed { i, (ic, label) ->
                        NavigationBarItem(
                            selected = m.tab == i, onClick = { m.tab = i },
                            icon = { Text(ic, fontSize = 26.sp) },
                            label = { Text(label, fontSize = 17.sp, fontWeight = if (m.tab == i) FontWeight.Bold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = p.text, unselectedTextColor = p.sub, indicatorColor = Blue.copy(alpha = 0.25f)))
                    }
                }
            },
        ) { pad ->
            if (wide) {
                // 넓은 화면: 일반 화면과 같은 메뉴 4개(말하기·슬롯·채널·설정)를 왼쪽 세로 줄로, 고른 화면은 오른쪽에 넓게,
                // 맨 아래는 어느 메뉴에서나 누를 수 있는 가로로 긴 말하기 버튼
                Row(Modifier.padding(pad).fillMaxSize()) {
                    NavigationRail(containerColor = p.card, modifier = Modifier.fillMaxHeight().width(112.dp)) {
                        Spacer(Modifier.height(8.dp))
                        TABS.forEachIndexed { i, (ic, label) ->
                            NavigationRailItem(
                                selected = m.tab == i, onClick = { m.tab = i },
                                icon = { Text(ic, fontSize = 30.sp) },
                                label = { Text(label, fontSize = 18.sp, fontWeight = if (m.tab == i) FontWeight.Bold else FontWeight.Normal) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedTextColor = p.text, unselectedTextColor = p.sub, indicatorColor = Blue.copy(alpha = 0.25f)),
                                modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(p.line))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (m.tab) {
                                0 -> TalkTab(m, s, now, p, withPtt = false)
                                1 -> SlotTab(m, s, p, tiles = true)
                                2 -> ChannelTab(m, s, p)
                                else -> SettingsTab(m, s, p, platform)
                            }
                        }
                        Box(Modifier.fillMaxWidth().background(p.line).height(1.dp))
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            PttButton(m, s, p, Modifier.fillMaxWidth().height(120.dp), inline = true)
                        }
                    }
                }
            } else {
                Box(Modifier.padding(pad).fillMaxSize()) { TabContent(m, s, now, p, platform, m.tab) }
            }
        }
        Toast(m.toast, p, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))   // 위쪽 상태 줄 바로 아래 — 말하기 버튼을 가리지 않게
    }
}

/** 알림 팝업: 둥근 카드 + 그림자, 위에서 내려왔다 사라짐. 내용에 따라 ✅ 완료 / ⚠️ 주의 / ℹ️ 안내 */
@Composable
private fun Toast(text: String, p: Palette, modifier: Modifier) {
    var last by remember { mutableStateOf("") }
    if (text.isNotEmpty()) last = text
    val warn = listOf("없습니다", "않아", "못", "실패", "넣어 주세요", "바꿀 수 없", "권한", "멈췄").any { it in last }
    val ok = listOf("저장했습니다", "완료", "바꿨습니다").any { it in last }
    val (icon, accent) = when { warn -> "⚠️" to Amber; ok -> "✅" to Green; else -> "ℹ️" to Blue }
    AnimatedVisibility(visible = text.isNotEmpty(), modifier = modifier.padding(horizontal = 16.dp),
        enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
        Row(Modifier.widthIn(max = 560.dp).shadow(14.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(p.card).border(BorderStroke(1.dp, p.line), RoundedCornerShape(18.dp)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(8.dp).heightIn(min = 72.dp).background(accent))
            Text(icon, fontSize = 28.sp, modifier = Modifier.padding(start = 16.dp, end = 12.dp))
            Text(last, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text, lineHeight = 26.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp, end = 20.dp))
        }
    }
}

@Composable
private fun TabContent(m: UiModel, s: RadioState, now: Long, p: Palette, platform: PlatformInfo, tab: Int) {
    when (tab) {
        0 -> TalkTab(m, s, now, p)
        1 -> SlotTab(m, s, p)
        2 -> ChannelTab(m, s, p)
        else -> SettingsTab(m, s, p, platform)
    }
}

// ---------------- 위쪽 상태 (모든 탭): 연결 상태 + 통화 대상을 크게 ----------------
@Composable
private fun StatusBar(m: UiModel, s: RadioState, p: Palette) {
    val (ct, cc) = when (s.conn) {
        UsrpClient.State.OFF -> "연결 안 됨" to Gray
        UsrpClient.State.RESOLVING, UsrpClient.State.WAITING -> "연결하는 중…" to Amber
        UsrpClient.State.ONLINE -> "연결됨" to Green
        UsrpClient.State.NO_REPLY -> "서버 응답 없음" to Red
    }
    val name = if (s.tg > 0) slotName(m, s) else ""
    Column(Modifier.fillMaxWidth().background(p.card).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(cc))
            Spacer(Modifier.width(10.dp))
            Text(ct, color = p.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            if (m.wide) {
                // 넓은 화면: 지금 채널을 오른쪽에 크게 (이름 · 번호 · 타임슬롯)
                if (s.tg > 0) Text(listOf(name, targetText(s), if (s.ts > 0) "타임슬롯 ${s.ts}" else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                    color = p.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (s.tg > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(targetText(s), color = p.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (name.isNotEmpty()) Text(name, color = p.sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (s.message.isNotEmpty()) Text(s.message, color = Amber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- 말하기 ----------------
@Composable
private fun TalkTab(m: UiModel, s: RadioState, now: Long, p: Palette, withPtt: Boolean = true) {
    val online = s.conn == UsrpClient.State.ONLINE
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val (bt, bg, fg) = when {
            s.tx -> Triple("🔴 말하는 중  ${(now - s.txSince) / 1000}초", Red, Color.White)
            s.rx -> Triple("🔊 듣는 중  ${(now - s.rxSince) / 1000}초", Green, Color.White)
            online -> Triple("조용함 · 기다리는 중", p.card, p.text)
            else -> Triple("연결 안 됨 → 설정 메뉴에서 연결", p.card, p.text)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(bt, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = fg)
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (s.rx) "지금 말하는 사람" else "마지막으로 들은 사람", fontSize = 17.sp, color = p.sub, fontWeight = FontWeight.Bold)
            val pr = s.peer
            when {
                pr == null -> Text("아직 없음", fontSize = 26.sp, color = p.text)
                pr.notice -> {
                    Text("📢 브릿지 공지", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                    val kind = when (pr.source) { "tts" -> "음성 합성"; "live" -> "실시간 마이크"; "schedule" -> "예약 공지"; else -> "음성 공지" }
                    Text(kind + if (pr.resume) " (이어서)" else "", fontSize = 19.sp, color = p.sub)
                    if (pr.name.isNotEmpty()) Text(pr.name, fontSize = 22.sp, color = p.text, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Text(pr.call.ifEmpty { pr.src.toString() }, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = p.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val who = listOf(pr.name, pr.loc).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (who.isNotEmpty()) Text(who, fontSize = 22.sp, color = p.text)
                    if (pr.alias.isNotEmpty()) Text("「${pr.alias}」", fontSize = 19.sp, color = p.text, fontWeight = FontWeight.Bold)
                    if (pr.intercom) Text("🔁 내부 채널 · BM으로 안 나감", fontSize = 19.sp, color = Green, fontWeight = FontWeight.Bold)
                    Text("${if (pr.priv) "개인" else "TG"} ${pr.dst} · 타임슬롯 ${pr.slot} · ID ${pr.src}", fontSize = 17.sp, color = p.sub)
                }
            }
        }

        if (s.recent.size > 1) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("최근", fontSize = 16.sp, color = p.sub, fontWeight = FontWeight.Bold)
                s.recent.drop(if (s.rx) 1 else 0).take(if (withPtt) 3 else 10).forEach {   // 넓은 화면은 왼쪽 칸을 최근 목록으로 채움
                    Text(it, fontSize = 17.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (withPtt) {
            // 휴대폰 배치: 말하기 버튼이 남는 세로 공간을 채움 (최소 190dp)
            Spacer(Modifier.weight(1f))
            PttButton(m, s, p, Modifier.fillMaxWidth().heightIn(min = 190.dp))
        }
    }
}

/** 말하기 버튼 + 마이크 크기 막대. 휴대폰은 말하기 화면 아래, 넓은 화면은 양쪽 칸 아래에 가로로 길게 */
@Composable
private fun PttButton(m: UiModel, s: RadioState, p: Palette, modifier: Modifier, inline: Boolean = false) {
    val online = s.conn == UsrpClient.State.ONLINE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pttColor = if (s.tx) Red else if (online) Blue else Gray
        Box(
            modifier.clip(RoundedCornerShape(28.dp)).background(pttColor)
                .pointerInput(m.toggleMode) {
                    detectTapGestures(onPress = {
                        m.pttPress()
                        tryAwaitRelease()
                        m.pttRelease()
                    })
                }.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // inline = 가로로 긴 버튼(넓은 화면): 아이콘과 글자를 한 줄로
            val br = if (inline) "  " else "\n"
            val hint = m.hooks.pttKeyHint?.let { "$br($it)" } ?: ""
            val label = when {
                s.tx && m.toggleMode -> "말하는 중${br}다시 누르면 끝"
                s.tx -> "말하는 중${br}손을 떼면 끝"
                !online -> "연결 안 됨"
                m.toggleMode -> "눌러서 말하기$hint"
                else -> "누르고 말하기$hint"
            }
            val icon = if (s.tx) "🔴" else "🎙"
            if (inline) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(icon, fontSize = 44.sp)
                Text(label, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 40.sp)
                Text(label, style = TextStyle(fontSize = 30.sp, lineHeight = 40.sp), fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center, color = Color.White)
            }
        }
        if (s.tx) LinearProgressIndicator(progress = { s.micLevel.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp),
            color = Red, trackColor = p.card)
    }
}

// ---------------- 채널 ----------------
@Composable
private fun ChannelTab(m: UiModel, s: RadioState, p: Palette) {
    val focus = LocalFocusManager.current
    var tg by remember { mutableStateOf(m.str("tg")) }
    var priv by remember { mutableStateOf(m.bool("priv", false)) }
    fun change(code: String) {
        focus.clearFocus()
        m.radio.changeTarget(code)?.let { m.toast = it }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(16.dp)) {
            Text("지금 통화 대상", fontSize = 17.sp, color = p.sub, fontWeight = FontWeight.Bold)
            Text(targetText(s) + if (s.ts > 0) "   타임슬롯 ${s.ts}" else "", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
        }
        if (s.intercomTg > 0) {
            val cur = s.tg == s.intercomTg
            Column(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(14.dp))
                .background(if (cur) Blue else p.card).border(BorderStroke(2.dp, if (cur) Blue else p.line), RoundedCornerShape(14.dp))
                .clickable { change(s.intercomTg.toString()) }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("🔁 ${s.intercomTg} 내부 채널" + if (cur) " ✓" else "", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (cur) Color.White else p.text)
                Text("같은 브릿지 사용자끼리 · BM으로 안 나감", fontSize = 16.sp, color = if (cur) Color.White else p.sub)
            }
        }
        Text("자주 쓰는 채널은 '슬롯' 탭에서 원하는 만큼 추가할 수 있습니다", fontSize = 16.sp, color = p.sub)
        Title("번호로 바꾸기", p)
        OutlinedTextField(tg, { v -> tg = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth().typing(m), singleLine = true,
            textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.text),
            label = { Text("TG 또는 DMR ID", fontSize = 17.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(priv, { priv = it })
            Spacer(Modifier.width(10.dp))
            Text("개인호출 (한 사람에게)", fontSize = 19.sp, color = p.text)
        }
        BigButton("이 번호로 바꾸기") {
            if (tg.isNotEmpty()) {
                m.save("tg", tg); m.save("priv", priv)
                change(tg + if (priv) "#" else "")
            }
        }
        Title("타임슬롯", p)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(1, 2).forEach { ts ->
                val cur = s.ts == ts
                Box(Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(if (cur) Blue else p.card)
                    .border(BorderStroke(2.dp, if (cur) Blue else p.line), RoundedCornerShape(14.dp))
                    .clickable { m.radio.changeSlot(ts)?.let { m.toast = it } }, contentAlignment = Alignment.Center) {
                    Text("타임슬롯 $ts" + if (cur) " ✓" else "", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = if (cur) Color.White else p.text)
                }
            }
        }
    }
}

// ---------------- 슬롯 (채널 메모리, 무한 추가 · 많아져도 보기 쉽게: ★즐겨찾기·그룹·찾기·간단히 보기) ----------------
@Composable
private fun SlotTab(m: UiModel, s: RadioState, p: Palette, tiles: Boolean = false) {
    var editing by remember { mutableIntStateOf(-1) }
    var editMode by remember { mutableStateOf(false) }      // 무전기형 타일: 고치기 모드 (누르면 고치기 창)      // -1 없음, -2 새 슬롯, 0.. 편집 중인 슬롯(원래 목록 위치)
    var query by remember { mutableStateOf("") }
    val groups = m.slots.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    val hasFav = m.slots.any { it.fav }
    // 걸러 보기: "" 전체, "★" 즐겨찾기, 그 밖 = 그룹 이름 (없어진 그룹이면 전체로)
    val filter = m.slotFilter.takeIf { it.isEmpty() || (it == "★" && hasFav) || it in groups } ?: ""
    val shown = m.slots.withIndex().filter { (_, c) ->
        (filter.isEmpty() || (filter == "★" && c.fav) || c.group.trim() == filter) &&
        (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true) || c.tg.toString().contains(query.trim()))
    }.sortedByDescending { it.value.fav }     // 즐겨찾기를 위로 (같은 안에서는 원래 순서)

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val paneWidth = maxWidth
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(if (tiles) "채널" else "채널 슬롯  (${m.slots.size}개)", p)
            Spacer(Modifier.weight(1f))
            if (tiles && editing == -1) {
                // 고치기 모드 켜고 끄기
                Box(Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(if (editMode) Amber else p.card)
                    .border(BorderStroke(2.dp, if (editMode) Amber else p.line), RoundedCornerShape(12.dp))
                    .clickable { editMode = !editMode }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(if (editMode) "✓ 고치기 끝" else "✎ 고치기", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (editMode) Color.White else p.text)
                }
                Spacer(Modifier.width(10.dp))
            }
            if (editing == -1) Button(onClick = { editing = -2 }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)) { Text("＋ 추가", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        }
        if (editing == -2) SlotEditor(m, null, p, groups, onDone = { c -> if (c != null) { m.slots.add(c); m.saveSlots() }; editing = -1 })

        // 걸러 보기 버튼: 전체 / ★ / 그룹들 (가로로 넘기기)
        if (groups.isNotEmpty() || hasFav) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("" to "전체") + (if (hasFav) listOf("★" to "★ 즐겨찾기") else emptyList()) + groups.map { it to it }).forEach { (v, label) ->
                    val cur = v == filter
                    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (cur) Blue else p.card)
                        .border(BorderStroke(2.dp, if (cur) Blue else p.line), RoundedCornerShape(24.dp))
                        .clickable { m.slotFilter = v }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (cur) Color.White else p.text)
                    }
                }
            }
        }
        // 많아지면 찾기 칸
        if (m.slots.size > 6) OutlinedTextField(query, { query = it.take(20) }, Modifier.fillMaxWidth().typing(m), singleLine = true,
            textStyle = TextStyle(fontSize = 20.sp, color = p.text), label = { Text("🔍 이름 또는 번호로 찾기") })
        if (tiles && editMode && editing == -1) Text("고칠 버튼을 누르세요", fontSize = 18.sp, color = Amber, fontWeight = FontWeight.Bold)
        // 보기 방식 (무전기형 타일에는 없음)
        if (!tiles) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("보기", fontSize = 17.sp, color = p.sub)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { Choice(listOf("크게" to false, "간단히" to true), m.slotCompact, p) { m.slotCompact = it; m.save("slot_compact", it) } }
        }

        if (m.slots.isEmpty() && editing == -1) Text("슬롯이 없습니다. '＋ 추가'를 눌러 만드세요.", fontSize = 19.sp, color = p.text)
        else if (shown.isEmpty()) Text("맞는 슬롯이 없습니다", fontSize = 19.sp, color = p.text)

        if (tiles) {
            // 무전기형: 채널 버튼 타일 (칸 너비에 맞춰 2~5개씩), 끝에 '＋ 번호로'
            val ed = shown.firstOrNull { it.index == editing }
            if (ed != null) {
                val i = ed.index
                SlotEditor(m, ed.value, p, groups, onDone = { n -> if (n != null) { m.slots[i] = n; m.saveSlots() }; editing = -1 },
                    onUp = if (i > 0) ({ m.slots.add(i - 1, m.slots.removeAt(i)); m.saveSlots(); editing = i - 1 }) else null,
                    onDown = if (i < m.slots.size - 1) ({ m.slots.add(i + 1, m.slots.removeAt(i)); m.saveSlots(); editing = i + 1 }) else null,
                    onDelete = { m.slots.removeAt(i); m.saveSlots(); editing = -1 })
            }
            val tcols = (paneWidth.value / 190f).toInt().coerceIn(2, 5)
            val cells: List<IndexedValue<ChannelSlot>?> = shown + listOf(null)   // null = '＋ 번호로'
            cells.chunked(tcols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { cell ->
                        Box(Modifier.weight(1f)) {
                            if (cell == null) NumberTile(p) { m.tab = 2 }   // 채널 메뉴(번호로 바꾸기)
                            else SlotTile(m, s, cell.value, p, editMode) {
                                if (editMode) editing = cell.index else m.applySlot(cell.value)
                            }
                        }
                    }
                    repeat(tcols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            return@Column
        }
        // 두 줄은 칸이 충분히 넓을 때만 (좁으면 한 줄 — 번호·타임슬롯이 잘리지 않게)
        val cols = if (paneWidth >= 640.dp) 2 else 1
        shown.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (i, c) ->
                    Box(Modifier.weight(1f)) {
                        if (editing == i) {
                            SlotEditor(m, c, p, groups, onDone = { n -> if (n != null) { m.slots[i] = n; m.saveSlots() }; editing = -1 },
                                onUp = if (i > 0) ({ m.slots.add(i - 1, m.slots.removeAt(i)); m.saveSlots(); editing = i - 1 }) else null,
                                onDown = if (i < m.slots.size - 1) ({ m.slots.add(i + 1, m.slots.removeAt(i)); m.saveSlots(); editing = i + 1 }) else null,
                                onDelete = { m.slots.removeAt(i); m.saveSlots(); editing = -1 })
                        } else SlotCard(m, s, c, p, onFav = { m.slots[i] = c.copy(fav = !c.fav); m.saveSlots() }, onEdit = { editing = i })
                    }
                }
                if (row.size < cols) Spacer(Modifier.weight(1f))
            }
        }
    }
    }
}

/** 무전기형 채널 버튼: 이름 크게, 번호·타임슬롯 작게. 지금 채널은 파랑, 즐겨찾기는 ★ */
@Composable
private fun SlotTile(m: UiModel, s: RadioState, c: ChannelSlot, p: Palette, editMode: Boolean, onClick: () -> Unit) {
    val cur = s.tg == c.tg && s.priv == c.priv && (s.ts == 0 || s.ts == c.ts)
    val fg = if (cur) Color.White else p.text
    val sub = if (cur) Color.White.copy(alpha = 0.85f) else p.sub
    Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(16.dp)).background(if (cur) Blue else p.card)
        .border(BorderStroke(2.dp, if (editMode) Amber else if (cur) Blue else p.line), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name.ifBlank { "${c.tg}" }, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (c.fav) Text("★", fontSize = 20.sp, color = if (cur) Color.White else Color(0xFFF2B300))
            if (editMode) Text(" ✎", fontSize = 20.sp, color = fg)
        }
        Text("${if (c.priv) "개인" else "TG"} ${c.tg}", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
        Text("타임슬롯 ${c.ts}" + (if (c.group.isNotBlank()) " · ${c.group}" else "") + (if (cur) "  ✓ 지금" else ""),
            fontSize = 15.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** '＋ 번호로' 타일: 번호를 직접 넣어 바꾸는 화면 열기 */
@Composable
private fun NumberTile(p: Palette, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(16.dp)).background(p.bg)
        .border(BorderStroke(2.dp, p.line), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("＋", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Blue)
        Text("번호로", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text)
    }
}

@Composable
private fun SlotCard(m: UiModel, s: RadioState, c: ChannelSlot, p: Palette, onFav: () -> Unit, onEdit: () -> Unit) {
    val cur = s.tg == c.tg && s.priv == c.priv && (s.ts == 0 || s.ts == c.ts)
    val fg = if (cur) Color.White else p.text
    val sub = if (cur) Color.White else p.sub
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (cur) Blue else p.card)
        .border(BorderStroke(2.dp, if (cur) Blue else p.line), RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
        SmallBtn(if (c.fav) "★" else "☆", if (c.fav) Color(0xFFF2B300) else sub, onClick = onFav)
        Column(Modifier.weight(1f).clickable { m.applySlot(c) }.padding(vertical = if (m.slotCompact) 8.dp else 14.dp)) {
            val title = c.name.ifBlank { "${c.tg}" } + if (cur) "  ✓" else ""
            if (m.slotCompact) {
                // 간단히: 한 줄 (이름 · 번호)
                Text("$title  ·  ${if (c.priv) "개인" else "TG"} ${c.tg}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${if (c.priv) "개인" else "TG"} ${c.tg} · 타임슬롯 ${c.ts}" + if (c.group.isNotBlank()) " · ${c.group}" else "",
                    fontSize = 18.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SmallBtn(if (m.slotCompact || m.wide) "✎" else "✎ 고치기", fg, onClick = onEdit)   // 넓은 배치에서는 글자 자리 확보
    }
}

@Composable
private fun SmallBtn(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 52.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onClick() }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (enabled) color else color.copy(alpha = 0.25f))
    }
}

@Composable
private fun SlotEditor(m: UiModel, c: ChannelSlot?, p: Palette, groups: List<String>, onDone: (ChannelSlot?) -> Unit,
                       onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    var confirmDel by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(c?.name ?: "") }
    var num by remember { mutableStateOf(c?.tg?.toString() ?: "") }
    var priv by remember { mutableStateOf(c?.priv ?: false) }
    var ts by remember { mutableIntStateOf(c?.ts ?: 2) }
    var group by remember { mutableStateOf(c?.group ?: m.slotFilter.takeIf { it.isNotEmpty() && it != "★" } ?: "") }
    val field = TextStyle(fontSize = 21.sp, color = p.text)
    Card(p) {
        Title(if (c == null) "새 슬롯" else "슬롯 고치기", p)
        OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth().typing(m), singleLine = true, textStyle = field,
            label = { Text("이름 (예: 우리 동네)") })
        OutlinedTextField(num, { v -> num = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth().typing(m), singleLine = true, textStyle = field,
            label = { Text("TG 또는 DMR ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(priv, { priv = it })
            Spacer(Modifier.width(10.dp))
            Text("개인호출 (한 사람에게)", fontSize = 19.sp, color = p.text)
        }
        Text("타임슬롯 (DMR 통화 길)", fontSize = 18.sp, color = p.text)
        Choice(listOf("타임슬롯 1" to 1, "타임슬롯 2" to 2), ts, p) { ts = it }
        Text("잘 모르면 '타임슬롯 2'를 고르세요 (대부분의 핫스팟이 2를 씀)", fontSize = 15.sp, color = p.sub)
        OutlinedTextField(group, { group = it.take(12) }, Modifier.fillMaxWidth().typing(m), singleLine = true, textStyle = field,
            label = { Text("그룹 (선택, 예: 지역·에코·동호회)") })
        if (groups.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { g ->
                Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (g == group) Blue else p.bg)
                    .border(BorderStroke(2.dp, if (g == group) Blue else p.line), RoundedCornerShape(22.dp))
                    .clickable { group = g }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(g, fontSize = 17.sp, color = if (g == group) Color.White else p.text)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                BigButton("저장") {
                    val tg = num.toIntOrNull()
                    if (tg == null || tg <= 0 || tg > 16777215) m.toast = "번호를 넣어 주세요"
                    else onDone(ChannelSlot(name.trim(), tg, priv, ts, group.trim(), c?.fav ?: false))
                }
            }
            Box(Modifier.weight(1f)) { BigButton("취소", color = Gray) { onDone(null) } }
        }
        if (onDelete != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { OutlinedButton(onClick = { onUp?.invoke() }, enabled = onUp != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("▲ 위로", fontSize = 18.sp) } }
                Box(Modifier.weight(1f)) { OutlinedButton(onClick = { onDown?.invoke() }, enabled = onDown != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("▼ 아래로", fontSize = 18.sp) } }
            }
            // 실수로 지우지 않게 두 번 누름
            BigButton(if (confirmDel) "정말 지울까요? 한 번 더 누르면 삭제" else "🗑 이 슬롯 지우기", color = Red) {
                if (confirmDel) onDelete() else confirmDel = true
            }
        }
    }
}

// ---------------- 설정 ----------------
@Composable
private fun SettingsTab(m: UiModel, s: RadioState, p: Palette, platform: PlatformInfo) {
    val focus = LocalFocusManager.current
    val off = s.conn == UsrpClient.State.OFF
    var host by remember { mutableStateOf(m.str("host")) }
    var port by remember { mutableStateOf(m.int("port", 32001).toString()) }
    var call by remember { mutableStateOf(m.str("callsign")) }
    var dmrId by remember { mutableStateOf(m.str("dmr_id")) }
    var name by remember { mutableStateOf(m.str("name")) }
    var device by remember { mutableStateOf(m.str("device")) }
    var volume by remember { mutableFloatStateOf(m.int("volume", 100) / 100f) }
    var gain by remember { mutableFloatStateOf(m.int("mic_gain", 100) / 100f) }
    var keepOn by remember { mutableStateOf(m.bool("keep_screen_on", false)) }
    var showLog by remember { mutableStateOf(false) }
    val field = TextStyle(fontSize = 21.sp, color = p.text)
    fun saveMe() {
        m.save("callsign", call.trim().uppercase()); m.save("dmr_id", dmrId)
        m.save("name", name.trim()); m.save("device", device.trim())
        m.radio.refreshMyInfo()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(p) {
            Title("보기", p)
            Text("글자 크기", fontSize = 18.sp, color = p.text)
            Choice(listOf("보통" to 1.0f, "크게" to 1.15f, "아주 크게" to 1.35f), m.textScale, p) { m.textScale = it; m.save("text_scale", it) }
            Text("화면 밝기", fontSize = 18.sp, color = p.text)
            Choice(listOf("자동" to 0, "밝게" to 1, "어둡게" to 2), m.themeMode, p) { m.themeMode = it; m.save("theme_mode", it) }
        }

        Card(p) {
            Title("서버 (DVBridge가 켜진 PC)", p)
            OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth().typing(m), enabled = off, singleLine = true, textStyle = field,
                label = { Text("주소") }, placeholder = { Text("예: 192.168.0.10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { v -> port = v.filter { it.isDigit() }.take(5) }, Modifier.fillMaxWidth().typing(m), enabled = off, singleLine = true,
                textStyle = field, label = { Text("포트") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            BigButton(if (off) "연결" else "연결 끊기", color = if (off) Blue else Red) {
                focus.clearFocus()
                if (!off) m.hooks.disconnect()
                else if (host.isBlank()) m.toast = "DVBridge 프로그램 화면에 나오는 PC 주소를 넣어 주세요"
                else {
                    val pt = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 32001
                    m.save("host", host); m.save("port", pt)
                    saveMe()
                    m.hooks.connect(host, pt)
                    m.tab = 0
                }
            }
        }

        Card(p) {
            Title("내 정보 (서버로 보냄)", p)
            OutlinedTextField(call, { call = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }, Modifier.fillMaxWidth().typing(m), singleLine = true,
                textStyle = field, label = { Text("콜사인") }, placeholder = { Text("예: HL0ABC") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            OutlinedTextField(dmrId, { v -> dmrId = v.filter { it.isDigit() }.take(8) }, Modifier.fillMaxWidth().typing(m), singleLine = true,
                textStyle = field, label = { Text("DMR ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth().typing(m), singleLine = true, textStyle = field, label = { Text("이름") })
            OutlinedTextField(device, { device = it.take(30) }, Modifier.fillMaxWidth().typing(m), singleLine = true, textStyle = field,
                label = { Text("장비명 (비우면 자동)") }, placeholder = { Text(platform.defaultDevice) })
            val dev = device.trim().ifEmpty { platform.defaultDevice }
            Text("상대 무전기 이름표: ${NamePreview.alias(call, dev)}", fontSize = 17.sp, color = p.text)
            Text("BM 기기 위치: ${NamePreview.location(dev)}", fontSize = 17.sp, color = p.text)
            Text("송신 콜사인·DMR ID는 서버 설정 값으로 나갑니다", fontSize = 15.sp, color = p.sub)
            BigButton("내 정보 저장") { focus.clearFocus(); saveMe(); m.toast = "내 정보를 저장했습니다" }
        }

        Card(p) {
            Title("말하기·소리", p)
            CheckRow("한 번 누르면 켜지고, 다시 누르면 꺼지게", m.toggleMode, p) { m.toggleMode = it; m.save("toggle_ptt", it) }
            if (m.hooks.showVolumeKeyOption)
                CheckRow("볼륨 아래(-) 버튼으로 말하기 (앱 화면이 열려 있을 때)", m.volumeKeyPtt, p) { m.volumeKeyPtt = it; m.save("volume_key_ptt", it) }
            m.hooks.keepScreenOn?.let { apply ->
                CheckRow("화면 켜 둔 채로 두기", keepOn, p) { keepOn = it; m.save("keep_screen_on", it); apply(it) }
            }
            Text("듣는 소리 크기  ${(volume * 100).toInt()}%", fontSize = 18.sp, color = p.text)
            Slider(volume, { volume = it; m.radio.setVolume(it) }, valueRange = 0f..2f,
                onValueChangeFinished = { m.save("volume", (volume * 100).toInt()) })
            Text("마이크 크기  ${(gain * 100).toInt()}%", fontSize = 18.sp, color = p.text)
            Slider(gain, { gain = it; m.radio.setMicGain(it) }, valueRange = 0.1f..4f,
                onValueChangeFinished = { m.save("mic_gain", (gain * 100).toInt()) })
            OutlinedButton(onClick = { m.radio.playTestTone() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("스피커 시험 (삐- 소리)", fontSize = 18.sp, color = p.text)
            }
        }

        m.hooks.extraSettings(this, p)

        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "기록 숨기기" else "기록 보기", fontSize = 18.sp) }
        if (showLog) Text(s.log.takeLast(40).joinToString("\n"), fontSize = 13.sp, lineHeight = 17.sp, color = p.text)
        Text("DVBridge 클라이언트 $APP_VERSION\n제작자: 6K2KZR 최승훈 · MIT 라이선스\n음성 변환은 DVBridge 서버가 하므로 이 앱에는 보코더가 없습니다.\n\n사용한 오픈소스 (Apache License 2.0): Kotlin, kotlinx.coroutines, kotlinx.serialization, Compose Multiplatform, AndroidX, Guava listenablefuture\nhttps://www.apache.org/licenses/LICENSE-2.0",
            fontSize = 14.sp, color = p.sub)
    }
}

// ---------------- 공통 조각 ----------------
@Composable
fun Card(p: Palette, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun Title(text: String, p: Palette) = Text(text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = p.text)

@Composable
fun BigButton(text: String, color: Color = Blue, onClick: () -> Unit) {
    Button(onClick = onClick, Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun <T> Choice(items: List<Pair<String, T>>, value: T, p: Palette, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, v) ->
            val cur = v == value
            Box(Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).background(if (cur) Blue else p.bg)
                .border(BorderStroke(2.dp, if (cur) Blue else p.line), RoundedCornerShape(12.dp))
                .clickable { onPick(v) }.padding(6.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (cur) Color.White else p.text,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, p: Palette, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, fontSize = 18.sp, color = p.text)
    }
}
