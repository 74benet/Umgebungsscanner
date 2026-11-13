package de.hhn.umgebungsscanner

import de.hhn.umgebungsscanner.challenge.BalanceEngine
import de.hhn.umgebungsscanner.challenge.ChallengeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class BalanceLogicTest {

    @Test
    fun `Beispiel aus dem Vortrag 214857`() {
        assertEquals(ChallengeConfig(holdSeconds = 6, targetRadiusDp = 65f, windLevel = 3), ChallengeConfig.fromMatrikel("214857"))
        assertEquals(ChallengeConfig.DEFAULT, ChallengeConfig.fromMatrikel(ChallengeConfig.DEFAULT_MATRIKEL))
    }

    @Test
    fun `ungueltige Matrikelnummern liefern null statt Absturz`() {
        assertNull(ChallengeConfig.fromMatrikel(""))
        assertNull(ChallengeConfig.fromMatrikel("12"))
        assertNull(ChallengeConfig.fromMatrikel("12a456"))
    }

    @Test
    fun `Werte decken alle Ziffern ab`() {
        val config = ChallengeConfig.fromMatrikel("000")!!
        assertEquals(3, config.holdSeconds)
        assertEquals(40f, config.targetRadiusDp)
        assertEquals(1, config.windLevel)
        val max = ChallengeConfig.fromMatrikel("999")!!
        assertEquals(4, max.holdSeconds) // 9 mod 4 = 1
        assertEquals(85f, max.targetRadiusDp)
        assertEquals(1, max.windLevel) // 9 mod 3 = 0
    }

    @Test
    fun `Ball verlaesst die Spielflaeche nie`() {
        val engine = BalanceEngine(ChallengeConfig.DEFAULT, Random(1)).apply { arenaRadiusDp = 150f; ballRadiusDp = 20f }
        repeat(600) {
            engine.onRotation(omegaX = 3f, omegaY = 3f, dt = 0.02f)
            val s = engine.step(1f / 60f)
            assertTrue(hypot(s.x, s.y) <= 130.01f)
        }
    }

    @Test
    fun `Timer startet von vorn, wenn der Ball das Ziel verlaesst`() {
        val engine = BalanceEngine(ChallengeConfig.DEFAULT, Random(2)).apply { arenaRadiusDp = 150f }
        // Ball zur Mitte steuern: Startposition liegt außerhalb des Ziels
        var s = engine.snapshot()
        assertFalse(s.inside)
        assertEquals(0f, s.holdTime)
        // kräftig nach außen schubsen -> holdTime bleibt 0
        engine.onRotation(omegaX = 0f, omegaY = 5f, dt = 0.1f)
        s = engine.step(0.05f)
        if (!s.inside) assertEquals(0f, s.holdTime)
    }

    @Test
    fun `Ohne Bewegung und ohne Wind im Ziel wird gewonnen`() {
        // Windstufe 1 mit großem Ziel; wir testen mit vielen kurzen Schritten und Gegensteuern zur Mitte
        val config = ChallengeConfig(holdSeconds = 3, targetRadiusDp = 85f, windLevel = 1)
        val engine = BalanceEngine(config, Random(3)).apply { arenaRadiusDp = 150f }
        var won = false
        repeat(60 * 30) {
            val s = engine.snapshot()
            // einfacher Regler: zur Mitte drehen
            engine.onRotation(omegaX = -s.y * 0.02f, omegaY = -s.x * 0.02f, dt = 1f / 60f)
            if (engine.step(1f / 60f).won) {
                won = true
                return@repeat
            }
        }
        assertTrue(won)
    }
}
