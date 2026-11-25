package app.snapask.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface SignInResult {
    data object Success : SignInResult
    data object Cancelled : SignInResult
    data class Failed(val code: String, val message: String) : SignInResult
}

/**
 * Google アカウントでのログイン。
 * 端末で Google の ID トークンを受け取り、サーバーで検証してもらう。
 * 利用できるかどうか(許可リスト)を決めるのはサーバー側。
 */
class AuthRepository(
    private val api: ApiClient,
    private val sessions: SessionStore,
    private val webClientId: String,
) {
    suspend fun signIn(activity: Activity): SignInResult {
        if (webClientId.isBlank()) {
            return SignInResult.Failed("misconfigured", "Google のクライアントIDが設定されていません(local.properties を確認してください)")
        }
        val idToken = try {
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                return SignInResult.Failed("invalid_token", "Google アカウントの情報を受け取れませんでした")
            }
        } catch (e: GetCredentialCancellationException) {
            return SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            return SignInResult.Failed("google", "Google でのログインに失敗しました。もう一度お試しください")
        } catch (e: Exception) {
            return SignInResult.Failed("google", "Google アカウントの情報を読み取れませんでした")
        }

        return try {
            sessions.save(api.signIn(idToken))
            SignInResult.Success
        } catch (e: ApiException) {
            SignInResult.Failed(e.code, e.message)
        }
    }

    suspend fun signOut(context: Context) {
        sessions.clear()
        // 次回のログインでアカウントを選び直せるようにする
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            // 失敗してもログアウト自体は済んでいる
        }
    }
}
