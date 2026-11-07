package dev.shehran.rawcull.importer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bytes hashed for the duplicate key and for Quick verification. */
const val HEAD_BYTES = 64 * 1024

private const val COPY_BUFFER_BYTES = 1024 * 1024
private const val SHA_256 = "SHA-256"

private fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return hex.toString()
}

private const val HEX = "0123456789abcdef"

fun interface HeadHash {
    /** SHA-256 hex of the first [HEAD_BYTES] of [input], or of all of it if it's shorter. */
    @Throws(IOException::class)
    fun of(input: InputStream): String
}

object Sha256HeadHash : HeadHash {
    override fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(HEAD_BYTES)
        var remaining = HEAD_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, remaining)
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().toHex()
    }
}

/** SHA-256 of a whole stream, for reading a copy back. */
@Throws(IOException::class)
fun sha256Of(input: InputStream, onBytes: (Int) -> Unit = {}): String {
    val digest = MessageDigest.getInstance(SHA_256)
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        onBytes(read)
    }
    return digest.digest().toHex()
}

/** The source failed while being read: the file is bad, or the card is gone. */
class SourceReadException(cause: IOException) : IOException(cause.message, cause)

/** The target failed while being written: the destination is full, or it went away. */
class TargetWriteException(cause: IOException) : IOException(cause.message, cause)

data class CopyResult(val bytes: Long, val headHash: String, val fullHash: String)

/**
 * Copies a stream and hashes it on the way through, so Full verification costs no extra card read.
 * Reads and writes fail with different exceptions, because the worker reacts to them differently.
 */
object StreamingHasher {

    /** @param onBytes called after each chunk; may throw to stop the copy (cancellation). */
    @Throws(IOException::class)
    fun copy(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit = {}): CopyResult {
        val full = MessageDigest.getInstance(SHA_256)
        val head = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L

        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw SourceReadException(e)
            }
            if (read < 0) break

            try {
                output.write(buffer, 0, read)
            } catch (e: IOException) {
                throw TargetWriteException(e)
            }
            full.update(buffer, 0, read)
            if (total < HEAD_BYTES) head.update(buffer, 0, minOf(read.toLong(), HEAD_BYTES - total).toInt())
            total += read
            onBytes(read)
        }

        try {
            output.flush()
        } catch (e: IOException) {
            throw TargetWriteException(e)
        }
        return CopyResult(total, head.digest().toHex(), full.digest().toHex())
    }
}
