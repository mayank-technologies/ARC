package com.example.ui.components

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.ai.VoiceEngineState
import com.example.ui.theme.ArcAlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcCyanSoft
import com.example.ui.theme.ArcDeepSpace
import com.example.ui.theme.ArcIonBlue
import com.example.ui.theme.ArcStandbyAmber
import com.example.ui.theme.ArcTeal
import com.example.ui.theme.ArcVoid
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Full-screen dark cybernetic backdrop with subtle radial core aura and precision grid lines.
 */
@Composable
fun ArcBackgroundGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(ArcVoid, ArcDeepSpace, ArcVoid)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ArcCyan.copy(alpha = 0.08f),
                    ArcIonBlue.copy(alpha = 0.03f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.42f),
                radius = size.minDimension * 0.72f
            ),
            radius = size.minDimension * 0.72f,
            center = Offset(size.width * 0.5f, size.height * 0.42f)
        )
        val step = 56.dp.toPx()
        val lineColor = ArcCyan.copy(alpha = 0.035f)
        var x = 0f
        while (x < size.width) {
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

// 4,400 bold, crisp sapphire stardust particles + 75 faint cosmic background specks
private const val CORE_PARTICLE_COUNT = 4400
private const val COSMIC_SPECK_COUNT = 75
private const val BUCKET_COUNT = 8

/**
 * Structure-of-Arrays (SoA) precomputed 3D particle cloud for zero-allocation 60fps rendering:
 * - unitX/Y/Z: normalized direction vectors on the unit sphere (Fibonacci lattice + organic jitter)
 * - radialNorm: 3-zone radial distance matching the reference visual:
 *     54% outer 3D spherical shell (0.82..1.00) -> dense, crisp royal-sapphire circular rim
 *     32% luminous mid-body (0.26..0.82) -> vivid starry sapphire depth all the way across the orb
 *     14% tight inner starburst (0.01..0.26) -> sparkling white & ice-blue central core
 * - flareWeight: per-particle radial flare responsiveness during speech peaks
 * - speckX/Y: faint cosmic specks scattered in the dark backdrop around the sphere
 */
private class ParticleCloudData(count: Int) {
    val unitX = FloatArray(count)
    val unitY = FloatArray(count)
    val unitZ = FloatArray(count)
    val radialNorm = FloatArray(count)
    val baseBrightness = FloatArray(count)
    val twinklePhase = FloatArray(count)
    val twinkleHarmonic = FloatArray(count)
    val flareWeight = FloatArray(count)

    val speckX = FloatArray(COSMIC_SPECK_COUNT)
    val speckY = FloatArray(COSMIC_SPECK_COUNT)
    val speckPhase = FloatArray(COSMIC_SPECK_COUNT)

    init {
        val rng = Random(4207)
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until count) {
            val baseY = 1f - (i / (count - 1f)) * 2f
            val jitterPhi = (rng.nextFloat() - 0.5f) * 0.16f
            val phi = (acos(baseY.coerceIn(-1f, 1f)) + jitterPhi).coerceIn(0.01f, (PI - 0.01).toFloat())
            val theta = goldenAngle * i + (rng.nextFloat() - 0.5f) * 0.30f

            val sinPhi = sin(phi)
            unitX[i] = sinPhi * cos(theta)
            unitY[i] = cos(phi)
            unitZ[i] = sinPhi * sin(theta)

            val u = rng.nextFloat()
            val band = rng.nextFloat()
            val r = when {
                // 54% outer spherical shell -> bold glowing sapphire circular rim
                band < 0.54f -> 1.00f - u.pow(1.75f) * 0.18f
                // 32% mid body -> rich visible sapphire stardust across the whole sphere
                band < 0.86f -> 0.26f + u.pow(1.05f) * 0.56f
                // 14% central starburst -> tight sparkling white/ice-blue core cluster
                else -> 0.01f + u.pow(1.70f) * 0.25f
            }
            radialNorm[i] = r
            // Outer-shell particles flare outward dynamically when ARC speaks
            flareWeight[i] = if (r > 0.78f) (0.35f + rng.nextFloat() * 0.65f) else 0.15f
            baseBrightness[i] = 0.62f + rng.nextFloat() * 0.38f
            twinklePhase[i] = rng.nextFloat() * (2f * PI.toFloat())
            twinkleHarmonic[i] = (1 + rng.nextInt(3)).toFloat()
        }

        // Faint cosmic specks scattered softly in the surrounding dark space
        for (s in 0 until COSMIC_SPECK_COUNT) {
            val angle = rng.nextFloat() * (2f * PI.toFloat())
            val dist = 1.07f + rng.nextFloat() * 0.40f
            speckX[s] = cos(angle) * dist
            speckY[s] = sin(angle) * dist
            speckPhase[s] = rng.nextFloat() * (2f * PI.toFloat())
        }
    }
}

