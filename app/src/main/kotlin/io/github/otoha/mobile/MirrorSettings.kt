package io.github.otoha.mobile

import java.net.URI

internal object MirrorSettings {
    /** Empty means the built-in failover list; explicit bases never silently fall back elsewhere. */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val uri = runCatching { URI(text) }.getOrElse {
            throw IllegalArgumentException("请输入有效的 HTTPS API 地址")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "API 地址必须使用 HTTPS 并包含有效域名"
        }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "API 地址不能包含账号、查询参数或片段"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "API 地址的端口无效" }
        return "https://${uri.rawAuthority.lowercase()}${uri.rawPath.orEmpty().trimEnd('/')}"
    }
}
