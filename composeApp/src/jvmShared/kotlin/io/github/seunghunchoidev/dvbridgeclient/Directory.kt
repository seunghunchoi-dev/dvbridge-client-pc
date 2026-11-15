// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 주소록의 사람 한 명 (RadioID 공개 명단) */
@Serializable
data class DmrUser(val id: Int, val call: String, val name: String = "", val city: String = "")

/**
 * DMR 주소록 자동 받기 (공개 자료, 개인정보 보내지 않음):
 *  - 채널(토크그룹) 목록: BrandMeister 공개 API  https://api.brandmeister.network/v2/talkgroup  (번호 → 이름)
 *  - 사람 목록: RadioID.net 공개 API, 한국(Korea Republic of)만  https://radioid.net/api/dmr/user/
 * 일주일에 한 번 백그라운드로 받아 기기 안 파일로 저장. 받는 중 실패하면 예전 목록을 그대로 씀.
 */
class Directory(private val store: KeyValueStore, private val dir: File) {
    companion object {
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val BM_TG = "https://api.brandmeister.network/v2/talkgroup"
        private const val RID = "https://radioid.net/api/dmr/user/?country=Korea%20Republic%20of"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val tgFile = File(dir, "talkgroups.json")
    private val userFile = File(dir, "users_kr.json")
    @Volatile private var tgs: Map<Int, String> = loadTgs()
    @Volatile private var users: List<DmrUser> = loadUsers()
    @Volatile private var byId: Map<Int, DmrUser> = users.associateBy { it.id }
    @Volatile var busy = false; private set

    var onChanged: (() -> Unit)? = null

    val tgCount get() = tgs.size
    val userCount get() = users.size
    val updatedAt get() = store.getString("dir_updated", "")
    val auto get() = store.getBoolean("dir_auto", true)
    fun setAuto(on: Boolean) = store.put("dir_auto", on)

    fun tgName(tg: Int): String = tgs[tg].orEmpty()
    fun user(id: Int): DmrUser? = byId[id]

    /** 콜사인(앞부분)·이름·번호로 찾기, 최대 n개 */
    fun searchUsers(q: String, n: Int = 8): List<DmrUser> {
        val t = q.trim().uppercase()
        if (t.length < 2) return emptyList()
        return users.asSequence().filter {
            it.call.startsWith(t) || it.id.toString().startsWith(t) || it.name.uppercase().contains(t)
        }.take(n).toList()
    }

    /** 채널 이름이나 번호로 찾기, 최대 n개 */
    fun searchTgs(q: String, n: Int = 8): List<Pair<Int, String>> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        return tgs.entries.asSequence().filter { (k, v) -> k.toString().startsWith(t) || v.contains(t, ignoreCase = true) }
            .sortedBy { it.key.toString().length * 1_000_000L + it.key }.take(n).map { it.key to it.value }.toList()
    }

    /** 자동 받기가 켜져 있고 일주일이 지났으면 백그라운드로 받기 */
    fun refreshIfStale() {
        val last = store.getString("dir_updated_ms", "0").toLongOrNull() ?: 0
        if (auto && (System.currentTimeMillis() - last > WEEK_MS || tgs.isEmpty() || users.isEmpty())) refreshAsync()
    }

    /** 지금 받기 (결과 문구를 onDone으로) */
    fun refreshAsync(onDone: (String) -> Unit = {}) {
        if (busy) return
        busy = true; onChanged?.invoke()
        Thread {
            val msg = try {
                val t = downloadTgs(); val u = downloadUsers()
                tgs = t; users = u; byId = u.associateBy { it.id }
                tgFile.writeText(json.encodeToString(t.mapKeys { it.key.toString() }))
                userFile.writeText(json.encodeToString(u))
                store.put("dir_updated_ms", System.currentTimeMillis().toString())
                store.put("dir_updated", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(java.util.Date()))
                "주소록을 받았습니다 (채널 ${t.size}개, 사람 ${u.size}명)"
            } catch (e: Exception) { "주소록을 받지 못했습니다: ${e.message ?: e}" }
            busy = false; onChanged?.invoke(); onDone(msg)
        }.apply { isDaemon = true; name = "directory"; start() }
    }

    private fun downloadTgs(): Map<Int, String> {
        val o = json.parseToJsonElement(get(BM_TG)).jsonObject
        return o.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to (v.jsonPrimitive.contentOrNull ?: "") } }.toMap()
    }

    private fun downloadUsers(): List<DmrUser> {
        val out = ArrayList<DmrUser>()
        var page = 1; var pages = 1
        while (page <= pages && page <= 60) {
            val o = json.parseToJsonElement(get("$RID&page=$page")).jsonObject
            pages = o["pages"]?.jsonPrimitive?.intOrNull ?: 1
            o["results"]?.jsonArray?.forEach { r ->
                val x = r as? JsonObject ?: return@forEach
                val id = x["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val s = { k: String -> x[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim() }
                out += DmrUser(id, s("callsign").uppercase(), s("fname").ifEmpty { s("name") }, s("city"))
            }
            page++
            Thread.sleep(300)      // 공개 서버에 부담 주지 않게 천천히
        }
        if (out.isEmpty()) throw IllegalStateException("사람 목록이 비어 있음")
        return out
    }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000
        con.setRequestProperty("User-Agent", "DVBridge-Client/$APP_VERSION")
        con.setRequestProperty("Accept", "application/json")
        try {
            if (con.responseCode != 200) throw IllegalStateException("HTTP ${con.responseCode}")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { con.disconnect() }
    }

    private fun loadTgs(): Map<Int, String> = try {
        json.decodeFromString<Map<String, String>>(tgFile.readText()).mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun loadUsers(): List<DmrUser> = try { json.decodeFromString(userFile.readText()) } catch (_: Exception) { emptyList() }
}
