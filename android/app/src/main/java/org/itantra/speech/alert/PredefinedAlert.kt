package org.itantra.speech.alert

/**
 * Predefined mission-critical emergency alerts for iTantra.
 *
 * Each alert defines:
 * - alertId: Unique persistent key for audio caching and deduplication
 * - title: Short human-readable title (English)
 * - hindiText: Exact Hindi Devanagari text for pre-synthesis and playback
 */
enum class PredefinedAlert(
    val alertId: String,
    val title: String,
    val hindiText: String
) {
    FIRE_EVACUATION(
        alertId = "alert_fire_001",
        title = "Fire Evacuation",
        hindiText = "आग लग गई है। तुरंत इमारत खाली करें।"
    ),
    MEDICAL_ASSISTANCE(
        alertId = "alert_medical_002",
        title = "Medical Assistance",
        hindiText = "चिकित्सा सहायता की आवश्यकता है। कृपया तुरंत डॉक्टर भेजें।"
    ),
    HAZARD_WARNING(
        alertId = "alert_hazard_003",
        title = "Hazard Warning",
        hindiText = "खतरे की चेतावनी। सभी लोग सुरक्षित स्थान पर जाएं।"
    ),
    EMERGENCY_NOTIFICATION(
        alertId = "alert_emergency_004",
        title = "Emergency Notification",
        hindiText = "आपातकालीन सूचना। शांति बनाए रखें और निर्देशों का पालन करें।"
    );

    companion object {
        fun findById(alertId: String): PredefinedAlert? {
            return values().firstOrNull { it.alertId == alertId }
        }
    }
}
