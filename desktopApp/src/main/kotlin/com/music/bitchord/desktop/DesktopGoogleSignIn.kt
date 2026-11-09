package com.music.bitchord.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.coroutines.cancellation.CancellationException

/**
 * Native sign-in: BitChord drives the real Google login page itself in an embedded Chromium
 * (JCEF), the same way Electron-based YouTube Music clients do, rather than scraping an
 * external browser's cookie store. Identical on every platform, so there's no browser
 * detection, no throwaway profile, and no sandbox permissions to fight.
 */
internal object DesktopGoogleSignIn {
    private const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
            "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    private const val MUSIC_URL = "https://music.youtube.com/"
    private const val CAPTURE_POLLS = 15
    private const val POLL_INTERVAL_MS = 1_000

    @Volatile
    private var cefApp: CefApp? = null

    private fun app(): CefApp = cefApp ?: synchronized(this) {
        cefApp ?: build().also { cefApp = it }
    }

    private fun build(): CefApp {
        val builder = CefAppBuilder()
        builder.setInstallDir(File(dataHome(), "jcef"))
        // The AppImage can't ship CEF's setuid sandbox helper, and GPU compositing is a common
        // source of a black, unrendered browser window on Linux without a working GL stack.
        builder.addJcefArgs("--no-sandbox")
        builder.addJcefArgs("--disable-gpu")
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
        return builder.build()
    }

    private fun dataHome(): File {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val base = xdg ?: "${System.getProperty("user.home")}/.local/share"
        return File(base, "bitchord")
    }

    /** Opens the embedded Google login page and returns the resulting YouTube cookie string. */
    suspend fun capture(): String {
        val result = CompletableDeferred<Result<String>>()
        SwingUtilities.invokeLater {
            try {
                openWindow(result)
            } catch (failure: Exception) {
                result.complete(Result.failure(failure))
            }
        }
        return withContext(Dispatchers.IO) { result.await() }.getOrThrow()
    }

    private fun openWindow(result: CompletableDeferred<Result<String>>) {
        val client = app().createClient()
        val browser = client.createBrowser(LOGIN_URL, false, false)

        val frame = JFrame("Sign in to YouTube Music")
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(browser.getUIComponent(), BorderLayout.CENTER)
        frame.preferredSize = Dimension(480, 640)
        frame.pack()
        frame.setLocationRelativeTo(null)

        val finished = AtomicBoolean(false)
        fun finish(outcome: Result<String>) {
            if (!finished.compareAndSet(false, true)) return
            result.complete(outcome)
            SwingUtilities.invokeLater {
                frame.isVisible = false
                frame.dispose()
                client.dispose()
            }
        }

        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                finish(Result.failure(CancellationException("Sign-in window was closed.")))
            }
        })

        val pollingStarted = AtomicBoolean(false)
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                loadedBrowser: CefBrowser,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (isLoading) return
                val url = loadedBrowser.getURL() ?: return
                if (!url.startsWith(MUSIC_URL)) return
                if (pollingStarted.compareAndSet(false, true)) pollForCookies(::finish)
            }
        })

        frame.isVisible = true
    }

    private fun pollForCookies(finish: (Result<String>) -> Unit) {
        var attempts = 0
        lateinit var timer: Timer
        timer = Timer(POLL_INTERVAL_MS) {
            attempts++
            readCookies { header ->
                when {
                    DesktopBrowserCookies.hasSigningSecret(header) -> {
                        timer.stop()
                        finish(Result.success(header))
                    }
                    attempts >= CAPTURE_POLLS -> {
                        timer.stop()
                        finish(
                            Result.failure(
                                IllegalStateException(
                                    "Signed in, but YouTube Music did not return a session. Try again.",
                                ),
                            ),
                        )
                    }
                }
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    /** Reads the current YouTube cookie jar; [onResult] runs exactly once, on the EDT. */
    private fun readCookies(onResult: (String) -> Unit) {
        val manager = CefCookieManager.getGlobalManager()
        val jar = LinkedHashMap<String, String>()
        val delivered = AtomicBoolean(false)
        fun deliver() {
            if (delivered.compareAndSet(false, true)) {
                onResult(jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        // visitAllCookies visits asynchronously; count == total - 1 marks the last cookie. If the
        // jar is empty the visitor may never run at all, so a short fallback timer covers that.
        val scheduled = manager.visitAllCookies { cookie, count, total, delete ->
            val domain = cookie.domain.removePrefix(".")
            if (domain == "youtube.com" || domain.endsWith(".youtube.com")) {
                jar[cookie.name] = cookie.value
            }
            delete.set(false)
            if (count == total - 1) deliver()
            true
        }
        if (!scheduled) {
            deliver()
        } else {
            Timer(500) { deliver() }.apply { isRepeats = false; start() }
        }
    }
}
