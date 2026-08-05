package com.johnson.fitness.http

import com.google.gson.JsonSyntaxException
import com.johnson.fitness.http.resource.NetworkError
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import retrofit2.HttpException
import java.io.IOException
import java.net.*

class RequestInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {

        val requestBuilder = chain.request()
            .newBuilder()

        val request = requestBuilder.build()
        try {
            val response = chain.proceed(request)
            return response
        } catch (e: Exception) { // 處理網路異常
            val networkError = when  {
                e.message == "Canceled" || chain.call().isCanceled() -> NetworkError.NO_SHOW_ERROR
                e is SocketTimeoutException -> NetworkError.TIMEOUT
                e is UnknownHostException -> NetworkError.NO_SHOW_ERROR
                e is SocketException ->
                    NetworkError.NO_SHOW_ERROR
                e is IOException -> NetworkError.CONNECTION_ERROR
                e is HttpException -> NetworkError.HTTP_ERROR
                e is JsonSyntaxException || e is JSONException -> NetworkError.PARSE_ERROR
                else -> NetworkError.UNKNOWN_ERROR
            }
            val errorJson = "{\"error\": \"${networkError.name}\", \"code\": ${networkError.code}, \"message\": \"${e.message}\"}"
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(networkError.code)
                .message(e.message ?: "error")
                .body(errorJson.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
            return response
        }
    }
}