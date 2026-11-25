package com.paulfruitful.screenguard.multimodal

/**
 * Fast O(1) on-device lookup of known adult/porn social media handles, adult studio brands,
 * and explicit hashtags.
 *
 * Handles are matched ONLY when prefixed with `@` or inside `x.com/` / `twitter.com/` URLs
 * so ordinary English words in normal posts never false-positive.
 */
object AdultAccountDirectory {

    private val HANDLE_REGEX = Regex("(?:@|(?:x|twitter)\\.com/)([a-z0-9_]{3,32})")
    private val HASHTAG_REGEX = Regex("#([a-z0-9_]{2,32})")

    /** Explicit hashtags that immediately identify adult / NSFW social posts. */
    val EXPLICIT_HASHTAGS: Set<String> = setOf(
        "nsfw", "nsfwtwt", "nsfwtwitter", "porn", "porno", "porntwt", "xxx", "18plus",
        "onlyfans", "fansly", "nudes", "sendnudes", "hentai", "rule34", "chudai", "desisex",
        "twerk", "twerking", "thirsttrap", "goon", "gooning", "goonette", "goonfuel",
        "backshots", "doggy", "doggystyle", "cowgirl", "reversecowgirl", "missionary",
        "milf", "pawg", "bbc", "horny", "freaky", "lewd", "erotica", "sexting", "camgirl",
        "adultcontent", "explicitcontent", "spicycontent", "wataa", "nyash", "yansh",
        "bootyshake", "assshake", "fap", "masturbation", "squirt", "squirting", "blowjob"
    )

    /** Adult studio brands. */
    val ADULT_BRANDS: List<String> = listOf(
        "chaturbate", "brazzers", "fake hub", "fakehub", "reality kings", "realitykings",
        "bangbros", "naughty america", "blacked.com", "vixen.com", "tushy.com", "mofos"
    )

    /** Known adult performer & Twitter/X porn aggregator handles (lowercase, without '@'). */
    val BLOCKED_HANDLES: Set<String> = setOf(
        // Twitter/X porn aggregator accounts
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
        val matchedTags: List<String> = emptyList()
    )

    fun inspect(lowerText: String): DirectoryMatch {
        if (lowerText.isBlank()) return DirectoryMatch()
        val tags = mutableListOf<String>()
        var accountHit = false
        var hashtagHit = false
        var brandHit = false

        // 1. Check @handles and x.com/<handle> / twitter.com/<handle>
        for (m in HANDLE_REGEX.findAll(lowerText)) {
            val handle = m.groupValues[1]
            if (handle in BLOCKED_HANDLES || handle.endsWith("xxx") || handle.startsWith("xxx")) {
                accountHit = true
                if (tags.size < 4) tags.add("acct:@$handle")
            }
        }

        // 2. Check explicit hashtags (#nsfw, #porn, #xxx, #twerk, #goon, etc.)
        for (m in HASHTAG_REGEX.findAll(lowerText)) {
            val tag = m.groupValues[1]
            if (tag in EXPLICIT_HASHTAGS) {
                hashtagHit = true
                if (tags.size < 4) tags.add("tag:#$tag")
            }
        }

        // 3. Check known adult studio brands
        for (brand in ADULT_BRANDS) {
            if (lowerText.contains(brand)) {
                brandHit = true
                if (tags.size < 4) tags.add("brand:$brand")
            }
        }

        return DirectoryMatch(
            matchedAdultAccount = accountHit,
            matchedExplicitHashtag = hashtagHit,
            matchedAdultBrand = brandHit,
            matchedTags = tags
        )
    }
}
