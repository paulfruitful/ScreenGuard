package com.paulfruitful.screenguard.protection

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.random.Random

data class MinistryBibleVerse(
    val reference: String,
    val text: String,
    val bibleComUrl: String,
    val osisRef: String
)

/**
 * Curated repository of scriptures that minister against lust, temptation, and sexual immorality.
 * Provides random verse selection and deep-link launching into installed Bible apps (YouVersion, etc.)
 * or bible.com.
 */
object BibleVerseRepository {

    private val VERSES = listOf(
        MinistryBibleVerse(
            reference = "1 Corinthians 6:18",
            text = "“Flee from sexual immorality. All other sins a person commits are outside the body, but whoever sins sexually, sins against their own body.”",
            bibleComUrl = "https://www.bible.com/bible/111/1CO.6.18-20.NIV",
            osisRef = "1CO.6.18"
        ),
        MinistryBibleVerse(
            reference = "1 Corinthians 10:13",
            text = "“No temptation has overtaken you except what is common to mankind. And God is faithful; He will not let you be tempted beyond what you can bear. But when you are tempted, He will also provide a way out so that you can endure it.”",
            bibleComUrl = "https://www.bible.com/bible/111/1CO.10.13.NIV",
            osisRef = "1CO.10.13"
        ),
        MinistryBibleVerse(
            reference = "Job 31:1",
            text = "“I made a covenant with my eyes not to look lustfully at a young woman.”",
            bibleComUrl = "https://www.bible.com/bible/111/JOB.31.1-4.NIV",
            osisRef = "JOB.31.1"
        ),
        MinistryBibleVerse(
            reference = "2 Timothy 2:22",
            text = "“Flee the evil desires of youth and pursue righteousness, faith, love and peace, along with those who call on the Lord out of a pure heart.”",
            bibleComUrl = "https://www.bible.com/bible/111/2TI.2.22.NIV",
            osisRef = "2TI.2.22"
        ),
        MinistryBibleVerse(
            reference = "Matthew 5:8",
            text = "“Blessed are the pure in heart, for they will see God.”",
            bibleComUrl = "https://www.bible.com/bible/111/MAT.5.8.NIV",
            osisRef = "MAT.5.8"
        ),
        MinistryBibleVerse(
            reference = "Matthew 5:28",
            text = "“But I tell you that anyone who looks at a woman lustfully has already committed adultery with her in his heart.”",
            bibleComUrl = "https://www.bible.com/bible/111/MAT.5.27-30.NIV",
            osisRef = "MAT.5.28"
        ),
        MinistryBibleVerse(
            reference = "Psalm 119:9, 11",
            text = "“How can a young person stay on the path of purity? By living according to Your word... I have hidden Your word in my heart that I might not sin against You.”",
            bibleComUrl = "https://www.bible.com/bible/111/PSA.119.9-11.NIV",
            osisRef = "PSA.119.9"
        ),
        MinistryBibleVerse(
            reference = "Galatians 5:16",
            text = "“So I say, walk by the Spirit, and you will not gratify the desires of the flesh.”",
            bibleComUrl = "https://www.bible.com/bible/111/GAL.5.16-17.NIV",
            osisRef = "GAL.5.16"
        ),
        MinistryBibleVerse(
            reference = "1 Thessalonians 4:3–4",
            text = "“It is God’s will that you should be sanctified: that you should avoid sexual immorality; that each of you should learn to control your own body in a way that is holy and honorable.”",
            bibleComUrl = "https://www.bible.com/bible/111/1TH.4.3-5.NIV",
            osisRef = "1TH.4.3"
        ),
        MinistryBibleVerse(
            reference = "Philippians 4:8",
            text = "“Whatever is true, whatever is noble, whatever is right, whatever is pure, whatever is lovely, whatever is admirable—if anything is excellent or praiseworthy—think about such things.”",
            bibleComUrl = "https://www.bible.com/bible/111/PHP.4.8.NIV",
            osisRef = "PHP.4.8"
        ),
        MinistryBibleVerse(
            reference = "Proverbs 4:23, 25",
            text = "“Above all else, guard your heart, for everything you do flows from it... Let your eyes look straight ahead; fix your gaze directly before you.”",
            bibleComUrl = "https://www.bible.com/bible/111/PRO.4.23-27.NIV",
            osisRef = "PRO.4.23"
        ),
        MinistryBibleVerse(
            reference = "James 4:7–8",
            text = "“Submit yourselves, then, to God. Resist the devil, and he will flee from you. Come near to God and He will come near to you.”",
            bibleComUrl = "https://www.bible.com/bible/111/JAS.4.7-8.NIV",
            osisRef = "JAS.4.7"
        )
    )

    @Volatile
    private var lastIndex: Int = -1

    fun pickRandomVerse(): MinistryBibleVerse {
        if (VERSES.size <= 1) return VERSES.first()
        var idx = Random.nextInt(VERSES.size)
        if (idx == lastIndex) {
            idx = (idx + 1) % VERSES.size
        }
        lastIndex = idx
        return VERSES[idx]
    }

    /**
     * Opens the scripture passage in an installed Bible app (e.g., YouVersion Bible `youversion://`
     * or app-linked `https://www.bible.com/...`) or in the user's browser on `bible.com`.
     */
    fun openScriptureInBibleAppOrWeb(context: Context, verse: MinistryBibleVerse) {
        val pm = context.packageManager

        // 1. Check if YouVersion Bible (`com.sirma.mobile.bible.android`) is installed and supports deep links
        val youVersionDeepLink = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("youversion://bible?reference=${verse.osisRef}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (youVersionDeepLink.resolveActivity(pm) != null) {
                context.startActivity(youVersionDeepLink)
                return
            }
        } catch (_: Exception) {
        }

        // 2. Standard https://www.bible.com/... intent (handled by YouVersion App Links, other Bible apps, or browser)
        try {
            val webOrAppLinkIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(verse.bibleComUrl)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webOrAppLinkIntent)
        } catch (_: Exception) {
        }
    }
}
