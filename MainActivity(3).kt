package com.example.launcher

import android.app.Activity
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import java.text.SimpleDateFormat
import java.util.*

// ───────────────────────── Activity ─────────────────────────

class MainActivity : ComponentActivity() {
    private val homeSignal = mutableIntStateOf(0)
    private lateinit var prefs: Prefs

    private var pickTarget = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) saveBackground(uri, pickTarget)
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveSound(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        prefs = Prefs(this)
        Thread { SoundFx.init(applicationContext) }.start()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPrefs provides prefs) {
                    LauncherRoot(
                        prefs, homeSignal.intValue,
                        { target ->
                            pickTarget = target
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        { pickAudio.launch("audio/*") }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeSignal.intValue++
    }

    // Copie la photo choisie dans le stockage de l'appli (redimensionnée)
    private fun saveSound(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(filesDir, "tap_custom").outputStream().use { out -> input.copyTo(out) }
                }
                SoundFx.loadCustom(applicationContext)
                prefs.updateSoundStyle(7)
                delay(600)
                SoundFx.play(7, prefs.soundVolume)
            }
        }
    }

    private fun saveBackground(uri: Uri, target: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                val rot = contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
                if (bmp != null) {
                    val finalBmp = if (rot != 0f) {
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                    } else bmp
                    File(filesDir, if (target == 1) "bg_drawer.jpg" else "bg.jpg").outputStream().use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (target == 1) {
                        prefs.updateDrawerMode(3)
                        prefs.bumpDrawerBg()
                    } else {
                        prefs.updateBgMode(2)
                        prefs.bumpBg()
                    }
                }
            }
        }
    }
}

// ───────────────────────── Catégories ─────────────────────────

// 0 = Tout, 1 = Jeux, 2 = Vidéo, 3 = Musique, 4 = Réseaux, 5 = Autres
// 6 = Favoris, 7 = Web, 100 et plus = onglets créés par toi
val catNames = listOf("Tout", "Jeux", "Vidéo", "Musique", "Réseaux", "Autres", "Favoris", "Web")
val catIcons = listOf("📱", "🎮", "🎬", "🎵", "💬", "📦", "⭐", "🌐")

class CustomTab(val id: Int, val icon: String, val name: String)
class WebLink(val id: Int, val name: String, val url: String)

val LocalPrefs = staticCompositionLocalOf<Prefs> { error("Prefs manquant") }

object LaunchOrigin { var center: Offset = Offset.Zero }

private fun clean(s: String): String = s.replace(Regex("[;|\n]"), " ").trim()

private fun parseTabs(s: String): List<CustomTab> =
    s.split(";").filter { it.isNotEmpty() }.mapNotNull { e ->
        val p = e.split("|")
        val id = p.getOrNull(0)?.toIntOrNull()
        if (id != null && p.size >= 3) CustomTab(id, p[1], p[2]) else null
    }

private fun parseWeb(s: String): List<WebLink> =
    s.split(";").filter { it.isNotEmpty() }.mapNotNull { e ->
        val p = e.split("|")
        val id = p.getOrNull(0)?.toIntOrNull()
        if (id != null && p.size >= 3) WebLink(id, p[1], p[2]) else null
    }

// Icône générée pour un site web : carré coloré avec la première lettre
fun makeWebIcon(label: String, seed: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val hue = (((seed % 360) + 360) % 360).toFloat()
    p.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
    c.drawRoundRect(0f, 0f, 144f, 144f, 40f, 40f, p)
    p.color = android.graphics.Color.WHITE
    p.textSize = 80f
    p.textAlign = android.graphics.Paint.Align.CENTER
    p.isFakeBoldText = true
    c.drawText(label.take(1).uppercase(), 72f, 99f, p)
    return bmp.asImageBitmap()
}

private val kwGames = listOf(
    "game", "supercell", "candycrush", "roblox", "minecraft", "pubg", "garena", "gameloft",
    "mojang", "activision", "ubisoft", "rovio", "innersloth", "freefire", "nianticlabs",
    "mihoyo", "epicgames", "clashofclans", "brawlstars"
)
private val kwSocial = listOf(
    "facebook", "instagram", "whatsapp", "twitter", "tiktok", "musically", "zhiliaoapp",
    "snapchat", "telegram", "discord", "linkedin", "reddit", "messenger", "threads", "signal",
    "pinterest", "bereal", "mastodon", "viber", "wechat", "tumblr", "tinder", "bluesky"
)
private val kwMusic = listOf(
    "music", "spotify", "deezer", "soundcloud", "shazam", "tidal", "napster", "podcast",
    "radio", "musique", "audiomack"
)
private val kwVideo = listOf(
    "youtube", "netflix", "twitch", "avod", "primevideo", "disney", "vlc", "mxtech", "plex",
    "canal", "molotov", "dailymotion", "tf1", "francetv", "vimeo", "kodi", "crunchyroll",
    "snaptube", "video", "movie", "cinema"
)

fun classify(info: ApplicationInfo, label: String): Int {
    when (info.category) {
        ApplicationInfo.CATEGORY_GAME -> return 1
        ApplicationInfo.CATEGORY_VIDEO -> return 2
        ApplicationInfo.CATEGORY_AUDIO -> return 3
        ApplicationInfo.CATEGORY_SOCIAL -> return 4
    }
    val s = (info.packageName + " " + label).lowercase()
    return when {
        kwGames.any { it in s } -> 1
        kwSocial.any { it in s } -> 4
        kwMusic.any { it in s } -> 3
        kwVideo.any { it in s } -> 2
        else -> 5
    }
}

// ───────────────────────── Données ─────────────────────────

