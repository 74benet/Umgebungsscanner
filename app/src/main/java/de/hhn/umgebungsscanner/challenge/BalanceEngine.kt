package de.hhn.umgebungsscanner.challenge

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Momentaufnahme des Spiels. Positionen in dp relativ zur Mitte der Spielfläche. */
data class BalanceSnapshot(
    val x: Float = 0f,
    val y: Float = 0f,
    val inside: Boolean = false,
    val holdTime: Float = 0f,
    val elapsed: Float = 0f,
    val tiltXDeg: Float = 0f,
    val tiltYDeg: Float = 0f,
    val won: Boolean = false,
)

/**
 * Physik der Balance-Challenge, ohne Android-Abhängigkeiten.
 *
 * Kette: Gyroskop (ω in rad/s) -> Geschwindigkeit (v += ω · k) -> Wind (v += Zufall)
 *        -> Position (p += v) -> Im Kreis? -> Timer läuft.
 *
 * Das Gyroskop misst Drehgeschwindigkeit, nicht Neigung. Hält man das Gerät still,
 * kommt kein neuer Schubs dazu, nur die Reibung bremst den Punkt langsam ab.
 */
class BalanceEngine(
    private val config: ChallengeConfig,
    private val random: Random = Random.Default,
    /** k: Empfindlichkeit in dp/s Geschwindigkeit pro rad Drehung. */
    private val sensitivity: Float = 420f,
    /** Reibung: Anteil der Geschwindigkeit, der pro Sekunde verloren geht (exponentiell). */
    private val friction: Float = 1.1f,
) {
    private val wind = WindSettings.forLevel(config.windLevel)

    /** Radius der Spielfläche in dp. Wird von der UI gesetzt, sobald die Größe bekannt ist. */
    var arenaRadiusDp: Float = 160f
    var ballRadiusDp: Float = 22f

    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var holdTime = 0f
    private var elapsed = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var won = false
    private var nextWindIn = nextWindDelay()

    init {
        reset()
    }

    /** Startet eine neue Runde. Der Punkt beginnt außerhalb des Ziels, damit man ihn hinsteuern muss. */
    fun reset() {
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val startDistance = (config.targetRadiusDp + arenaRadiusDp) / 2f
        x = cos(angle) * startDistance
        y = sin(angle) * startDistance
        vx = 0f
        vy = 0f
        holdTime = 0f
        elapsed = 0f
        tiltX = 0f
        tiltY = 0f
        won = false
        nextWindIn = nextWindDelay()
    }

    /**
     * Neue Gyroskop-Messung.
     * Kippen nach links/rechts = Drehung um die y-Achse -> Bewegung in x-Richtung.
     * Kippen nach vorne/hinten = Drehung um die x-Achse -> Bewegung in y-Richtung (Bildschirm-y zeigt nach unten).
     *
     * @param dt Zeit seit der letzten Messung in Sekunden (aus SensorEvent.timestamp).
     */
    fun onRotation(omegaX: Float, omegaY: Float, dt: Float) {
        if (won) return
        val step = dt.coerceIn(0f, MAX_SENSOR_DT)
        vx += omegaY * sensitivity * step
        vy += omegaX * sensitivity * step
        tiltX += Math.toDegrees((omegaY * step).toDouble()).toFloat()
        tiltY += Math.toDegrees((omegaX * step).toDouble()).toFloat()
    }

    /** Ein Simulationsschritt pro Frame. */
    fun step(dtSeconds: Float): BalanceSnapshot {
        if (won) return snapshot()
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_DT)
        elapsed += dt

        // Wind: in zufälligen Abständen ein kleiner Stoß in zufällige Richtung
        nextWindIn -= dt
        if (nextWindIn <= 0f) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val strength = wind.impulseDpPerSecond * (0.6f + random.nextFloat() * 0.8f)
            vx += cos(angle) * strength
            vy += sin(angle) * strength
            nextWindIn = nextWindDelay()
        }

        // Reibung, damit der Punkt nicht ewig weiterrollt
        val damping = exp(-friction * dt)
        vx *= damping
        vy *= damping

        // Position
        x += vx * dt
        y += vy * dt

        // Am Rand der Spielfläche hängen bleiben: zurücksetzen und die Bewegung nach außen stoppen
        val maxDistance = (arenaRadiusDp - ballRadiusDp).coerceAtLeast(0f)
        val distance = hypot(x, y)
        if (distance > maxDistance && distance > 0f) {
            val nx = x / distance
            val ny = y / distance
            x = nx * maxDistance
            y = ny * maxDistance
            val outward = vx * nx + vy * ny
            if (outward > 0f) {
                vx -= outward * nx
                vy -= outward * ny
            }
        }

        // Im Kreis? Dann läuft der Timer, sonst beginnt er von vorn
        val inside = isInside()
        holdTime = if (inside) holdTime + dt else 0f
        if (holdTime >= config.holdSeconds) {
            holdTime = config.holdSeconds.toFloat()
            won = true
        }
        return snapshot()
    }

    fun snapshot() = BalanceSnapshot(
        x = x,
        y = y,
        inside = isInside(),
        holdTime = holdTime,
        elapsed = elapsed,
        tiltXDeg = tiltX,
        tiltYDeg = tiltY,
        won = won,
    )

    /** Der Punkt zählt als "im Ziel", wenn sein Mittelpunkt im Zielkreis liegt. */
    private fun isInside() = hypot(x, y) < config.targetRadiusDp

    private fun nextWindDelay(): Float =
        wind.minIntervalSeconds + random.nextFloat() * (wind.maxIntervalSeconds - wind.minIntervalSeconds)

    private companion object {
        const val MAX_SENSOR_DT = 0.1f
        const val MAX_FRAME_DT = 0.05f
    }
}
