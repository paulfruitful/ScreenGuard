package com.paulfruitful.screenguard.multimodal

import android.content.Context
import org.json.JSONArray

/**
 * Fast O(1) on-device lookup of known adult/porn social media handles, adult studio brands,
 * explicit hashtags, and user-added custom blocked pages/handles.
 *
 * Handles are matched ONLY when prefixed with `@` or inside `x.com/` / `twitter.com/` URLs
 * so ordinary English words in normal posts never false-positive. User-added custom handles/names
 * are persisted locally and enforced immediately across all apps.
 */
object AdultAccountDirectory {

    private const val PREFS_NAME = "screenguard_custom_blocklist"
    private const val KEY_CUSTOM_ENTRIES = "custom_blocked_pages_v1"

    private val customLock = Any()

    @Volatile
    private var customLoaded: Boolean = false

    @Volatile
    private var customBlockedEntries: List<String> = emptyList()

    fun init(context: Context) {
        if (customLoaded) return
        synchronized(customLock) {
            if (customLoaded) return
            customBlockedEntries = loadCustomLocked(context)
            customLoaded = true
        }
    }

    fun getCustomBlockedPages(context: Context): List<String> {
        init(context)
        return customBlockedEntries
    }

    fun addCustomBlockedPage(context: Context, rawEntry: String): List<String> {
        init(context)
        val normalized = normalizeCustomEntry(rawEntry)
        if (normalized.isBlank()) return customBlockedEntries
        synchronized(customLock) {
            val updated = (customBlockedEntries + normalized).distinct()
            saveCustomLocked(context, updated)
            customBlockedEntries = updated
            return updated
        }
    }

    fun removeCustomBlockedPage(context: Context, entry: String): List<String> {
        init(context)
        val norm = normalizeCustomEntry(entry)
        synchronized(customLock) {
            val updated = customBlockedEntries.filterNot {
                it.equals(entry.trim(), ignoreCase = true) || it.equals(norm, ignoreCase = true)
            }
            saveCustomLocked(context, updated)
            customBlockedEntries = updated
            return updated
        }
    }

    fun setCustomBlockedEntriesForTests(entries: Collection<String>) {
        synchronized(customLock) {
            customBlockedEntries = entries.mapNotNull { normalizeCustomEntry(it).takeIf { s -> s.isNotBlank() } }.distinct()
            customLoaded = true
        }
    }

    internal fun normalizeCustomEntry(raw: String): String {
        var s = raw.trim().lowercase()
        if (s.isBlank()) return ""
        // Strip common profile URL prefixes if the user pastes a full link
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        for (prefix in listOf("x.com/", "twitter.com/", "instagram.com/", "tiktok.com/", "facebook.com/", "onlyfans.com/")) {
            if (s.startsWith(prefix)) {
                s = s.removePrefix(prefix).substringBefore('/').substringBefore('?')
                if (s.isNotBlank() && !s.startsWith("@")) s = "@$s"
                break
            }
        }
        s = s.trim()
        val bare = s.removePrefix("@")
        if (bare.length < 2) return ""
        return s
    }