class AppItem(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: ImageBitmap,
    val auto: Int,
    val raw: ImageBitmap? = null
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var accent by mutableIntStateOf(sp.getInt("accent", 0xFF7C4DFF.toInt())); private set
    var columns by mutableIntStateOf(sp.getInt("columns", 4)); private set
    var iconSize by mutableIntStateOf(sp.getInt("iconSize", 56)); private set
    var dim by mutableFloatStateOf(sp.getFloat("dim", 0.45f)); private set
    var dock by mutableStateOf(sp.getString("dock", "")!!.split("|").filter { it.isNotEmpty() }); private set

    // Arrière-plan : 0 = fond du téléphone, 1 = dégradé, 2 = photo, 3 = noir
    var bgMode by mutableIntStateOf(sp.getInt("bgMode", 0)); private set
    var bgVersion by mutableIntStateOf(0); private set
    // Animation du tiroir : 0 = glisse, 1 = zoom, 2 = fondu, 3 = pivot 3D
    var animStyle by mutableIntStateOf(sp.getInt("animStyle", 0)); private set
    // Animation des icônes : 0 = rebond, 1 = doux, 2 = aucune
    var pressStyle by mutableIntStateOf(sp.getInt("pressStyle", 0)); private set
    // Éléments de l'écran d'accueil
    var showCards by mutableStateOf(sp.getBoolean("showCards", false)); private set
    var showDock by mutableStateOf(sp.getBoolean("showDock", true)); private set
    // Style verre liquide
    var glass by mutableStateOf(sp.getBoolean("glass", true)); private set
    // Applis sur l'écran d'accueil
    var homeApps by mutableStateOf(sp.getString("home", "")!!.split("|").filter { it.isNotEmpty() }); private set
    // Horloge : style 0 = fin, 1 = gras, 2 = 3D ; alignement 0 = gauche, 1 = centre
    var clockStyle by mutableIntStateOf(sp.getInt("clockStyle", 0)); private set
    var clockSize by mutableIntStateOf(sp.getInt("clockSize", 78)); private set
    var clockAlign by mutableIntStateOf(sp.getInt("clockAlign", 0)); private set
    // Fond du tiroir : 0 = verre, 1 = sombre, 2 = couleur
    var drawerMode by mutableIntStateOf(sp.getInt("drawerMode", 0)); private set
    var drawerAlpha by mutableFloatStateOf(sp.getFloat("drawerAlpha", 0.55f)); private set
    var blurAmount by mutableIntStateOf(sp.getInt("blurAmount", 45)); private set
    // Favoris
    var favs by mutableStateOf(sp.getString("favs", "")!!.split("|").filter { it.isNotEmpty() }); private set
    var showFavs by mutableStateOf(sp.getBoolean("showFavs", true)); private set
    // Animation au lancement d'une appli : 0 aucune, 1 zoom, 2 tourbillon, 3 onde, 4 flip 3D, 5 spirale, 6 fusée
    var launchStyle by mutableIntStateOf(sp.getInt("launchStyle", 1)); private set
    var clockStack by mutableStateOf(sp.getBoolean("clockStack", false)); private set
    // Forme des icônes : 0 original, 1 rond, 2 carré, 3 rectangle, 4 étoile, 5 dragon
    var iconShape by mutableIntStateOf(sp.getInt("iconShape", 0)); private set
    // Son au toucher : 0 aucun, 1..6 sons intégrés, 7 ton propre son
    var soundStyle by mutableIntStateOf(sp.getInt("soundStyle", 1)); private set
    var soundVolume by mutableFloatStateOf(sp.getFloat("soundVolume", 0.6f)); private set
    var drawerBgVersion by mutableIntStateOf(0); private set
    // Onglets (ordre, créés par toi) et sites web
    var tabOrder by mutableStateOf(
        sp.getString("tabOrder", "0,6,1,2,3,4,5,7")!!.split(",").mapNotNull { it.toIntOrNull() }
    ); private set
    var customTabs by mutableStateOf(parseTabs(sp.getString("customTabs", "")!!)); private set
    var webLinks by mutableStateOf(parseWeb(sp.getString("webLinks", "")!!)); private set

    val overrides = mutableStateMapOf<String, Int>().apply {
        sp.getString("cats", "")!!.split(";").filter { it.contains("=") }.forEach { s ->
            val parts = s.split("=")
            parts[1].toIntOrNull()?.let { n -> put(parts[0], n) }
        }
    }

    fun catOf(a: AppItem): Int {
        val o = overrides[a.pkg]
        return if (o != null && o in tabOrder) o else a.auto
    }

    fun tabName(id: Int): String =
        if (id < 100) catNames.getOrElse(id) { "Onglet" }
        else customTabs.firstOrNull { it.id == id }?.name ?: "Onglet"

    fun tabIcon(id: Int): String =
        if (id < 100) catIcons.getOrElse(id) { "📁" }
        else customTabs.firstOrNull { it.id == id }?.icon ?: "📁"

    fun updateCat(pkg: String, c: Int) {
        if (c == 0) overrides.remove(pkg) else overrides[pkg] = c
        sp.edit().putString("cats", overrides.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    fun updateAccent(v: Int) { accent = v; sp.edit().putInt("accent", v).apply() }
    fun updateColumns(v: Int) { columns = v; sp.edit().putInt("columns", v).apply() }
    fun updateIconSize(v: Int) { iconSize = v; sp.edit().putInt("iconSize", v).apply() }
    fun updateDim(v: Float) { dim = v; sp.edit().putFloat("dim", v).apply() }
    fun updateBgMode(v: Int) { bgMode = v; sp.edit().putInt("bgMode", v).apply() }
    fun bumpBg() { bgVersion++ }
    fun updateAnimStyle(v: Int) { animStyle = v; sp.edit().putInt("animStyle", v).apply() }
    fun updatePressStyle(v: Int) { pressStyle = v; sp.edit().putInt("pressStyle", v).apply() }
    fun updateShowCards(v: Boolean) { showCards = v; sp.edit().putBoolean("showCards", v).apply() }
    fun updateShowDock(v: Boolean) { showDock = v; sp.edit().putBoolean("showDock", v).apply() }
    fun updateGlass(v: Boolean) { glass = v; sp.edit().putBoolean("glass", v).apply() }
    fun updateClockStyle(v: Int) { clockStyle = v; sp.edit().putInt("clockStyle", v).apply() }
    fun updateClockSize(v: Int) { clockSize = v; sp.edit().putInt("clockSize", v).apply() }
    fun updateClockAlign(v: Int) { clockAlign = v; sp.edit().putInt("clockAlign", v).apply() }
    fun updateDrawerMode(v: Int) { drawerMode = v; sp.edit().putInt("drawerMode", v).apply() }
    fun updateDrawerAlpha(v: Float) { drawerAlpha = v; sp.edit().putFloat("drawerAlpha", v).apply() }
    fun updateBlur(v: Int) { blurAmount = v; sp.edit().putInt("blurAmount", v).apply() }
    fun updateShowFavs(v: Boolean) { showFavs = v; sp.edit().putBoolean("showFavs", v).apply() }
    fun updateLaunchStyle(v: Int) { launchStyle = v; sp.edit().putInt("launchStyle", v).apply() }
    fun updateClockStack(v: Boolean) { clockStack = v; sp.edit().putBoolean("clockStack", v).apply() }
    fun updateIconShape(v: Int) { iconShape = v; sp.edit().putInt("iconShape", v).apply() }
    fun updateSoundStyle(v: Int) { soundStyle = v; sp.edit().putInt("soundStyle", v).apply() }
    fun updateSoundVolume(v: Float) { soundVolume = v; sp.edit().putFloat("soundVolume", v).apply() }
    fun bumpDrawerBg() { drawerBgVersion++ }

    private fun saveTabs() {
        sp.edit()
            .putString("tabOrder", tabOrder.joinToString(","))
            .putString("customTabs", customTabs.joinToString(";") { "${it.id}|${it.icon}|${it.name}" })
            .apply()
    }

    fun addTab(name: String, icon: String) {
        val id = (customTabs.maxOfOrNull { it.id } ?: 99) + 1
        customTabs = customTabs + CustomTab(id, clean(icon).ifBlank { "📁" }, clean(name).ifBlank { "Onglet" })
        tabOrder = tabOrder + id
        saveTabs()
    }

    fun deleteTab(id: Int) {
        if (id >= 100) {
            customTabs = customTabs.filter { it.id != id }
            tabOrder = tabOrder.filter { it != id }
            saveTabs()
        }
    }

    fun moveTab(id: Int, dir: Int) {
        val i = tabOrder.indexOf(id)
        val j = i + dir
        if (i >= 0 && j in tabOrder.indices) {
            val m = tabOrder.toMutableList()
            val tmp = m[i]
            m[i] = m[j]
            m[j] = tmp
            tabOrder = m
            saveTabs()
        }
    }

    private fun saveWeb() {
        sp.edit().putString("webLinks", webLinks.joinToString(";") { "${it.id}|${it.name}|${it.url}" }).apply()
    }

    fun addWeb(name: String, url: String) {
        var u = url.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        u = u.replace(";", "%3B").replace("|", "%7C")
        val id = (webLinks.maxOfOrNull { it.id } ?: 0) + 1
        val n = clean(name).ifBlank { u.removePrefix("https://").removePrefix("http://") }
        webLinks = webLinks + WebLink(id, n, u)
        saveWeb()
    }

    fun deleteWeb(id: Int) {
        webLinks = webLinks.filter { it.id != id }
        saveWeb()
    }
    fun toggleFav(pkg: String) {
        favs = if (pkg in favs) favs - pkg else favs + pkg
        sp.edit().putString("favs", favs.joinToString("|")).apply()
    }
    fun toggleHome(pkg: String) {
        homeApps = if (pkg in homeApps) homeApps - pkg else if (homeApps.size < 20) homeApps + pkg else homeApps
        sp.edit().putString("home", homeApps.joinToString("|")).apply()
    }
    fun togglePin(pkg: String) {
        dock = if (pkg in dock) dock - pkg else if (dock.size < 5) dock + pkg else dock
        sp.edit().putString("dock", dock.joinToString("|")).apply()
    }
}

suspend fun loadApps(ctx: Context): List<AppItem> = withContext(Dispatchers.Default) {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            val label = ri.loadLabel(pm).toString()
            val d = ri.loadIcon(pm)
            val masked = d.toBitmap(144, 144).asImageBitmap()
            // Version "brute" (sans le masque du système) pour pouvoir changer la forme des icônes
            val raw: ImageBitmap? = if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
                runCatching {
                    val sz = 216
                    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(b)
                    d.background?.let { it.setBounds(0, 0, sz, sz); it.draw(cv) }
                    d.foreground?.let { it.setBounds(0, 0, sz, sz); it.draw(cv) }
                    Bitmap.createBitmap(b, 36, 36, 144, 144).asImageBitmap()
                }.getOrNull()
            } else null
            AppItem(
                label,
                ri.activityInfo.packageName,
                ri.activityInfo.name,
                masked,
                classify(ri.activityInfo.applicationInfo, label),
                raw
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun loadBg(ctx: Context, name: String = "bg.jpg"): ImageBitmap? {
    val f = File(ctx.filesDir, name)
    return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
}

fun launchApp(ctx: Context, app: AppItem) {
    if (app.pkg.startsWith("web:")) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(app.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return
    }
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.cls)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(i) }
}

val swatches = listOf(
    0xFF7C4DFF, 0xFF00BCD4, 0xFFFF4081, 0xFFFF9800,
    0xFF4CAF50, 0xFF2196F3, 0xFFF44336, 0xFFFFEB3B
).map { it.toInt() }

// ───────────────────────── Verre liquide ─────────────────────────

fun Modifier.glassSurface(shape: Shape, tint: Color): Modifier = this
    .clip(shape)
    .background(Color.Black.copy(alpha = 0.18f))
    .background(
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.20f),
                Color.White.copy(alpha = 0.06f),
                tint.copy(alpha = 0.12f)
            )
        )
    )
    .drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                endY = size.height * 0.45f
            )
        )
    }
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.8f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.4f)
            )
        ),
        shape
    )

