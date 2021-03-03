package it.matteocrippa.flutternfcreader

import android.Manifest
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.charset.Charset


const val PERMISSION_NFC:Int = 1007

class FlutterNfcReaderPlugin(val registrar: Registrar) : MethodCallHandler, EventChannel.StreamHandler, NfcAdapter.ReaderCallback {

    private val activity = registrar.activity()

    private var isReading = false
    private var nfcAdapter: NfcAdapter? = null
    private var nfcManager: NfcManager? = null

    public var eventSink: EventChannel.EventSink? = null

    private var kId = "nfcId"
    private var kContent = "nfcContent"
    private var kError = "nfcError"
    private var kStatus = "nfcStatus"

    private var READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar): Unit {
            val messenger = registrar.messenger()
            val channel = MethodChannel(messenger, "flutter_nfc_reader")
            val eventChannel = EventChannel(messenger, "it.matteocrippa.flutternfcreader.flutter_nfc_reader")
            val plugin = FlutterNfcReaderPlugin(registrar)
            channel.setMethodCallHandler(plugin)
            eventChannel.setStreamHandler(plugin)
        }
    }

    init {
        nfcManager = activity.getSystemService(Context.NFC_SERVICE) as? NfcManager
        nfcAdapter = nfcManager?.defaultAdapter
    }

    override fun onMethodCall(call: MethodCall, result: Result): Unit {

        when (call.method) {
            "NfcRead" -> {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    activity.requestPermissions(
                            arrayOf(Manifest.permission.NFC),
                            PERMISSION_NFC
                    )
                }

                startNFC()

                if (!isReading) {
                    result.error("404", "NFC Hardware not found", null)
                    return
                }

                result.success(null)
            }
            "NfcStop" -> {
                stopNFC()
                val data = mapOf(kId to "", kContent to "", kError to "", kStatus to "stopped")
                result.success(data)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    // EventChannel.StreamHandler methods
    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        this.eventSink = eventSink
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        stopNFC()
    }

    private fun startNFC(): Boolean {
        isReading = if (nfcAdapter?.isEnabled == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                nfcAdapter?.enableReaderMode(registrar.activity(), this, READER_FLAGS, null )
            }
            true
        } else {
            false
        }
        return isReading
    }

    private fun stopNFC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(registrar.activity())
        }
        isReading = false
        eventSink = null
    }

    // handle discovered NDEF Tags
    override fun onTagDiscovered(tag: Tag?) {
        // convert tag to NDEF tag
        val ndef = Ndef.get(tag)
        // ndef will be null if the discovered tag is not a NDEF tag
        // read NDEF message
        ndef?.connect()
        val message = ndef?.ndefMessage
                ?.toByteArray()
                ?.toString(Charset.forName("UTF-8")) ?: ""
        //val id = tag?.id?.toString(Charset.forName("ISO-8859-1")) ?: ""
//        tag?.id?.forEach{it->
//            android.util.Log.d("TAG", "onTagDiscovered: "+it)
//        }

//        val id = bytesToHexString(tag?.id) ?: ""
        val id = convertId(tag?.id) ?: ""

        ndef?.close()
        if (message != null) {
            val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "read")
            activity?.let {
                it.runOnUiThread {
                    eventSink?.success(data)
                }
            }

        }
    }

    private fun convertId(inarray: ByteArray?): String? {
        if (inarray == null || inarray.size != 4) {
//            Log.e(LOGTAG, "卡片数据不合法")
            return null
        }
        // 移除最后一个byte, 前三个byte反序
        /*卡上印的编号 015138
        对应id  0000113094
        读出来id
        C6 B9 01 1C
        转为
        01 B9 C6
        16进制 01b9c6转为10进制113094
        前方补0 0000113094*/
        val nbyte =
                byteArrayOf(0, 0, 0, 0, 0, inarray[2], inarray[1], inarray[0])
        val value = longFrom8Bytes(nbyte, 0, false)
        return String.format("%010d", value)
    }

    /***
     * 8位byte[]转long
     */
    private fun longFrom8Bytes(
            input: ByteArray,
            offset: Int,
            littleEndian: Boolean
    ): Long {
        var value: Long = 0
        // 循环读取每个字节通过移位运算完成long的8个字节拼装
        for (count in 0..7) {
            val shift = (if (littleEndian) count else 7 - count) shl 3
            value =
                    value or (0xff.toLong() shl shift and (input[offset + count].toLong() shl shift))
        }
        return value
    }

    private fun bytesToHexString(src: ByteArray?): String? {
        val stringBuilder = StringBuilder("0x")
        if (src == null || src.isEmpty()) {
            return null
        }

        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit(src[i].toInt().ushr(4).and(0x0F), 16)
            buffer[1] = Character.forDigit(src[i].toInt().and(0x0F), 16)
            stringBuilder.append(buffer)
        }

        return stringBuilder.toString()
    }
}