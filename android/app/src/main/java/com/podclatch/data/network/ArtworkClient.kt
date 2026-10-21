package com.podclatch.data.network

import com.podclatch.Config
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The HTTP client Coil uses for artwork. It is built from scratch with no interceptor: the bearer token
 * is added only inside [OkHttpPodClatchApi], per request, so artwork requests (which go to arbitrary
 * publisher hosts) can never carry it.
 */
fun artworkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(Config.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(Config.IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