    private fun loadCustomLocked(context: Context): List<String> {
        return try {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_CUSTOM_ENTRIES, null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = normalizeCustomEntry(arr.optString(i, ""))
                    if (v.isNotBlank()) add(v)
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomLocked(context: Context, entries: List<String>) {
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(it) }
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CUSTOM_ENTRIES, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private val HANDLE_REGEX = Regex("(?:@|(?:x|twitter)\\.com/)([a-z0-9_]{3,32})")
    private val HASHTAG_REGEX = Regex("#([a-z0-9_]{2,32})")

    val SOCIAL_PACKAGES: Set<String> = setOf(
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "com.facebook.katana",
        "com.snapchat.android",
        "com.reddit.frontpage"
    )

    /** Adult emojis commonly paired with "nut" or suggestive captions on X. */
    private val ADULT_EMOJIS = listOf("🥜", "💦", "🍆", "🍑", "🥵", "👅", "🔞")

    /** Exemptions so innocent news/education/mythology text never false-positives. */
    private val BBC_NEWS_EXEMPTIONS = listOf(
        "bbc news", "bbc sport", "bbc world", "bbc africa", "bbc radio",
        "bbc earth", "bbc sounds", "bbc weather", "bbc pidgin", "bbc.com", "bbc.co.uk"
    )
    private val MEDUSA_SAFE_EXEMPTIONS = listOf(
        "mythology", "greek", "jellyfish", "museum", "statue", "perseus", "versace"
    )
    private val EDUCATIONAL_EXEMPTIONS = listOf(
        "bible", "scripture", "sermon", "church", "breast cancer", "sex education",
        "reproductive health", "medical", "biology", "wikipedia", "dictionary"
    )

    /** Explicit hashtags that immediately identify adult / NSFW social posts. */
    val EXPLICIT_HASHTAGS: Set<String> = setOf(
        "nsfw", "nsfwtwt", "nsfwtwitter", "porn", "porno", "porntwt", "xxx", "18plus",
        "onlyfans", "fansly", "nudes", "sendnudes", "hentai", "rule34", "chudai", "desisex",
        "twerk", "twerking", "thirsttrap", "goon", "goons", "gooning", "goonette", "goonfuel",
        "backshots", "doggy", "doggystyle", "cowgirl", "reversecowgirl", "missionary",
        "milf", "pawg", "bbc", "ebony", "shmood", "slut", "freaks", "horny", "freaky",
        "lewd", "erotica", "sexting", "camgirl", "adultcontent", "explicitcontent",
        "spicycontent", "wataa", "nyash", "yansh", "bootyshake", "assshake",
        "fap", "masturbation", "squirt", "squirting", "blowjob", "throatgoat", "phatass"
    )

    /** Adult studio brands & high-priority explicit X account names/phrases (substring matched). */
    val ADULT_BRANDS: List<String> = listOf(
        "chaturbate", "brazzers", "fake hub", "fakehub", "reality kings", "realitykings",
        "bangbros", "naughty america", "blacked.com", "vixen.com", "tushy.com", "mofos",
        // Major X porn accounts, brands, and performer names (exact identifiers)
        "thelionsdens28", "shmoodnasty", "699lbs", "shmdtv", "deznut", "baddiex",
        "freaksstown", "ebonyxvids", "callingbooty", "dopeassxxxvids", "freakmajor",
        "umdavazo", "pozzyanach", "wacksackxxx", "snowworshipper", "blackwomenfuck",
        "thethroatnurse", "toptiertits", "goodgoodgoons", "ebornyporn", "acervodasputas",
        "chopdckkk", "_shmoodtv", "whosbonnieandclyde", "slut maker", "flix_nut",
        "nikonasty", "nuttyvidspage", "herfave_page", "baddieshub", "drillpink",
        "shmoodfreakz", "hunnyd", "sluttymommon", "blackxxfreaks", "ebonycct",
        "ebonyleakz", "mariannamuse", "awfullotta", "blowmyfrank", "captiontv",
        "bigstroke69", "slvttyvisions", "toxicbbyonx", "petiteebonyx", "baddiexxx",
        "wetwrldx", "fvckingpalace", "vidvaultxshadytaboo", "vidvaultx", "shadytaboo",
        // High-priority performer / studio names
        "willow rider", "princess emily", "troy franciso", "troy francisco",
        "ricky's room", "rickys room",
        // High-priority explicit X phrases
        "heads and backshots", "throat goat", "big thicc", "ebony light skin",
        "beach sex", "booty clapper 187", "booty clapper", "phat ass", "ms nasty",
        "18+"
    )

    /**
     * Absolute-priority substring roots on X / social apps (any handle or text containing these
     * triggers split-second blocking, e.g., shmood*, goons*, slut*, slvt*, freaks*, ebony*, fvck*).
     */
    private val PRIORITY_SUBSTRINGS_ON_SOCIAL: List<String> = listOf(
        "shmood", "shmdtv", "goons", "gooning", "goonette", "goonfuel",
        "slut", "slvt", "freaks", "ebony", "eborny", "pawg", "chudai",
        "wataa", "pussy", "fvck", "backshots", "doggystyle", "reversecowgirl"
    )

    /**
     * Absolute-priority whole-word keywords on X / social apps (strict word boundaries so
     * "class", "pass", "peanut", "minute", "cockpit" never false-positive).
     */
    private val PRIORITY_WHOLE_WORDS_ON_SOCIAL: List<String> = listOf(
        "goon", "freak", "nutty", "ass", "dick", "cock", "tits", "moan", "moaning",
        "horny", "doggy", "cowgirl", "missionary", "bbc", "driler", "medusa"
    )

    /** Known adult performer & Twitter/X porn aggregator handles (lowercase, without '@'). */
    val BLOCKED_HANDLES: Set<String> = setOf(
        // Major X porn accounts & aggregators
        "thelionsdens28", "shmoodnasty", "699lbs", "shmdtv", "deznut", "baddiex",
        "freaksstown", "ebonyxvids", "callingbooty", "dopeassxxxvids", "freakmajor",
        "umdavazo", "pozzyanach", "driler", "wacksackxxx", "snowworshipper",
        "blackwomenfuck", "thethroatnurse", "toptiertits", "goodgoodgoons",
        "ebornyporn", "acervodasputas", "chopdckkk", "_shmoodtv", "whosbonnieandclyde",
        "flix_nut", "nikonasty", "nuttyvidspage", "herfave_page", "baddieshub",
        "drillpink", "shmoodfreakz", "hunnyd", "sluttymommon", "blackxxfreaks",
        "ebonycct", "ebonyleakz", "mariannamuse", "awfullotta", "blowmyfrank",
        "captiontv", "bigstroke69", "slvttyvisions", "toxicbbyonx", "petiteebonyx",
        "baddiexxx", "wetwrldx", "fvckingpalace", "vidvaultxshadytaboo",
        "vidvaultx", "shadytaboo",
        "shmoods", "joeexxxx", "nastyaddictive", "shefreakyyasf", "emmanuellustin_",
        "madultmoodz", "teamstee", "thekalirayne", "moodafxxx", "uniquelymade981",
        "stillows_", "olivianomad", "sweetpxrns", "ladyylluscious", "rubyylan",
        // Adult performer handles
        "aaliyahlove69", "abbycrossxxx", "abella_danger", "msabigailmac", "adiraallurexxx",
        "adreena_winters", "adriaxxrae", "adrianachechik", "aidenashley", "aidenstarr",
        "xaidrafoxx", "ajsapplebooty", "alanaevansxxx", "alanaluvxxx", "alessa_savage",
        "alessandrajanex", "alessialunaxxx", "alettaoceanxxxx", "alexblake420", "alexxxcoal",
        "alexgreyxxx", "msevilalexanova", "alexisfawx", "alexistaex", "alexis_texas",
        "alexxavice", "alirosepb", "hheadshhot", "alinaxbelle", "henessyalina",
        "itsalinalopez", "yiayiawest", "alisonreyxxx", "thealixlynx", "alliehaze",
        "allienicolexxx", "allisonparker22", "alonabloomxxx", "alyastarkreal", "alyssareecexo",
        "amandacerny", "xxxamara", "adulteressamber", "amberchase", "amberdeenxxx",
        "amber_jaynexx", "ambermoorexxx", "ambernevada", "amiamiley", "amirahadaraxxx",
        "amyanderssen1", "anafoxxx", "analorde_", "angelsmallsxxx", "angelawhite",
        "angelikagrays", "angelineredxxx", "angielynxxx", "aniakinski", "anikkaalbrite",
        "anisajomha", "anissakate", "anisyiaxxx", "annabellpeaksxx", "annaclairecloud",
        "annadevillexxx", "annabelredd", "theannabellexxx", "anniefuckincruz", "anriokita_real",
        "anyakrey", "anyaolsenxxx", "arialeexxx", "arielrebel", "ariellaferrera",
        "revarielle", "realarmaniblack", "artemisialove9", "arubajasmine", "arya_fae",
        "msaryanaadin", "asaakira", "xoxoashleyadams", "ashleyalban94", "ashleyredxxx",
        "ash_wolf_", "asspenrose", "astridstarbaby", "athenafarisx", "realaubreyblack",
        "audreymadisonsm", "autumnxfallsx", "avaaddams", "ava_austen", "avanicks",
        "averyblvck", "theaverycristy", "averyjanexo", "xbaileybrookex", "thebaileyrayne",
        "inkybellabellz", "bunnydelphine", "bethanylilya", "bhadbhabie", "blkblssm",
        "blissdulce", "blysssteele", "bobbieden", "brandi_love", "braylinbaileyxx",
        "breeolson", "brennasparksxxx", "iamspanishdoll", "britney_amber", "djbritstar",
        "xbrooklynbluex", "brooklyn_chase", "thecadencelux", "misscalicarter", "caliskyeinc",
        "candicedare", "realcandysamira", "canelaskinoff", "clubcarmenxxx", "xcarolinasweets",
        "cartercruise", "caseycalvertxxx", "iamcassbanks", "cassidyxklein", "cassiecurses",
        "catalinacruz", "officialcclionx", "chanelpreston", "bunsofuns", "charlee_chase",
        "gothcharlotte", "charlottesuxxx", "cheriedeville", "mycherrycrush", "realchloeamour",
        "xchloefosterx", "iluvchristie", "christinashine_", "clca69", "christymack",
        "cindystarfall", "trinity_clara", "cleagaultier", "codi_vore", "sweetbluecj",
        "corychasexxx", "_rush187_", "realcytherea", "daisystonex", "dakota_skyexxx",
        "danadearmond", "danavespoli", "akadanidaniels", "xdanixjensenx", "danimathers",
        "deewilliamsxxx", "delilahxday", "demisutra", "destinycruzxxx", "destinydixon",
        "diamondfoxxx2", "dillionharper", "thedoomdoll", "electrarayne", "megaplaygirll",
        "eliza22ibarra", "ellahughesxxx", "ellaknoxxx", "ellareesexxx", "ellieleen1",
        "embersnowxxx", "theemilybloom", "emilywillisxoxo", "missemmaglover", "emmahixofficial",
        "emmasirus", "estarletto", "erineverheart", "esperanzagomez", "evaelfie",
        "fallinlovia", "evanotty", "evelinedellai1", "luvevelynclaire", "realewasonnet",
        "fallonwestxxx", "f1abraham", "felicityxfeline", "fionababyx", "francescalexxx",
        "freyamayer_", "ladygemmamassey", "misslyallxxx", "missgiadibella", "gia_dimarco",
        "giapaige", "therealgianna", "gina_gerson", "trulyginalynn", "gvalentinaxxx",
        "gingerbanks1", "xogizellexo", "glamchowdr", "haleyreedx", "haleyspades",
        "misshannahhub", "hannah_cpalmer", "toooomuchatonce", "imharmonywonder", "hsugarcookie",
        "helga_model", "hitomi_official", "littlehollybeth", "xxxhollyhalston", "hollyhendrix_",
        "hollymadison", "realhoneyhayes", "hyley_winters", "npcprincess666", "irenerouse10",
        "cageirina", "isabella_clark1", "isabelledeltore", "ivanasugar", "ivylebellexxx",
        "izzylush_", "thejaclyntaylor", "the_jada_fire", "officialjadakai", "jadastevens420",
        "jadebakerxx", "jadejantzenxxx", "jadekushxiii", "justkeepflowing", "janewildexxx",
        "janetmasonxxx", "rejaniced", "poutyjas", "jasminejamesxx1", "realjasminevega",
        "jasminewilde69", "jaydencole", "jaydenmichele", "jayladeangelis", "jelenajensen",
        "jenevievehexxx", "onlyjennafoxx", "jennahaze", "jennanoellexxx", "jessaerosaexxx",
        "onetriickpony", "jessicajaymes", "j3ssicastarling", "jessieslife", "jezabelvisser",
        "xojillianjanson", "joannaangel", "missjojokiss", "thejordanmaxx", "joselinekellyx",
        "josephinejxxx", "juelzventura", "therealjuliaann", "juliadeluciax", "juliaredx",
        "juliejess69", "julzgxxx", "kaiia_eve", "kaliroses", "kalinaryu",
        "sexykarenfisher", "karleegreyxxx", "karma_rx", "katana__k", "katana_lens",
        "kateengland21", "kate_maxx__", "katy_hartlova", "kathianobilixx", "katiebanksdd",
        "captainkcx", "thekatiemorgan", "tequila_katrin", "kj_fetishmodel", "katrinamorenoof",
        "katyaclover", "kaycarterxxx", "kayden_kross", "kaylakayden", "spicystepmom",
        "kaylanilive", "kayleygunner", "keiracroft", "kellydivine", "imkellymadison",
        "officialkmbooty", "kendallkayden", "kendralust", "kendraspade", "kslibrarygirl",
        "kennajames21", "kenziemadisonxx", "kenziereevesxxx", "thekenzietaylor", "kianna_dior",
        "misskiaracole", "mysexykiaramia", "kiaramoontv", "thekiaraskye", "kikiminajxxx",
        "kimmygrangerxxx", "only_kimmy_kimm", "kimoraquin", "kindlymyers", "kinsleykanexx",
        "kinsleykarter", "kinuskikakku1", "kira_axe_actres", "thekiranoir", "kiraroller",
        "kirsten_price", "kissasins", "kitmercerxo", "kleiovalentien", "krissylynnxxx",
        "kristinarosexxx", "krystalswift1", "goddesskyaa", "kylerquinnporn", "kyliepagexxx",
        "kyliexquinn", "rocketkylie", "laceylondonxx", "misslacylennon", "lanarhoades",
        "iamlanaroy", "lanasharapovax", "larkinlovexxx", "lasirena69_", "laurenfillsup",
        "laurenpixie_", "laylapricexoxo", "mslaytonbenton", "leahgotti___", "leanalovings",
        "leanna_decker", "lelastartm", "thelenaanderson", "lenaisapeach_", "lenatheplug",
        "lexiaaane", "omgitslexi", "lexilore", "lexxxilondonxxx", "lexybandera",
        "leyafalcon", "xlimonade1", "likastarx", "lilu_moon", "lilyadamsxo",
        "lilyivymfc", "lilykawaiix", "lilylabbeau", "lilylabeau", "lilylanexxx",
        "lilylarimar", "lilyrader", "lindseypelas", "thereallisaann", "lisadaniels3",
        "theliseysweet", "littlecapricetm", "liyasilver", "lizadelsierra", "lizaxrowe",
        "lizzyborden6", "lolafaexxx", "londonkeyes", "lottiirose", "louloupetite",
        "ashley19_love", "luciewildexxx", "louprival", "cutelunastar", "luscious_lopez",
        "mackenziemacex", "macykennedy22", "madelynxmonroe", "madison420ivy", "madisonmorganxo",
        "madithebadixxx", "maitlandward", "malmorganxxx", "yourmandygirl", "mandymuse69",
        "marica_1029", "marinavalmont", "marleybrinx", "martinasmeraldi", "realmarycarey",
        "maryrockprnstar", "xmarilynsugar", "clubmaseratixxx", "therealmaxinex", "themaythai",
        "mayabijouxxx", "mayafarrellreal", "littlesexbuddha", "megansagexxx", "meganwintersx",
        "melenamariarya_", "ohmelissamoore", "melodymarksxxx", "mihadoan1", "missmiakayxxx",
        "miamalkova", "realmiamelano", "splitmia", "michelejamesmv", "themichellejean",
        "mila_azul", "realmilamilan", "itsmilamonet", "mindimink", "minxxmarley",
        "xmishacrossx", "mynxmisha", "missyxmartinez", "mistystonexxx", "mistress_grey",
        "iammollycavalli", "realmonaazar", "monawalesxxx", "moniquealexande", "lilkymchii",
        "moriahmillsss", "therealmyamays", "nadianoja", "bunnycolby", "nancya_official",
        "spitslut666_", "naomi_swann", "ocean_nata", "thenataliaqueen", "nataliaxstarr",
        "nknightxxx", "natalieporkmanx", "benicenatasha", "xbabysnowx", "nianaccixxx",
        "nickeyhuntsman", "xnicoleanistonx", "thenicolearia", "nicolette_shea", "nikitabelluccix",
        "nikitavonjames", "nikkibenz", "nikkidelano", "nikkiknightly", "nikki_nova",
        "nikkipeachxxx", "nikolenashxxx", "itsninadiazxxxx", "ninaellexoxo", "xninanorthx",
        "noelleeaston", "noemiebilas", "sexy_nyomixx", "octaviaxred", "glassolive",
        "oliviaaustinxxx", "oliviaolovely1", "osalovelyxxx", "paigeowensxxx", "paigeturnahx",
        "paolahardxxx", "pariswhitexxx", "penelopewoodsx", "pennypax", "pmarizzle",
        "piperblushexp", "perripiper", "priyaanjalirai", "infiltrateproxy", "pumaswede",
        "rachaelcavalli", "rachelstarrxxx", "redmilfrachel", "ravenbayxxx", "thereaganfoxx",
        "volpettirebecca", "reenaskyvip", "littlereislin_", "remymeow", "reya__sunshine",
        "richelleryan", "rileyxjean1", "rileyxxxjensen", "rileynixon_", "rileyreidx3",
        "romi_rain", "roryroryknox", "rosalynsphinx", "rosexmonroex", "roxyrayexxx",
        "ryanconner69", "ryankeely", "ryder_rey", "sabinarouge", "sabrinanichol",
        "salenastorm", "thebadbentley", "samreignsxxx", "samantharone1", "misssaintxxx",
        "sarajayxxx", "sarahjessiexxx", "iamsarahlace", "sarahshevon", "mssarahvandella",
        "sashagrey", "sasharose_sport", "savanastyles", "thesavannahsixx", "scarletskiesxxx",
        "scarlettinbloom", "scarletthampt0n", "thescarletthope", "scarlettjonesuk", "scarlitscandal",
        "seltin_sweety", "thesensualjane", "xoispyseptember", "seraryder", "shandafay",
        "shaylaren", "shylajdotcom", "msshylastylez", "sialustxoxo", "sia_siberia",
        "adayinsienna", "siennawestland", "sinnamonlove", "skin_diamond", "skypierce_",
        "skylarsnowxxx", "vdthegoat", "sofiryanxoxo", "_solazola_", "sonyablazeonly",
        "thesophiawest", "sophiedee", "sophieshox", "stacycruz_xxx", "stellastephan69",
        "thestellacox", "stormydaniels", "stoya", "summerbailey", "summervixxxen",
        "sunnyleone", "surayastars", "sybila_official", "sydneycolexxx", "syrendemerxxx",
        "talia_mint", "idunno_1123", "tanyatate", "tanyavirago", "tashareign",
        "msteagan", "teannatrump", "tiakaidotcom", "tiffanydollxxx", "tiffanytothxoxo",
        "tiffanywatsonx", "tillytoy_tweets", "torimontanaxxx", "trukaitx", "valdoddsxxx",
        "asphyxiateval", "valenappi", "valeriekaywet", "msveracruzv", "vannabardot",
        "veronicaavluvxx", "lilveronicar", "slimthick_vic", "vickichase", "vickibaybeee",
        "vickyvette", "x_summers", "vikingbarbie_", "vinaskyy", "viaarthur",
        "missvioletstarr", "violetsummerss", "vivaathenax", "viviandasilva", "whitneywrightx",
        "xxlaynamarie_", "itskinkcity", "yukkiamey", "yumisinofficial", "realzazieskymm",
        "zoebloomxxx", "zoesparx", "itszoeyfoxx"
    )

    data class DirectoryMatch(
        val matchedAdultAccount: Boolean = false,
        val matchedExplicitHashtag: Boolean = false,
        val matchedAdultBrand: Boolean = false,
        val matchedPriorityKeyword: Boolean = false,
        val matchedTags: List<String> = emptyList()
    ) {
        val shouldBlockImmediately: Boolean
            get() = matchedAdultAccount || matchedAdultBrand || matchedExplicitHashtag || matchedPriorityKeyword
    }

    fun inspect(rawText: String, isSocialApp: Boolean = true): DirectoryMatch {
        if (rawText.isBlank()) return DirectoryMatch()
        val lowerText = rawText.lowercase()
        val tags = mutableListOf<String>()
        var accountHit = false
        var hashtagHit = false
        var brandHit = false
        var priorityHit = false

        val hasEducationalContext = EDUCATIONAL_EXEMPTIONS.any { lowerText.contains(it) }

        // 0. Always check user-added custom blocked handles & page names first (works across ALL apps)
        val customList = customBlockedEntries
        if (customList.isNotEmpty()) {
            for (entry in customList) {
                val bare = entry.removePrefix("@")
                if (bare.length < 2) continue
                val matched = if (entry.startsWith("@") || !bare.contains(' ')) {
                    lowerText.contains("@$bare") ||
                        lowerText.contains("x.com/$bare") ||
                        lowerText.contains("twitter.com/$bare") ||
                        lowerText.contains("instagram.com/$bare") ||
                        lowerText.contains("tiktok.com/@$bare") ||
                        containsWholeWord(lowerText, bare)
                } else {
                    lowerText.contains(bare)
                }
                if (matched) {
                    accountHit = true
                    if (tags.size < 4) tags.add("custom:$entry")
                }
            }
        }

        // 1. Check @handles and x.com/<handle> / twitter.com/<handle>
        for (m in HANDLE_REGEX.findAll(lowerText)) {
            val handle = m.groupValues[1]
            if (handle in BLOCKED_HANDLES ||
                handle.endsWith("xxx") ||
                handle.startsWith("xxx") ||
                handle.contains("shmood") ||
                handle.contains("goons") ||
                handle.contains("ebony") ||
                handle.contains("slut") ||
                handle.contains("slvt")
            ) {
                accountHit = true
                if (tags.size < 4) tags.add("acct:@$handle")
            }
        }

        // 2. Check explicit hashtags (#nsfw, #porn, #xxx, #twerk, #goon, #ebony, etc.)
        for (m in HASHTAG_REGEX.findAll(lowerText)) {
            val tag = m.groupValues[1]
            if (tag in EXPLICIT_HASHTAGS) {
                hashtagHit = true
                if (tags.size < 4) tags.add("tag:#$tag")
            }
        }

        // 3. Check known adult studio brands, X account display names, and explicit phrases
        for (brand in ADULT_BRANDS) {
            if (lowerText.contains(brand)) {
                // Skip educational articles on generic phrases like "18+" only if clearly medical/bible
                if (brand == "18+" && hasEducationalContext) continue
                brandHit = true
                if (tags.size < 4) tags.add("brand:$brand")
            }
        }

        // 4. Split-second high-priority X / social keywords, roots, and "nut" + emoji
        if (isSocialApp && !hasEducationalContext) {
            for (root in PRIORITY_SUBSTRINGS_ON_SOCIAL) {
                if (lowerText.contains(root)) {
                    priorityHit = true
                    if (tags.size < 4) tags.add("kw:$root")
                }
            }

            val isBbcNews = BBC_NEWS_EXEMPTIONS.any { lowerText.contains(it) }
            val isSafeMedusa = MEDUSA_SAFE_EXEMPTIONS.any { lowerText.contains(it) }

            for (word in PRIORITY_WHOLE_WORDS_ON_SOCIAL) {
                if (word == "bbc" && isBbcNews) continue
                if (word == "medusa" && isSafeMedusa) continue
                if (containsWholeWord(lowerText, word)) {
                    priorityHit = true
                    if (tags.size < 4) tags.add("kw:$word")
                }
            }

            // "nut" / "nutted" / "nutting" paired with adult emojis (🥜, 💦, 🍆, 🍑, 🥵, 👅, 🔞)
            val hasAdultEmoji = ADULT_EMOJIS.any { rawText.contains(it) }
            if (hasAdultEmoji &&
                (containsWholeWord(lowerText, "nut") ||
                    containsWholeWord(lowerText, "nuts") ||
                    lowerText.contains("nutted") ||
                    lowerText.contains("nutting"))
            ) {
                priorityHit = true
                if (tags.size < 4) tags.add("kw:nut+emoji")
            }
        }

        return DirectoryMatch(
            matchedAdultAccount = accountHit,
            matchedExplicitHashtag = hashtagHit,
            matchedAdultBrand = brandHit,
            matchedPriorityKeyword = priorityHit,
            matchedTags = tags
        )
    }

    private fun containsWholeWord(haystack: String, needle: String): Boolean {
        var fromIndex = 0
        while (true) {
            val idx = haystack.indexOf(needle, fromIndex)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
            val afterIdx = idx + needle.length
            val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            fromIndex = idx + 1
        }
    }
}