fun Modifier.panel(glass: Boolean, shape: Shape, accent: Color, flatAlpha: Float = 0.2f): Modifier =
    if (glass) glassSurface(shape, accent)
    else this.clip(shape)
        .background(accent.copy(alpha = flatAlpha))
        .border(1.dp, accent.copy(alpha = 0.5f), shape)

// ───────────────────────── Formes d'icônes ─────────────────────────

private val rectShape = GenericShape { size, _ ->
    addRoundRect(
        RoundRect(0f, size.height * 0.16f, size.width, size.height * 0.84f, size.width * 0.08f, size.width * 0.08f)
    )
}

private val starShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height * 0.53f
    for (k in 0 until 10) {
        val ang = -Math.PI / 2 + k * Math.PI / 5
        val r = (if (k % 2 == 0) 0.5f else 0.22f) * size.width
        val x = cx + (r * cos(ang)).toFloat()
        val y = cy + (r * sin(ang)).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

// Tête de dragon stylisée : deux cornes, joues et menton pointu
private val dragonShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val pts = listOf(
        0.18f to 0.00f, 0.34f to 0.22f, 0.66f to 0.22f, 0.82f to 0.00f,
        0.95f to 0.30f, 0.88f to 0.62f, 0.72f to 0.80f, 0.50f to 1.00f,
        0.28f to 0.80f, 0.12f to 0.62f, 0.05f to 0.30f
    )
    pts.forEachIndexed { k, pt ->
        if (k == 0) moveTo(pt.first * w, pt.second * h) else lineTo(pt.first * w, pt.second * h)
    }
    close()
}

fun shapeFor(style: Int): Shape = when (style) {
    1 -> CircleShape
    2 -> RoundedCornerShape(22)
    3 -> rectShape
    4 -> starShape
    5 -> dragonShape
    else -> RectangleShape
}

@Composable
fun AppIcon(app: AppItem, modifier: Modifier, shapeStyle: Int) {
    val bmp = if (shapeStyle == 0) app.icon else (app.raw ?: app.icon)
    Image(bmp, null, modifier.clip(shapeFor(shapeStyle)))
}

// ───────────────────────── Sons ─────────────────────────

object SoundFx {
    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()
    private var custom = -1

    fun init(ctx: Context) {
        if (pool != null) return
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool = sp
        for (style in 1..6) {
            val f = File(ctx.cacheDir, "tap_v1_$style.wav")
            if (!f.exists()) f.writeBytes(synth(style))
            ids[style] = sp.load(f.absolutePath, 1)
        }
        loadCustom(ctx)
    }

    fun loadCustom(ctx: Context) {
        val f = File(ctx.filesDir, "tap_custom")
        if (f.exists()) custom = pool?.load(f.absolutePath, 1) ?: -1
    }

    fun play(style: Int, volume: Float) {
        if (style <= 0) return
        val id = if (style == 7) custom else (ids[style] ?: -1)
        if (id > 0) pool?.play(id, volume, volume, 1, 0, 1f)
    }

    // Sons fabriqués par le code : 1 goutte d'eau, 2 bulle, 3 clic, 4 cristal, 5 bois, 6 pop
    private fun synth(style: Int): ByteArray {
        val sr = 22050
        val dur = when (style) {
            1 -> 0.16
            2 -> 0.20
            3 -> 0.05
            4 -> 0.50
            5 -> 0.10
            else -> 0.12
        }
        val n = (sr * dur).toInt()
        val pcm = ShortArray(n)
        var ph = 0.0
        var ph2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val p = t / dur
            val v: Double
            when (style) {
                1 -> {
                    val f = 420.0 + 1500.0 * p
                    ph += 2 * Math.PI * f / sr
                    v = sin(ph) * Math.exp(-t * 26.0)
                }
                2 -> {
                    val f = 300.0 + 800.0 * p * p
                    ph += 2 * Math.PI * f / sr
                    v = sin(ph) * sin(Math.PI * p)
                }
                3 -> {
                    ph += 2 * Math.PI * 1800.0 / sr
                    v = sin(ph) * Math.exp(-t * 160.0)
                }
                4 -> {
                    ph += 2 * Math.PI * 1320.0 / sr
                    ph2 += 2 * Math.PI * 1980.0 / sr
                    v = (sin(ph) * 0.6 + sin(ph2) * 0.4) * Math.exp(-t * 8.0)
                }
                5 -> {
                    val f = 900.0 - 300.0 * p
                    ph += 2 * Math.PI * f / sr
                    ph2 += 2 * Math.PI * f * 2 / sr
                    v = (sin(ph) * 0.8 + sin(ph2) * 0.3) * Math.exp(-t * 45.0)
                }
                else -> {
                    val f = 900.0 * Math.exp(-p * 1.5) + 150.0
                    ph += 2 * Math.PI * f / sr
                    v = sin(ph) * Math.exp(-t * 24.0)
                }
            }
            val fade = minOf(1.0, i / 44.0)
            pcm[i] = (v * fade * 0.8 * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        val bb = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + n * 2)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sr)
        bb.putInt(sr * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(n * 2)
        for (x in pcm) bb.putShort(x)
        return bb.array()
    }
}

// ───────────────────────── Contrôleur du tiroir ─────────────────────────

