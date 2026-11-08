package dev.shehran.rawcull.data.export

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The real ExifInterface on a real (tiny) JPEG: the copy gets all three times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExifJpegTimeWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `all three capture times are written and the offset stays`() {
        val file = temp.newFile("copy.jpg")
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", file)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:01 10:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            saveAttributes()
        }

        ExifJpegTimeWriter().write(file, "2023:11:14 22:13:20")

        val exif = ExifInterface(file)
        assertEquals("2023:11:14 22:13:20", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("2023:11:14 22:13:20", exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        assertEquals("2023:11:14 22:13:20", exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertEquals("+02:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
    }
}
