package dev.shehran.rawcull.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class HashingTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    @Test
    fun `head hash of a short file covers the whole file`() {
        val short = bytes(10)
        assertEquals(sha256(short), Sha256HeadHash.of(ByteArrayInputStream(short)))
    }

    @Test
    fun `head hash of an empty file is the empty hash`() {
        assertEquals(sha256(ByteArray(0)), Sha256HeadHash.of(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `head hash stops at 64 KB`() {
        val long = bytes(HEAD_BYTES + 5000)
        assertEquals(sha256(long.copyOf(HEAD_BYTES)), Sha256HeadHash.of(ByteArrayInputStream(long)))
    }

    @Test
    fun `head hash ignores anything after the head`() {
        val a = bytes(HEAD_BYTES + 10)
        val b = a.copyOf().also { it[HEAD_BYTES + 1] = 99 }
        assertEquals(Sha256HeadHash.of(ByteArrayInputStream(a)), Sha256HeadHash.of(ByteArrayInputStream(b)))
    }

    @Test
    fun `head hash copes with streams that return few bytes per read`() {
        val long = bytes(HEAD_BYTES + 100)
        val trickle = object : InputStream() {
            private val inner = ByteArrayInputStream(long)
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, 777))
        }
        assertEquals(sha256(long.copyOf(HEAD_BYTES)), Sha256HeadHash.of(trickle))
    }

    @Test
    fun `copy writes every byte and hashes the same content`() {
        val source = bytes(3 * 1024 * 1024 + 17)
        val target = ByteArrayOutputStream()

        val result = StreamingHasher.copy(ByteArrayInputStream(source), target)

        assertArrayEquals(source, target.toByteArray())
        assertEquals(source.size.toLong(), result.bytes)
        assertEquals(sha256(source), result.fullHash)
        assertEquals(sha256(source.copyOf(HEAD_BYTES)), result.headHash)
    }

    @Test
    fun `copy of a file shorter than the head has equal hashes`() {
        val result = StreamingHasher.copy(ByteArrayInputStream(bytes(100)), ByteArrayOutputStream())
        assertEquals(result.fullHash, result.headHash)
        assertEquals(100L, result.bytes)
    }

    @Test
    fun `the head hash is right when a chunk straddles the 64 KB line`() {
        val source = bytes(HEAD_BYTES * 2)
        val chunky = object : InputStream() {
            private val inner = ByteArrayInputStream(source)
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, 50_000))
        }
        assertEquals(sha256(source.copyOf(HEAD_BYTES)), StreamingHasher.copy(chunky, ByteArrayOutputStream()).headHash)
    }

    @Test
    fun `progress adds up to the file size`() {
        var seen = 0L
        StreamingHasher.copy(ByteArrayInputStream(bytes(2_500_000)), ByteArrayOutputStream()) { seen += it }
        assertEquals(2_500_000L, seen)
    }

    @Test
    fun `read and write failures are told apart`() {
        val brokenInput = object : InputStream() {
            override fun read(): Int = throw IOException("card gone")
        }
        val brokenOutput = object : OutputStream() {
            override fun write(b: Int) = throw IOException("ENOSPC")
        }

        assertThrows(SourceReadException::class.java) { StreamingHasher.copy(brokenInput, ByteArrayOutputStream()) }
        val failure = assertThrows(TargetWriteException::class.java) {
            StreamingHasher.copy(ByteArrayInputStream(bytes(10)), brokenOutput)
        }
        assertEquals("ENOSPC", failure.message)
    }

    @Test
    fun `reading back a copy gives the streaming hash`() {
        val source = bytes(1_500_000)
        val copied = StreamingHasher.copy(ByteArrayInputStream(source), ByteArrayOutputStream())
        assertEquals(copied.fullHash, sha256Of(ByteArrayInputStream(source)))
        assertNotEquals(copied.fullHash, sha256Of(ByteArrayInputStream(source.copyOf(10))))
    }
}