class DrawerCtl(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    private var job: Job? = null

    fun drag(delta: Float) {
        job?.cancel()
        progress = (progress + delta).coerceIn(0f, 1f)
    }

    fun settle(target: Float, velocity: Float = 0f) {
        job?.cancel()
        job = scope.launch {
            animate(progress, target, velocity, spring(dampingRatio = 1f, stiffness = 330f)) { v, _ ->
                progress = v.coerceIn(0f, 1f)
            }
        }
    }
}

// ───────────────────────── Écran principal ─────────────────────────

@Composable
fun LauncherRoot(prefs: Prefs, homeSignal: Int, onPickPhoto: (Int) -> Unit, onPickSound: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = remember { DrawerCtl(scope) }
    var installedApps by remember { mutableStateOf(emptyList<AppItem>()) }
    val apps = remember(installedApps, prefs.webLinks) {
        installedApps + prefs.webLinks.map {
            AppItem(it.name, "web:${it.id}", it.url, makeWebIcon(it.name, it.name.hashCode()), 7)
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf<AppItem?>(null) }
    var launchOrigin by remember { mutableStateOf(Offset.Zero) }
    val fx = remember { Animatable(0f) }
    var selectMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val pendingUninstall = remember { mutableStateListOf<String>() }
    var selectedCat by rememberSaveable { mutableIntStateOf(0) }
    val accent = Color(prefs.accent)

    val bgImage by produceState<ImageBitmap?>(null, prefs.bgVersion) {
        value = withContext(Dispatchers.IO) { loadBg(ctx) }
    }
    val drawerBg by produceState<ImageBitmap?>(null, prefs.drawerBgVersion) {
        value = withContext(Dispatchers.IO) { loadBg(ctx, "bg_drawer.jpg") }
    }

    val counts by remember(apps) {
        derivedStateOf {
            val m = HashMap<Int, Int>()
            m[0] = apps.size
            apps.forEach { val c = prefs.catOf(it); m[c] = (m[c] ?: 0) + 1 }
            m[6] = apps.count { it.pkg in prefs.favs }
            m
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refresh++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    LaunchedEffect(refresh) { installedApps = loadApps(ctx) }

    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) { showSettings = false; launching = null; drawer.settle(0f) }
    }

    // Animation de lancement : l'icône s'anime puis l'appli s'ouvre
    LaunchedEffect(launching) {
        val app = launching
        if (app != null) {
            fx.snapTo(0f)
            val job = launch { delay(200); launchApp(ctx, app) }
            fx.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
            job.join()
            delay(300)
            launching = null
        }
    }
    val startApp: (AppItem) -> Unit = { app ->
        if (launching == null) {
            if (prefs.launchStyle == 0) launchApp(ctx, app)
            else { launchOrigin = LaunchOrigin.center; launching = app }
        }
    }

    // Désinstallation de plusieurs applis : Android demande de confirmer une par une
    DisposableEffect(Unit) {
        val owner = ctx as? androidx.lifecycle.LifecycleOwner
        val obs = object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(
                source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event
            ) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && pendingUninstall.isNotEmpty()) {
                    val pkg = pendingUninstall.removeAt(0)
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val doUninstall: () -> Unit = {
        val list = selected.toList()
        selected.clear()
        selectMode = false
        list.forEach { pkg ->
            if (pkg.startsWith("web:")) {
                pkg.removePrefix("web:").toIntOrNull()?.let { prefs.deleteWeb(it) }
            } else {
                pendingUninstall.add(pkg)
            }
        }
        if (pendingUninstall.isNotEmpty()) {
            val first = pendingUninstall.removeAt(0)
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$first"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            val w = (ctx as? Activity)?.window
            snapshotFlow { (drawer.progress * prefs.blurAmount).toInt() }.collect { r ->
                runCatching { w?.setBackgroundBlurRadius(r) }
            }
        }
    }

    val open by remember { derivedStateOf { drawer.progress > 0f } }
    LaunchedEffect(open) { if (!open) selectedCat = 0 }

    BackHandler(enabled = showSettings || open) {
        if (showSettings) showSettings = false
        else if (selectMode) { selectMode = false; selected.clear() }
        else drawer.settle(0f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        val range = hPx * 0.7f

        // Arrière-plan choisi
        when (prefs.bgMode) {
            1 -> Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(lerp(Color.Black, accent, 0.6f), Color(0xFF05050A)))
                )
            )
            2 -> {
                val img = bgImage
                if (img != null) {
                    Image(
                        img, null,
                        Modifier.fillMaxSize().graphicsLayer {
                            if (Build.VERSION.SDK_INT >= 31) {
                                val r = drawer.progress * prefs.blurAmount * 0.9f
                                renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                            }
                        },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
            3 -> Box(Modifier.fillMaxSize().background(Color.Black))
        }

        // Voile coloré
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.20f), Color.Black.copy(alpha = prefs.dim))
                )
            )
        )

        // ── Accueil ──
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 1f - drawer.progress
                    val s = 1f - 0.06f * drawer.progress
                    scaleX = s; scaleY = s
                }
                .draggable(
                    state = rememberDraggableState { d -> drawer.drag(-d / range) },
                    orientation = Orientation.Vertical,
                    onDragStopped = { v ->
                        val t = when {
                            v < -700f -> 1f
                            v > 700f -> 0f
                            drawer.progress > 0.3f -> 1f
                            else -> 0f
                        }
                        drawer.settle(t, -v / range)
                    }
                )
                .pointerInput(Unit) { detectTapGestures(onLongPress = { showSettings = true }) }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Clock(prefs, accent)
            if (prefs.showCards) {
                Spacer(Modifier.height(16.dp))
                CategoryCards(apps, prefs, counts, accent) { c -> selectedCat = c; drawer.settle(1f) }
            }
            Spacer(Modifier.weight(1f))
            if (prefs.showFavs && prefs.favs.isNotEmpty()) {
                FavoritesRow(apps, prefs, accent) { startApp(it) }
                Spacer(Modifier.height(12.dp))
            }
            if (prefs.homeApps.isNotEmpty()) {
                HomeGrid(apps, prefs, accent) { startApp(it) }
                Spacer(Modifier.height(12.dp))
            }
            if (prefs.showDock && prefs.dock.isNotEmpty()) {
                Dock(apps, prefs, accent) { startApp(it) }
            }
        }

        // ── Tiroir d'applis ──
        if (open) {
            Drawer(
                apps = apps, prefs = prefs, accent = accent, counts = counts,
                selectedCat = selectedCat, onSelectCat = { selectedCat = it },
                drawer = drawer, range = range,
                selectMode = selectMode, selected = selected, drawerBg = drawerBg,
                onToggleSel = { pkg -> if (pkg in selected) selected.remove(pkg) else selected.add(pkg) },
                onEnterSelect = { selectMode = true },
                onExitSelect = { selectMode = false; selected.clear() },
                onUninstall = doUninstall,
                onOpenSettings = { showSettings = true },
                modifier = Modifier.graphicsLayer {
                    val p = drawer.progress
                    when (prefs.animStyle) {
                        1 -> {
                            val s = 0.8f + 0.2f * p
                            scaleX = s; scaleY = s
                            alpha = p
                        }
                        2 -> alpha = p
                        3 -> {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            rotationX = (1f - p) * 70f
                            cameraDistance = 14f * density
                            alpha = p
                        }
                        else -> {
                            translationY = (1f - p) * hPx * 0.6f
                            alpha = p
                        }
                    }
                },
                onLaunch = { startApp(it) }
            )
        }

        // ── Réglages ──
        if (showSettings) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { showSettings = false }
            )
        }
        AnimatedVisibility(
            visible = showSettings,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            SettingsPanel(prefs, accent, onPickPhoto, onPickSound) {
                showSettings = false
                selectMode = true
                drawer.settle(1f)
            }
        }

        val lApp = launching
        if (lApp != null && prefs.launchStyle != 0) {
            LaunchOverlay(lApp, launchOrigin, { fx.value }, prefs.launchStyle, accent, hPx)
        }
    }
}

