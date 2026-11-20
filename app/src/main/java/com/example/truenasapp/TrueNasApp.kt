package com.example.truenasapp

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.truenasapp.data.SecureStore
import com.example.truenasapp.data.SessionManager
import com.example.truenasapp.data.TrueNasApi
import com.example.truenasapp.data.TrueNasClient
import com.example.truenasapp.lock.AppLockManager
import com.example.truenasapp.perf.PerformanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One shared instance of each "service" for the whole app. */
object Graph {
    lateinit var store: SecureStore; private set
    lateinit var client: TrueNasClient; private set
    lateinit var api: TrueNasApi; private set
    lateinit var session: SessionManager; private set
    lateinit var lock: AppLockManager; private set
    lateinit var perf: PerformanceManager; private set

    /** For work that should outlive a screen (downloads, logout). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(app: Application) {
        store = SecureStore(app)
        perf = PerformanceManager(app, store)
        client = TrueNasClient(store, perf)
        api = TrueNasApi(client, store)
        session = SessionManager(store, client, perf)
        lock = AppLockManager(store)
        session.onSessionEnded = { lock.reset() }
    }
}

class TrueNasApp : Application(), ImageLoaderFactory {

    /**
     * Image loader for app icons.
     * Icons hosted on your NAS go through the app's connection (so self-signed https works);
     * icons from the internet (TrueNAS's catalog CDN) use a normal client with normal checks.
     */
    override fun newImageLoader(): ImageLoader {
        val internet = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(this)
            .callFactory(object : Call.Factory {
                override fun newCall(request: Request): Call {
                    val nasHost = Graph.store.serverUrl?.toHttpUrlOrNull()?.host
                    return if (request.url.host == nasHost) Graph.client.http.newCall(request)
                    else internet.newCall(request)
                }
            })
            .components { add(SvgDecoder.Factory()) }
            // Bigger image cache on phones with more RAM, smaller on 6 GB and below.
            .memoryCache {
                val ramGb = Graph.perf.device.ramGb
                val share = when { ramGb >= 8 -> 0.20; ramGb >= 6 -> 0.15; else -> 0.10 }
                MemoryCache.Builder(this).maxSizePercent(share).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("app_icons"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false) // icons rarely change; keep them even if the CDN says not to
            .crossfade(!Graph.perf.device.lowRamDevice)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.perf.start()

        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(Graph.lock)
        processLifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Graph.perf.onForeground()
                Graph.session.onAppForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                Graph.perf.onBackground()
                Graph.session.onAppBackground()
            }
        })

        // Wi-Fi / mobile data / VPN changed: check the connection right away instead of
        // waiting for a dead socket to time out.
        getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = Graph.session.onNetworkChanged()
                override fun onLost(network: Network) = Graph.session.onNetworkChanged()
            })

        Graph.session.resumeIfLoggedIn()
    }
}
