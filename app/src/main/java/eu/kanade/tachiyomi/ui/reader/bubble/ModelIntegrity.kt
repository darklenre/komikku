package eu.kanade.tachiyomi.ui.reader.bubble

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Memory-maps a bundled `.tflite` asset (stored uncompressed, so it maps straight from the APK) and
 * checks it against [expectedSha]. A truncated or corrupt model otherwise fails as a native SIGABRT
 * deep inside TFLite instead of a catchable error. Throws [IllegalStateException] on mismatch.
 */
internal fun mmapVerifiedModel(context: Context, assetPath: String, expectedSha: String): MappedByteBuffer {
    val buffer = context.assets.openFd(assetPath).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            // The mapping stays valid after the fd / channel are closed.
            channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
    val actual = sha256Hex(buffer.duplicate())
    check(actual == expectedSha) { "$assetPath SHA-256 mismatch (expected $expectedSha, got $actual)" }
    buffer.rewind()
    return buffer
}

internal fun sha256Hex(buffer: ByteBuffer): String {
    val md = MessageDigest.getInstance("SHA-256")
    val chunk = ByteArray(1 shl 16)
    buffer.rewind()
    while (buffer.hasRemaining()) {
        val n = minOf(chunk.size, buffer.remaining())
        buffer.get(chunk, 0, n)
        md.update(chunk, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
