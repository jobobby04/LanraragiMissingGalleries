package com.jobobby.missing

import kotlinx.serialization.Serializable

@Serializable
data class LanraragiArchive(
    val arcid: String,
    val extension: String,
    val isnew: String,
    val pagecount: Int,
    val progress: Int,
    val tags: String,
    val title: String
)