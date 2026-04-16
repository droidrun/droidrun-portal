package com.mobilerun.portal.service

import android.os.Environment
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class FileOperationsTest {

    companion object {
        private const val TEST_PROTOCOL = "testhttp"
        private const val MAX_FILE_SIZE = 100L * 1024L * 1024L

        @JvmStatic
        @BeforeClass
        fun installUrlHandlerFactory() {
            try {
                URL.setURLStreamHandlerFactory(FakeHttpUrlStreamHandlerFactory)
            } catch (_: Error) {
                // The JVM only allows one factory. Test execution in this repo does not
                // register another one, so this fallback is only for repeated class loading.
            }
        }
    }

    private lateinit var externalStorageDir: File
    private lateinit var fileOperations: FileOperations

    @Before
    fun setUp() {
        externalStorageDir = Files.createTempDirectory("file-operations-test").toFile().canonicalFile
        fileOperations = FileOperations()

        mockkStatic(Environment::class)
        every { Environment.getExternalStorageDirectory() } returns externalStorageDir
        every { Environment.isExternalStorageManager() } returns true

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        FakeHttpRegistry.clear()
    }

    @After
    fun tearDown() {
        FakeHttpRegistry.clear()
        externalStorageDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun resolvePath_rejectsPathTraversal() {
        try {
            fileOperations.resolvePath("../secret.txt")
            fail("Expected SecurityException")
        } catch (error: SecurityException) {
            assertEquals("Path contains '..' - path traversal not allowed", error.message)
        }
    }

    @Test
    fun listFiles_returnsFailureWhenLsExitsNonZero() {
        val relativePath = "downloads/restricted"
        File(externalStorageDir, relativePath).mkdirs()
        val fileOperations =
            FileOperations(
                listFilesCommandRunner = {
                    ListFilesCommandResult(
                        exitCode = 2,
                        stdout = "",
                        stderr = "Permission denied",
                    )
                },
            )

        val result = fileOperations.listFiles(relativePath)

        assertTrue(result.isFailure)
        assertEquals("Failed to list files: Permission denied", result.exceptionOrNull()?.message)
    }

    @Test
    fun listFiles_returnsEmptySuccessForEmptyDirectoryWhenLsSucceeds() {
        val relativePath = "downloads/empty"
        File(externalStorageDir, relativePath).mkdirs()
        val fileOperations =
            FileOperations(
                listFilesCommandRunner = {
                    ListFilesCommandResult(
                        exitCode = 0,
                        stdout = "total 0\n",
                        stderr = "",
                    )
                },
            )

        val result = fileOperations.listFiles(relativePath)

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().total)
        assertTrue(result.getOrThrow().files.isEmpty())
    }

    @Test
    fun fetchFile_rejectsOversizedResponseWithoutContentLength() {
        val relativePath = "downloads/missing-content-length.bin"
        val url = registerResponse(
            contentLength = -1L,
            totalBytes = MAX_FILE_SIZE + 1,
        )

        val result = fileOperations.fetchFile(url, relativePath)

        assertTrue(result.isFailure)
        assertEquals(
            "File too large (max 100MB)",
            result.exceptionOrNull()?.message,
        )
        assertFalse(File(externalStorageDir, relativePath).exists())
        assertFalse(File(externalStorageDir, "$relativePath.download.tmp").exists())
    }

    @Test
    fun fetchFile_rejectsOversizedResponseWithIncorrectContentLength() {
        val relativePath = "downloads/incorrect-content-length.bin"
        val url = registerResponse(
            contentLength = 1_024L,
            totalBytes = MAX_FILE_SIZE + 1,
        )

        val result = fileOperations.fetchFile(url, relativePath)

        assertTrue(result.isFailure)
        assertEquals(
            "File too large (max 100MB)",
            result.exceptionOrNull()?.message,
        )
        assertFalse(File(externalStorageDir, relativePath).exists())
        assertFalse(File(externalStorageDir, "$relativePath.download.tmp").exists())
    }

    @Test
    fun fetchFile_cleansUpPartialTempFileAndPreservesExistingDestinationOnAbort() {
        val relativePath = "downloads/existing.bin"
        val destination = File(externalStorageDir, relativePath).apply {
            parentFile?.mkdirs()
            writeText("original")
        }
        val url = registerResponse(
            contentLength = -1L,
            totalBytes = MAX_FILE_SIZE + 1,
        )

        val result = fileOperations.fetchFile(url, relativePath)

        assertTrue(result.isFailure)
        assertEquals("original", destination.readText())
        assertFalse(File(externalStorageDir, "$relativePath.download.tmp").exists())
    }

    @Test
    fun fetchFile_rejectsExistingDirectoryDestinationWithoutReplacingIt() {
        val relativePath = "downloads/existing-dir"
        val destination = File(externalStorageDir, relativePath).apply { mkdirs() }
        val url = registerResponse(
            contentLength = 16L,
            totalBytes = 16L,
        )

        val result = fileOperations.fetchFile(url, relativePath)

        assertTrue(result.isFailure)
        assertEquals("Destination path is not a file", result.exceptionOrNull()?.message)
        assertTrue(destination.exists())
        assertTrue(destination.isDirectory)
        assertFalse(File(externalStorageDir, "$relativePath.download.tmp").exists())
    }

    private fun registerResponse(
        contentLength: Long,
        totalBytes: Long,
    ): String {
        val host = "case-${System.nanoTime()}"
        FakeHttpRegistry.register(
            host = host,
            response = FakeHttpResponse(
                contentLength = contentLength,
                body = { FixedSizeInputStream(totalBytes) },
            ),
        )
        return "$TEST_PROTOCOL://$host/file.bin"
    }
}

private object FakeHttpRegistry {
    private val responses = ConcurrentHashMap<String, FakeHttpResponse>()

    fun register(host: String, response: FakeHttpResponse) {
        responses[host] = response
    }

    fun take(host: String): FakeHttpResponse {
        return responses.remove(host) ?: error("No fake response registered for $host")
    }

    fun clear() {
        responses.clear()
    }
}

private data class FakeHttpResponse(
    val code: Int = HttpURLConnection.HTTP_OK,
    val contentLength: Long,
    val body: () -> InputStream,
)

private object FakeHttpUrlStreamHandlerFactory : URLStreamHandlerFactory {
    override fun createURLStreamHandler(protocol: String): URLStreamHandler? {
        if (protocol != "testhttp") {
            return null
        }

        return object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                return FakeHttpURLConnection(url, FakeHttpRegistry.take(url.host))
            }
        }
    }
}

private class FakeHttpURLConnection(
    url: URL,
    private val response: FakeHttpResponse,
) : HttpURLConnection(url) {

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        connected = false
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = response.code

    override fun getContentLengthLong(): Long = response.contentLength

    override fun getInputStream(): InputStream {
        if (response.code != HTTP_OK) {
            throw java.io.IOException("HTTP ${response.code}")
        }
        return response.body()
    }
}

private class FixedSizeInputStream(
    private val totalBytes: Long,
) : InputStream() {
    private var emittedBytes = 0L

    override fun read(): Int {
        if (emittedBytes >= totalBytes) {
            return -1
        }
        emittedBytes += 1
        return 0x61
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (emittedBytes >= totalBytes) {
            return -1
        }

        val bytesToEmit = minOf(length.toLong(), totalBytes - emittedBytes).toInt()
        buffer.fill(0x61.toByte(), offset, offset + bytesToEmit)
        emittedBytes += bytesToEmit
        return bytesToEmit
    }
}
