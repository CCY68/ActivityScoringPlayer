package com.johnson.fitness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 免驗證端點 `course/info` 回應的解析（不打網路，只測解析與錯誤訊息）。 */
class CoursePlayUrlRepositoryTest {

    @Test
    fun `正常回應取出 playUrl`() {
        val body = """
            {"code":200,"msg":"SUCCESS","data":{"playUrl":"https://cdn.example/vod/hls.m3u8",
            "courseTitle":"銀髮族健康操","courseDesc":null,"courseTags":["Welltivity"]},
            "requestId":"abc"}
        """.trimIndent()
        val result = CoursePlayUrlRepository.parsePlayUrl(body)
        assertTrue(result is CoursePlayUrlRepository.Result.Success)
        assertEquals(
            "https://cdn.example/vod/hls.m3u8",
            (result as CoursePlayUrlRepository.Result.Success).playUrl
        )
    }

    @Test
    fun `未登入之類的錯誤回應給明確訊息`() {
        val result = CoursePlayUrlRepository.parsePlayUrl(
            """{"code":401,"msg":"请先登录","data":null}"""
        )
        assertTrue(result is CoursePlayUrlRepository.Result.Failure)
        assertTrue((result as CoursePlayUrlRepository.Result.Failure).message.contains("401"))
    }

    @Test
    fun `data 裡沒有 playUrl 時視為不可播放`() {
        val result = CoursePlayUrlRepository.parsePlayUrl(
            """{"code":200,"data":{"courseTitle":"某課程"}}"""
        )
        assertTrue(result is CoursePlayUrlRepository.Result.Failure)
    }

    @Test
    fun `回應不是 JSON 時不丟例外`() {
        val result = CoursePlayUrlRepository.parsePlayUrl("<html>502 Bad Gateway</html>")
        assertTrue(result is CoursePlayUrlRepository.Result.Failure)
    }

    @Test
    fun `端點樣板帶得進課程編號`() {
        assertEquals(
            "https://asia.welltivity.com.tw/api/app/open/course/info?courseId=17421781954041251",
            CoursePlayUrlRepository.COURSE_INFO_ENDPOINT.replace("{courseId}", "17421781954041251")
        )
    }
}
