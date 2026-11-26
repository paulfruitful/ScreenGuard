package com.paulfruitful.screenguard.multimodal

import kotlin.math.exp

/**
 * Result of lightweight on-device contextual NLP classification over combined
 * Accessibility text nodes + ML Kit OCR burned-in video captions.
 */
data class NlpContextScore(
    val nonSexualContextProb: Float = 0.5f,
    val suggestivePromoProb: Float = 0f,
    val explicitSexualProb: Float = 0f,
    val explicitPornDomainDetected: Boolean = false,
    val dominantClass: String = "NEUTRAL", // NON_SEXUAL | SUGGESTIVE_PROMO | EXPLICIT_SEXUAL | NEUTRAL
    val matchedContextTags: List<String> = emptyList()
)

/**
 * Lightweight, zero-latency on-device NLP text context classifier.
 *
 * Replaces brittle single-keyword checks with:
 * 1. Leetspeak / obfuscation normalization (`p0rn` -> `porn`, `s3x` -> `sex`, `n00ds` -> `nudes`, `0nlyf@ns` -> `onlyfans`).
 * 2. Weighted n-gram log-odds scoring across three calibrated classes:
 *    - `NON_SEXUAL`: everyday vlogs, tailoring/business, fitness/gym, combat sports, medical/health, faith/scripture, parenting.
 *    - `SUGGESTIVE_PROMO`: twerking, thirst traps, adult creator promos, spicy teasing.
 *    - `EXPLICIT_SEXUAL`: hardcore pornographic titles, descriptions, and explicit adult domains.
 *
 * Safety invariant: Text alone NEVER blocks an innocent visual screen unless an explicit adult
 * domain URL is present; instead it acts as a bidirectional context verifier in [MultimodalFusionEngine].
 */
object OnDeviceNlpContextClassifier {

    private val EXPLICIT_DOMAINS = listOf(
        "pornhub.", "xvideos.", "xnxx.", "xhamster.", "redtube.", "youporn.",
        "spankbang.", "eporner.", "brazzers.", "chaturbate.", "stripchat.",
        "bongacams.", "cam4.", "rule34.", "nhentai.", "hanime.", "beeg.",
        "motherless.", "hqporner.", "sxyprn.", "daftsex.", "porntrex.",
        "onlylinks.com", "onlyfans.com", "fansly.com"
    )

    private val EXPLICIT_NGRAM_WEIGHTS = mapOf(
        "hardcore porn" to 2.8f,
        "sex tape" to 2.6f,
        "blowjob" to 2.6f,
        "handjob" to 2.4f,
        "titjob" to 2.6f,
        "footjob" to 2.4f,
        "rimjob" to 2.6f,
        "cumshot" to 2.8f,
        "creampie" to 2.8f,
        "gangbang" to 2.6f,
        "doggystyle" to 2.6f,
        "doggy" to 2.3f,
        "missionary" to 2.1f,
        "cowgirl" to 2.2f,
        "reversecowgirl" to 2.8f,
        "reverse cowgirl" to 2.8f,
        "backshots" to 2.6f,
        "rawdog" to 2.6f,
        "deepthroat" to 2.6f,
        "full porn" to 2.6f,
        "pornhub" to 2.8f,
        "xvideos" to 2.8f,
        "xnxx" to 2.8f,
        "xhamster" to 2.8f,
        "redtube" to 2.6f,
        "youporn" to 2.6f,
        "spankbang" to 2.6f,
        "having sex" to 2.2f,
        "public sex" to 2.4f,
        "jerk off" to 2.8f,
        "jrk off" to 2.8f,
        "goonette" to 2.6f,
        "gooning" to 2.6f,
        "goonfuel" to 2.8f,
        "gooncave" to 2.8f,
        "chudai" to 2.8f,
        "desi sex" to 2.8f,
        "lund" to 2.4f,
        "chut" to 2.4f,
        "kpekus" to 2.6f,
        "picture my ass" to 2.6f,
        "ass on your dick" to 3.0f,
        "on your dick" to 2.6f,
        "lock your doors" to 1.6f,
        "stroke it" to 2.2f,
        "masturbate" to 2.6f,
        "masturbation" to 2.4f,
        "orgasm" to 2.2f,
        "squirting" to 2.6f,
        "squirt" to 2.2f,
        "threesome" to 2.4f,
        "foursome" to 2.4f,
        "bukkake" to 2.8f,
        "hentai" to 2.6f,
        "rule 34" to 2.8f,
        "rule34" to 2.8f,
        "r34" to 2.4f,
        "doujinshi" to 2.6f,
        "doujin" to 2.4f,
        "nsfw art" to 2.4f,
        "erotic art" to 2.2f,
        "lewd art" to 2.2f,
        "milf" to 2.2f,
        "pawg" to 2.4f,
        "fap" to 2.2f,
        "pussy" to 2.4f,
        "cock" to 2.0f,
        "dick" to 2.0f,
        "porn" to 2.4f
    )

