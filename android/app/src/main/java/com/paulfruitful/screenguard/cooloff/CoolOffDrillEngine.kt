package com.paulfruitful.screenguard.cooloff

enum class ExerciseType {
    TIMED_GUIDED,
    GUIDED_BREATHING,
    THOUGHT_REFRAMING,
    TRIGGER_REFLECTION,
    VISUALISATION,
    SCRIPTURE_MEDITATION
}

data class ReframingPair(
    val automaticThought: String,
    val healthierAlternative: String
)

data class CoolOffExercise(
    val id: String,
    val name: String,
    val description: String,
    val minDurationMinutes: Int,
    val maxDurationMinutes: Int,
    val defaultDurationMinutes: Int,
    val type: ExerciseType,
    val instructions: List<String>,
    val reframingPairs: List<ReframingPair> = emptyList(),
    val reflectionPrompts: List<String> = emptyList(),
    val theme: String,
    val scriptureReference: String,
    val requiredInteractions: Int = 0
)

/**
 * Extensible Psychological Exercise Library & Drill Progression Engine.
 * Supports 1-, 3-, and 5-minute sessions and validates completion criteria.
 */
object CoolOffDrillEngine {

    val EXERCISES: List<CoolOffExercise> = listOf(
        CoolOffExercise(
            id = "urge_surfing",
            name = "Urge Surfing",
            description = "Acknowledge the urge, observe its physical sensations, and let the wave crest and pass without acting on it.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 3,
            type = ExerciseType.TIMED_GUIDED,
            instructions = listOf(
                "Sit comfortably and place both feet flat on the floor.",
                "Recognize the urge clearly without judging or shaming yourself.",
                "Notice where you feel tension or restlessness in your body.",
                "Observe how the sensation rises, shifts, and gradually softens.",
                "Remind yourself calmly: an urge is a temporary wave, not a command."
            ),
            theme = "urge_surfing",
            scriptureReference = "1 Corinthians 10:13",
            requiredInteractions = 0
        ),
        CoolOffExercise(
            id = "guided_breathing",
            name = "Guided Breathing",
            description = "Slow your nervous system with a steady 4-second inhale and 6-second exhale rhythm.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 1,
            type = ExerciseType.GUIDED_BREATHING,
            instructions = listOf(
                "Relax your jaw and drop your shoulders away from your ears.",
                "Inhale slowly through your nose for 4 seconds.",
                "Exhale gently through your mouth for 6 seconds.",
                "Keep your eyes on the breathing ring as your heart rate settles."
            ),
            theme = "breathing",
            scriptureReference = "Galatians 5:22–23",
            requiredInteractions = 0
        ),
        CoolOffExercise(
            id = "thought_reframing",
            name = "Thought Reframing",
            description = "Challenge impulsive automatic thoughts and intentionally adopt a grounded, truthful alternative.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 3,
            type = ExerciseType.THOUGHT_REFRAMING,
            instructions = listOf(
                "Read each automatic thought honestly without rushing.",
                "Tap the healthier alternative to replace the old mental script.",
                "Repeat the new statement slowly to yourself as the timer finishes."
            ),
            reframingPairs = listOf(
                ReframingPair(
                    automaticThought = "“I need to satisfy this urge right now.”",
                    healthierAlternative = "“I can experience this feeling without acting on it.”"
                ),
                ReframingPair(
                    automaticThought = "“Just one quick look won't hurt anything.”",
                    healthierAlternative = "“One look feeds the loop; walking away builds real freedom.”"
                ),
                ReframingPair(
                    automaticThought = "“I'm stressed or bored, so I deserve an escape.”",
                    healthierAlternative = "“Real rest comes from peace and integrity, not a shortcut I'll regret.”"
                )
            ),
            theme = "thought_reframing",
            scriptureReference = "Philippians 4:8",
            requiredInteractions = 3
        ),
        CoolOffExercise(
            id = "trigger_reflection",
            name = "Trigger Reflection",
            description = "Identify what led up to this moment so you can interrupt the pattern earlier next time.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 3,
            type = ExerciseType.TRIGGER_REFLECTION,
            instructions = listOf(
                "Take a quiet moment with each reflection question below.",
                "Tap each prompt once you have answered it honestly in your mind."
            ),
            reflectionPrompts = listOf(
                "What were you doing right before this happened?",
                "What were you feeling (stressed, lonely, tired, bored, anxious)?",
                "What specific cue, app, or thought triggered the urge?",
                "What is one concrete thing you can do differently in the next 10 minutes?"
            ),
            theme = "trigger_reflection",
            scriptureReference = "James 1:19–20",
            requiredInteractions = 4
        ),
        CoolOffExercise(
            id = "future_self_visualisation",
            name = "Future-Self Visualisation",
            description = "Mentally rehearse encountering a trigger and making a calm, deliberate choice to walk away.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 3,
            type = ExerciseType.VISUALISATION,
            instructions = listOf(
                "Picture yourself tomorrow morning feeling clear-headed and proud of this moment.",
                "Visualise encountering a familiar trigger on your phone.",
                "See yourself pausing, taking one deep breath, and locking the screen.",
                "Notice the quiet relief and self-respect that follows choosing self-control.",
                "Carry that deliberate posture back with you when this timer finishes."
            ),
            theme = "future_self",
            scriptureReference = "2 Timothy 1:7",
            requiredInteractions = 0
        ),
        CoolOffExercise(
            id = "scripture_meditation",
            name = "Scripture Meditation",
            description = "Anchor your mind in truth by reading and reflecting slowly on scripture.",
            minDurationMinutes = 1,
            maxDurationMinutes = 5,
            defaultDurationMinutes = 3,
            type = ExerciseType.SCRIPTURE_MEDITATION,
            instructions = listOf(
                "Read the scripture passage above slowly, word by word.",
                "Pause on the phrase that speaks most directly to this moment.",
                "Reflect quietly on the prompt beneath the passage.",
                "Breathe calmly and commit to walking in clarity for the next hour."
            ),
            theme = "meditation",
            scriptureReference = "Psalm 119:9–11",
            requiredInteractions = 0
        )
    )