// ───────────────────────── Animation de lancement ─────────────────────────

@Composable
fun LaunchOverlay(
    app: AppItem, origin: Offset, progress: () -> Float, style: Int, accent: Color, hPx: Float
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Color.Black.copy(alpha = (progress() * 0.55f).coerceIn(0f, 0.55f)))
            }
        )
        if (style == 3) {
            Canvas(Modifier.fillMaxSize()) {
                val p = progress()
                drawCircle(
                    accent.copy(alpha = (0.9f - 0.5f * p).coerceIn(0f, 1f)),
                    radius = p * size.maxDimension * 1.3f,
                    center = origin
                )
            }
        }
        Image(
            app.icon, null,
            Modifier
                .size(64.dp)
                .offset { IntOffset((origin.x - 32.dp.toPx()).toInt(), (origin.y - 32.dp.toPx()).toInt()) }
                .graphicsLayer {
                    val p = progress()
                    cameraDistance = 14f * density
                    when (style) {
                        1 -> {
                            val sc = 1f + p * 12f
                            scaleX = sc; scaleY = sc; alpha = 1f - p * 0.8f
                        }
                        2 -> {
                            val sc = 1f + p * 9f
                            scaleX = sc; scaleY = sc; rotationZ = p * 720f; alpha = 1f - p * 0.8f
                        }
                        3 -> {
                            val sc = 1f + p * 1.5f
                            scaleX = sc; scaleY = sc; alpha = 1f - p
                        }
                        4 -> {
                            val sc = 1f + p * 8f
                            scaleX = sc; scaleY = sc; rotationY = p * 540f; alpha = 1f - p * 0.8f
                        }
                        5 -> {
                            val sc = if (p < 0.4f) 1f - p * 1.75f else 0.3f + (p - 0.4f) / 0.6f * 12f
                            scaleX = sc; scaleY = sc; rotationZ = p * 1080f; alpha = 1f - p * 0.7f
                        }
                        else -> {
                            val sc = 1f + p * 2f
                            translationY = -p * hPx
                            scaleX = sc; scaleY = sc; alpha = 1f - p * 0.6f
                        }
                    }
                }
        )
    }
}

// ───────────────────────── Horloge ─────────────────────────

@Composable
fun Clock(prefs: Prefs, accent: Color) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val loc = Locale.getDefault()
    val d = Date(now)
    val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", loc).format(d)
    val hh = SimpleDateFormat(if (is24) "HH" else "h", loc).format(d)
    val mm = SimpleDateFormat("mm", loc).format(d)
    val date = SimpleDateFormat("EEEE d MMMM", loc).format(d).replaceFirstChar { it.uppercase() }
    val size = prefs.clockSize.sp
    val align: Alignment.Horizontal =
        if (prefs.clockAlign == 1) Alignment.CenterHorizontally else Alignment.Start
    val tight = Modifier.layout { m, c ->
        val pl = m.measure(c)
        layout(pl.width, (pl.height * 0.78f).toInt()) { pl.place(0, -(pl.height * 0.11f).toInt()) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = align) {
        if (prefs.clockStack) {
            Box(tight) { ClockText(hh, size, accent, prefs.clockStyle) }
            Box(tight) { ClockText(mm, size, accent, prefs.clockStyle) }
        } else {
            ClockText(time, size, accent, prefs.clockStyle)
        }
        Text(
            date, color = accent,
            fontSize = (prefs.clockSize * 0.23f).coerceIn(14f, 28f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ClockText(text: String, size: TextUnit, accent: Color, style: Int) {
    when (style) {
        1 -> Text(
            text, color = Color.White, fontSize = size, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false
        )
        2 -> Clock3D(text, size, accent)
        3 -> FancyClock(
            text, size,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.08f))),
            Color.White.copy(alpha = 0.9f), null, null, false
        )
        4 -> FancyClock(
            text, size,
            Brush.verticalGradient(
                listOf(
                    Color(0xFFF2FDFF),
                    Color(0xFF8FDDF7).copy(alpha = 0.75f),
                    Color(0xFFC8F2FF).copy(alpha = 0.55f)
                )
            ),
            Color(0xFFEFFBFF).copy(alpha = 0.95f), Color(0xFF9BE3FF), Color(0xFF7FD8F6), true
        )
        5 -> FancyClock(text, size, SolidColor(Color.White), accent, null, accent, false)
        6 -> FancyClock(
            text, size,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.06f))),
            Color.White.copy(alpha = 0.85f), Color.White, null, true
        )
        7 -> FancyClock(text, size, null, Color.White, null, null, false)
        else -> Text(
            text, color = Color.White, fontSize = size, fontWeight = FontWeight.Thin,
            maxLines = 1, softWrap = false
        )
    }
}

// Horloge en relief : plusieurs couches décalées + légère perspective
@Composable
fun Clock3D(text: String, size: TextUnit, accent: Color) {
    Box(
        Modifier
            .padding(end = 14.dp, bottom = 14.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                rotationY = -14f
                cameraDistance = 16f * density
            }
    ) {
        for (i in 12 downTo 1) {
            Text(
                text, fontSize = size, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
                color = lerp(accent, Color.Black, i / 14f),
                modifier = Modifier.offset(x = (i * 0.8f).dp, y = (i * 0.8f).dp)
            )
        }
        Text(
            text, maxLines = 1, softWrap = false,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.verticalGradient(listOf(Color.White, lerp(Color.White, accent, 0.55f))),
                fontSize = size,
                fontWeight = FontWeight.Black
            )
        )
    }
}

// Horloges verre / glace / néon / contour
@Composable
fun FancyClock(
    text: String, size: TextUnit, fill: Brush?, stroke: Color?, depth: Color?, glow: Color?, tilt: Boolean
) {
    val w = FontWeight.Black
    Box(
        Modifier
            .padding(end = 14.dp, bottom = 14.dp)
            .graphicsLayer {
                if (tilt) {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = -14f
                    cameraDistance = 16f * density
                }
            }
    ) {
        if (depth != null) {
            for (i in 12 downTo 1) {
                Text(
                    text, fontSize = size, fontWeight = w, maxLines = 1, softWrap = false,
                    color = depth.copy(alpha = 0.09f),
                    modifier = Modifier.offset(x = (i * 0.8f).dp, y = (i * 0.8f).dp)
                )
            }
        }
        if (fill != null) {
            Text(
                text, maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(
                    brush = fill, fontSize = size, fontWeight = w,
                    shadow = if (glow != null) Shadow(glow, Offset.Zero, 36f) else null
                )
            )
        }
        if (stroke != null) {
            Text(
                text, maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(
                    color = stroke, fontSize = size, fontWeight = w,
                    drawStyle = Stroke(width = size.value * 0.02f + 1.5f),
                    shadow = if (fill == null && glow != null) Shadow(glow, Offset.Zero, 36f) else null
                )
            )
        }
    }
}

// ───────────────────────── Favoris ─────────────────────────

