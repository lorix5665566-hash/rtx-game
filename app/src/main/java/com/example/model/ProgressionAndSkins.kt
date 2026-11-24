package com.example.model

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Character level, EXP progression, and coin economy rules:
 * - +100 EXP per kill (outside warmup).
 * - Level 1 -> Level 2 requires 500 EXP.
 * - Each subsequent level requires +25% more EXP than the previous step, up to Level 20.
 *   (Level 19 -> 20 is the 19th step = round(500 * 1.25^18) = 27756 EXP.)
 * - From Level 20 onward, the +25% increase stops and every level requires the fixed Level-20 cap step.
 * - Each level-up awards +50 coins.
 * - Each skin case costs 100 coins.
 */
object LevelProgression {
    const val XP_PER_KILL: Int = 100
    const val BASE_LEVEL_XP: Int = 500
    const val LEVEL_GROWTH_FACTOR: Double = 1.25
    const val GROWTH_CAP_LEVEL: Int = 20
    const val COINS_PER_LEVEL_UP: Int = 50
    const val CASE_PRICE_COINS: Int = 100

    /**
     * EXP needed to advance from [currentLevel] to [currentLevel] + 1.
     * Matches SQL `public.calc_level_step_xp(integer)` 1:1.
     */
    fun xpRequiredForLevelUp(currentLevel: Int): Int {
        val lvl = currentLevel.coerceAtLeast(1)
        val exponent = (lvl - 1).coerceAtMost(GROWTH_CAP_LEVEL - 2) // capped at 18 (19->20)
        return (BASE_LEVEL_XP * LEVEL_GROWTH_FACTOR.pow(exponent)).roundToInt()
    }

    data class LevelProgressInfo(
        val level: Int,
        val totalXp: Long,
        val xpIntoLevel: Int,
        val xpForNextLevel: Int,
        val progressFraction: Float
    )

    fun computeFromTotalXp(totalXp: Long): LevelProgressInfo {
        var remaining = totalXp.coerceAtLeast(0L)
        var lvl = 1
        while (true) {
            val step = xpRequiredForLevelUp(lvl).toLong()
            if (remaining < step) {
                val fraction = (remaining.toFloat() / step.toFloat()).coerceIn(0f, 1f)
                return LevelProgressInfo(
                    level = lvl,
                    totalXp = totalXp.coerceAtLeast(0L),
                    xpIntoLevel = remaining.toInt(),
                    xpForNextLevel = step.toInt(),
                    progressFraction = fraction
                )
            }
            remaining -= step
            lvl++
            if (lvl >= 999) {
                return LevelProgressInfo(
                    level = 999,
                    totalXp = totalXp.coerceAtLeast(0L),
                    xpIntoLevel = step.toInt(),
                    xpForNextLevel = step.toInt(),
                    progressFraction = 1f
                )
            }
        }
    }
}

enum class SkinRarity(
    val displayRu: String,
    val accentHex: Long,
    val badgeBgHex: Long,
    val dropWeight: Int,
    val duplicateRefundCoins: Int
) {
    MIL_SPEC(
        displayRu = "Армейское",
        accentHex = 0xFF4B8BFA,
        badgeBgHex = 0xFF152647,
        dropWeight = 52,
        duplicateRefundCoins = 30
    ),
    RESTRICTED(
        displayRu = "Запрещённое",
        accentHex = 0xFF9C5BFF,
        badgeBgHex = 0xFF281748,
        dropWeight = 26,
        duplicateRefundCoins = 45
    ),
    CLASSIFIED(
        displayRu = "Засекреченное",
        accentHex = 0xFFE94B9F,
        badgeBgHex = 0xFF421430,
        dropWeight = 13,
        duplicateRefundCoins = 65
    ),
    COVERT(
        displayRu = "Тайное",
        accentHex = 0xFFFF4D4D,
        badgeBgHex = 0xFF461618,
        dropWeight = 7,
        duplicateRefundCoins = 85
    ),
    LEGENDARY(
        displayRu = "Легендарное",
        accentHex = 0xFFFFC13B,
        badgeBgHex = 0xFF47330E,
        dropWeight = 2,
        duplicateRefundCoins = 120
    );

    val accentColor: Color get() = Color(accentHex)
    val badgeBgColor: Color get() = Color(badgeBgHex)
}

