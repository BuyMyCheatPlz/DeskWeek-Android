package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 已配对设备表（P3）。
 *
 * 配对时记录对端 actorId + 公钥 + groupId；之后每次连接都要求
 * HELLO 里的公钥与记录一致，否则拒绝——这是把 TOFU 收紧成「首次确认后固定身份」。
 *
 * 未实现（保持诚实）：证书固定、密钥轮换、逐对撤销的完整语义见协议 §2。
 */
class TrustStore(private val dir: File) {

    data class Peer(
        val actorId: String,
        val publicKeyB64: String,
        val groupId: String,
        val pairedAt: String,
        val sas: String,
    )

    private val file = File(dir, "trusted-peers.json")
    private val peers = LinkedHashMap<String, Peer>()

    init {
        dir.mkdirs()
        load()
    }

    fun all(): List<Peer> = peers.values.toList()

    fun get(actorId: String): Peer? = peers[actorId]

    /** 公钥必须与配对时一致；仅同 actorId 但换了键视为身份变化，拒绝 */
    fun isTrusted(actorId: String, publicKeyB64: String): Boolean =
        peers[actorId]?.publicKeyB64 == publicKeyB64

    fun remember(peer: Peer) {
        peers[peer.actorId] = peer
        persist()
    }

    fun forget(actorId: String) {
        peers.remove(actorId)
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val array = Json.parseToJsonElement(file.readText()).jsonObject["peers"] as? JsonArray ?: return
            array.forEach { element ->
                val obj = element.jsonObject
                val peer = Peer(
                    actorId = obj["actorId"]!!.jsonPrimitive.content,
                    publicKeyB64 = obj["publicKeyB64"]!!.jsonPrimitive.content,
                    groupId = obj["groupId"]!!.jsonPrimitive.content,
                    pairedAt = obj["pairedAt"]!!.jsonPrimitive.content,
                    sas = obj["sas"]!!.jsonPrimitive.content,
                )
                peers[peer.actorId] = peer
            }
        }
    }

    private fun persist() {
        val payload = buildJsonObject {
            put("peers", kotlinx.serialization.json.buildJsonArray {
                peers.values.forEach { peer ->
                    add(buildJsonObject {
                        put("actorId", peer.actorId)
                        put("publicKeyB64", peer.publicKeyB64)
                        put("groupId", peer.groupId)
                        put("pairedAt", peer.pairedAt)
                        put("sas", peer.sas)
                    })
                }
            })
        }
        val tmp = File(dir, "trusted-peers.json.tmp")
        tmp.writeText(payload.toString())
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        /**
         * 短认证码：两端各自用「双方公钥排序后拼接」算 SHA-256，取前 3 字节转 6 位数字。
         * 排序保证双方算出同一个值，人工核对即可确认没有中间人。
         */
        fun shortAuthString(publicKeyA: String, publicKeyB: String): String {
            val pair = listOf(publicKeyA, publicKeyB).sorted()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((pair[0] + "|" + pair[1]).toByteArray(Charsets.UTF_8))
            val value = ((digest[0].toInt() and 0xFF) shl 16) or
                ((digest[1].toInt() and 0xFF) shl 8) or
                (digest[2].toInt() and 0xFF)
            return "%06d".format(value % 1_000_000)
        }
    }
}