    private val SUGGESTIVE_PROMO_WEIGHTS = mapOf(
        "twerk" to 2.0f,
        "twerking" to 2.2f,
        "twerk challenge" to 2.4f,
        "buss it challenge" to 2.2f,
        "silhouette challenge" to 2.0f,
        "clapping behind" to 2.2f,
        "clap behind" to 2.2f,
        "clap it" to 1.8f,
        "shaking ass" to 2.2f,
        "ecchi" to 2.0f,
        "wataa" to 2.2f,
        "wata" to 1.6f,
        "goon" to 2.0f,
        "69" to 1.8f,
        "buss it" to 1.8f,
        "throw it back" to 1.8f,
        "shake that ass" to 2.2f,
        "thirst trap" to 2.0f,
        "onlyfans" to 2.2f,
        "fansly" to 2.0f,
        "of link" to 1.8f,
        "vip link" to 1.6f,
        "link in bio" to 1.2f,
        "spicy content" to 2.0f,
        "spicy link" to 2.0f,
        "exclusive content" to 1.3f,
        "18+ only" to 1.8f,
        "nsfw" to 1.8f,
        "nudes" to 2.0f,
        "send nudes" to 2.2f,
        "dickpic" to 2.4f,
        "uncensored" to 1.5f,
        "leaked video" to 1.8f,
        "leaked tape" to 2.2f,
        "camgirl" to 2.2f,
        "sexting" to 2.0f,
        "nyash" to 1.8f,
        "yansh" to 1.8f,
        "olosho" to 2.0f,
        "toto" to 1.8f,
        "bhabhi" to 1.8f,
        "freaky" to 1.3f,
        "horny" to 1.8f,
        "baddie" to 1.0f,
        "booty shake" to 2.1f,
        "sexy dance" to 1.9f
    )

    private val NON_SEXUAL_CONTEXT_WEIGHTS = mapOf(
        // Combat sports & athletics
        "ufc" to 2.4f,
        "mma" to 2.4f,
        "boxing" to 2.2f,
        "wrestling" to 2.2f,
        "jiu-jitsu" to 2.2f,
        "bjj" to 2.0f,
        "muay thai" to 2.2f,
        "kickboxing" to 2.2f,
        "olympics" to 2.0f,
        "swimming" to 2.0f,
        "triathlon" to 2.0f,
        "marathon" to 2.0f,
        "surfing" to 1.8f,
        "beach volleyball" to 2.0f,
        "athlete" to 1.6f,
        // Fitness & gym
        "workout" to 2.0f,
        "gym" to 1.8f,
        "fitness" to 1.8f,
        "training" to 1.8f,
        "of training" to 2.4f,
        "training neck" to 2.6f,
        "neck training" to 2.6f,
        "neck curls" to 2.4f,
        "stretching" to 1.8f,
        "mobility" to 1.8f,
        "cardio" to 1.8f,
        "pushups" to 2.0f,
        "pullups" to 2.0f,
        "jawline" to 1.8f,
        "mewing" to 1.8f,
        "bodybuilding" to 2.2f,
        "crossfit" to 2.0f,
        "calisthenics" to 2.0f,
        "deadlift" to 2.2f,
        "bench press" to 2.2f,
        "physique" to 1.8f,
        "yoga" to 1.6f,
        "pilates" to 1.6f,
        // Medical, health & parenting
        "medical" to 2.2f,
        "anatomy" to 2.2f,
        "dermatology" to 2.4f,
        "skincare" to 2.0f,
        "first aid" to 2.2f,
        "physical therapy" to 2.2f,
        "physiotherapy" to 2.2f,
        "chiropractic" to 2.0f,
        "breast cancer" to 2.6f,
        "breastfeeding" to 2.6f,
        "maternal" to 2.2f,
        "postpartum" to 2.2f,
        "pediatric" to 2.2f,
        "pregnancy" to 2.0f,
        "sex education" to 2.4f,
        "sexual health" to 2.4f,
        "reproductive health" to 2.4f,
        "newborn" to 2.0f,
        "baby bath" to 2.4f,
        "toddler" to 1.8f,
        // Everyday business, tailoring, university, faith, news
        "garment" to 2.2f,
        "alteration" to 2.0f,
        "tailor" to 2.0f,
        "tailoring" to 2.0f,
        "university" to 1.8f,
        "student" to 1.5f,
        "customers" to 1.5f,
        "business" to 1.4f,
        "tutorial" to 1.6f,
        "cooking" to 1.8f,
        "recipe" to 1.8f,
        "bible" to 2.2f,
        "scripture" to 2.2f,
        "sermon" to 2.2f,
        "church" to 2.0f,
        "prayer" to 2.0f,
        "wikipedia" to 2.0f,
        "dictionary" to 2.0f,
        "biology" to 2.0f,
        "news" to 1.4f,
        // Pets, animals & wildlife
        "dog" to 2.2f,
        "dogs" to 2.2f,
        "pitbull" to 2.6f,
        "puppy" to 2.4f,
        "puppies" to 2.4f,
        "cat" to 2.2f,
        "cats" to 2.2f,
        "kitten" to 2.4f,
        "pet" to 2.0f,
        "pets" to 2.0f,
        "animal" to 2.2f,
        "animals" to 2.2f,
        "wildlife" to 2.4f
    )

