package com.example.domain

/**
 * Default launch-center coordinates for initial map centering and fallback telemetry
 * before a live GPS fix arrives (Borai, Mohanpur Block, Paschim Medinipur, West Bengal).
 * Does NOT restrict rides or drivers in any other West Bengal district.
 */
object BoraiLaunchConfig {
    const val DEFAULT_PLACE_ID = "borai_721457"
    const val DEFAULT_AREA_NAME = "Borai Market & Stand (PIN 721457)"
    const val DEFAULT_DISPLAY_LABEL = "Borai Market & Stand (PIN 721457), Mohanpur, Paschim Medinipur"
    const val DEFAULT_DISTRICT = "Paschim Medinipur"
    const val DEFAULT_BLOCK = "Mohanpur"
    const val DEFAULT_PIN_CODE = "721457"
    const val DEFAULT_LAT = 21.8160
    const val DEFAULT_LNG = 87.3703
    const val DEFAULT_ZOOM = 13.5
}
