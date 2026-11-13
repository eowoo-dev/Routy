package com.noonmaru.routy.pleos.data

import com.noonmaru.routy.contracts.api.DefaultApi
import com.noonmaru.routy.contracts.model.HealthResponse
import com.noonmaru.routy.pleos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class HealthRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val api = DefaultApi(basePath = BuildConfig.API_BASE_URL, client = client)

    suspend fun getHealth(): HealthResponse = withContext(Dispatchers.IO) { api.getHealth() }
}