@Composable
fun FavoritesRow(apps: List<AppItem>, prefs: Prefs, accent: Color, onLaunch: (AppItem) -> Unit) {
    val items = prefs.favs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    if (items.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .panel(prefs.glass, RoundedCornerShape(26.dp), accent, 0.18f)
            .padding(vertical = 12.dp)
    ) {
        Text(
            "⭐ Favoris", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            items.forEach { app ->
                AppCell(
                    app, (prefs.iconSize - 6).dp, app.pkg in prefs.dock, true, accent,
                    prefs.catOf(app), prefs.pressStyle, app.pkg in prefs.homeApps, true,
                    { onLaunch(app) },
                    { prefs.togglePin(app.pkg) },
                    { prefs.toggleHome(app.pkg) },
                    { prefs.toggleFav(app.pkg) },
                    { c -> prefs.updateCat(app.pkg, c) }
                )
            }
        }
    }
}

// ───────────────────────── Applis de l'écran d'accueil ─────────────────────────

@Composable
fun HomeGrid(apps: List<AppItem>, prefs: Prefs, accent: Color, onLaunch: (AppItem) -> Unit) {
    val items = prefs.homeApps.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    val cols = minOf(prefs.columns, 5)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppCell(
                            app, prefs.iconSize.dp, app.pkg in prefs.dock, true, accent,
                            prefs.catOf(app), prefs.pressStyle, true, app.pkg in prefs.favs,
                            { onLaunch(app) },
                            { prefs.togglePin(app.pkg) },
                            { prefs.toggleHome(app.pkg) },
                            { prefs.toggleFav(app.pkg) },
                            { c -> prefs.updateCat(app.pkg, c) }
                        )
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ───────────────────────── Cartes de catégories (accueil) ─────────────────────────

@Composable
fun CategoryCards(
    apps: List<AppItem>, prefs: Prefs, counts: Map<Int, Int>, accent: Color, onClick: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(listOf(1, 2), listOf(3, 4)).forEach { rowCats ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCats.forEach { c ->
                    val previews = apps.filter { prefs.catOf(it) == c }.take(4)
                    Column(
                        Modifier
                            .weight(1f)
                            .panel(prefs.glass, RoundedCornerShape(22.dp), accent, 0.18f)
                            .clickable { onClick(c) }
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(catIcons[c], fontSize = 26.sp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(catNames[c], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${counts[c] ?: 0} applis", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.height(30.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            previews.forEach { app ->
                                AppIcon(app, Modifier.size(30.dp), prefs.iconShape)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Dock ─────────────────────────

@Composable
fun Dock(apps: List<AppItem>, prefs: Prefs, accent: Color, onLaunch: (AppItem) -> Unit) {
    val dockApps = prefs.dock.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    Box(
        Modifier
            .fillMaxWidth()
            .panel(prefs.glass, RoundedCornerShape(32.dp), accent, 0.22f)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (dockApps.isEmpty()) {
            Text(
                "Appui long sur une appli (dans le tiroir) pour l'épingler ici",
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(12.dp)
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                dockApps.forEach { app ->
                    AppCell(
                        app, prefs.iconSize.dp, true, false, accent, prefs.catOf(app), prefs.pressStyle,
                        app.pkg in prefs.homeApps, app.pkg in prefs.favs,
                        { onLaunch(app) },
                        { prefs.togglePin(app.pkg) },
                        { prefs.toggleHome(app.pkg) },
                        { prefs.toggleFav(app.pkg) },
                        { c -> prefs.updateCat(app.pkg, c) }
                    )
                }
            }
        }
    }
}

// ───────────────────────── Tiroir ─────────────────────────

@Composable
fun Drawer(
    apps: List<AppItem>, prefs: Prefs, accent: Color, counts: Map<Int, Int>,
    selectedCat: Int, onSelectCat: (Int) -> Unit,
    drawer: DrawerCtl, range: Float, modifier: Modifier,
    selectMode: Boolean, selected: List<String>, drawerBg: ImageBitmap?,
    onToggleSel: (String) -> Unit, onEnterSelect: () -> Unit, onExitSelect: () -> Unit,
    onUninstall: () -> Unit, onOpenSettings: () -> Unit,
    onLaunch: (AppItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val filtered by remember(apps, selectedCat, query) {
        derivedStateOf {
            apps.filter {
                (selectedCat == 0 ||
                    (if (selectedCat == 6) it.pkg in prefs.favs else prefs.catOf(it) == selectedCat)) &&
                    (query.isBlank() || it.label.contains(query, ignoreCase = true))
            }
        }
    }

    val connection = remember(range) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    drawer.drag(-available.y / range)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (drawer.progress < 1f) drawer.settle(if (drawer.progress > 0.6f) 1f else 0f)
                return Velocity.Zero
            }
        }
    }

    val a = prefs.drawerAlpha
    val drawerBrush: Brush = when (prefs.drawerMode) {
        1 -> Brush.verticalGradient(listOf(Color.Black.copy(alpha = a), Color.Black.copy(alpha = a)))
        2 -> Brush.verticalGradient(
            listOf(
                lerp(Color.Black, accent, 0.45f).copy(alpha = a),
                lerp(Color.Black, accent, 0.8f).copy(alpha = a)
            )
        )
        else -> Brush.verticalGradient(
            listOf(
                Color.Black.copy(alpha = a * 0.8f),
                lerp(Color.Black, accent, 0.35f).copy(alpha = (a * 1.1f).coerceAtMost(1f))
            )
        )
    }

    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gapPx = with(LocalDensity.current) { 4.dp.toPx() }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val widths = remember { mutableStateMapOf<Int, Float>() }
    var bottomBarH by remember { mutableIntStateOf(0) }
    val pill = RoundedCornerShape(50)

    Box(modifier.fillMaxSize()) {
    if (prefs.drawerMode == 3 && drawerBg != null) {
        Image(drawerBg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (prefs.drawerMode == 3) SolidColor(Color.Black.copy(alpha = a * 0.6f)) else drawerBrush
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (selectMode) {
                        Text(
                            "${selected.size} sélectionnée(s)",
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Touche les applis à désinstaller",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                        )
                    } else {
                        Text(
                            "${prefs.tabIcon(selectedCat)} ${if (selectedCat == 0) "Applications" else prefs.tabName(selectedCat)}",
                            color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${filtered.size} applications",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                        )
                    }
                }
                if (selectMode) {
                    TextButton(onClick = onExitSelect) { Text("Annuler", color = Color.White) }
                    TextButton(onClick = onUninstall) { Text("🗑 Désinstaller", color = accent) }
                } else {
                    TextButton(onClick = onEnterSelect) { Text("☑️ Choisir", color = accent) }
                }
            }

            if (searchOpen) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { filtered.firstOrNull()?.let(onLaunch) }),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .panel(prefs.glass, RoundedCornerShape(28.dp), accent, 0.12f)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 16.sp)
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text("Rechercher", color = Color.White.copy(alpha = 0.5f), fontSize = 17.sp)
                                }
                                inner()
                            }
                        }
                    }
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(prefs.columns),
                modifier = Modifier.fillMaxSize().nestedScroll(connection),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp,
                    bottom = with(density) { bottomBarH.toDp() } + 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.pkg + it.cls }, contentType = { 0 }) { app ->
                    Box(Modifier.fillMaxWidth().animateItem(), contentAlignment = Alignment.Center) {
                        AppCell(
                            app, prefs.iconSize.dp, app.pkg in prefs.dock, true, accent,
                            prefs.catOf(app), prefs.pressStyle, app.pkg in prefs.homeApps,
                            app.pkg in prefs.favs,
                            { if (selectMode) onToggleSel(app.pkg) else onLaunch(app) },
                            { prefs.togglePin(app.pkg) },
                            { prefs.toggleHome(app.pkg) },
                            { prefs.toggleFav(app.pkg) },
                            { c -> prefs.updateCat(app.pkg, c) }
                        )
                        if (selectMode && app.pkg in selected) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(accent.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                Text("✅", fontSize = 18.sp, modifier = Modifier.padding(6.dp))
                            }
                        }
                    }
                }
            }
        }

        // Barre flottante en verre : onglets (appui long + glisse pour les déplacer) + bouton recherche
        Row(
            Modifier
                .onSizeChanged { bottomBarH = it.height }
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .panel(prefs.glass, pill, accent, 0.25f)
                    .horizontalScroll(rememberScrollState())
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                prefs.tabOrder.forEach { i ->
                    key(i) {
                        val sel = i == selectedCat
                        val dragging = dragId == i
                        Column(
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationX = if (dragging) dragOffset else 0f
                                    val sc = if (dragging) 1.1f else 1f
                                    scaleX = sc; scaleY = sc
                                }
                                .then(
                                    if (sel) Modifier
                                        .clip(pill)
                                        .background(accent.copy(alpha = 0.35f))
                                        .border(1.dp, Color.White.copy(alpha = 0.55f), pill)
                                    else Modifier
                                )
                                .onSizeChanged { widths[i] = it.width.toFloat() }
                                .pointerInput(i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragId = i
                                            dragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.x
                                            val order = prefs.tabOrder
                                            val idx = order.indexOf(i)
                                            if (dragOffset > 0f && idx >= 0 && idx < order.size - 1) {
                                                val nw = widths[order[idx + 1]] ?: 0f
                                                if (dragOffset > nw / 2f + gapPx / 2f) {
                                                    prefs.moveTab(i, 1)
                                                    dragOffset -= nw + gapPx
                                                }
                                            } else if (dragOffset < 0f && idx > 0) {
                                                val pw = widths[order[idx - 1]] ?: 0f
                                                if (-dragOffset > pw / 2f + gapPx / 2f) {
                                                    prefs.moveTab(i, -1)
                                                    dragOffset += pw + gapPx
                                                }
                                            }
                                        },
                                        onDragEnd = { dragId = null; dragOffset = 0f },
                                        onDragCancel = { dragId = null; dragOffset = 0f }
                                    )
                                }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    SoundFx.play(prefs.soundStyle, prefs.soundVolume)
                                    onSelectCat(i)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .widthIn(min = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(prefs.tabIcon(i), fontSize = 18.sp)
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "${counts[i] ?: 0}",
                                    color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp
                                )
                            }
                            Text(
                                prefs.tabName(i), color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .size(54.dp)
                    .panel(prefs.glass, CircleShape, accent, 0.25f)
                    .clickable {
                        SoundFx.play(prefs.soundStyle, prefs.soundVolume)
                        onOpenSettings()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙️", fontSize = 20.sp, color = Color.White)
            }
            Box(
                Modifier
                    .size(54.dp)
                    .panel(prefs.glass, CircleShape, accent, 0.25f)
                    .clickable {
                        SoundFx.play(prefs.soundStyle, prefs.soundVolume)
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (searchOpen) "✕" else "🔍", fontSize = 20.sp, color = Color.White)
            }
        }
    }
    }
}

