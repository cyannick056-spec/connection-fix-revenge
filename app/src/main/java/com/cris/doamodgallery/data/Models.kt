package com.cris.doamodgallery.data

data class ModItem(
    val id: String,
    val title: String,
    val pageUrl: String,
    val previewUrl: String = "",
    val hdUrl: String = "",
    val character: String = "",
    val megaName: String = "",
    val megaHandle: String = "",
    val megaKeyBase64: String = "",
    val megaIvBase64: String = "",
    val megaSize: Long = 0L,
    val megaScore: Double = 0.0
)

data class MegaFile(
    val handle: String,
    val parentHandle: String,
    val name: String,
    val path: String,
    val character: String,
    val size: Long,
    val fileKeyBase64: String,
    val ivBase64: String
)

data class MegaDownloadInfo(val url: String, val size: Long)
data class AppCache(val items: List<ModItem> = emptyList(), val updatedAt: Long = 0L)
data class MegaCache(val files: List<MegaFile> = emptyList(), val updatedAt: Long = 0L)
