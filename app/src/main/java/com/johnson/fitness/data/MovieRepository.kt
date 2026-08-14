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
            "這是線上mp4測試影片",
            "這是m3u8測試影片",
            "Introducing Gmail Blue",
            "Introducing Google Fiber to the Pole",
            "Introducing Google Nose"
        )
        val description = "Fusce id nisi turpis. Praesent viverra bibendum semper. " +
            "Donec tristique, orci sed semper lacinia, quam erat rhoncus massa, non congue tellus est " +
            "quis tellus. Sed mollis orci venenatis quam scelerisque accumsan."
        val studios = arrayOf("Studio Zero", "Studio One", "Studio Two", "Studio Three", "Studio Four")
        val videoUrls = arrayOf(
            "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/video-137.mp4",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Gmail%20Blue.mp4",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Fiber%20to%20the%20Pole.mp4",
            "https://commondatastorage.googleapis.com/android-tv/Sample%20videos/April%20Fool's%202013/Introducing%20Google%20Nose.mp4"
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
