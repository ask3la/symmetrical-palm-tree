package com.example.modularcrm

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.MessageDigest

/** Per-install salted PBKDF2 verifier. Never stores plaintext PIN. */
class PinSecurity(context:Context) {
    private val prefs=context.getSharedPreferences("pin_auth",Context.MODE_PRIVATE)
    fun configured():Boolean=prefs.contains("verifier")
    fun set(pin:String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit))
        val salt=ByteArray(16).also {SecureRandom().nextBytes(it)}
        val digest=derive(pin,salt)
        prefs.edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP))
            .putString("verifier",Base64.encodeToString(digest,Base64.NO_WRAP)).apply()
    }
    fun verify(pin:String):Boolean {
        if(!configured()) return false
        val salt=Base64.decode(prefs.getString("salt", "") ?: "",Base64.NO_WRAP)
        val expected=Base64.decode(prefs.getString("verifier", "") ?: "",Base64.NO_WRAP)
        return MessageDigest.isEqual(derive(pin,salt),expected)
    }
    private fun derive(pin:String,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(pin.toCharArray(),salt,210_000,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
    }
}