// ───────────────────────── Icône d'appli ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(
    app: AppItem, size: Dp, pinned: Boolean, showLabel: Boolean, accent: Color, cat: Int,
    pressStyle: Int, homePinned: Boolean, favPinned: Boolean,
    onClick: () -> Unit, onTogglePin: () -> Unit, onToggleHome: () -> Unit,
    onToggleFav: () -> Unit, onSetCat: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val isWeb = app.pkg.startsWith("web:")
    val centerHolder = remember { floatArrayOf(0f, 0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val target = if (!pressed) 1f else when (pressStyle) {
        0 -> 0.86f
        1 -> 0.93f
        else -> 1f
    }
    val scale by animateFloatAsState(
        target,
        if (pressStyle == 0) spring<Float>(dampingRatio = 0.5f, stiffness = 500f)
        else spring<Float>(dampingRatio = 1f, stiffness = 600f),
        label = "press"
    )
    var menu by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }

    Box {
        Column(
            Modifier
                .onGloballyPositioned { c ->
                    val pos = c.positionInRoot()
                    centerHolder[0] = pos.x + c.size.width / 2f
                    centerHolder[1] = pos.y + c.size.height / 2f
                }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .combinedClickable(
                    interactionSource = source, indication = null,
                    onClick = {
                        SoundFx.play(prefs.soundStyle, prefs.soundVolume)
                        LaunchOrigin.center = Offset(centerHolder[0], centerHolder[1])
                        onClick()
                    },
                    onLongClick = { menu = true }
                )
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(size)) {
                AppIcon(app, Modifier.fillMaxSize(), prefs.iconShape)
                if (favPinned) {
                    Text("⭐", fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
            if (showLabel) {
                Text(
                    app.label, color = Color.White, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).widthIn(max = size + 16.dp)
                )
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false; page = 0 },
            containerColor = Color(0xFF1E1E24)
        ) {
            if (page == 0) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Retirer du dock" else "Épingler au dock", color = Color.White) },
                    onClick = { menu = false; onTogglePin() }
                )
                DropdownMenuItem(
                    text = {
                        Text(if (favPinned) "Retirer des favoris" else "Ajouter aux favoris ⭐", color = Color.White)
                    },
                    onClick = { menu = false; onToggleFav() }
                )
                DropdownMenuItem(
                    text = {
                        Text(if (homePinned) "Retirer de l'accueil" else "Ajouter à l'accueil", color = Color.White)
                    },
                    onClick = { menu = false; onToggleHome() }
                )
                DropdownMenuItem(
                    text = { Text("Catégorie : ${prefs.tabName(cat)} …", color = Color.White) },
                    onClick = { page = 1 }
                )
                if (isWeb) {
                    DropdownMenuItem(
                        text = { Text("Supprimer le site", color = accent) },
                        onClick = {
                            menu = false
                            app.pkg.removePrefix("web:").toIntOrNull()?.let { prefs.deleteWeb(it) }
                        }
                    )
                } else {
                DropdownMenuItem(
                    text = { Text("Infos de l'appli", color = Color.White) },
                    onClick = {
                        menu = false
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Désinstaller", color = accent) },
                    onClick = {
                        menu = false
                        ctx.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
                }
            } else {
                prefs.tabOrder.filter { it != 0 && it != 6 }.forEach { c ->
                    DropdownMenuItem(
                        text = {
                            Text("${prefs.tabIcon(c)} ${prefs.tabName(c)}", color = if (c == cat) accent else Color.White)
                        },
                        onClick = { menu = false; page = 0; onSetCat(c) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Automatique", color = Color.White.copy(alpha = 0.7f)) },
                    onClick = { menu = false; page = 0; onSetCat(0) }
                )
            }
        }
    }
}

// ───────────────────────── Réglages ─────────────────────────

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
        modifier = Modifier.padding(top = 14.dp)
    )
}

@Composable
fun TabDialogs(prefs: Prefs, accent: Color, dialog: Int, onClose: () -> Unit) {
    when (dialog) {
        1 -> {
            var name by remember { mutableStateOf("") }
            var icon by remember { mutableStateOf("📁") }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Nouvel onglet") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(icon, { icon = it }, label = { Text("Logo (emoji)") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { prefs.addTab(name, icon); onClose() }) { Text("Créer", color = accent) }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } }
            )
        }
        2 -> {
            var name by remember { mutableStateOf("") }
            var url by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Ajouter un site web") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text("Nom (ex : YouTube)") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(url, { url = it }, label = { Text("Adresse (ex : youtube.com)") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { prefs.addWeb(name, url); onClose() }) { Text("Ajouter", color = accent) }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } }
            )
        }
        3 -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Mes onglets") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    prefs.tabOrder.forEach { id ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${prefs.tabIcon(id)} ${prefs.tabName(id)}", modifier = Modifier.weight(1f))
                            Text("▲", modifier = Modifier.clickable { prefs.moveTab(id, -1) }.padding(10.dp))
                            Text("▼", modifier = Modifier.clickable { prefs.moveTab(id, 1) }.padding(10.dp))
                            if (id >= 100) {
                                Text("🗑", modifier = Modifier.clickable { prefs.deleteTab(id) }.padding(10.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("OK", color = accent) } }
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.Black)
        )
    }
}

