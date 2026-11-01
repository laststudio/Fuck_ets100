package com.shuaiqiu.fuckets100

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class FeCapSolverTest {

    @Test
    fun `fnv1a and generatePrngHex match reference implementation`() {
        val hex1 = generatePrngHex("hello", 32)
        assertEquals("eb492c6e1655ea8ccb83decebb129a83", hex1)

        val hex2 = generatePrngHex("test123d", 3)
        assertEquals("63a", hex2)
    }

    @Test
    fun `solveOne finds valid nonce matching target prefix`() {
        val salt = "eb492c6e1655ea8ccb83decebb129a83"
        val target = "63a"
        val nonce = solveOne(salt, target)
        assertTrue(nonce >= 0)

        // Verify SHA-256 hash matches prefix
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest((salt + nonce).toByteArray(Charsets.UTF_8))
        val targetHex = hash.joinToString("") { "%02x".format(it) }
        assertTrue(targetHex.startsWith(target))
    }

    @Test
    fun `domSumMock matches reference implementation`() {
        assertEquals(124, domSumMock(123, 456, 789))
        assertEquals(0, domSumMock(0, 0, 0))
        assertEquals(220, domSumMock(-5, 42, 100))
    }

    @Test
    fun `live cap solver succeeds against production endpoint`() = runBlocking {
        // 生产端点存在速率限制，默认跳过；需要实测时设置 FE_LIVE_CAP_TEST=1。
        assumeTrue(System.getenv("FE_LIVE_CAP_TEST") == "1")
        val solver = FeCapSolver()
        var lastProgress = 0f
        val result = solver.solve { progress ->
            lastProgress = progress
        }
        assertTrue("Expected solve to succeed, but was $result", result.isSuccess)
        val token = result.getOrThrow()
        assertNotNull(token)
        assertTrue(token.startsWith("f0d2ec6d51:"))
        assertTrue(lastProgress >= 0.99f)
    }
}
