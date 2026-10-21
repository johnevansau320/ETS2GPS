package com.ets2gps

/** Pictograms for the points of interest: SVG paths on a 24 x 24 grid (same as the web and Windows apps), filled even-odd. */
object PoiIcons {
    val paths: Map<Int, String> = mapOf(
        1 to "M5 3h9a1 1 0 0 1 1 1v17h1v1H4v-1h1z M7 5v5h6V5z M15 9h1.5a2 2 0 0 1 2 2v5a.5 .5 0 0 0 1 0v-6l-2-2 1-1 3 3v6a2.5 2.5 0 0 1-5 0v-5H15z",
        2 to "M20.5 6.8a5 5 0 0 1-6.6 5.9L7 19.6a2 2 0 0 1-2.8-2.8l6.9-6.9A5 5 0 0 1 17.2 3.5l-3 3 .8 2.8 2.8 .8z",
        3 to "M7 3h6.5a5 5 0 0 1 0 10H11v8H7z M11 7v2.5h2.5a1.25 1.25 0 0 0 0-2.5z",
        4 to "M3 6h18v2H3z M11 3h2v16h-2z M7 19h10v2H7z M5 8l-3.2 7a3.2 3.2 0 0 0 6.4 0z M19 8l-3.2 7a3.2 3.2 0 0 0 6.4 0z",
        5 to "M1.5 5h12v11h-12z M14.5 8h3.8l3.2 3.7V16h-7z M5 16.5a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 1 0-4.4 0z M15.6 16.5a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 1 0-4.4 0z",
        6 to "M2 10l10-7 10 7v11H2z M6 21h12V12H6z M8 14h8v1.5H8z M8 17h8v1.5H8z",
        7 to "M12 3.5a3.7 3.7 0 1 0 0 7.4 3.7 3.7 0 1 0 0-7.4z M4.5 21c0-4.4 3.2-7.4 7.5-7.4s7.5 3 7.5 7.4z",
        8 to "M3 5h2v9h16v6h-2v-3H5v3H3z M7 8h4a2 2 0 0 1 2 2v2H7z M14 9h4a3 3 0 0 1 3 3v2h-7z",
        9 to "M2 14.5l2.6 5.5h14.8l2.6-5.5z M5.5 12V8h13v4z M9.5 8V4.5h5V8z",
        10 to "M7 3h10a2 2 0 0 1 2 2v10a3 3 0 0 1-3 3l2 3h-2l-1.5-2h-5L8 21H6l2-3a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2z M7 5.5v5h10v-5z M8 13.4a1.3 1.3 0 1 0 2.6 0 1.3 1.3 0 1 0-2.6 0z M13.4 13.4a1.3 1.3 0 1 0 2.6 0 1.3 1.3 0 1 0-2.6 0z",
    )

    val colors: Map<Int, Int> = mapOf(
        1 to 0xFF32CD32.toInt(),
        2 to 0xFF78828F.toInt(),
        3 to 0xFF286EDC.toInt(),
        4 to 0xFF965ABE.toInt(),
        5 to 0xFFBE3C3C.toInt(),
        6 to 0xFF3C8C78.toInt(),
        7 to 0xFFA08C28.toInt(),
        8 to 0xFF5A5AAA.toInt(),
        9 to 0xFF0078AA.toInt(),
        10 to 0xFF6E6E7C.toInt(),
    )
}
