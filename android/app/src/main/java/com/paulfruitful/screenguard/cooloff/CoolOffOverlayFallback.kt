package com.paulfruitful.screenguard.cooloff

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Unbreakable native overlay views for Screen Guard's Royal Blue & Gold design system:
 *
 * 1. [buildTakeABreakChooserView]: Shown immediately after exiting the social app when the user
 *    clicks "Take a Break", allowing them to choose ANY Cool-Off interval (1..60 minutes) or finish.
 * 2. [InOverlayCoolOffController]: Full interactive Cool-Off Room rendered directly inside the
 *    Accessibility Overlay whenever an OEM ROM (e.g. Xiaomi HyperOS MIUIOP 10021) blocks
 *    background `startActivity(CoolOffActivity)`. Guarantees zero dead clicks on Cool Off!
 */
object CoolOffOverlayViews {

    private val ROYAL_BLUE = Color.parseColor("#0C2D84")
    private val GOLD = Color.parseColor("#F5B82E")
    private val GOLD_TEXT = Color.parseColor("#0A1D4E")
    private val SURFACE = Color.parseColor("#FFFFFF")
    private val SURFACE_SUBTLE = Color.parseColor("#F8FAFF")
    private val BORDER = Color.parseColor("#E2E8F4")
    private val TEXT_PRIMARY = Color.parseColor("#0F1F4B")
    private val TEXT_SECONDARY = Color.parseColor("#5B6B8C")
    private val SUCCESS = Color.parseColor("#1BB55C")

