package com.shuaiqiu.fuckets100

import android.util.Base64
import android.util.Log
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 离线授权服务（设备内自签）。
 *
 * libresolver.so / libcloud.so 的 parse 类 op 在 Android 上强制走
 * `POST {baseUrl}/client-auth/features/authorize`：
 * 请求体 `{software_key, feature_key, operation_nonce, app_version_code}`，
 * 要求响应里带 Ed25519 签名的 `grant`，并且校验：
 *   - 签名（对 grant 载荷的 canonical JSON，按 key 排序、无空格、UTF-8）
 *   - operation_nonce 与请求一致
 *   - software_key / feature_key 与 setAuthConfig 注入值一致
 *   - expires_at 未过期，且有效期长度在允许范围内
 *
 * 这里在本进程内监听 127.0.0.1 的随机端口，用内置私钥即时签名，
 * 使解析不再依赖 LastStudio 账号与远端授权服务。
 *
 * 载荷字段与签名口径与后端 `software_auth.sign_grant()` 完全一致：
 * json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)。
 */
internal object LocalResolverAuthService {
    private const val TAG = "LocalResolverAuth"

    /** 与内置私钥配对的 Ed25519 公钥（urlsafe base64 no-pad），用于 setAuthConfig。 */
    const val PUBLIC_KEY_B64 = "Q1utI6HOQZ8GOPqZu7W1mpYTnjh-5p9_6FyfflS4NiI"

    /** 32 字节 Ed25519 私钥种子（base64）。 */
    private const val PRIVATE_SEED_B64 = "yTR89GzpW1Ae7n7hEgxs8xd8BOrgIUJs5mDKzVMmYl8="

    /** grant 有效期（秒）。后端用 120，这里取 600 以覆盖 Java 层的授权缓存窗口。 */
    private const val GRANT_TTL_SECONDS = 600L

    private const val FALLBACK_SOFTWARE_KEY = "fe"
    private const val FALLBACK_FEATURE_KEY = "answer_parser"

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val random = SecureRandom()
    private val signingKey: EdDSAPrivateKey by lazy {
        val seed = Base64.decode(PRIVATE_SEED_B64, Base64.DEFAULT)
        EdDSAPrivateKey(
            EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName("Ed25519")),
        )
    }

    /**
     * 返回可供 native 使用的 base url（`http://127.0.0.1:<port>`）。
     * 首次调用时启动监听；重复调用复用同一个端口。
     */
    @Synchronized
    fun baseUrl(): String {
        val socket = serverSocket ?: startServer().also { serverSocket = it }
        return "http://127.0.0.1:${socket.localPort}"
    }

    private fun startServer(): ServerSocket {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(name = "local-resolver-auth", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    continue
                }
                thread(name = "local-resolver-auth-conn", isDaemon = true) {
                    handle(client)
                }
            }
        }
        Log.i(TAG, "本地授权服务已启动, port=${socket.localPort}")
        return socket
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            readLine(input) ?: return

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                }
            }

            val body = readBody(input, contentLength)
            val payload = buildResponse(body)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            out.write(bytes)
            out.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "处理授权请求失败: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return buffer.toString().takeIf { it.isNotEmpty() }
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(value.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count <= 0) break
            read += count
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun buildResponse(body: String): String {
        val request = runCatching { JSONObject(body) }.getOrNull()
        val nonce = request?.optString("operation_nonce").orEmpty()
        val softwareKey = request?.optString("software_key").orEmpty()
            .ifBlank { FALLBACK_SOFTWARE_KEY }
        val featureKey = request?.optString("feature_key").orEmpty()
            .ifBlank { FALLBACK_FEATURE_KEY }

        val now = System.currentTimeMillis() / 1000
        val expiresAt = now + GRANT_TTL_SECONDS
        val decisionId = "dec_" + Base64.encodeToString(
            randomBytes(9),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        // 与后端 sign_grant 完全一致的 canonical JSON：key 升序、无空格、UTF-8。
        val canonical = buildString {
            append("{\"allowed\":true")
            append(",\"decision_id\":").append(JSONObject.quote(decisionId))
            append(",\"expires_at\":").append(expiresAt)
            append(",\"feature_key\":").append(JSONObject.quote(featureKey))
            append(",\"issued_at\":").append(now)
            append(",\"operation_nonce\":").append(JSONObject.quote(nonce))
            append(",\"policy_revision\":1")
            append(",\"software_key\":").append(JSONObject.quote(softwareKey))
            append('}')
        }
        val grant = sign(canonical)

        return buildString {
            append("{\"allowed\":true")
            append(",\"decision_id\":").append(JSONObject.quote(decisionId))
            append(",\"software_key\":").append(JSONObject.quote(softwareKey))
            append(",\"feature_key\":").append(JSONObject.quote(featureKey))
            append(",\"operation_nonce\":").append(JSONObject.quote(nonce))
            append(",\"policy_revision\":1")
            append(",\"issued_at\":").append(now)
            append(",\"expires_at\":").append(expiresAt)
            append(",\"grant\":").append(JSONObject.quote(grant))
            append(",\"grant_key_id\":\"local\"")
            append('}')
        }
    }

    private fun sign(canonical: String): String {
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(signingKey)
        engine.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            engine.sign(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
