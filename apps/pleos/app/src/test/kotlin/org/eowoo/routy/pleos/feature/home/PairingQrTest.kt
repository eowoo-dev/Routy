package org.eowoo.routy.pleos.feature.home
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import kotlin.test.assertEquals
class PairingQrTest {
 @Test fun encodedPublicLinkCanBeScannedWithoutAnyInstallationCredential(){
  val uri="https://routy.eowoo.org/?connect=ABCD2345"
  val matrix=PairingQr.matrix(uri)
  val pixels=IntArray(matrix.width*matrix.height){i->if(matrix[i%matrix.width,i/matrix.width])0xff000000.toInt() else 0xffffffff.toInt()}
  val scan=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width,matrix.height,pixels))))
  assertEquals(uri,scan.text)
 }
}
