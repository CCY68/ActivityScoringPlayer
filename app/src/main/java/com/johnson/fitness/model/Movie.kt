package com.johnson.fitness.model

data class Movie(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val backgroundImageUrl: String = "",
    val cardImageUrl: String = "",
    val videoUrl: String = "",
    val studio: String = ""
)
