package com.johnson.fitness.ui.common

import com.johnson.fitness.model.Movie
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseCardStyleTest {

    @Test
    fun `時長格式`() {
        assertEquals("", CourseCardStyle.formatDuration(0))
        assertEquals("", CourseCardStyle.formatDuration(-5))
        assertEquals("15 分鐘", CourseCardStyle.formatDuration(900))
        assertEquals("20 分鐘", CourseCardStyle.formatDuration(1200))
        assertEquals("57 分鐘", CourseCardStyle.formatDuration(3420))
        assertEquals("1 小時", CourseCardStyle.formatDuration(3600))
        assertEquals("1 小時 5 分", CourseCardStyle.formatDuration(3900))
    }

    @Test
    fun `可評分標記看的是有沒有 maf`() {
        val scorable = Movie(id = 1L, courseId = "1", title = "A", mafAsset = "A-1.maf")
        val playOnly = Movie(id = 2L, courseId = "2", title = "B", mafAsset = null)
        assertEquals("可評分", CourseCardStyle.scoringBadge(scorable))
        assertEquals("僅播放", CourseCardStyle.scoringBadge(playOnly))
    }
}
