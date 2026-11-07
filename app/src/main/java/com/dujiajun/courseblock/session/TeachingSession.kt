package com.dujiajun.courseblock.session

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Restores the teaching site's session through the existing account session.
 * The interactive login screen is needed only when this recovery cannot authenticate.
 */
internal class TeachingSession(
    client: OkHttpClient,
    private val loginUrl: HttpUrl,
    private val accountUrl: HttpUrl,
) : Interceptor {
    class LoginRequiredException : IOException("Interactive login is required")

    private val authenticationClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val authenticationLock = Any()
    private var generation = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val observedGeneration = synchronized(authenticationLock) { generation }
        val original = chain.proceed(chain.request())
        if (!requiresLogin(original)) return original
        original.close()

        synchronized(authenticationLock) {
            if (generation == observedGeneration) {
                restoreSession()
                generation++
            }
        }

        val retried = chain.proceed(chain.request())
        if (requiresLogin(retried)) {
            retried.close()
            throw LoginRequiredException()
        }
        return retried
    }

    private fun restoreSession() {
        var url = loginUrl
        repeat(10) {
            authenticationClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful && response.request.url.host == loginUrl.host &&
                    response.request.url.encodedPath.endsWith("/index_initMenu.html")) {
                    return
                }
                if (response.code !in listOf(301, 302, 303, 307, 308)) {
                    if (response.code >= 500) {
                        throw IOException("Authentication server returned HTTP ${response.code}")
                    }
                    throw LoginRequiredException()
                }
                val location = response.header("Location") ?: throw LoginRequiredException()
                val target = url.resolve(location) ?: throw LoginRequiredException()
                url = trustedRedirect(target) ?: throw LoginRequiredException()
            }
        }
        throw LoginRequiredException()
    }

    private fun trustedRedirect(target: HttpUrl): HttpUrl? {
        var url = target
        if (loginUrl.isHttps && !url.isHttps) {
            // The teaching site currently registers an HTTP OAuth callback.
            // Upgrade it before sending the authorization code.
            if (url.host != loginUrl.host) return null
            url = url.newBuilder().scheme("https").build()
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val teachingOrigin = url.host == loginUrl.host && url.port == loginUrl.port
        val accountOrigin = url.host == accountUrl.host && url.port == accountUrl.port
        return if (teachingOrigin || accountOrigin) url else null
    }

    private fun isLoginUrl(url: HttpUrl): Boolean {
        return (url.host == accountUrl.host && url.encodedPath.startsWith(accountUrl.encodedPath)) ||
            (url.host == loginUrl.host &&
                url.encodedPath in listOf(loginUrl.encodedPath, "/xtgl/login_slogin.html"))
    }

    private fun requiresLogin(response: Response): Boolean {
        try {
            if (response.code == 401 || isLoginUrl(response.request.url)) return true
            val location = response.header("Location")
            if (response.isRedirect && location != null) {
                val target = response.request.url.resolve(location)
                if (target != null && isLoginUrl(target)) return true
            }
            val prefix = response.peekBody(65_536).string().trimStart()
            return prefix.startsWith("<") && prefix.contains("jAccount")
        } catch (error: IOException) {
            response.close()
            throw error
        }
    }
}
