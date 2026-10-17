package dev.wifibridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Protocol and QR decoding must stay JVM-pure so the tests above exercise the shipped code. */
class PureLayerTest {
    @Test fun noAndroidImportsInPureLayers() {
        val src = File(System.getProperty("wb.srcMain") ?: "src/main/java")
        val dirs = listOf("dev/wifibridge/protocol", "dev/wifibridge/qr").map { File(src, it) }
        val files = dirs.flatMap { d -> d.walk().filter { it.extension == "kt" }.toList() }
        assertTrue("no sources found under $src", files.size >= 5)
        val offenders = files.filter { f ->
            f.readLines().any { it.trim().startsWith("import android") || it.trim().startsWith("import androidx") }
        }
        assertEquals(emptyList<File>(), offenders)
    }
}
