package app.pocketagent.android.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Custom credential headers (e.g. x-api-key) are not stripped by OkHttp on redirects.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
