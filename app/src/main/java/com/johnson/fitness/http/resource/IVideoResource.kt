package com.johnson.fitness.http.resource

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.QueryMap

interface IVideoResource {

    @GET("/stock/bull-bear-force-data")
    suspend fun getVideoData(@QueryMap body: Map<String, Long>): Response<Unit>
}