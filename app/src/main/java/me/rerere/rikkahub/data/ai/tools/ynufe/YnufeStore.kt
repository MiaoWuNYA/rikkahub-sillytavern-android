package me.rerere.rikkahub.data.ai.tools.ynufe

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 云南财经教务登录凭据 / 会话的隔离持久化存储。
 * 与全局 PreferencesStore 分离，避免改动其迁移链；用独立 DataStore("ynufe")。
 *
 * 存储字段：
 *  - account      学号（明文）
 *  - passwordObf  密码（XOR+Base64 混淆，见 [CredentialObfuscator]）
 *  - jsessionid   强智教务会话 ID
 *  - jsxsd        强智次级 cookie
 *  - lastLoginTs  最近登录时间戳
 */
private val Context.ynufeDataStore by preferencesDataStore(name = "ynufe")

data class YnufeAuth(
    val account: String = "",
    val passwordObf: String = "",
    val jsessionid: String = "",
    val jsxsd: String = "",
    val lastLoginTs: Long = 0L,
) {
    val hasCredentials: Boolean get() = account.isNotEmpty() && passwordObf.isNotEmpty()
}

class YnufeStore(private val context: Context) {

    private object Keys {
        val ACCOUNT = stringPreferencesKey("account")
        val PASSWORD_OBF = stringPreferencesKey("password_obf")
        val JSESSIONID = stringPreferencesKey("jsessionid")
        val JSXSD = stringPreferencesKey("jsxsd")
        val LAST_LOGIN = longPreferencesKey("last_login_ts")
    }

    private val store get() = context.ynufeDataStore

    /** Observable auth state, for the settings page. */
    val authFlow: Flow<YnufeAuth> = store.data.map { p ->
        YnufeAuth(
            account = p[Keys.ACCOUNT] ?: "",
            passwordObf = p[Keys.PASSWORD_OBF] ?: "",
            jsessionid = p[Keys.JSESSIONID] ?: "",
            jsxsd = p[Keys.JSXSD] ?: "",
            lastLoginTs = p[Keys.LAST_LOGIN] ?: 0L,
        )
    }

    /** One-shot read (used inside the AI tool which runs on IO). */
    suspend fun snapshot(): YnufeAuth = authFlow.first()

    suspend fun saveAccount(account: String) {
        store.edit { it[Keys.ACCOUNT] = account }
    }

    suspend fun savePassword(obf: String) {
        store.edit { it[Keys.PASSWORD_OBF] = obf }
    }

    suspend fun saveCredentials(account: String, obf: String) {
        store.edit {
            it[Keys.ACCOUNT] = account
            it[Keys.PASSWORD_OBF] = obf
        }
    }

    suspend fun saveSession(jsessionid: String, jsxsd: String?, ts: Long = System.currentTimeMillis()) {
        store.edit {
            if (jsessionid.isNotEmpty()) it[Keys.JSESSIONID] = jsessionid
            if (jsxsd != null && jsxsd.isNotEmpty()) it[Keys.JSXSD] = jsxsd
            it[Keys.LAST_LOGIN] = ts
        }
    }

    suspend fun clearSession() {
        store.edit {
            it.remove(Keys.JSESSIONID)
            it.remove(Keys.JSXSD)
            it.remove(Keys.LAST_LOGIN)
        }
    }

    suspend fun clearAll() {
        store.edit {
            it.clear()
        }
    }
}