data class WeaponSkin(
    val id: String,
    val weapon: WeaponType,
    val nameRu: String,
    val subtitleRu: String,
    val rarity: SkinRarity,
    val primaryArgb: Long,
    val secondaryArgb: Long,
    val accentArgb: Long,
    val emissiveArgb: Long = 0xFF000000,
    val emissiveIntensity: Float = 0f,
    val metallic: Float = 0.45f,
    val roughness: Float = 0.38f
) {
    val primaryColor: Color get() = Color(primaryArgb)
    val secondaryColor: Color get() = Color(secondaryArgb)
    val accentColor: Color get() = Color(accentArgb)
}

data class SkinCase(
    val id: String,
    val nameRu: String,
    val subtitleRu: String,
    val priceCoins: Int = LevelProgression.CASE_PRICE_COINS,
    val bannerPrimaryHex: Long,
    val bannerAccentHex: Long,
    val skinIds: List<String>
) {
    val bannerPrimaryColor: Color get() = Color(bannerPrimaryHex)
    val bannerAccentColor: Color get() = Color(bannerAccentHex)
}

object SkinCatalog {

    val allSkins: List<WeaponSkin> = listOf(
        // === AK-47 ===
        WeaponSkin(
            id = "ak47_arctic_camo",
            weapon = WeaponType.AK47,
            nameRu = "Арктический Шторм",
            subtitleRu = "Матовый белый и ледяной графит",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFFD9E6F2,
            secondaryArgb = 0xFF4B6075,
            accentArgb = 0xFF8CC5FF,
            metallic = 0.30f,
            roughness = 0.46f
        ),
        WeaponSkin(
            id = "ak47_neon_pulse",
            weapon = WeaponType.AK47,
            nameRu = "Неоновый Пульс",
            subtitleRu = "Ультрафиолет и кибер-розовый",
            rarity = SkinRarity.CLASSIFIED,
            primaryArgb = 0xFF1F163B,
            secondaryArgb = 0xFFFF2F92,
            accentArgb = 0xFF22F5FF,
            emissiveArgb = 0xFFFF2F92,
            emissiveIntensity = 0.42f,
            metallic = 0.55f,
            roughness = 0.28f
        ),
        WeaponSkin(
            id = "ak47_blood_emperor",
            weapon = WeaponType.AK47,
            nameRu = "Кровавый Император",
            subtitleRu = "Карбон, багровый лак и золото",
            rarity = SkinRarity.COVERT,
            primaryArgb = 0xFFC61829,
            secondaryArgb = 0xFF1A1A20,
            accentArgb = 0xFFFFC23E,
            emissiveArgb = 0xFFFF3333,
            emissiveIntensity = 0.25f,
            metallic = 0.72f,
            roughness = 0.22f
        ),
        WeaponSkin(
            id = "ak47_gold_sovereign",
            weapon = WeaponType.AK47,
            nameRu = "Золотой Суверен",
            subtitleRu = "Полированное золото и обсидиан",
            rarity = SkinRarity.LEGENDARY,
            primaryArgb = 0xFFF4B936,
            secondaryArgb = 0xFF1B191C,
            accentArgb = 0xFFFFE58F,
            emissiveArgb = 0xFFFFB830,
            emissiveIntensity = 0.22f,
            metallic = 0.92f,
            roughness = 0.16f
        ),

        // === M4A1-S ===
        WeaponSkin(
            id = "m4a1s_cobalt_tactical",
            weapon = WeaponType.M4A1_S,
            nameRu = "Кобальт Спектр",
            subtitleRu = "Глубокий синий анодированный сплав",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFF1F54B8,
            secondaryArgb = 0xFF1C2536,
            accentArgb = 0xFF66B3FF,
            metallic = 0.62f,
            roughness = 0.34f
        ),
        WeaponSkin(
            id = "m4a1s_cyber_mint",
            weapon = WeaponType.M4A1_S,
            nameRu = "Кибер-Мята",
            subtitleRu = "Керамика и люминесцентная бирюза",
            rarity = SkinRarity.RESTRICTED,
            primaryArgb = 0xFF17272D,
            secondaryArgb = 0xFF00E5B4,
            accentArgb = 0xFFD9FFF5,
            emissiveArgb = 0xFF00E5B4,
            emissiveIntensity = 0.32f,
            metallic = 0.44f,
            roughness = 0.30f
        ),
        WeaponSkin(
            id = "m4a1s_hyper_plasma",
            weapon = WeaponType.M4A1_S,
            nameRu = "Гипер-Плазма",
            subtitleRu = "Электрический фиолет и плазма",
            rarity = SkinRarity.COVERT,
            primaryArgb = 0xFF7B2CFF,
            secondaryArgb = 0xFF131325,
            accentArgb = 0xFF33F0FF,
            emissiveArgb = 0xFF8A3CFF,
            emissiveIntensity = 0.45f,
            metallic = 0.68f,
            roughness = 0.22f
        ),

        // === AWP ===
        WeaponSkin(
            id = "awp_desert_viper",
            weapon = WeaponType.AWP,
            nameRu = "Пустынная Гадюка",
            subtitleRu = "Тактический песок и олива",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFFC7A876,
            secondaryArgb = 0xFF3E4532,
            accentArgb = 0xFFE5CFA0,
            metallic = 0.22f,
            roughness = 0.56f
        ),
        WeaponSkin(
            id = "awp_cobalt_rail",
            weapon = WeaponType.AWP,
            nameRu = "Рельсотрон",
            subtitleRu = "Белый композит и синяя катушка",
            rarity = SkinRarity.CLASSIFIED,
            primaryArgb = 0xFFE4ECF4,
            secondaryArgb = 0xFF1B2436,
            accentArgb = 0xFF1DBBFF,
            emissiveArgb = 0xFF1DBBFF,
            emissiveIntensity = 0.40f,
            metallic = 0.58f,
            roughness = 0.26f
        ),
        WeaponSkin(
            id = "awp_dragon_ember",
            weapon = WeaponType.AWP,
            nameRu = "Пламя Дракона",
            subtitleRu = "Золотой шёлк и раскалённый уголь",
            rarity = SkinRarity.LEGENDARY,
            primaryArgb = 0xFFE39E25,
            secondaryArgb = 0xFF2B1712,
            accentArgb = 0xFFFF5A1F,
            emissiveArgb = 0xFFFF6A1A,
            emissiveIntensity = 0.36f,
            metallic = 0.85f,
            roughness = 0.18f
        ),

        // === DESERT EAGLE ===
        WeaponSkin(
            id = "deagle_titanium",
            weapon = WeaponType.DESERT_EAGLE,
            nameRu = "Чистый Титан",
            subtitleRu = "Шлифованный титан и воронение",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFFB5C2D1,
            secondaryArgb = 0xFF282F38,
            accentArgb = 0xFFE8F1FA,
            metallic = 0.85f,
            roughness = 0.24f
        ),
        WeaponSkin(
            id = "deagle_blaze_core",
            weapon = WeaponType.DESERT_EAGLE,
            nameRu = "Пламенный Заряд",
            subtitleRu = "Огненный оранжевый и хром",
            rarity = SkinRarity.CLASSIFIED,
            primaryArgb = 0xFFFF591A,
            secondaryArgb = 0xFF1A1B22,
            accentArgb = 0xFFFFD03B,
            emissiveArgb = 0xFFFF591A,
            emissiveIntensity = 0.35f,
            metallic = 0.78f,
            roughness = 0.20f
        ),
        WeaponSkin(
            id = "deagle_amethyst",
            weapon = WeaponType.DESERT_EAGLE,
            nameRu = "Королевский Аметист",
            subtitleRu = "Глубокий фиолетовый металлик",
            rarity = SkinRarity.COVERT,
            primaryArgb = 0xFF9C30FF,
            secondaryArgb = 0xFF1A1426,
            accentArgb = 0xFFFF78E6,
            emissiveArgb = 0xFF9C30FF,
            emissiveIntensity = 0.30f,
            metallic = 0.82f,
            roughness = 0.18f
        ),

        // === GLOCK-18 ===
        WeaponSkin(
            id = "glock_crimson_grid",
            weapon = WeaponType.GLOCK_18,
            nameRu = "Алая Сетка",
            subtitleRu = "Алый затвор и чёрный полимер",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFFD92B3A,
            secondaryArgb = 0xFF1D1F24,
            accentArgb = 0xFFFF6673,
            metallic = 0.38f,
            roughness = 0.40f
        ),
        WeaponSkin(
            id = "glock_toxic_venom",
            weapon = WeaponType.GLOCK_18,
            nameRu = "Токсичный Яд",
            subtitleRu = "Кислотно-лаймовый и графит",
            rarity = SkinRarity.RESTRICTED,
            primaryArgb = 0xFF6DFF24,
            secondaryArgb = 0xFF19201B,
            accentArgb = 0xFFC5FF66,
            emissiveArgb = 0xFF55FF1A,
            emissiveIntensity = 0.34f,
            metallic = 0.35f,
            roughness = 0.36f
        ),
        WeaponSkin(
            id = "glock_sunset_fade",
            weapon = WeaponType.GLOCK_18,
            nameRu = "Градиент Заката",
            subtitleRu = "Розово-золотой перламутр",
            rarity = SkinRarity.CLASSIFIED,
            primaryArgb = 0xFFFF579F,
            secondaryArgb = 0xFFFFB22E,
            accentArgb = 0xFF8C4DFF,
            emissiveArgb = 0xFFFF579F,
            emissiveIntensity = 0.22f,
            metallic = 0.80f,
            roughness = 0.18f
        ),

        // === USP-S ===
        WeaponSkin(
            id = "usps_frost_steel",
            weapon = WeaponType.USP_S,
            nameRu = "Морозная Сталь",
            subtitleRu = "Ледяной голубой и хром",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFF63B8FF,
            secondaryArgb = 0xFF202A36,
            accentArgb = 0xFFD6F0FF,
            metallic = 0.68f,
            roughness = 0.28f
        ),
        WeaponSkin(
            id = "usps_emerald_shadow",
            weapon = WeaponType.USP_S,
            nameRu = "Изумрудная Тень",
            subtitleRu = "Тёмный изумруд и серебро",
            rarity = SkinRarity.RESTRICTED,
            primaryArgb = 0xFF11B86A,
            secondaryArgb = 0xFF141E1B,
            accentArgb = 0xFF72FFB8,
            emissiveArgb = 0xFF11B86A,
            emissiveIntensity = 0.22f,
            metallic = 0.72f,
            roughness = 0.24f
        ),
        WeaponSkin(
            id = "usps_neo_white",
            weapon = WeaponType.USP_S,
            nameRu = "Нео-Нуар",
            subtitleRu = "Белоснежный корпус и розовый неон",
            rarity = SkinRarity.COVERT,
            primaryArgb = 0xFFF0F4FA,
            secondaryArgb = 0xFF1E1A2E,
            accentArgb = 0xFFFF389C,
            emissiveArgb = 0xFFFF389C,
            emissiveIntensity = 0.30f,
            metallic = 0.42f,
            roughness = 0.24f
        ),

        // === MP9 ===
        WeaponSkin(
            id = "mp9_hazard_yellow",
            weapon = WeaponType.MP9,
            nameRu = "Жёлтая Угроза",
            subtitleRu = "Сигнальный жёлтый и антрацит",
            rarity = SkinRarity.MIL_SPEC,
            primaryArgb = 0xFFFFC81A,
            secondaryArgb = 0xFF202329,
            accentArgb = 0xFFFFEA80,
            metallic = 0.32f,
            roughness = 0.42f
        ),
        WeaponSkin(
            id = "mp9_cyber_cyan",
            weapon = WeaponType.MP9,
            nameRu = "Кибер-Импульс",
            subtitleRu = "Неоновый голубой и карбон",
            rarity = SkinRarity.RESTRICTED,
            primaryArgb = 0xFF00D4FF,
            secondaryArgb = 0xFF151C28,
            accentArgb = 0xFF99F2FF,
            emissiveArgb = 0xFF00D4FF,
            emissiveIntensity = 0.36f,
            metallic = 0.56f,
            roughness = 0.26f
        ),

        // === P90 ===
        WeaponSkin(
            id = "p90_orange_striker",
            weapon = WeaponType.P90,
            nameRu = "Азимов Штурм",
            subtitleRu = "Белый композит и оранжевый акцент",
            rarity = SkinRarity.RESTRICTED,
            primaryArgb = 0xFFF2F4F7,
            secondaryArgb = 0xFFFF6D1F,
            accentArgb = 0xFF1D2026,
            metallic = 0.30f,
            roughness = 0.34f
        ),
        WeaponSkin(
            id = "p90_void_magenta",
            weapon = WeaponType.P90,
            nameRu = "Пульсар Бездны",
            subtitleRu = "Глубокий индиго и малиновый свет",
            rarity = SkinRarity.CLASSIFIED,
            primaryArgb = 0xFF28174A,
            secondaryArgb = 0xFFFF2478,
            accentArgb = 0xFF75D4FF,
            emissiveArgb = 0xFFFF2478,
            emissiveIntensity = 0.38f,
            metallic = 0.62f,
            roughness = 0.24f
        ),

        // === KNIFE ===
        WeaponSkin(
            id = "knife_sapphire_doppler",
            weapon = WeaponType.KNIFE,
            nameRu = "Нож | Сапфир",
            subtitleRu = "Зеркальный сапфировый клинок",
            rarity = SkinRarity.COVERT,
            primaryArgb = 0xFF1A5CFF,
            secondaryArgb = 0xFF0F1B3D,
            accentArgb = 0xFF70C2FF,
            emissiveArgb = 0xFF2B7BFF,
            emissiveIntensity = 0.34f,
            metallic = 0.94f,
            roughness = 0.12f
        ),
        WeaponSkin(
            id = "knife_ruby_crimson",
            weapon = WeaponType.KNIFE,
            nameRu = "Нож | Рубин",
            subtitleRu = "Кроваво-рубиновая полировка",
            rarity = SkinRarity.LEGENDARY,
            primaryArgb = 0xFFE61938,
            secondaryArgb = 0xFF2A0C14,
            accentArgb = 0xFFFF6B82,
            emissiveArgb = 0xFFFF1F44,
            emissiveIntensity = 0.38f,
            metallic = 0.95f,
            roughness = 0.11f
        ),
        WeaponSkin(
            id = "knife_gold_karat",
            weapon = WeaponType.KNIFE,
            nameRu = "Нож | Золотой Карат",
            subtitleRu = "Литое золото 999 пробы",
            rarity = SkinRarity.LEGENDARY,
            primaryArgb = 0xFFFFC933,
            secondaryArgb = 0xFF211C12,
            accentArgb = 0xFFFFF0A8,
            emissiveArgb = 0xFFFFC126,
            emissiveIntensity = 0.26f,
            metallic = 0.96f,
            roughness = 0.12f
        )
    )