/**
 * Central CORE particle sphere avatar matching the reference sapphire stardust globe:
 * - Bold, high-contrast cobalt/sapphire stardust particles at full brightness even on standby
 * - Tight sparkling white starburst core that doesn't wash out the surrounding blue globe
 * - Large presence (`baseRadius = 0.40 * minDimension` inside an ~84% screen-width box = ~60% diameter)
 * - 4,400 crisp anti-aliased stardust micro-dots (1.0dp..1.58dp) with zero fog blur
 * - Real-time speech-reactive IN/OUT radial breathing + outer particle flare when ARC speaks
 */
@Composable
fun ArcCoreSphere(
    voiceState: VoiceEngineState,
    isOnline: Boolean,
    audioEnergy: Float,
    modifier: Modifier = Modifier
) {
    val cloud = remember { ParticleCloudData(CORE_PARTICLE_COUNT) }

    val bucketBuffers = remember { Array(BUCKET_COUNT) { FloatArray(CORE_PARTICLE_COUNT * 2) } }
    val bucketCounts = remember { IntArray(BUCKET_COUNT) }
    val bucketPaints = remember {
        Array(BUCKET_COUNT) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        }
    }
    val speckBuffer = remember { FloatArray(COSMIC_SPECK_COUNT * 2) }
    val speckPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
    }

    val infinite = rememberInfiniteTransition(label = "arc_core")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing)
        ),
        label = "rotation"
    )
    val twinklePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4400, easing = LinearEasing)
        ),
        label = "twinkle"
    )

    // Fast-attack, smooth-release spring so the sphere visibly breathes IN and OUT on every spoken syllable
    val animatedEnergy by animateFloatAsState(
        targetValue = audioEnergy.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 260f),
        label = "energy"
    )

    val isError = voiceState == VoiceEngineState.ERROR
    val isSpeaking = voiceState == VoiceEngineState.SPEAKING

    // Bold high-contrast cobalt-sapphire palette (alert crimson only on error)
    val deepCobalt = if (isError) Color(0xFF991B1B) else Color(0xFF1D4ED8)
    val sapphireMid = if (isError) Color(0xFFDC2626) else Color(0xFF2563EB)
    val electricSapphire = if (isError) ArcAlertCrimson else Color(0xFF3B82F6)
    val skyBlueHighlight = if (isError) Color(0xFFFCA5A5) else Color(0xFF60A5FA)
    val iceBlueSpark = if (isError) Color(0xFFFEE2E2) else Color(0xFF93C5FD)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Keeps full flare within 0.50f canvas bounds while displaying ~58-60% screen-width diameter
        val baseRadius = size.minDimension * 0.40f

        val idleBreath = 1f + if (isOnline) 0.015f * sin(twinklePhase) else 0.008f * sin(twinklePhase)
        val dynamicScale = when {
            isSpeaking -> 0.98f + animatedEnergy * 0.19f
            voiceState == VoiceEngineState.LISTENING -> 1.00f + 0.022f * sin(twinklePhase * 2f) + animatedEnergy * 0.05f
            voiceState == VoiceEngineState.PROCESSING || voiceState == VoiceEngineState.CONNECTING ->
                1.01f + 0.025f * sin(twinklePhase * 3f)
            else -> idleBreath
        }
        val radius = baseRadius * dynamicScale
        // Keep sapphire stardust at full vivid brightness even on standby so outer particles never look washed out
        val intensity = 1f
        val speechFlare = if (isSpeaking) animatedEnergy else 0f

        // 1) Crisp ambient sapphire edge aura
        val outerHaloRadius = (radius * 1.14f).coerceAtMost(size.minDimension * 0.50f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    sapphireMid.copy(alpha = 0.22f * intensity),
                    deepCobalt.copy(alpha = 0.10f * intensity),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = outerHaloRadius
            ),
            radius = outerHaloRadius,
            center = Offset(cx, cy)
        )

        // 2) Luminous sapphire inner body depth so the mid-ring carries rich blue tone
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    electricSapphire.copy(alpha = 0.26f * intensity),
                    sapphireMid.copy(alpha = 0.18f * intensity),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius * 0.94f
            ),
            radius = radius * 0.94f,
            center = Offset(cx, cy)
        )

        // 3) Tight sparkling white-to-ice-blue central starburst (focused so it never washes out the blue sphere)
        val coreGlowRadius = radius * (0.19f + speechFlare * 0.07f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    iceBlueSpark.copy(alpha = 0.45f),
                    electricSapphire.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = coreGlowRadius
            ),
            radius = coreGlowRadius,
            center = Offset(cx, cy)
        )

        val corePinRadius = radius * (0.08f + speechFlare * 0.03f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    Color.White.copy(alpha = 0.55f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = corePinRadius
            ),
            radius = corePinRadius,
            center = Offset(cx, cy)
        )

        // 4) Configure bold high-contrast sapphire stardust & white core micro-dot Paints
        bucketPaints[0].strokeWidth = 1.00.dp.toPx()
        bucketPaints[0].color = deepCobalt.copy(alpha = 0.78f).toArgb()

        bucketPaints[1].strokeWidth = 1.10.dp.toPx()
        bucketPaints[1].color = sapphireMid.copy(alpha = 0.86f).toArgb()

        bucketPaints[2].strokeWidth = 1.20.dp.toPx()
        bucketPaints[2].color = electricSapphire.copy(alpha = 0.92f).toArgb()

        bucketPaints[3].strokeWidth = 1.28.dp.toPx()
        bucketPaints[3].color = skyBlueHighlight.copy(alpha = 0.96f).toArgb()

        bucketPaints[4].strokeWidth = 1.36.dp.toPx()
        bucketPaints[4].color = iceBlueSpark.copy(alpha = 0.98f).toArgb()

        bucketPaints[5].strokeWidth = 1.44.dp.toPx()
        bucketPaints[5].color = Color(0xFFDBEAFE).copy(alpha = 0.98f).toArgb()

        bucketPaints[6].strokeWidth = 1.50.dp.toPx()
        bucketPaints[6].color = Color.White.copy(alpha = 0.98f).toArgb()

        bucketPaints[7].strokeWidth = 1.58.dp.toPx()
        bucketPaints[7].color = Color.White.toArgb()

        speckPaint.strokeWidth = 0.85.dp.toPx()
        speckPaint.color = skyBlueHighlight.copy(alpha = 0.36f).toArgb()

        bucketCounts.fill(0)

        val cosRot = cos(rotation)
        val sinRot = sin(rotation)
        val tiltCos = 0.966f
        val tiltSin = 0.258f

        for (i in 0 until CORE_PARTICLE_COUNT) {
            val ux = cloud.unitX[i]
            val uy = cloud.unitY[i]
            val uz = cloud.unitZ[i]

            // 3D rotation around Y axis + gentle tilt
            val rx = ux * cosRot - uz * sinRot
            val rz0 = ux * sinRot + uz * cosRot
            val ry = uy * tiltCos - rz0 * tiltSin
            val rz = uy * tiltSin + rz0 * tiltCos

            val rNorm = cloud.radialNorm[i]
            val shimmerWave = sin(twinklePhase * cloud.twinkleHarmonic[i] + cloud.twinklePhase[i])

            // Idle subtle micro-shimmer + outward radial flare on outer-shell grains when ARC speaks
            val flareBoost = speechFlare * 0.11f * cloud.flareWeight[i]
            val radialScale = (rNorm + flareBoost) * (1f + shimmerWave * 0.010f)
            val projScale = radius * radialScale

            val px = cx + rx * projScale
            val py = cy + ry * projScale

            val depth = (rz + 1.15f) / 2.30f // 0..1 front vs back hemisphere
            val twinkleMod = 0.72f + 0.28f * shimmerWave
            val speechGlowBoost = speechFlare * 0.22f

            // Tight white starburst only in the inner core; rich electric-to-cobalt sapphire grains across body & rim
            val bucket = when {
                rNorm < 0.10f && (cloud.baseBrightness[i] * twinkleMod + speechGlowBoost) > 0.56f -> 7
                rNorm < 0.18f && (cloud.baseBrightness[i] * twinkleMod + speechGlowBoost) > 0.52f -> 6
                rNorm < 0.25f && twinkleMod > 0.76f -> 5
                else -> {
                    val shellEnergy = ((0.42f + 0.58f * depth) * cloud.baseBrightness[i] * twinkleMod + speechGlowBoost)
                        .coerceIn(0f, 1f)
                    when {
                        rNorm > 0.85f && shellEnergy > 0.76f -> 4
                        shellEnergy > 0.64f -> 3
                        shellEnergy > 0.46f -> 2
                        shellEnergy > 0.28f -> 1
                        else -> 0
                    }
                }
            }

            val idx = bucketCounts[bucket]
            val buf = bucketBuffers[bucket]
            buf[idx] = px
            buf[idx + 1] = py
            bucketCounts[bucket] = idx + 2
        }

        // Compute faint outer cosmic specks positions
        var speckFloats = 0
        for (s in 0 until COSMIC_SPECK_COUNT) {
            val sx = cx + cloud.speckX[s] * baseRadius
            val sy = cy + cloud.speckY[s] * baseRadius
            if (sx in 4f..(size.width - 4f) && sy in 4f..(size.height - 4f)) {
                speckBuffer[speckFloats] = sx
                speckBuffer[speckFloats + 1] = sy
                speckFloats += 2
            }
        }

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (speckFloats > 0) {
                native.drawPoints(speckBuffer, 0, speckFloats, speckPaint)
            }
            for (b in 0 until BUCKET_COUNT) {
                val floatCount = bucketCounts[b]
                if (floatCount > 0) {
                    native.drawPoints(bucketBuffers[b], 0, floatCount, bucketPaints[b])
                }
            }
        }
    }
}

/**
 * Amplitude-reactive WhatsApp-style vertical bar waveform integrated under the CORE sphere.
 * Remains visible without flickering or collapsing during silence gaps while a session is active.
 */
@Composable
fun ArcWaveform(
    bars: List<Float>,
    visible: Boolean,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(280)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bars.forEach { rawHeight ->
                val smoothHeight by animateFloatAsState(
                    targetValue = rawHeight.coerceIn(0.12f, 1f),
                    animationSpec = tween(durationMillis = 75),
                    label = "bar"
                )
                Surface(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height((6f + smoothHeight * 34f).dp),
                    shape = RoundedCornerShape(50),
                    color = if (isSpeaking) ArcCyan else ArcTeal.copy(alpha = 0.9f)
                ) {}
            }
        }
    }
}
