package com.example.aivideostudio.render

import kotlin.random.Random

data class Particle(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var size: Float,
    var alpha: Float,
    var life: Float
)

enum class ParticleType {
    SNOW,
    FIRE,
    SPARK,
    RAIN,
    SMOKE
}

class ParticleSystem(
    private val type: ParticleType,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    particleCount: Int
) {

    private val particles = MutableList(particleCount) { createParticle() }
    private val random = Random(System.nanoTime())

    private fun createParticle(): Particle {
        return when (type) {
            ParticleType.SNOW -> Particle(
                x = random.nextFloat() * canvasWidth,
                y = random.nextFloat() * canvasHeight,
                velocityX = (random.nextFloat() - 0.5f) * 10f,
                velocityY = 20f + random.nextFloat() * 30f,
                size = 2f + random.nextFloat() * 4f,
                alpha = 0.5f + random.nextFloat() * 0.5f,
                life = 1f
            )
            ParticleType.FIRE -> Particle(
                x = random.nextFloat() * canvasWidth,
                y = canvasHeight + random.nextFloat() * 50f,
                velocityX = (random.nextFloat() - 0.5f) * 15f,
                velocityY = -(60f + random.nextFloat() * 80f),
                size = 4f + random.nextFloat() * 8f,
                alpha = 0.8f,
                life = 1f
            )
            ParticleType.SPARK -> Particle(
                x = random.nextFloat() * canvasWidth,
                y = random.nextFloat() * canvasHeight,
                velocityX = (random.nextFloat() - 0.5f) * 120f,
                velocityY = (random.nextFloat() - 0.5f) * 120f,
                size = 1f + random.nextFloat() * 2f,
                alpha = 1f,
                life = 1f
            )
            ParticleType.RAIN -> Particle(
                x = random.nextFloat() * canvasWidth,
                y = random.nextFloat() * canvasHeight,
                velocityX = -20f,
                velocityY = 400f + random.nextFloat() * 200f,
                size = 1f,
                alpha = 0.6f,
                life = 1f
            )
            ParticleType.SMOKE -> Particle(
                x = random.nextFloat() * canvasWidth,
                y = canvasHeight + random.nextFloat() * 100f,
                velocityX = (random.nextFloat() - 0.5f) * 8f,
                velocityY = -(15f + random.nextFloat() * 20f),
                size = 20f + random.nextFloat() * 40f,
                alpha = 0.15f + random.nextFloat() * 0.1f,
                life = 1f
            )
        }
    }

    fun update(deltaTimeSeconds: Float, intensity: Float): List<Particle> {
        for (i in particles.indices) {
            val particle = particles[i]
            particle.x += particle.velocityX * deltaTimeSeconds * (0.5f + intensity)
            particle.y += particle.velocityY * deltaTimeSeconds * (0.5f + intensity)
            particle.life -= deltaTimeSeconds * 0.2f

            val outOfBounds = particle.y < -50f || particle.y > canvasHeight + 50f ||
                particle.x < -50f || particle.x > canvasWidth + 50f || particle.life <= 0f

            if (outOfBounds) {
                particles[i] = createParticle()
            }
        }
        return particles
    }
}
