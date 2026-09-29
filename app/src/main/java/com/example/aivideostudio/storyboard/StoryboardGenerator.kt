package com.example.aivideostudio.storyboard

import kotlin.math.abs

class ScenePromptGenerator {

    private val introTemplates = listOf(
        "Die Band steht geschlossen in einer rauen nordischen Landschaft, während Nebel und Sturm langsam aufziehen.",
        "Die sechs Musiker stehen gemeinsam vor einer dunklen nordischen Berglandschaft im ersten Licht des Tages."
    )

    private val verseTemplates = listOf(
        "Die Band bewegt sich gemeinsam durch eine raue nordische Landschaft, jedes Mitglied klar erkennbar und mit seinem individuellen Erscheinungsbild.",
        "Mehrere Mitglieder der Band stehen gemeinsam im Zentrum der Szene und blicken entschlossen in die Ferne."
    )

    private val preChorusTemplates = listOf(
        "Die Spannung steigt, während die sechs Bandmitglieder gemeinsam vor dunklen Wolken stehen und der Sturm näher kommt.",
        "Die Kamera nähert sich langsam der Band, deren Mitglieder gemeinsam vor einem dramatischen nordischen Himmel stehen."
    )

    private val chorusTemplates = listOf(
        "Alle sechs Mitglieder der Band stehen gemeinsam in einer mächtigen Schildwall-Formation vor Sturm, Feuer und Rauch.",
        "Die komplette Band steht gemeinsam vor einer dramatischen nordischen Landschaft, während Blitze und Feuer die Szene beleuchten.",
        "Intensive Performance der gesamten Band mit allen sechs Mitgliedern, Feuer, Funken, Rauch und dramatischer Beleuchtung."
    )

    private val breakdownTemplates = listOf(
        "Die komplette Band steht in einer dunklen, vom Sturm gezeichneten Landschaft, Fackeln und Rauch bewegen sich im Wind.",
        "Die sechs Bandmitglieder stehen gemeinsam im Schnee vor einer rauen nordischen Festung, während dichter Rauch im Hintergrund aufsteigt."
    )

    private val soloTemplates = listOf(
        "Dynamische Performance der Band vor einer brennenden nordischen Landschaft mit Funken, Rauch und dramatischem Gegenlicht.",
        "Nahaufnahme eines Bandmitglieds während der Performance, während die übrigen Mitglieder im Hintergrund klar erkennbar bleiben."
    )

    private val outroTemplates = listOf(
        "Die komplette Band steht gemeinsam im aufziehenden Nebel, während der Sturm langsam nachlässt.",
        "Ein letzter weiter Blick auf die sechs Bandmitglieder in der dunklen nordischen Landschaft im schwindenden Licht."
    )

    fun generateScenePrompt(
        sectionLabel: String,
        sceneIndexInSection: Int,
        globalVisualStyle: String,
        userVisualPrompt: String,
        songSeed: Int,
        sceneIntensity: Float
    ): String {
        val templates = templatesForSection(sectionLabel)

        val template =
            templates[
                selectTemplateIndex(
                    templates.size,
                    sceneIndexInSection,
                    songSeed,
                    sceneIntensity
                )
            ]

        return buildString {
            append(template)

            append(" ")

            append(
                "Die sechs festen Bandmitglieder müssen über alle Szenen hinweg " +
                    "konsistent dargestellt werden. Gesichter, Haarfarben, Frisuren, " +
                    "Bärte, Körperbau, Tätowierungen, Gesichtsbemalung, Kleidung und " +
                    "Instrumente müssen sich an der bereitgestellten Bandreferenz orientieren. "
            )

            append(
                "Keine neuen Personen, keine austauschbaren Charaktere, keine zufälligen " +
                    "Gesichter und keine Veränderung der Identität der Bandmitglieder. "
            )

            append(
                "Fotorealistisch, erwachsene Musiker, authentische dunkle Nordic-" +
                    "Viking-Metal-Ästhetik, natürliche Hautdetails, realistische Haare " +
                    "und Bärte, glaubwürdige Beleuchtung, keine Comic-Optik, keine " +
                    "übertriebene High-Fantasy-Darstellung. "
            )

            if (userVisualPrompt.isNotBlank()) {
                append(userVisualPrompt.trim())
                append(" ")
            }

            if (globalVisualStyle.isNotBlank()) {
                append(globalVisualStyle.trim())
                append(" ")
            }

            append(
                "Szene ${sceneIndexInSection + 1}, Intensität " +
                    "${sceneIntensity.coerceIn(0f, 1f)}."
            )
        }
    }

    private fun selectTemplateIndex(
        templateCount: Int,
        sceneIndexInSection: Int,
        songSeed: Int,
        sceneIntensity: Float
    ): Int {
        if (templateCount <= 1) {
            return 0
        }

        val intensityComponent =
            (sceneIntensity * 1000f).toInt()

        val combinedValue =
            songSeed +
                (sceneIndexInSection * 97) +
                intensityComponent

        return abs(combinedValue) % templateCount
    }

    private fun templatesForSection(
        sectionLabel: String
    ): List<String> {
        return when (sectionLabel) {
            "Intro" -> introTemplates
            "Chorus" -> chorusTemplates
            "Pre-Chorus" -> preChorusTemplates
            "Breakdown" -> breakdownTemplates
            "Solo" -> soloTemplates
            "Outro" -> outroTemplates
            else -> verseTemplates
        }
    }
}
