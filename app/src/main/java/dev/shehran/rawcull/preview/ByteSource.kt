package dev.shehran.rawcull.preview

import java.io.Closeable
import java.io.InputStream

/** The most any one preview may ask for, and the most a non-seekable file is ever read. */
const val MAX_PREVIEW_BYTES: Long = 64L * 1024 * 1024

const val HEADER_SLICE_BYTES: Int = 256 * 1024

/** Random access to a file's bytes, however they really arrive. */
interface ByteSource {
    /** Null while the length isn't known, as with a pipe that hasn't been read to its end. */
    val size: Long?

    val seekable: Boolean

    /** Up to [length] bytes from [offset]. Shorter only at the end of the file. */
    fun read(offset: Long, length: Int): ByteArray
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size) return ByteArray(0)
        val end = minOf(offset + length, bytes.size.toLong()).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

/** The part of [parent] from [base] on, with offsets counted from there. TIFF inside a JPEG works this way. */
class WindowSource(
    private val parent: ByteSource,
    private val base: Long,
    private val limit: Long,
) : ByteSource {
    override val size: Long get() = limit
    override val seekable: Boolean get() = parent.seekable

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= limit) return ByteArray(0)
        return parent.read(base + offset, minOf(length.toLong(), limit - offset).toInt())
    }
}

/**
 * Keeps recent 64 KB-aligned blocks, so walking an IFD doesn't turn into dozens of tiny reads.
 * Reads of a whole preview go straight through: they'd only push the metadata out.
 */
class BlockCachedSource(
    private val inner: ByteSource,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
) : ByteSource {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(maxBlocks, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean = size > maxBlocks
    }

    override val size: Long? get() = inner.size
    override val seekable: Boolean get() = inner.seekable

    /** Fetches a range in one read. Reading starts here, with the header slice. */
    fun preload(offset: Long, length: Int) {
        val first = offset / blockSize
        val bytes = inner.read(first * blockSize, length)
        var block = first
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + blockSize, bytes.size)
            blocks[block++] = bytes.copyOfRange(at, end)
            at = end
        }
    }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0) return ByteArray(0)
        if (length >= blockSize * DIRECT_READ_BLOCKS) return inner.read(offset, length)
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val position = offset + filled
            val block = block(position / blockSize)
            val inBlock = (position % blockSize).toInt()
            if (inBlock >= block.size) break
            val count = minOf(block.size - inBlock, length - filled)
            block.copyInto(out, filled, inBlock, inBlock + count)
            filled += count
            if (block.size < blockSize) break // That was the end of the file
        }
        return if (filled == length) out else out.copyOf(filled)
    }

    private fun block(index: Long): ByteArray =
        blocks[index] ?: inner.read(index * blockSize, blockSize).also { blocks[index] = it }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024
        private const val DEFAULT_MAX_BLOCKS = 32
        private const val DIRECT_READ_BLOCKS = 4
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * For a descriptor that can't seek, such as a pipe from a cloud provider: reads forward in
 * RapidReady's tiers (256 KB, 2 MB, then the file) and stops at [cap]. What's past the cap reads
 * as the end of the file, so a preview out there is simply not found.
 */
class StreamTierSource(
    private val open: () -> InputStream,
    private val tiers: List<Long> = DEFAULT_TIERS,
    private val cap: Long = MAX_PREVIEW_BYTES,
) : ByteSource, Closeable {
    private var stream: InputStream? = null
    private var buffer = ByteArray(0)
    private var filled = 0
    private var ended = false

    /** How far forward the stream has been read, for tests and the log. */
    val bytesRead: Int get() = filled

    override val size: Long? get() = if (ended) filled.toLong() else null
    override val seekable: Boolean get() = false

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        fillTo(offset + length)
        if (offset >= filled) return ByteArray(0)
        return buffer.copyOfRange(offset.toInt(), minOf(offset + length, filled.toLong()).toInt())
    }

    private fun fillTo(end: Long) {
        if (end <= filled || ended) return
        val target = (tiers.firstOrNull { it >= end } ?: cap).coerceAtMost(cap).toInt()
        if (target <= filled) return
        if (buffer.size < target) buffer = buffer.copyOf(target)
        val input = stream ?: open().also { stream = it }
        while (filled < target) {
            val count = input.read(buffer, filled, target - filled)
            if (count < 0) {
                ended = true
                break
            }
            filled += count
        }
    }

    override fun close() {
        stream?.close()
        stream = null
    }

    companion object {
        val DEFAULT_TIERS: List<Long> = listOf(HEADER_SLICE_BYTES.toLong(), 2L * 1024 * 1024)
    }
}
