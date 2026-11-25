package app.snapask.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ログイン状態(サーバーが発行したセッション)の保管。
 * 端末の Android Keystore にある鍵で暗号化して保存する。鍵は端末から取り出せない。
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())

    val session: StateFlow<Session?> = _session.asStateFlow()

    fun current(): Session? = _session.value

    fun save(session: Session) {
        val plain = listOf(session.expiresAt.toString(), session.email, session.token).joinToString("\n")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun load(): Session? {
        return try {
            val iv = prefs.getString(KEY_IV, null) ?: return null
            val data = prefs.getString(KEY_DATA, null) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val parts = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8).split("\n")
            if (parts.size != 3) return null
            val session = Session(token = parts[2], email = parts[1], expiresAt = parts[0].toLong())
            if (session.expiresAt * 1000 <= System.currentTimeMillis()) null else session
        } catch (e: Exception) {
            // 鍵が失われた(機種変更など)場合は、ログインし直してもらう
            null
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "session"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val KEY_ALIAS = "snapask_session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
