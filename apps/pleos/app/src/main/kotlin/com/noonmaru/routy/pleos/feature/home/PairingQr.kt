package com.noonmaru.routy.pleos.feature.home
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
object PairingQr {
    fun matrix(uri:String)=QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,280,280,mapOf(EncodeHintType.MARGIN to 4,EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
}
