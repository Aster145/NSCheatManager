package com.nscheatmanager.app.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nscheatmanager.app.ui.game.ShareArchive
import com.nscheatmanager.app.ui.share.ZipDocumentReader
import com.nscheatmanager.app.ui.share.ZipShareService
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareZipTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun documentReaderReturnsExactBytesAndRejectsAnythingOverLimit() {
        val file = File(context.cacheDir, "shared/cheat-zips/import.zip").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ZipDocumentReader(context, 4).read(uri))
        try {
            ZipDocumentReader(context, 3).read(uri)
            fail("Expected oversized document rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun shareIntentUsesZipMimeReadGrantAndNarrowFileProviderCachePath() {
        val intent = ZipShareService(context).createIntent(ShareArchive("game.zip", byteArrayOf(9, 8, 7)))
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("com.nscheatmanager.app.fileprovider", uri.authority)
        assertArrayEquals(byteArrayOf(9, 8, 7), context.contentResolver.openInputStream(uri)!!.readBytes())
    }
}