    fun findById(id: String?): CoolOffExercise {
        if (id.isNullOrBlank()) return EXERCISES.first()
        return EXERCISES.firstOrNull { it.id == id } ?: EXERCISES.first()
    }

    fun selectForDuration(durationMinutes: Int, rotationIndex: Int = 0): CoolOffExercise {
        val candidates = EXERCISES.filter {
            durationMinutes in it.minDurationMinutes..it.maxDurationMinutes
        }.ifEmpty { EXERCISES }
        val idx = ((rotationIndex % candidates.size) + candidates.size) % candidates.size
        return candidates[idx]
    }

    /**
     * Returns the currently highlighted instruction index for progressive display.
     */
    fun currentStepIndex(exercise: CoolOffExercise, elapsedSec: Int, totalSec: Int): Int {
        if (exercise.instructions.isEmpty()) return 0
        val safeTotal = totalSec.coerceAtLeast(1)
        val fraction = (elapsedSec.toFloat() / safeTotal.toFloat()).coerceIn(0f, 0.999f)
        return (fraction * exercise.instructions.size).toInt()
            .coerceIn(0, exercise.instructions.lastIndex)
    }

    /**
     * Validates strict completion criteria:
     * - Timed exercises: countdown timer must reach total duration.
     * - Interactive exercises: BOTH timer completion AND required interactions must be fulfilled.
     */
    fun isCompletionValid(
        exercise: CoolOffExercise,
        elapsedSec: Int,
        totalSec: Int,
        completedInteractions: Int
    ): Boolean {
        val timerFinished = totalSec > 0 && elapsedSec >= totalSec
        if (!timerFinished) return false
        return completedInteractions >= exercise.requiredInteractions
    }
}
