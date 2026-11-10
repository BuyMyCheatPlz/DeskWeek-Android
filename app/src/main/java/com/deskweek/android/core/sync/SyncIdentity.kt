package com.deskweek.android.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * 同步身份与操作签名（P2 切片）。
 *
 * 与 JS 侧 `src/main/sync/identity.cjs` 固定同一组选择：
 * - P-256（secp256r1）+ SHA-256 + ECDSA，签名 DER 编码后 base64
 * - 公钥 SPKI/DER base64，私钥 PKCS8/DER base64
 * - 被签内容 = 去掉 signature 后的规范化 JSON
 *
 * JVM 没有「私钥推导公钥」的可移植 API，因此身份文件同时保存公私钥；
 * 加载时用一次签名/验签自检，不一致就重新生成。
 * 原型阶段私钥落在应用私有目录，正式版应迁移到 Android Keystore。
 */
class SyncIdentity(
    val actorId: String,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
) {

    val publicKeyB64: String = Base64.getEncoder().encodeToString(publicKey.encoded)

    fun sign(message: JsonObject): String {
        val canonical = CanonicalJson.canonicalize(stripSignature(message))
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** 返回带 signature 字段的副本 */
    fun signed(message: JsonObject): JsonObject = buildJsonObject {
        message.forEach { (key, value) -> if (key != "signature") put(key, value) }
        put("signature", JsonPrimitive(sign(message)))
    }

    /** 自检：能签能验，且公钥与私钥匹配 */
    fun selfCheck(): Boolean {
        val probe = buildJsonObject { put("probe", JsonPrimitive(actorId)) }
        return verify(probe, publicKeyB64, sign(probe))
    }

    companion object {

        private const val CURVE = "secp256r1"
        private const val FILE_NAME = "sync-identity.json"

        fun loadOrCreate(dir: File): SyncIdentity {
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            if (file.exists()) {
                val restored = runCatching { restore(file) }.getOrNull()
                if (restored != null && restored.selfCheck()) return restored
            }
            val generated = generate()
            file.writeText(
                buildJsonObject {
                    put("actorId", JsonPrimitive(generated.actorId))
                    put("privateKeyB64", JsonPrimitive(Base64.getEncoder().encodeToString(generated.privateKey.encoded)))
                    put("publicKeyB64", JsonPrimitive(generated.publicKeyB64))
                }.toString(),
            )
            runCatching { file.setReadable(false, false); file.setReadable(true, true) }
            return generated
        }

        fun generate(): SyncIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            val pair = generator.generateKeyPair()
            return SyncIdentity(UUID.randomUUID().toString(), pair.private, pair.public)
        }

        /** 从固定密钥对导入（测试向量用） */
        fun import(actorId: String, privateKeyB64: String, publicKeyB64: String): SyncIdentity {
            val factory = KeyFactory.getInstance("EC")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyB64)))
            val publicKey = factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            return SyncIdentity(actorId, privateKey, publicKey)
        }

        fun verify(message: JsonObject, publicKeyB64: String, signatureB64: String): Boolean {
            if (signatureB64.isEmpty()) return false
            return runCatching {
                val keyBytes = Base64.getDecoder().decode(publicKeyB64)
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
                val canonical = CanonicalJson.canonicalize(stripSignature(message))
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                verifier.update(canonical.toByteArray(Charsets.UTF_8))
                verifier.verify(Base64.getDecoder().decode(signatureB64))
            }.getOrDefault(false)
        }

        private fun restore(file: File): SyncIdentity {
            val saved = Json.parseToJsonElement(file.readText()).jsonObject
            val actorId = saved["actorId"]!!.jsonPrimitive.content
            val factory = KeyFactory.getInstance("EC")
            val privateKey = factory.generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(saved["privateKeyB64"]!!.jsonPrimitive.content)),
            )
            val publicKey = factory.generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(saved["publicKeyB64"]!!.jsonPrimitive.content)),
            )
            return SyncIdentity(actorId, privateKey, publicKey)
        }

        private fun stripSignature(message: JsonObject): JsonObject = buildJsonObject {
            message.forEach { (key, value) -> if (key != "signature") put(key, value) }
        }
    }
}