    fun classify(rawText: String): NlpContextScore {
        if (rawText.isBlank()) {
            return NlpContextScore()
        }
        val lower = rawText.lowercase()
        val normalized = normalizeObfuscations(lower)

        val matched = mutableListOf<String>()
        var isPornDomain = false
        for (domain in EXPLICIT_DOMAINS) {
            if (lower.contains(domain) || normalized.contains(domain)) {
                isPornDomain = true
                matched.add("domain:${domain.trimEnd('.')}")
            }
        }

        // Check known adult social handles, priority X accounts/keywords, explicit hashtags (#nsfw), and adult studios
        val directoryMatch = AdultAccountDirectory.inspect(rawText, isSocialApp = true)
        if (directoryMatch.shouldBlockImmediately) {
            matched.addAll(directoryMatch.matchedTags)
        }
        val isDeterministicAdultSource = isPornDomain || directoryMatch.shouldBlockImmediately

        var explicitLogit = when {
            isDeterministicAdultSource -> 4.2f
            else -> -1.4f
        }
        var suggestiveLogit = -1.1f
        var nonSexualLogit = -0.3f

        for ((term, weight) in EXPLICIT_NGRAM_WEIGHTS) {
            if (matchesTerm(normalized, term)) {
                explicitLogit += weight
                if (matched.size < 6) matched.add("exp:$term")
            }
        }

        for ((term, weight) in SUGGESTIVE_PROMO_WEIGHTS) {
            if (matchesTerm(normalized, term)) {
                suggestiveLogit += weight
                if (matched.size < 6) matched.add("sug:$term")
            }
        }

        for ((term, weight) in NON_SEXUAL_CONTEXT_WEIGHTS) {
            if (matchesTerm(normalized, term)) {
                nonSexualLogit += weight
                if (matched.size < 6) matched.add("safe:$term")
            }
        }

        // Convert logits to calibrated 3-way softmax probabilities
        val maxLogit = maxOf(nonSexualLogit, suggestiveLogit, explicitLogit)
        val eSafe = exp((nonSexualLogit - maxLogit).toDouble()).toFloat()
        val eSug = exp((suggestiveLogit - maxLogit).toDouble()).toFloat()
        val eExp = exp((explicitLogit - maxLogit).toDouble()).toFloat()
        val sum = (eSafe + eSug + eExp).coerceAtLeast(1e-6f)

        val pSafe = (eSafe / sum).coerceIn(0f, 1f)
        val pSug = (eSug / sum).coerceIn(0f, 1f)
        val pExp = (eExp / sum).coerceIn(0f, 1f)

        val dominant = when {
            isDeterministicAdultSource || pExp >= 0.60f -> "EXPLICIT_SEXUAL"
            pSug >= 0.52f -> "SUGGESTIVE_PROMO"
            pSafe >= 0.65f && matched.any { it.startsWith("safe:") } -> "NON_SEXUAL"
            else -> "NEUTRAL"
        }

        return NlpContextScore(
            nonSexualContextProb = pSafe,
            suggestivePromoProb = pSug,
            explicitSexualProb = pExp,
            explicitPornDomainDetected = isDeterministicAdultSource,
            dominantClass = dominant,
            matchedContextTags = matched.distinct()
        )
    }

    internal fun normalizeObfuscations(input: String): String {
        // 1. Decode common censored spellings before leetspeak pass
        var text = input
            .replace("d*ck", "dick")
            .replace("c*ck", "cock")
            .replace("f*ck", "fuck")
            .replace("p*ssy", "pussy")
            .replace("pxrns", "porn")
            .replace("pxrn", "porn")
            .replace("p*rn", "porn")
            .replace("s*x", "sex")
            .replace("a$$", "ass")

        // 2. Decode leetspeak substitutions
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    '0' -> 'o'
                    '1' -> 'i'
                    '3' -> 'e'
                    '4', '@' -> 'a'
                    '5', '$' -> 's'
                    '7' -> 't'
                    else -> ch
                }
            )
        }

        // 3. Collapse elongated letter runs (e.g. "jrkkk offf" -> "jrk of", "assss" -> "ass")
        val collapsed = sb.toString()
            .replace(Regex("j+r+k+"), "jrk")
            .replace(Regex("o+f{2,}"), "off")
            .replace(Regex("([a-z])\\1{2,}"), "$1$1")
        return collapsed
    }

    private fun matchesTerm(haystack: String, needle: String): Boolean {
        if (needle.length >= 6 || needle == "porn" || needle.contains(' ') || needle.contains('.')) {
            return haystack.contains(needle)
        }
        val idx = haystack.indexOf(needle)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
        val afterIdx = idx + needle.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
        return beforeOk && afterOk
    }
}
