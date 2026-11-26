package com.paulfruitful.screenguard.cooloff

import kotlin.random.Random

data class CoolOffScripture(
    val reference: String,
    val passage: String,
    val translation: String,
    val contextExplanation: String,
    val reflectionPrompt: String,
    val themes: List<String>
)

/**
 * Offline local scripture library using the public-domain World English Bible (WEB).
 * Supports theme-based selection for psychological exercises and scripture meditation.
 */
object CoolOffScriptureRepository {

    const val TRANSLATION_WEB = "WEB (Public Domain)"

    val ALL_SCRIPTURES: List<CoolOffScripture> = listOf(
        CoolOffScripture(
            reference = "1 Corinthians 10:13",
            passage = "No temptation has taken you except what is common to man. God is faithful, who will not allow you to be tempted above what you are able, but will with the temptation also make the way of escape, that you may be able to endure it.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Every urge feels uniquely urgent, yet you are never trapped by it. Pausing right now is the way of escape.",
            reflectionPrompt = "What is the practical way of escape available to you in this exact moment?",
            themes = listOf("temptation", "urge_surfing", "endurance", "escape")
        ),
        CoolOffScripture(
            reference = "Galatians 5:22–23",
            passage = "But the fruit of the Spirit is love, joy, peace, patience, kindness, goodness, faith, gentleness, and self-control. Against such things there is no law.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Self-control is grown through repeated, quiet choices to pause rather than react on impulse.",
            reflectionPrompt = "How does choosing peace and self-control right now align with the person you want to be?",
            themes = listOf("self_control", "breathing", "peace", "patience")
        ),
        CoolOffScripture(
            reference = "2 Timothy 1:7",
            passage = "For God didn't give us a spirit of fear, but of power, love, and self-control.",
            translation = TRANSLATION_WEB,
            contextExplanation = "You are not powerless in front of a screen. You have been equipped with a sound, disciplined mind.",
            reflectionPrompt = "What would exercising calm strength and a sound mind look like over the next hour?",
            themes = listOf("discipline", "courage", "future_self", "self_control")
        ),
        CoolOffScripture(
            reference = "Philippians 4:8",
            passage = "Finally, brothers, whatever things are true, whatever things are honorable, whatever things are just, whatever things are pure, whatever things are lovely, whatever things are of good report: if there is any virtue and if there is any praise, think about these things.",
            translation = TRANSLATION_WEB,
            contextExplanation = "What holds your attention shapes your appetite. Redirecting your thoughts is an active skill you can practice.",
            reflectionPrompt = "Name one true, constructive, or meaningful thing you can shift your attention toward after this pause.",
            themes = listOf("guarding_thoughts", "thought_reframing", "purity", "mind")
        ),
        CoolOffScripture(
            reference = "Psalm 119:9–11",
            passage = "How can a young man keep his way pure? By living according to your word. With my whole heart I have sought you. Don't let me wander from your commandments. I have hidden your word in my heart, that I might not sin against you.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Integrity is protected long before the moment of temptation by what you regularly store in your heart.",
            reflectionPrompt = "Which short phrase from this passage can you repeat to yourself when the next urge appears?",
            themes = listOf("purity", "discipline", "meditation", "scripture")
        ),
        CoolOffScripture(
            reference = "Romans 12:2",
            passage = "Don't be conformed to this world, but be transformed by the renewing of your mind, so that you may prove what is the good, well-pleasing, and perfect will of God.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Habit loops can be rewired. Every time you interrupt an automatic habit, you strengthen a renewed mind.",
            reflectionPrompt = "What old mental script is trying to run right now, and what healthier truth replaces it?",
            themes = listOf("renewing_mind", "thought_reframing", "future_self", "transformation")
        ),
        CoolOffScripture(
            reference = "James 1:19–20",
            passage = "So, then, my beloved brothers, let every man be swift to hear, slow to speak, and slow to anger; for the anger of man doesn't produce the righteousness of God.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Slowing down between stimulus and response breaks impulsive cycles—whether driven by stress, boredom, or frustration.",
            reflectionPrompt = "What emotion was sitting underneath this urge before you reached for your phone?",
            themes = listOf("pausing", "trigger_reflection", "breathing", "patience")
        ),
        CoolOffScripture(
            reference = "Matthew 26:41",
            passage = "Watch and pray, that you don't enter into temptation. The spirit indeed is willing, but the flesh is weak.",
            translation = TRANSLATION_WEB,
            contextExplanation = "Self-awareness without shame is wisdom. Recognizing vulnerable moments early lets you step away before the pull grows.",
            reflectionPrompt = "What time of day, place, or mood makes you most vulnerable, and how can you guard it today?",
            themes = listOf("watchfulness", "prayer", "trigger_reflection", "temptation")
        )
    )

    fun findByReference(reference: String?): CoolOffScripture {
        if (reference.isNullOrBlank()) return ALL_SCRIPTURES.first()
        return ALL_SCRIPTURES.firstOrNull {
            it.reference.equals(reference, ignoreCase = true)
        } ?: ALL_SCRIPTURES.first()
    }

    fun selectForTheme(theme: String, seed: Int = -1): CoolOffScripture {
        val normalized = theme.trim().lowercase()
        val matches = ALL_SCRIPTURES.filter { scripture ->
            scripture.themes.any { it.equals(normalized, ignoreCase = true) }
        }
        val pool = if (matches.isNotEmpty()) matches else ALL_SCRIPTURES
        val idx = if (seed >= 0) seed % pool.size else Random.nextInt(pool.size)
        return pool[idx]
    }
}
