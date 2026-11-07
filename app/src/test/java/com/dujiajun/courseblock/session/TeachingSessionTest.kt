package com.dujiajun.courseblock.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class TeachingSessionTest {
    private class BrowserCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
    }

    private fun authenticatedClient(client: OkHttpClient, server: MockWebServer): OkHttpClient {
        return TeachingSession.client(client, server.url("/login"), server.url("/sso/"))
    }

    @Test
    fun restoresExpiredTeachingSessionUsingExistingAccountSession() {
        MockWebServer().use { server ->
            val paths = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths.add(request.path!!)
                    return when (request.path) {
                        "/courses?term=1" -> {
                            assertEquals("POST", request.method)
                            assertEquals("year=2026", request.body.readUtf8())
                            if (request.getHeader("Cookie").orEmpty().contains("JSESSIONID=fresh")) {
                                MockResponse().setBody("""{"kbList": []}""")
                            } else {
                                MockResponse().setBody("<html>jAccount</html>")
                            }
                        }
                        "/login" -> MockResponse().setResponseCode(302)
                            .addHeader("Location", "/sso/authorize")
                        "/sso/authorize" -> MockResponse().setResponseCode(302)
                            .addHeader("Location", "/login?code=test")
                        "/login?code=test" -> MockResponse().setResponseCode(302)
                            .addHeader("Set-Cookie", "JSESSIONID=fresh; Path=/")
                            .addHeader("Location", "/xtgl/index_initMenu.html")
                        "/xtgl/index_initMenu.html" -> MockResponse().setBody("Timetable home")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val client = OkHttpClient.Builder().cookieJar(BrowserCookies()).build()
            val request = Request.Builder().url(server.url("/courses?term=1"))
                .post(okhttp3.FormBody.Builder().add("year", "2026").build()).build()
            authenticatedClient(client, server).newCall(request).execute().use { result ->
                assertEquals(200, result.code)
                assertEquals("""{"kbList": []}""", result.body!!.string())
            }
            assertEquals(listOf("/courses?term=1", "/login", "/sso/authorize", "/login?code=test",
                "/xtgl/index_initMenu.html", "/courses?term=1"), paths)
        }
    }

    @Test
    fun authenticatedJsonContainingAccountNameDoesNotStartLogin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"note":"jAccount","kbList":[]}"""))
            val client = authenticatedClient(OkHttpClient(), server)
            client.newCall(Request.Builder().url(server.url("/courses")).build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun expiredAccountRequiresInteractiveLoginWithoutReplayingDataRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "/xtgl/login_slogin.html"))
            server.enqueue(MockResponse().setBody("<html>jAccount password form</html>"))
            val client = authenticatedClient(OkHttpClient(), server)
            assertThrows(TeachingSession.LoginRequiredException::class.java) {
                client.newCall(Request.Builder().url(server.url("/courses")).build()).execute()
            }
            assertEquals(2, server.requestCount)
            assertEquals("/courses", server.takeRequest().path)
            assertEquals("/login", server.takeRequest().path)
        }
    }

    @Test
    fun rejectedRetryDoesNotRenewAgain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "/xtgl/index_initMenu.html"))
            server.enqueue(MockResponse().setBody("Home"))
            server.enqueue(MockResponse().setResponseCode(401))
            val client = authenticatedClient(OkHttpClient(), server)
            assertThrows(TeachingSession.LoginRequiredException::class.java) {
                client.newCall(Request.Builder().url(server.url("/courses")).build()).execute()
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun authenticationServiceFailureIsReportedAsNetworkFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(503))
            val client = authenticatedClient(OkHttpClient(), server)
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/courses")).build()).execute()
            }
            assertTrue(error !is TeachingSession.LoginRequiredException)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun unexpectedAuthenticationRedirectIsNotFollowed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302)
                .addHeader("Location", "https://unexpected.example/callback?code=test"))
            val client = authenticatedClient(OkHttpClient(), server)
            assertThrows(TeachingSession.LoginRequiredException::class.java) {
                client.newCall(Request.Builder().url(server.url("/courses")).build()).execute()
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun redirectLoopStopsAfterTenAuthenticationRequests() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            repeat(10) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))
            }
            val client = authenticatedClient(OkHttpClient(), server)
            assertThrows(TeachingSession.LoginRequiredException::class.java) {
                client.newCall(Request.Builder().url(server.url("/courses")).build()).execute()
            }
            assertEquals(11, server.requestCount)
        }
    }

    @Test
    fun concurrentExpiredRequestsShareOneSuccessfulRecovery() {
        MockWebServer().use { server ->
            val bothRequests = CountDownLatch(2)
            val loginPaths = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/courses" -> {
                            if (request.getHeader("Cookie").orEmpty().contains("JSESSIONID=fresh")) {
                                MockResponse().setBody("{}")
                            } else {
                                bothRequests.countDown()
                                assertTrue(bothRequests.await(4, TimeUnit.SECONDS))
                                MockResponse().setResponseCode(401)
                            }
                        }
                        "/login" -> {
                            loginPaths.add(request.path!!)
                            MockResponse().setResponseCode(302)
                                .addHeader("Set-Cookie", "JSESSIONID=fresh; Path=/")
                                .addHeader("Location", "/xtgl/index_initMenu.html")
                        }
                        else -> MockResponse().setBody("Home")
                    }
                }
            }
            val client = authenticatedClient(OkHttpClient.Builder().cookieJar(BrowserCookies()).build(), server)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val requests = (1..2).map {
                    executor.submit<Int> {
                        client.newCall(Request.Builder().url(server.url("/courses")).build())
                            .execute().use { response -> response.code }
                    }
                }
                requests.forEach { assertEquals(200, it.get(6, TimeUnit.SECONDS)) }
                assertEquals(listOf("/login"), loginPaths)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun teachingCallbackUsesHttpsEvenWhenRegisteredRedirectIsHttp() {
        val visited = mutableListOf<HttpUrl>()
        var dataRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            visited.add(request.url)
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .message("Fixture").body("".toResponseBody())
            when (request.url.encodedPath) {
                "/courses" -> response.code(if (++dataRequests == 1) 401 else 200)
                "/login" -> response.code(302)
                    .header("Location", "http://teaching.test/callback?code=test")
                "/callback" -> response.code(302)
                    .header("Location", "/xtgl/index_initMenu.html")
                else -> response.code(200)
            }.build()
        }.build()
        val authenticated = TeachingSession.client(client, "https://teaching.test/login".toHttpUrl(),
            "https://account.test/".toHttpUrl())
        authenticated.newCall(Request.Builder().url("https://teaching.test/courses").build())
            .execute().use { assertEquals(200, it.code) }
        assertTrue(visited.all { it.isHttps })
        assertEquals("https://teaching.test/callback?code=test", visited[2].toString())
    }

    @Test
    fun cancellationStopsAnInProgressAuthenticationRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("Login").setHeadersDelay(3, TimeUnit.SECONDS))
            val client = authenticatedClient(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), server)
            val call = client.newCall(Request.Builder().url(server.url("/courses")).build())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Int> { call.execute().use { it.code } }
                assertEquals("/courses", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                assertEquals("/login", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                call.cancel()
                val error = assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }
                assertTrue(error.cause is IOException)
                assertEquals(2, server.requestCount)
            } finally {
                call.cancel()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun concurrentRequestsShareFailedRecoveryAndLaterRequestsCanTryAgain() {
        MockWebServer().use { server ->
            val bothRequests = CountDownLatch(2)
            val loginPaths = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/courses") {
                        bothRequests.countDown()
                        assertTrue(bothRequests.await(4, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(401)
                    }
                    loginPaths.add(request.path!!)
                    return MockResponse().setBody("Interactive login needed")
                }
            }
            val client = authenticatedClient(OkHttpClient(), server)
            val request = Request.Builder().url(server.url("/courses")).build()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val attempts = (1..2).map {
                    executor.submit<Boolean> {
                        try {
                            client.newCall(request).execute().close()
                            false
                        } catch (error: TeachingSession.LoginRequiredException) {
                            true
                        }
                    }
                }
                attempts.forEach { assertTrue(it.get(6, TimeUnit.SECONDS)) }
                assertEquals(listOf("/login"), loginPaths)
                assertThrows(TeachingSession.LoginRequiredException::class.java) {
                    client.newCall(request).execute()
                }
                assertEquals(listOf("/login", "/login"), loginPaths)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
