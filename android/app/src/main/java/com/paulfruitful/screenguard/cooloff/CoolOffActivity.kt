package com.paulfruitful.screenguard.cooloff

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.paulfruitful.screenguard.MainActivity

/**
 * Full-screen Cool-Off Room Activity using Screen Guard's exact Royal Blue & Gold design system.
 *
 * Reliability guarantees:
 * - Uses monotonic [SystemClock.elapsedRealtime] delta + persisted [CoolOffSessionManager] state
 *   so timer progress survives screen lock, app switching, and Activity recreation.
 * - Escalated state is ONLY cleared when [CoolOffSessionManager.tryCompleteSession] validates
 *   both timer completion and any required interactive steps.
 * - Background instrumental audio fades in smoothly on start, pauses cleanly on lifecycle pause,
 *   and fades out on session completion.
 */
class CoolOffActivity : Activity() {

    companion object {
        const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"
        const val EXTRA_FORCE_NEW_SESSION = "extra_force_new_session"

        @Volatile
        var isCurrentlyForeground: Boolean = false
            private set

        fun launch(
            context: Context,
            durationMinutes: Int? = null,
            forceNewSession: Boolean = false
        ) {
            val intent = Intent(context, CoolOffActivity::class.java).apply {
                if (durationMinutes != null) {
                    putExtra(EXTRA_DURATION_MINUTES, durationMinutes.coerceIn(1, 60))
                }
                putExtra(EXTRA_FORCE_NEW_SESSION, forceNewSession)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            // Prefer PendingIntent with explicit BAL allow options on Android 14+, with direct startActivity fallback
            var launched = false
            try {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getActivity(context, 4901, intent, flags)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && optionsBundle != null) {
                    pi.send(context, 0, null, null, null, null, optionsBundle)
                } else {
                    pi.send()
                }
                launched = true
            } catch (_: Throwable) {
            }
            if (!launched) {
                try {
                    if (optionsBundle != null) {
                        context.startActivity(intent, optionsBundle)
                    } else {
                        context.startActivity(intent)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    // Screen Guard existing palette (Royal Blue, Gold, Crisp White, Soft Blue-Gray)
    private val colorRoyalBlue = Color.parseColor("#0C2D84")
    private val colorRoyalBlueDeep = Color.parseColor("#092266")
    private val colorGold = Color.parseColor("#F5B82E")
    private val colorGoldText = Color.parseColor("#0A1D4E")
    private val colorSurface = Color.parseColor("#FFFFFF")
    private val colorSurfaceSubtle = Color.parseColor("#F8FAFF")
    private val colorBorder = Color.parseColor("#E2E8F4")
    private val colorTextPrimary = Color.parseColor("#0F1F4B")
    private val colorTextSecondary = Color.parseColor("#5B6B8C")
    private val colorTextOnBlueMuted = Color.parseColor("#C7D6F8")
    private val colorSuccess = Color.parseColor("#1BB55C")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioEngine: CoolOffAudioEngine
    private lateinit var session: CoolOffSessionState
    private lateinit var exercise: CoolOffExercise
    private lateinit var scripture: CoolOffScripture

    private var baseElapsedSeconds: Int = 0
    private var activeResumedMonoMs: Long = 0L
    private var isTicking: Boolean = false
    private val completedInteractiveIndices = mutableSetOf<Int>()

    // Dynamic UI references
    private lateinit var rootContainer: FrameLayout
    private lateinit var activeSessionScroll: ScrollView
    private lateinit var completionContainer: LinearLayout

    private lateinit var timerText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressCaption: TextView
    private lateinit var exerciseTitleText: TextView
    private lateinit var exerciseDescText: TextView
    private lateinit var instructionsContainer: LinearLayout
    private lateinit var interactiveContainer: LinearLayout
    private lateinit var breathingView: BreathingGuideView
    private lateinit var breathingLabelText: TextView
    private lateinit var scriptureRefText: TextView
    private lateinit var scripturePassageText: TextView
    private lateinit var scriptureContextText: TextView
    private lateinit var scripturePromptText: TextView
    private lateinit var audioCategoryText: TextView
    private lateinit var muteButton: TextView
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var durationPillsRow: LinearLayout
    private lateinit var completeCtaHint: TextView

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isTicking || session.isCompleted) return
            val elapsed = currentElapsedSeconds()
            renderTimerAndProgress(elapsed)

            if (exercise.type == ExerciseType.GUIDED_BREATHING) {
                updateBreathingAnimation()
            }

            if (elapsed >= session.durationSeconds) {
                attemptCompletionIfValid(elapsed)
            }

            if (isTicking && !session.isCompleted) {
                handler.postDelayed(this, 250L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioEngine = CoolOffAudioEngine(applicationContext)

        val requestedDuration = if (intent.hasExtra(EXTRA_DURATION_MINUTES)) {
            intent.getIntExtra(EXTRA_DURATION_MINUTES, CoolOffPolicyConfig.DEFAULT_SESSION_DURATION_MINUTES)
        } else null
        val forceNew = intent.getBooleanExtra(EXTRA_FORCE_NEW_SESSION, false)

        session = if (forceNew) {
            CoolOffSessionManager.startFreshSession(this, requestedDuration)
        } else {
            CoolOffSessionManager.getOrCreateSession(this, requestedDuration)
        }
        bindModelsFromSession()
        buildUi()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        isCurrentlyForeground = true
        if (!session.isCompleted) {
            baseElapsedSeconds = session.elapsedSeconds
            activeResumedMonoMs = SystemClock.elapsedRealtime()
            isTicking = true
            audioEngine.start(
                initialCategory = session.audioCategory,
                initialVolume = session.audioVolume,
                initialMuted = session.audioMuted
            )
            handler.removeCallbacks(tickRunnable)
            handler.post(tickRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        isCurrentlyForeground = false
        pauseTimerAndPersist()
        audioEngine.stopImmediately()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        audioEngine.stopImmediately()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If the user is in an escalated Cool-Off-required state and has not completed the session,
        // do not silently bypass; allow leaving only to Screen Guard Home while escalation persists.
        if (session.isCompleted) {
            navigateToSafeHome()
            return
        }
        pauseTimerAndPersist()
        super.onBackPressed()
    }

    private fun bindModelsFromSession() {
        exercise = CoolOffDrillEngine.findById(session.exerciseId)
        scripture = CoolOffScriptureRepository.findByReference(session.scriptureReference)
        baseElapsedSeconds = session.elapsedSeconds
        completedInteractiveIndices.clear()
        for (i in 0 until session.completedInteractions) {
            completedInteractiveIndices.add(i)
        }
    }

    private fun currentElapsedSeconds(): Int {
        if (!isTicking || activeResumedMonoMs <= 0L) {
            return baseElapsedSeconds.coerceIn(0, session.durationSeconds)
        }
        val deltaSec = ((SystemClock.elapsedRealtime() - activeResumedMonoMs) / 1000L).toInt().coerceAtLeast(0)
        return (baseElapsedSeconds + deltaSec).coerceIn(0, session.durationSeconds)
    }

    private fun pauseTimerAndPersist() {
        if (isTicking) {
            baseElapsedSeconds = currentElapsedSeconds()
            isTicking = false
            handler.removeCallbacks(tickRunnable)
            CoolOffSessionManager.updateProgress(
                context = this,
                elapsedSeconds = baseElapsedSeconds,
                completedInteractions = completedInteractiveIndices.size
            )?.let { session = it }
        }
    }

    private fun attemptCompletionIfValid(elapsed: Int) {
        val ok = CoolOffSessionManager.tryCompleteSession(
            context = this,
            elapsedSeconds = elapsed,
            completedInteractions = completedInteractiveIndices.size
        )
        if (ok) {
            session = session.copy(
                elapsedSeconds = session.durationSeconds,
                completedInteractions = completedInteractiveIndices.size,
                isCompleted = true
            )
            isTicking = false
            handler.removeCallbacks(tickRunnable)
            audioEngine.fadeOutAndStop()
            showCompletionScreen()
        } else {
            val remaining = (exercise.requiredInteractions - completedInteractiveIndices.size).coerceAtLeast(0)
            if (remaining > 0) {
                completeCtaHint.visibility = View.VISIBLE
                completeCtaHint.text = "Timer complete — finish $remaining remaining reflection step${if (remaining == 1) "" else "s"} above to complete your Cool-Off."
            }
        }
    }

    private fun buildUi() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(colorRoyalBlue)
        }

        // 1. Active Session ScrollView
        activeSessionScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dpInt(20f)
            setPadding(padH, dpInt(42f), padH, dpInt(32f))
        }

        // Top Branding Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(16f) }
        }
        val shield = SmallShieldEmblemView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(36f), dpInt(36f)).apply {
                marginEnd = dpInt(10f)
            }
        }
        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brandCol.addView(TextView(this).apply {
            text = "Screen Guard · Cool-Off Room"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        brandCol.addView(TextView(this).apply {
            text = "Protection remains active · 100% private"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colorTextOnBlueMuted)
        })
        topBar.addView(shield)
        topBar.addView(brandCol)
        content.addView(topBar)

        // Encouraging Heading
        content.addView(TextView(this).apply {
            text = "Pause. Breathe. Choose Self-Control."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(14f) }
        })

        // Timer & Progress Card
        val timerCard = createWhiteCard()
        val timerTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10f) }
        }
        timerText = TextView(this).apply {
            text = "03:00"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        durationPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        timerTopRow.addView(timerText)
        timerTopRow.addView(durationPillsRow)
        timerCard.addView(timerTopRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpInt(8f)
            ).apply { bottomMargin = dpInt(6f) }
        }
        timerCard.addView(progressBar)

        progressCaption = TextView(this).apply {
            text = "Session in progress"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorTextSecondary)
        }
        timerCard.addView(progressCaption)
        content.addView(timerCard)

        // Active Psychological Exercise Card (Primary Focus)
        val exerciseCard = createWhiteCard()
        val exHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(6f) }
        }
        exerciseTitleText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(colorTextPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchExBtn = TextView(this).apply {
            text = "Switch Exercise"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pillDrawable(Color.parseColor("#EEF3FA"), colorBorder)
            setPadding(dpInt(12f), dpInt(6f), dpInt(12f), dpInt(6f))
            setOnClickListener { switchToNextExercise() }
        }
        exHeaderRow.addView(exerciseTitleText)
        exHeaderRow.addView(switchExBtn)
        exerciseCard.addView(exHeaderRow)

        exerciseDescText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorTextSecondary)
            setLineSpacing(dp(2.5f), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(14f) }
        }
        exerciseCard.addView(exerciseDescText)

        // Guided Breathing Visual (only visible for GUIDED_BREATHING)
        breathingView = BreathingGuideView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(132f), dpInt(132f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpInt(8f)
            }
            visibility = View.GONE
        }
        breathingLabelText = TextView(this).apply {
            text = "Inhale slowly · 4s"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(12f) }
            visibility = View.GONE
        }
        exerciseCard.addView(breathingView)
        exerciseCard.addView(breathingLabelText)

        instructionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        exerciseCard.addView(instructionsContainer)

        interactiveContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(8f) }
        }
        exerciseCard.addView(interactiveContainer)

        completeCtaHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpInt(10f) }
        }
        exerciseCard.addView(completeCtaHint)
        content.addView(exerciseCard)

        // Offline Scripture Card
        val scriptureCard = createWhiteCard()
        val scriptureHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(8f) }
        }
        scriptureRefText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        scriptureHeader.addView(scriptureRefText)
        scriptureHeader.addView(TextView(this).apply {
            text = "WEB · Offline"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colorTextSecondary)
        })
        scriptureCard.addView(scriptureHeader)

        scripturePassageText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorTextPrimary)
            typeface = Typeface.create("serif", Typeface.ITALIC)
            setLineSpacing(dp(3f), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10f) }
        }
        scriptureCard.addView(scripturePassageText)

        scriptureContextText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colorTextSecondary)
            setLineSpacing(dp(2f), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(8f) }
        }
        scriptureCard.addView(scriptureContextText)

        scripturePromptText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f)
                setColor(colorSurfaceSubtle)
                setStroke(dpInt(1f), colorBorder)
            }
            setPadding(dpInt(12f), dpInt(10f), dpInt(12f), dpInt(10f))
        }
        scriptureCard.addView(scripturePromptText)
        content.addView(scriptureCard)

        // Background Instrumental Audio Card
        val audioCard = createWhiteCard()
        val audioTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10f) }
        }
        val audioTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        audioTitleCol.addView(TextView(this).apply {
            text = "Calming Instrumental"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(colorTextPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        audioCategoryText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorTextSecondary)
        }
        audioTitleCol.addView(audioCategoryText)

        val nextTrackBtn = TextView(this).apply {
            text = "Change Style"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(colorRoyalBlue)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pillDrawable(Color.parseColor("#EEF3FA"), colorBorder)
            setPadding(dpInt(11f), dpInt(6f), dpInt(11f), dpInt(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpInt(8f) }
            setOnClickListener { cycleAudioCategory() }
        }

        muteButton = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pillDrawable(colorRoyalBlue, colorRoyalBlue)
            setPadding(dpInt(12f), dpInt(6f), dpInt(12f), dpInt(6f))
            setOnClickListener { toggleAudioMute() }
        }

        audioTopRow.addView(audioTitleCol)
        audioTopRow.addView(nextTrackBtn)
        audioTopRow.addView(muteButton)
        audioCard.addView(audioTopRow)

        volumeSeekBar = SeekBar(this).apply {
            max = 100
            progress = (session.audioVolume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val vol = (progress / 100f).coerceIn(0f, 1f)
                    audioEngine.setVolume(vol)
                    CoolOffSessionManager.updateAudioPreferences(
                        this@CoolOffActivity,
                        session.audioCategory,
                        session.audioMuted,
                        vol
                    )?.let { session = it }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        audioCard.addView(volumeSeekBar)
        content.addView(audioCard)

        activeSessionScroll.addView(content)
        rootContainer.addView(activeSessionScroll)

        // 2. Completion Screen Container (hidden until validated completion)
        completionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(colorRoyalBlue)
            val pad = dpInt(24f)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        val doneCard = createWhiteCard().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = dpInt(24f)
            val padV = dpInt(30f)
            setPadding(padH, padV, padH, padV)
        }
        doneCard.addView(SmallShieldEmblemView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(68f), dpInt(68f)).apply {
                bottomMargin = dpInt(14f)
            }
        })
        doneCard.addView(TextView(this).apply {
            text = "✓  Cool-Off Completed"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pillDrawable(colorSuccess, colorSuccess)
            setPadding(dpInt(14f), dpInt(6f), dpInt(14f), dpInt(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(16f) }
        })
        doneCard.addView(TextView(this).apply {
            text = "You made a deliberate choice to pause. That's progress. Keep going."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19.5f)
            setTextColor(colorTextPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4f), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(12f) }
        })
        doneCard.addView(TextView(this).apply {
            text = "Screen Guard protection remains active in the background."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(22f) }
        })
        doneCard.addView(TextView(this).apply {
            text = "Return to Safe Browsing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(colorGoldText)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pillDrawable(colorGold, colorGold)
            setPadding(dpInt(20f), dpInt(15f), dpInt(20f), dpInt(15f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { navigateToSafeHome() }
        })
        completionContainer.addView(doneCard)
        rootContainer.addView(completionContainer)

        setContentView(rootContainer)
    }

    private fun renderAll() {
        if (session.isCompleted) {
            showCompletionScreen()
            return
        }
        activeSessionScroll.visibility = View.VISIBLE
        completionContainer.visibility = View.GONE
        completeCtaHint.visibility = View.GONE

        renderDurationPills()
        renderTimerAndProgress(currentElapsedSeconds())

        exerciseTitleText.text = exercise.name
        exerciseDescText.text = exercise.description

        val isBreathing = exercise.type == ExerciseType.GUIDED_BREATHING
        breathingView.visibility = if (isBreathing) View.VISIBLE else View.GONE
        breathingLabelText.visibility = if (isBreathing) View.VISIBLE else View.GONE

        renderInstructions(currentElapsedSeconds())
        renderInteractiveWidgets()

        scriptureRefText.text = scripture.reference
        scripturePassageText.text = "“${scripture.passage}”"
        scriptureContextText.text = scripture.contextExplanation
        scripturePromptText.text = "Reflection: ${scripture.reflectionPrompt}"

        audioCategoryText.text = "${session.audioCategory.displayName} · Offline loop"
        muteButton.text = if (session.audioMuted) "Unmute" else "Mute"
        volumeSeekBar.progress = (session.audioVolume * 100).toInt()
    }

    private fun renderDurationPills() {
        durationPillsRow.removeAllViews()
        val currentMinutes = session.durationSeconds / 60
        for (min in CoolOffPolicyConfig.SUPPORTED_DURATIONS_MINUTES) {
            val selected = min == currentMinutes
            val pill = TextView(this).apply {
                text = "${min}m"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (selected) colorGoldText else colorTextSecondary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pillDrawable(
                    if (selected) colorGold else Color.parseColor("#EEF3FA"),
                    if (selected) colorGold else colorBorder
                )
                setPadding(dpInt(11f), dpInt(6f), dpInt(11f), dpInt(6f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dpInt(6f) }
                setOnClickListener {
                    if (min != currentMinutes && currentElapsedSeconds() < 15) {
                        session = CoolOffSessionManager.startFreshSession(
                            context = this@CoolOffActivity,
                            durationMinutes = min,
                            exerciseId = exercise.id
                        )
                        bindModelsFromSession()
                        activeResumedMonoMs = SystemClock.elapsedRealtime()
                        renderAll()
                    }
                }
            }
            durationPillsRow.addView(pill)
        }
    }

    private fun renderTimerAndProgress(elapsed: Int) {
        val remaining = (session.durationSeconds - elapsed).coerceAtLeast(0)
        val mins = remaining / 60
        val secs = remaining % 60
        timerText.text = String.format("%02d:%02d", mins, secs)

        val ratio = if (session.durationSeconds > 0) {
            (elapsed.toFloat() / session.durationSeconds.toFloat()).coerceIn(0f, 1f)
        } else 1f
        progressBar.progress = (ratio * 1000).toInt()
        val pct = (ratio * 100).toInt()
        progressCaption.text = if (exercise.requiredInteractions > 0) {
            "Progress $pct% · Interactive steps: ${completedInteractiveIndices.size}/${exercise.requiredInteractions}"
        } else {
            "Progress $pct% · Stay present until the timer completes"
        }

        renderInstructions(elapsed)
    }

    private fun renderInstructions(elapsed: Int) {
        val activeIdx = CoolOffDrillEngine.currentStepIndex(exercise, elapsed, session.durationSeconds)
        instructionsContainer.removeAllViews()
        exercise.instructions.forEachIndexed { idx, stepText ->
            val isCurrent = idx == activeIdx
            val isPast = idx < activeIdx
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12f)
                    setColor(
                        when {
                            isCurrent -> Color.parseColor("#EEF3FA")
                            else -> Color.TRANSPARENT
                        }
                    )
                }
                setPadding(dpInt(10f), dpInt(8f), dpInt(10f), dpInt(8f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(4f) }
            }
            val num = TextView(this).apply {
                text = if (isPast) "✓" else "${idx + 1}."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (isPast) colorSuccess else colorRoyalBlue)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dpInt(24f), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val body = TextView(this).apply {
                text = stepText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(if (isCurrent) colorTextPrimary else colorTextSecondary)
                typeface = if (isCurrent) {
                    Typeface.create("sans-serif-medium", Typeface.BOLD)
                } else {
                    Typeface.create("sans-serif", Typeface.NORMAL)
                }
                setLineSpacing(dp(2f), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(num)
            row.addView(body)
            instructionsContainer.addView(row)
        }
    }

    private fun renderInteractiveWidgets() {
        interactiveContainer.removeAllViews()
        when (exercise.type) {
            ExerciseType.THOUGHT_REFRAMING -> {
                exercise.reframingPairs.forEachIndexed { idx, pair ->
                    val reframed = completedInteractiveIndices.contains(idx)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(14f)
                            setColor(if (reframed) Color.parseColor("#EDF9F2") else colorSurfaceSubtle)
                            setStroke(dpInt(1f), if (reframed) colorSuccess else colorBorder)
                        }
                        setPadding(dpInt(14f), dpInt(12f), dpInt(14f), dpInt(12f))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dpInt(8f) }
                        setOnClickListener {
                            completedInteractiveIndices.add(idx)
                            CoolOffSessionManager.updateProgress(
                                this@CoolOffActivity,
                                currentElapsedSeconds(),
                                completedInteractiveIndices.size
                            )?.let { session = it }
                            renderInteractiveWidgets()
                            val elapsed = currentElapsedSeconds()
                            renderTimerAndProgress(elapsed)
                            if (elapsed >= session.durationSeconds) {
                                attemptCompletionIfValid(elapsed)
                            }
                        }
                    }
                    card.addView(TextView(this).apply {
                        text = if (reframed) "✓ Healthier Truth Adopted" else "Automatic Thought (Tap to Reframe)"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(if (reframed) colorSuccess else colorTextSecondary)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    })
                    card.addView(TextView(this).apply {
                        text = if (reframed) pair.healthierAlternative else pair.automaticThought
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setTextColor(colorTextPrimary)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dpInt(4f) }
                    })
                    interactiveContainer.addView(card)
                }
            }
            ExerciseType.TRIGGER_REFLECTION -> {
                exercise.reflectionPrompts.forEachIndexed { idx, prompt ->
                    val checked = completedInteractiveIndices.contains(idx)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(14f)
                            setColor(if (checked) Color.parseColor("#EDF9F2") else colorSurfaceSubtle)
                            setStroke(dpInt(1f), if (checked) colorSuccess else colorBorder)
                        }
                        setPadding(dpInt(14f), dpInt(12f), dpInt(14f), dpInt(12f))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dpInt(8f) }
                        setOnClickListener {
                            completedInteractiveIndices.add(idx)
                            CoolOffSessionManager.updateProgress(
                                this@CoolOffActivity,
                                currentElapsedSeconds(),
                                completedInteractiveIndices.size
                            )?.let { session = it }
                            renderInteractiveWidgets()
                            val elapsed = currentElapsedSeconds()
                            renderTimerAndProgress(elapsed)
                            if (elapsed >= session.durationSeconds) {
                                attemptCompletionIfValid(elapsed)
                            }
                        }
                    }
                    card.addView(TextView(this).apply {
                        text = if (checked) "✓" else "○"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(if (checked) colorSuccess else colorRoyalBlue)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(dpInt(26f), LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    card.addView(TextView(this).apply {
                        text = prompt
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(colorTextPrimary)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    interactiveContainer.addView(card)
                }
            }
            else -> Unit
        }
    }

    private fun updateBreathingAnimation() {
        // 10-second cycle: 0..4s Inhale, 4..10s Exhale
        val cycleMs = SystemClock.elapsedRealtime() % 10_000L
        if (cycleMs < 4_000L) {
            val fraction = cycleMs / 4_000f
            val secLeft = (4 - (cycleMs / 1000L)).coerceAtLeast(1)
            breathingView.setPhase(fraction, isInhale = true)
            breathingLabelText.text = "Inhale slowly · ${secLeft}s"
        } else {
            val fraction = 1f - ((cycleMs - 4_000L) / 6_000f)
            val secLeft = (6 - ((cycleMs - 4_000L) / 1000L)).coerceAtLeast(1)
            breathingView.setPhase(fraction, isInhale = false)
            breathingLabelText.text = "Exhale gently · ${secLeft}s"
        }
    }

    private fun switchToNextExercise() {
        val currentIdx = CoolOffDrillEngine.EXERCISES.indexOfFirst { it.id == exercise.id }
        val next = CoolOffDrillEngine.EXERCISES[(currentIdx + 1) % CoolOffDrillEngine.EXERCISES.size]
        val elapsedSoFar = currentElapsedSeconds()
        session = CoolOffSessionManager.startFreshSession(
            context = this,
            durationMinutes = session.durationSeconds / 60,
            exerciseId = next.id
        ).let { fresh ->
            // Preserve elapsed seconds so switching exercises does not penalize progress
            CoolOffSessionManager.updateProgress(this, elapsedSoFar, 0) ?: fresh
        }
        bindModelsFromSession()
        activeResumedMonoMs = SystemClock.elapsedRealtime()
        renderAll()
    }

    private fun cycleAudioCategory() {
        val all = InstrumentalCategory.entries
        val next = all[(all.indexOf(session.audioCategory) + 1) % all.size]
        audioEngine.setCategory(next)
        CoolOffSessionManager.updateAudioPreferences(
            this,
            next,
            session.audioMuted,
            session.audioVolume
        )?.let { session = it }
        audioCategoryText.text = "${session.audioCategory.displayName} · Offline loop"
    }

    private fun toggleAudioMute() {
        val nextMuted = !session.audioMuted
        audioEngine.setMuted(nextMuted)
        CoolOffSessionManager.updateAudioPreferences(
            this,
            session.audioCategory,
            nextMuted,
            session.audioVolume
        )?.let { session = it }
        muteButton.text = if (nextMuted) "Unmute" else "Mute"
    }

    private fun showCompletionScreen() {
        activeSessionScroll.visibility = View.GONE
        completionContainer.visibility = View.VISIBLE
    }

    private fun navigateToSafeHome() {
        try {
            val sgHome = Intent(this, MainActivity::class.java).apply {
                putExtra("navigateToTab", "HOME")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(sgHome)
        } catch (_: Exception) {
        }
        finish()
    }

    private fun createWhiteCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f)
                setColor(colorSurface)
                setStroke(dpInt(1f), colorBorder)
            }
            val padH = dpInt(18f)
            val padV = dpInt(16f)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(14f) }
        }
    }

    private fun pillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999f)
            setColor(fillColor)
            setStroke(dpInt(1f), strokeColor)
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )

    private fun dpInt(value: Float): Int = dp(value).toInt()

    private class BreathingGuideView(context: Context) : View(context) {
        private var phaseFraction: Float = 0.2f
        private var inhale: Boolean = true

        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#CBD5E8")
        }
        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0C2D84")
        }
        private val goldRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#F5B82E")
        }

        fun setPhase(fraction: Float, isInhale: Boolean) {
            phaseFraction = fraction.coerceIn(0f, 1f)
            inhale = isInhale
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = minOf(cx, cy) - 10f
            val minR = maxR * 0.42f
            val currentR = minR + (maxR - minR) * phaseFraction

            canvas.drawCircle(cx, cy, maxR, outerRingPaint)
            innerCirclePaint.alpha = if (inhale) 235 else 190
            canvas.drawCircle(cx, cy, currentR, innerCirclePaint)
            canvas.drawCircle(cx, cy, currentR, goldRimPaint)
        }
    }

    private class SmallShieldEmblemView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0C2D84")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#F5B82E")
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val shieldPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            shieldPath.reset()
            shieldPath.moveTo(w * 0.50f, h * 0.08f)
            shieldPath.lineTo(w * 0.85f, h * 0.21f)
            shieldPath.lineTo(w * 0.85f, h * 0.50f)
            shieldPath.quadTo(w * 0.85f, h * 0.78f, w * 0.50f, h * 0.93f)
            shieldPath.quadTo(w * 0.15f, h * 0.78f, w * 0.15f, h * 0.50f)
            shieldPath.lineTo(w * 0.15f, h * 0.21f)
            shieldPath.close()

            canvas.drawPath(shieldPath, fillPaint)
            canvas.drawPath(shieldPath, strokePaint)

            val cx = w * 0.50f
            val cy = h * 0.49f
            val arm = w * 0.16f
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        }
    }
}