    private val byIdMap: Map<String, WeaponSkin> = allSkins.associateBy { it.id }

    val allCases: List<SkinCase> = listOf(
        SkinCase(
            id = "case_vanguard",
            nameRu = "Кейс «Авангард»",
            subtitleRu = "Боевая классика, тактика и Золотой Суверен",
            priceCoins = LevelProgression.CASE_PRICE_COINS,
            bannerPrimaryHex = 0xFF1D3557,
            bannerAccentHex = 0xFF38BDF8,
            skinIds = listOf(
                "ak47_arctic_camo",
                "m4a1s_cobalt_tactical",
                "awp_desert_viper",
                "deagle_titanium",
                "mp9_hazard_yellow",
                "p90_orange_striker",
                "usps_emerald_shadow",
                "awp_cobalt_rail",
                "ak47_blood_emperor",
                "ak47_gold_sovereign",
                "knife_gold_karat"
            )
        ),
        SkinCase(
            id = "case_neon",
            nameRu = "Кейс «Неон»",
            subtitleRu = "Кибер-свечение, плазма и Сапфировый клинок",
            priceCoins = LevelProgression.CASE_PRICE_COINS,
            bannerPrimaryHex = 0xFF2B124C,
            bannerAccentHex = 0xFFFF3CAC,
            skinIds = listOf(
                "usps_frost_steel",
                "glock_toxic_venom",
                "m4a1s_cyber_mint",
                "mp9_cyber_cyan",
                "ak47_neon_pulse",
                "p90_void_magenta",
                "glock_sunset_fade",
                "m4a1s_hyper_plasma",
                "usps_neo_white",
                "knife_sapphire_doppler",
                "awp_dragon_ember"
            )
        ),
        SkinCase(
            id = "case_obsidian",
            nameRu = "Кейс «Обсидиан»",
            subtitleRu = "Алые клинки, раскалённый металл и Рубин",
            priceCoins = LevelProgression.CASE_PRICE_COINS,
            bannerPrimaryHex = 0xFF3B1219,
            bannerAccentHex = 0xFFFF6B35,
            skinIds = listOf(
                "glock_crimson_grid",
                "deagle_titanium",
                "awp_desert_viper",
                "usps_emerald_shadow",
                "p90_orange_striker",
                "deagle_blaze_core",
                "ak47_neon_pulse",
                "deagle_amethyst",
                "ak47_blood_emperor",
                "knife_ruby_crimson",
                "awp_dragon_ember"
            )
        )
    )

