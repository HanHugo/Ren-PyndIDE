package com.renpy.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.renpy.mobile.i18n.t

/**
 * 在线更新检查：向分发站查询最新版本信息。
 * POST user/appid，返回 code/msg/edition/link/content（content 为 Markdown 公告）。
 */
object UpdateChecker {
    private const val ENDPOINT = "https://yuju.99kpk.top:81/api/update_obtain.php"
    private const val USER = "3501494838"
    private const val APPID = "501"

    data class UpdateInfo(
        val edition: String,
        val link: String,
        val content: String,
        val msg: String,
    )

    /** 请求更新信息。成功返回 UpdateInfo，失败抛异常。 */
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val body = "user=" + URLEncoder.encode(USER, "UTF-8") +
            "&appid=" + URLEncoder.encode(APPID, "UTF-8")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "RenPyndIDE-UpdateChecker/1.0")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                sb.toString()
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            val json = JSONObject(text)
            val resultCode = json.optString("code", "")
            if (resultCode != "1") {
                throw IllegalStateException(json.optString("msg", t("获取更新失败")).ifBlank { t("获取更新失败") })
            }
            UpdateInfo(
                edition = json.optString("edition", ""),
                link = json.optString("link", ""),
                content = json.optString("content", ""),
                msg = json.optString("msg", ""),
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 比较两个版本号，判断 remote 是否比 local 新。
     * 按 "." 分段逐段数字比较，忽略前导 v 与非数字后缀。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = normalize(remote)
        val l = normalize(local)
        val rs = r.split(".").map { it.toIntOrNull() ?: 0 }
        val ls = l.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(rs.size, ls.size)) {
            val rv = rs.getOrElse(i) { 0 }
            val lv = ls.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun normalize(v: String): String =
        v.trim().lowercase().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            .trim('.').ifEmpty { "0" }
}
