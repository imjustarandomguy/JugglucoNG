package tk.glucodata

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A G7 pairing (EC-JPAKE) must not reuse the key pairs of an earlier run.
 *
 * The two pairs used to be made once, with the sensor's stream, so every run sent the same
 * round 1 and round 2. On a new sensor the first run was refused (0A 03 after round 2), and so
 * were the next 48, 12 seconds apart, until the sensor was added again: the new stream's new
 * pairs paired on the first try. These are source scans because the driver needs the Android
 * runtime and the native library.
 */
class DexcomPairingKeysTests {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/dex/java/tk/glucodata/DexGattCallback.java").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Common/src not found from ${System.getProperty("user.dir")}")
    }

    private fun source(path: String) = File(repoRoot(), path).readText().replace(Regex("\\s+"), "")

    /** The text between the braces of the block that [signature] opens (whitespace removed). */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var end = start + signature.length - 1
        var depth = 0
        do {
            if (text[end] == '{') depth++ else if (text[end] == '}') depth--
            end++
        } while (depth > 0 && end < text.length)
        return text.substring(start + signature.length, end - 1)
    }

    @Test
    fun everyPairingRunStartsWithNewKeyPairs() {
        val run = body(
            source("Common/src/dex/java/tk/glucodata/DexGattCallback.java"),
            "privatevoiddocmd0(BluetoothGattbluetoothGatt){",
        )
        val renew = run.indexOf("Natives.dexNewKeys(dataptr);")
        val round1 = run.indexOf("cmd(bluetoothGatt,0x0);")
        assertTrue("docmd0 must renew the key pairs", renew >= 0)
        assertTrue("before it asks for round 1", round1 > renew)
    }

    @Test
    fun theNativeCallRenewsBothPairs() {
        assertTrue(
            source("Common/src/main/java/tk/glucodata/Natives.java")
                .contains("publicstaticnativevoiddexNewKeys(longdataptr);")
        )
        val call = body(
            source("Common/src/main/cpp/dexcom/java.cpp"),
            "fromjava(dexNewKeys)(JNIEnv*envin,jclasscl,jlongdataptr){",
        )
        assertTrue(
            "every pair of the stream's context is renewed",
            call.contains("for(KeyPair&key:reinterpret_cast<dexcomstream*>(dataptr)->dexcontext.key)key.renew();"),
        )
    }

    @Test
    fun renewingFreesTheOldKeyBeforeMakingTheNewOne() {
        val renew = body(source("Common/src/main/cpp/dexcom/ecJPake.cpp"), "voidKeyPair::renew(){")
        val free = renew.indexOf("EC_KEY_free(keyptr);")
        val make = renew.indexOf("keyptr=EC_KEY_new_by_curve_name(curveNID);")
        val generate = renew.indexOf("EC_KEY_generate_key(keyptr);")
        assertTrue("the old key is freed", free >= 0)
        assertTrue("then a new one made", make > free)
        assertTrue("and generated", generate > make)
    }
}