    val skinnableWeapons: List<WeaponType> = listOf(
        WeaponType.AK47,
        WeaponType.M4A1_S,
        WeaponType.AWP,
        WeaponType.DESERT_EAGLE,
        WeaponType.GLOCK_18,
        WeaponType.USP_S,
        WeaponType.MP9,
        WeaponType.P90,
        WeaponType.KNIFE
    )

    fun byId(id: String?): WeaponSkin? = if (id.isNullOrBlank()) null else byIdMap[id]

    fun forWeapon(weapon: WeaponType): List<WeaponSkin> = allSkins.filter { it.weapon == weapon }

    fun skinsInCase(skinCase: SkinCase): List<WeaponSkin> =
        skinCase.skinIds.mapNotNull { byIdMap[it] }

    /**
     * Weighted roll based on each skin's [SkinRarity.dropWeight].
     */
    fun rollSkinFromCase(skinCase: SkinCase, random: Random = Random.Default): WeaponSkin {
        val pool = skinsInCase(skinCase).ifEmpty { allSkins }
        val totalWeight = pool.sumOf { it.rarity.dropWeight }
        var ticket = random.nextInt(totalWeight)
        for (skin in pool) {
            ticket -= skin.rarity.dropWeight
            if (ticket < 0) return skin
        }
        return pool.first()
    }
}
