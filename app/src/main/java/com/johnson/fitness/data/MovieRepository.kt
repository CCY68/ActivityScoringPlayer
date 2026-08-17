package com.johnson.fitness.data

import com.johnson.fitness.model.Movie

object MovieRepository {

    val categories = arrayOf(
        "Category Zero", "Category One", "Category Two",
        "Category Three", "Category Four", "Category Five"
    )

    val movies: List<Movie> by lazy { buildMovies() }

    fun getMovieById(id: Long): Movie? = movies.find { it.id == id }

    private fun buildMovies(): List<Movie> {
        val titles = arrayOf(
            "銀髮族健康操",
            "初階瑜珈體位法 1 - 英雄1 & 英雄2",
            "太極藝術體驗課 (中文字幕)",
            "mp4影片測試",
            "兔子影片測試"
        )
        val description = "Fusce id nisi turpis. Praesent viverra bibendum semper. " +
            "Donec tristique, orci sed semper lacinia, quam erat rhoncus massa, non congue tellus est " +
            "quis tellus. Sed mollis orci venenatis quam scelerisque accumsan."
        val studios = arrayOf("Studio Zero", "Studio One", "Studio Two", "Studio Three", "Studio Four")
        val videoUrls = arrayOf(
            "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/cd5651d3-9c24-4930-8f0f-0c6c8ecedc15/vod/hls.m3u8",
            "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/209581d1-c9cb-477e-aaa9-386b9033219e/vod/hls.m3u8",
            "https://75d61619-eeb7-4283-b5ed-36e1930a7dcf.cdn.blendvision.com/6ddcc065-ee8f-4449-a4c7-f9d9eb11a978/vod/763e63bb-5ad2-43e5-9d7f-695f1ece3ec9/vod/hls.m3u8",
            "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/video-137.mp4",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        )
        val bgImageUrls = arrayOf(
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Zeitgeist/Zeitgeist%202010_%20Year%20in%20Review/bg.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Demo%20Slam/Google%20Demo%20Slam_%2020ft%20Search/bg.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Gmail%20Blue/bg.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Fiber%20to%20the%20Pole/bg.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Nose/bg.jpg"
        )
        val cardImageUrls = arrayOf(
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Zeitgeist/Zeitgeist%202010_%20Year%20in%20Review/card.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/Demo%20Slam/Google%20Demo%20Slam_%2020ft%20Search/card.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Gmail%20Blue/card.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Fiber%20to%20the%20Pole/card.jpg",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Nose/card.jpg"
        )

        return titles.indices.map { i ->
            Movie(
                id = i.toLong(),
                title = titles[i],
                description = description,
                studio = studios[i],
                videoUrl = videoUrls[i],
                cardImageUrl = cardImageUrls[i],
                backgroundImageUrl = bgImageUrls[i]
            )
        }
    }
}