    fun buildTakeABreakChooserView(
        context: Context,
        closedAppName: String,
        initialMinutes: Int,
        onStartCoolOff: (Int) -> Unit,
        onSkipBreak: () -> Unit
    ): View {
        var selectedMinutes = initialMinutes.coerceIn(1, 60)
        val presetButtons = mutableListOf<Pair<Int, TextView>>()
        lateinit var customCountText: TextView
        lateinit var startCtaBtn: TextView

        fun refreshSelectionUi() {
            customCountText.text = "$selectedMinutes min"
            startCtaBtn.text = "Start $selectedMinutes-Minute Cool-Off"
            for ((preset, tv) in presetButtons) {
                val active = preset == selectedMinutes
                tv.setTextColor(if (active) Color.WHITE else TEXT_SECONDARY)
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(if (active) ROYAL_BLUE else Color.parseColor("#EEF3FA"))
                    setStroke(dpInt(context, 1f), if (active) ROYAL_BLUE else BORDER)
                }
            }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 24f)
                setColor(SURFACE)
                setStroke(dpInt(context, 1f), BORDER)
            }
            setPadding(dpInt(context, 24f), dpInt(context, 26f), dpInt(context, 24f), dpInt(context, 26f))
        }

        val badge = TextView(context).apply {
            text = "✓  Break Started"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(SUCCESS)
            }
            setPadding(dpInt(context, 14f), dpInt(context, 6f), dpInt(context, 14f), dpInt(context, 6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 14f) }
        }
        card.addView(badge)

        val heading = TextView(context).apply {
            text = "Good call taking a break."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 8f) }
        }
        card.addView(heading)

        val appLabel = closedAppName.ifBlank { "the feed" }
        val subtitle = TextView(context).apply {
            text = "You've stepped away from $appLabel. Want to cool off and reset your focus? Choose any time interval below."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 3f), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 18f) }
        }
        card.addView(subtitle)

        // Preset duration pills: 1m, 3m, 5m, 10m, 15m
        val presetsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 14f) }
        }
        for (min in listOf(1, 3, 5, 10, 15)) {
            val pill = TextView(context).apply {
                text = "${min}m"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dpInt(context, 13f), dpInt(context, 9f), dpInt(context, 13f), dpInt(context, 9f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpInt(context, 4f)
                    marginEnd = dpInt(context, 4f)
                }
                setOnClickListener {
                    selectedMinutes = min
                    refreshSelectionUi()
                }
            }
            presetButtons.add(min to pill)
            presetsRow.addView(pill)
        }
        card.addView(presetsRow)

        // Custom interval stepper (- / X min / +) so user can pick ANY time interval (1..60 min)
        val stepperBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 16f)
                setColor(SURFACE_SUBTLE)
                setStroke(dpInt(context, 1f), BORDER)
            }
            setPadding(dpInt(context, 14f), dpInt(context, 10f), dpInt(context, 14f), dpInt(context, 10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 18f) }
        }

        val minusBtn = TextView(context).apply {
            text = "−"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ROYAL_BLUE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6EEFC"))
            }
            layoutParams = LinearLayout.LayoutParams(dpInt(context, 40f), dpInt(context, 40f))
            setOnClickListener {
                selectedMinutes = (selectedMinutes - 1).coerceAtLeast(1)
                refreshSelectionUi()
            }
        }
        customCountText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val plusBtn = TextView(context).apply {
            text = "+"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ROYAL_BLUE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6EEFC"))
            }
            layoutParams = LinearLayout.LayoutParams(dpInt(context, 40f), dpInt(context, 40f))
            setOnClickListener {
                selectedMinutes = (selectedMinutes + 1).coerceAtMost(60)
                refreshSelectionUi()
            }
        }
        stepperBox.addView(minusBtn)
        stepperBox.addView(customCountText)
        stepperBox.addView(plusBtn)
        card.addView(stepperBox)

        // Primary Gold CTA: Start Cool-Off
        startCtaBtn = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(GOLD_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(GOLD)
            }
            setPadding(dpInt(context, 20f), dpInt(context, 15f), dpInt(context, 20f), dpInt(context, 15f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(context, 10f) }
            setOnClickListener {
                onStartCoolOff(selectedMinutes)
            }
        }
        card.addView(startCtaBtn)

        val skipBtn = TextView(context).apply {
            text = "Not Now, I'm Good"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(TEXT_SECONDARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#F4F7FC"))
                setStroke(dpInt(context, 1f), BORDER)
            }
            setPadding(dpInt(context, 16f), dpInt(context, 12f), dpInt(context, 16f), dpInt(context, 12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSkipBreak() }
        }
        card.addView(skipBtn)

        refreshSelectionUi()
        return card
    }

    /**
     * Full interactive Cool-Off session controller rendered inside the Accessibility Overlay
     * when Xiaomi HyperOS / Android BAL blocks starting [CoolOffActivity] from the background.
     */
    class InOverlayCoolOffController(
        private val context: Context,
        durationMinutes: Int?,
        forceNew: Boolean,
        private val onSessionFinishedAndClose: () -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val audioEngine = CoolOffAudioEngine(context.applicationContext)
        private val voiceReader = CoolOffScriptureVoiceReader(
            appContext = context.applicationContext,
            onStateChanged = { state -> renderVoiceToggleButton(state) },
            onSpeakingActiveChanged = { active -> audioEngine.setVoiceDucking(active) }
        )
        private var session: CoolOffSessionState = if (forceNew) {
            CoolOffSessionManager.startFreshSession(context, durationMinutes)
        } else {
            CoolOffSessionManager.getOrCreateSession(context, durationMinutes)
        }
        private val exercise: CoolOffExercise = CoolOffDrillEngine.findById(session.exerciseId)
        private val scripture: CoolOffScripture = CoolOffScriptureRepository.findByReference(session.scriptureReference)
        private val completedStepIndices = mutableSetOf<Int>()
        private var baseElapsedSeconds: Int = session.elapsedSeconds
        private var startedMonoMs: Long = SystemClock.elapsedRealtime()
        private var running: Boolean = true
        private var muted: Boolean = session.audioMuted

        private lateinit var timerText: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var voiceToggleBtn: TextView
        private lateinit var muteBtn: TextView
        private lateinit var statusHint: TextView
        private lateinit var finishBtn: TextView
        private lateinit var activeSection: LinearLayout
        private lateinit var doneSection: LinearLayout

        private val tickRunnable = object : Runnable {
            override fun run() {
                if (!running || session.isCompleted) return
                val elapsed = currentElapsed()
                renderTimer(elapsed)
                if (elapsed >= session.durationSeconds) {
                    tryComplete(elapsed)
                }
                if (running && !session.isCompleted) {
                    handler.postDelayed(this, 250L)
                }
            }
        }

        fun buildAndStart(): View {
            for (i in 0 until session.completedInteractions) completedStepIndices.add(i)

            val scroll = ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 24f)
                    setColor(SURFACE)
                    setStroke(dpInt(context, 1f), BORDER)
                }
                setPadding(dpInt(context, 22f), dpInt(context, 24f), dpInt(context, 22f), dpInt(context, 24f))
            }

            // Active Session Section
            activeSection = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val badge = TextView(context).apply {
                text = "Screen Guard · Cool-Off Room"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(ROYAL_BLUE)
                }
                setPadding(dpInt(context, 14f), dpInt(context, 6f), dpInt(context, 14f), dpInt(context, 6f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 12f) }
            }
            activeSection.addView(badge)

            timerText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
                setTextColor(ROYAL_BLUE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            activeSection.addView(timerText)

            progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpInt(context, 8f)
                ).apply {
                    topMargin = dpInt(context, 6f)
                    bottomMargin = dpInt(context, 16f)
                }
            }
            activeSection.addView(progressBar)

            // Exercise Card
            val exBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 16f)
                    setColor(SURFACE_SUBTLE)
                    setStroke(dpInt(context, 1f), BORDER)
                }
                setPadding(dpInt(context, 16f), dpInt(context, 14f), dpInt(context, 16f), dpInt(context, 14f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 14f) }
            }

            exBox.addView(TextView(context).apply {
                text = exercise.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 6f) }
            })

            exercise.instructions.forEachIndexed { idx, line ->
                exBox.addView(TextView(context).apply {
                    text = "${idx + 1}. $line"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setTextColor(TEXT_SECONDARY)
                    setLineSpacing(dp(context, 2f), 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpInt(context, 4f) }
                })
            }

            val interactiveItems: List<String> = when {
                exercise.reflectionPrompts.isNotEmpty() -> exercise.reflectionPrompts
                exercise.reframingPairs.isNotEmpty() -> exercise.reframingPairs.map {
                    "“${it.automaticThought}” -> ${it.healthierAlternative}"
                }
                else -> emptyList()
            }
            if (interactiveItems.isNotEmpty()) {
                interactiveItems.forEachIndexed { idx, prompt ->
                    val btn = TextView(context).apply {
                        val isChecked = completedStepIndices.contains(idx)
                        text = if (isChecked) "✓  $prompt" else "○  $prompt"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(if (isChecked) SUCCESS else ROYAL_BLUE)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        isClickable = true
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(context, 12f)
                            setColor(if (isChecked) Color.parseColor("#ECFDF3") else Color.parseColor("#EEF3FA"))
                            setStroke(dpInt(context, 1f), if (isChecked) SUCCESS else BORDER)
                        }
                        setPadding(dpInt(context, 12f), dpInt(context, 10f), dpInt(context, 12f), dpInt(context, 10f))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dpInt(context, 6f) }
                        setOnClickListener {
                            completedStepIndices.add(idx)
                            text = "✓  $prompt"
                            setTextColor(SUCCESS)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(context, 12f)
                                setColor(Color.parseColor("#ECFDF3"))
                                setStroke(dpInt(context, 1f), SUCCESS)
                            }
                            val elapsed = currentElapsed()
                            if (elapsed >= session.durationSeconds) {
                                tryComplete(elapsed)
                            }
                        }
                    }
                    exBox.addView(btn)
                }
            }
            activeSection.addView(exBox)

            // Scripture Box
            val scriptureBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 16f)
                    setColor(SURFACE_SUBTLE)
                    setStroke(dpInt(context, 1f), Color.parseColor("#DCE6F7"))
                }
                setPadding(dpInt(context, 14f), dpInt(context, 12f), dpInt(context, 14f), dpInt(context, 12f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 14f) }
            }
            scriptureBox.addView(TextView(context).apply {
                text = "“${scripture.passage}”"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#1E293B"))
                typeface = Typeface.create("serif", Typeface.ITALIC)
                gravity = Gravity.CENTER
            })
            scriptureBox.addView(TextView(context).apply {
                text = "— ${scripture.reference}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ROYAL_BLUE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpInt(context, 5f) }
            })
            voiceToggleBtn = TextView(context).apply {
                text = "Preparing Bible Voice..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(ROYAL_BLUE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(Color.parseColor("#E6EEFC"))
                    setStroke(dpInt(context, 1f), Color.parseColor("#BDD0F5"))
                }
                setPadding(dpInt(context, 16f), dpInt(context, 8f), dpInt(context, 16f), dpInt(context, 8f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dpInt(context, 10f)
                }
                setOnClickListener {
                    voiceReader.togglePauseResume()
                }
            }
            scriptureBox.addView(voiceToggleBtn)
            activeSection.addView(scriptureBox)

            // Audio mute toggle
            muteBtn = TextView(context).apply {
                text = if (muted) "Unmute Calming Audio" else "Mute Calming Audio (${session.audioCategory.displayName})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ROYAL_BLUE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(Color.parseColor("#EEF3FA"))
                    setStroke(dpInt(context, 1f), BORDER)
                }
                setPadding(dpInt(context, 16f), dpInt(context, 10f), dpInt(context, 16f), dpInt(context, 10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 8f) }
                setOnClickListener {
                    muted = !muted
                    audioEngine.setMuted(muted)
                    text = if (muted) "Unmute Calming Audio" else "Mute Calming Audio (${session.audioCategory.displayName})"
                }
            }
            activeSection.addView(muteBtn)

            statusHint = TextView(context).apply {
                text = "Stay here until the timer finishes to complete your Cool-Off."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(TEXT_SECONDARY)
                gravity = Gravity.CENTER
            }
            activeSection.addView(statusHint)

            // Completion Section (initially hidden)
            doneSection = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            doneSection.addView(TextView(context).apply {
                text = "✓  Cool-Off Complete"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(SUCCESS)
                }
                setPadding(dpInt(context, 16f), dpInt(context, 7f), dpInt(context, 16f), dpInt(context, 7f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 14f) }
            })
            doneSection.addView(TextView(context).apply {
                text = "Well done. Your mind is reset."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(TEXT_PRIMARY)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 10f) }
            })
            doneSection.addView(TextView(context).apply {
                text = "You chose self-control over impulse. Protection remains active."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(TEXT_SECONDARY)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(context, 20f) }
            })
            finishBtn = TextView(context).apply {
                text = "Return to Home"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(GOLD_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999f)
                    setColor(GOLD)
                }
                setPadding(dpInt(context, 20f), dpInt(context, 15f), dpInt(context, 20f), dpInt(context, 15f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    stop()
                    onSessionFinishedAndClose()
                }
            }
            doneSection.addView(finishBtn)

            card.addView(activeSection)
            card.addView(doneSection)
            scroll.addView(card)

            startedMonoMs = SystemClock.elapsedRealtime()
            renderTimer(baseElapsedSeconds)
            audioEngine.start(
                initialCategory = session.audioCategory,
                initialVolume = session.audioVolume,
                initialMuted = muted
            )
            voiceReader.loadAndStart(scripture, autoPlay = true)
            handler.post(tickRunnable)
            return scroll
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tickRunnable)
            if (!session.isCompleted) {
                CoolOffSessionManager.updateProgress(
                    context = context,
                    elapsedSeconds = currentElapsed(),
                    completedInteractions = completedStepIndices.size
                )
            }
            voiceReader.stopAndRelease()
            audioEngine.stopImmediately()
        }

        private fun renderVoiceToggleButton(state: VoiceReaderState) {
            if (!::voiceToggleBtn.isInitialized) return
            when (state) {
                VoiceReaderState.INITIALIZING -> {
                    voiceToggleBtn.visibility = View.VISIBLE
                    voiceToggleBtn.text = "Preparing Bible Voice..."
                    voiceToggleBtn.setTextColor(ROYAL_BLUE)
                    voiceToggleBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 999f)
                        setColor(Color.parseColor("#E6EEFC"))
                        setStroke(dpInt(context, 1f), Color.parseColor("#BDD0F5"))
                    }
                }
                VoiceReaderState.SPEAKING -> {
                    voiceToggleBtn.visibility = View.VISIBLE
                    voiceToggleBtn.text = "⏸  Pause Bible Voice"
                    voiceToggleBtn.setTextColor(Color.WHITE)
                    voiceToggleBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 999f)
                        setColor(ROYAL_BLUE)
                    }
                }
                VoiceReaderState.PAUSED -> {
                    voiceToggleBtn.visibility = View.VISIBLE
                    voiceToggleBtn.text = "▶  Resume Bible Voice"
                    voiceToggleBtn.setTextColor(ROYAL_BLUE)
                    voiceToggleBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 999f)
                        setColor(Color.parseColor("#E6EEFC"))
                        setStroke(dpInt(context, 1f), Color.parseColor("#BDD0F5"))
                    }
                }
                VoiceReaderState.COMPLETED -> {
                    voiceToggleBtn.visibility = View.VISIBLE
                    voiceToggleBtn.text = "↻  Replay Bible Verse"
                    voiceToggleBtn.setTextColor(ROYAL_BLUE)
                    voiceToggleBtn.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(context, 999f)
                        setColor(Color.parseColor("#E6EEFC"))
                        setStroke(dpInt(context, 1f), Color.parseColor("#BDD0F5"))
                    }
                }
                VoiceReaderState.UNAVAILABLE -> {
                    voiceToggleBtn.visibility = View.GONE
                }
            }
        }

        private fun currentElapsed(): Int {
            val delta = ((SystemClock.elapsedRealtime() - startedMonoMs) / 1000L).toInt().coerceAtLeast(0)
            return (baseElapsedSeconds + delta).coerceIn(0, session.durationSeconds)
        }

        private fun renderTimer(elapsed: Int) {
            val remaining = (session.durationSeconds - elapsed).coerceAtLeast(0)
            val m = remaining / 60
            val s = remaining % 60
            timerText.text = "%02d:%02d".format(m, s)
            val frac = if (session.durationSeconds > 0) (elapsed * 1000) / session.durationSeconds else 1000
            progressBar.progress = frac.coerceIn(0, 1000)
        }

        private fun tryComplete(elapsed: Int) {
            val ok = CoolOffSessionManager.tryCompleteSession(
                context = context,
                elapsedSeconds = elapsed,
                completedInteractions = completedStepIndices.size
            )
            if (ok) {
                session = session.copy(elapsedSeconds = session.durationSeconds, isCompleted = true)
                running = false
                handler.removeCallbacks(tickRunnable)
                voiceReader.stopAndRelease()
                audioEngine.fadeOutAndStop()
                activeSection.visibility = View.GONE
                doneSection.visibility = View.VISIBLE
            } else {
                val left = (exercise.requiredInteractions - completedStepIndices.size).coerceAtLeast(0)
                if (left > 0) {
                    statusHint.text = "Timer complete — tap $left remaining reflection step${if (left == 1) "" else "s"} above to finish."
                    statusHint.setTextColor(ROYAL_BLUE)
                }
            }
        }
    }

    private fun dp(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }

    private fun dpInt(context: Context, value: Float): Int = dp(context, value).toInt()
}