@Composable
fun OptionChips(options: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, name ->
            val sel = i == selected
            Text(
                name,
                color = if (sel) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) accent else Color.White.copy(alpha = 0.12f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
fun SettingsPanel(
    prefs: Prefs, accent: Color, onPickPhoto: (Int) -> Unit,
    onPickSound: () -> Unit, onSelectApps: () -> Unit
) {
    val ctx = LocalContext.current
    var hue by remember { mutableFloatStateOf(0f) }
    var dialog by remember { mutableIntStateOf(0) }
    val hasPhoto = File(ctx.filesDir, "bg.jpg").exists()
    val hasDrawerPhoto = File(ctx.filesDir, "bg_drawer.jpg").exists()
    val sliderColors = SliderDefaults.colors(
        thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xE61A1A1F))
            .border(
                1.dp, Color.White.copy(alpha = 0.25f),
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
            .navigationBarsPadding()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Personnalisation", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        SectionLabel("Écran d'accueil")
        SwitchRow("Cartes des catégories", prefs.showCards, accent) { prefs.updateShowCards(it) }
        SwitchRow("Dock (applis épinglées)", prefs.showDock, accent) { prefs.updateShowDock(it) }
        SwitchRow("Favoris sur l'accueil", prefs.showFavs, accent) { prefs.updateShowFavs(it) }
        SwitchRow("Style verre liquide", prefs.glass, accent) { prefs.updateGlass(it) }

        SectionLabel("Horloge")
        OptionChips(
            listOf("Fin", "Gras", "3D", "Verre", "Glace", "Néon", "3D Verre", "Contour"),
            prefs.clockStyle, accent
        ) { prefs.updateClockStyle(it) }
        OptionChips(listOf("Gauche", "Centre"), prefs.clockAlign, accent) { prefs.updateClockAlign(it) }
        OptionChips(
            listOf("Une ligne", "Deux lignes (plus gros)"), if (prefs.clockStack) 1 else 0, accent
        ) { prefs.updateClockStack(it == 1) }
        SettingSlider(
            "Taille de l'horloge : ${prefs.clockSize}", prefs.clockSize.toFloat(), 40f..260f, sliderColors, 0
        ) { prefs.updateClockSize(it.toInt()) }

        SectionLabel("Couleur")
        LazyRow(
            Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(swatches) { c ->
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(if (c == prefs.accent) 3.dp else 0.dp, Color.White, CircleShape)
                        .clickable { prefs.updateAccent(c) }
                )
            }
        }
        Slider(
            value = hue,
            onValueChange = { hue = it; prefs.updateAccent(Color.hsv(it, 0.7f, 1f).toArgb()) },
            valueRange = 0f..360f, colors = sliderColors
        )

        SectionLabel("Arrière-plan")
        OptionChips(
            listOf("📱 Téléphone", "🎨 Dégradé", "🖼️ Photo", "⚫ Noir"),
            prefs.bgMode, accent
        ) { i ->
            if (i == 2 && !hasPhoto) onPickPhoto(0) else prefs.updateBgMode(i)
        }
        TextButton(onClick = { onPickPhoto(0) }) {
            Text(if (hasPhoto) "Changer la photo de fond" else "Choisir une photo de fond", color = accent)
        }

        SectionLabel("Fond du tiroir")
        OptionChips(
            listOf("Verre", "Sombre", "Couleur", "🖼️ Photo"), prefs.drawerMode, accent
        ) { i ->
            if (i == 3 && !hasDrawerPhoto) onPickPhoto(1) else prefs.updateDrawerMode(i)
        }
        TextButton(onClick = { onPickPhoto(1) }) {
            Text(
                if (hasDrawerPhoto) "Changer la photo du tiroir" else "Choisir une photo pour le tiroir",
                color = accent
            )
        }
        SettingSlider(
            "Opacité : ${(prefs.drawerAlpha * 100).toInt()} %", prefs.drawerAlpha, 0.1f..0.95f, sliderColors, 0
        ) { prefs.updateDrawerAlpha(it) }
        SettingSlider(
            "Flou du fond : ${prefs.blurAmount}", prefs.blurAmount.toFloat(), 0f..80f, sliderColors, 0
        ) { prefs.updateBlur(it.toInt()) }

        SectionLabel("Forme des icônes")
        OptionChips(
            listOf("Original", "Rond", "Carré", "Rectangle", "Étoile", "Dragon"),
            prefs.iconShape, accent
        ) { prefs.updateIconShape(it) }

        SectionLabel("Son quand tu appuies")
        OptionChips(
            listOf("Aucun", "💧 Goutte d'eau", "Bulle", "Clic", "Cristal", "Bois", "Pop", "Mon son"),
            prefs.soundStyle, accent
        ) { i ->
            if (i == 7 && !File(ctx.filesDir, "tap_custom").exists()) onPickSound()
            else {
                prefs.updateSoundStyle(i)
                SoundFx.play(i, prefs.soundVolume)
            }
        }
        TextButton(onClick = onPickSound) { Text("🎵 Choisir mon propre son (court)", color = accent) }
        SettingSlider(
            "Volume du son : ${(prefs.soundVolume * 100).toInt()} %", prefs.soundVolume, 0f..1f, sliderColors, 0
        ) { prefs.updateSoundVolume(it) }

        SectionLabel("Applications")
        TextButton(onClick = onSelectApps) { Text("🗑 Désinstaller plusieurs applis d'un coup", color = accent) }

        SectionLabel("Onglets")
        TextButton(onClick = { dialog = 1 }) { Text("➕ Créer un onglet", color = accent) }
        TextButton(onClick = { dialog = 2 }) { Text("🌐 Ajouter un site web", color = accent) }
        TextButton(onClick = { dialog = 3 }) { Text("↕️ Déplacer / supprimer mes onglets", color = accent) }
        TabDialogs(prefs, accent, dialog) { dialog = 0 }

        SectionLabel("Animation au lancement d'une appli")
        OptionChips(
            listOf("Aucune", "Zoom", "Tourbillon", "Onde", "Flip 3D", "Spirale", "Fusée"),
            prefs.launchStyle, accent
        ) { prefs.updateLaunchStyle(it) }

        SectionLabel("Animation du tiroir")
        OptionChips(
            listOf("Glisse", "Zoom", "Fondu", "Pivot 3D"),
            prefs.animStyle, accent
        ) { prefs.updateAnimStyle(it) }

        SectionLabel("Animation des icônes")
        OptionChips(
            listOf("Rebond", "Doux", "Aucune"),
            prefs.pressStyle, accent
        ) { prefs.updatePressStyle(it) }

        SettingSlider("Colonnes : ${prefs.columns}", prefs.columns.toFloat(), 3f..6f, sliderColors, 2) {
            prefs.updateColumns(it.toInt())
        }
        SettingSlider("Taille des icônes : ${prefs.iconSize}", prefs.iconSize.toFloat(), 40f..72f, sliderColors, 0) {
            prefs.updateIconSize(it.toInt())
        }
        SettingSlider("Assombrissement du fond", prefs.dim, 0f..0.8f, sliderColors, 0) { prefs.updateDim(it) }

        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Définir comme launcher par défaut", color = accent) }
    }
}

@Composable
fun SettingSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    colors: SliderColors, steps: Int, onChange: (Float) -> Unit
) {
    Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, colors = colors)
}
