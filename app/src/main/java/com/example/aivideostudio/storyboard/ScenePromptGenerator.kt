package com.example.aivideostudio.storyboard

class ScenePromptGenerator {

    private val introTemplates = listOf(
        "Ein einsamer Krieger steht in der Ferne, während der Nebel langsam aufsteigt.",
        "Eine weite, verlassene Landschaft liegt im ersten Licht des Morgens."
    )

    private val verseTemplates = listOf(
        "Eine Gruppe von Kriegern bewegt sich langsam durch das Gelände.",
        "Ein einzelner Charakter blickt in die Ferne, angespannt und wachsam."
    )

    private val preChorusTemplates = listOf(
        "Spannung baut sich auf, während sich dunkle Wolken zusammenziehen.",
        "Die Kamera nähert sich langsam einer zentralen Figur vor dramatischem Himmel."
    )

    private val chorusTemplates = listOf(
        "Eine Schildwall-Formation marschiert entschlossen durch das Schlachtfeld.",
        "Ein Krieger zieht sein Schwert, während Blitze über dem Himmel einschlagen.",
        "Intensive Kampfszene mit Funken, Feuer und dramatischer Beleuchtung."
    )

    private val breakdownTemplates = listOf(
        "Eine zerstörte Festung liegt im Schneesturm, Fackeln flackern an den Mauern.",
        "Ein gefallener Krieger kniet erschöpft im Schnee, Rauch steigt im Hintergrund auf."
    )

    private val soloTemplates = listOf(
        "Dynamische Kamerafahrt entlang einer brennenden Schlachtlinie.",
        "Nahaufnahme eines entschlossenen Blicks, umgeben von Funken und Feuer."
    )

    private val outroTemplates = listOf(
        "Der Krieger geht langsam in den Nebel, während sich der Sturm legt.",
        "Ein letzter Blick auf das Schlachtfeld im schwindenden Licht."
    )

    fun generateScenePrompt(
        sectionLabel: String,
        sceneIndexInSection: Int,
        globalVisualStyle: String,
        userVisualPrompt: String
    ): String {
        val templates = templatesForSection(sectionLabel)
        val template = templates[sceneIndexInSection % templates.size]
        return buildString {
            append(template)
            if (userVisualPrompt.isNotBlank()) {
                append(" ")
                append(userVisualPrompt)
            }
            if (globalVisualStyle.isNotBlank()) {
                append(", ")
                append(globalVisualStyle)
            }
        }
    }

    private fun templatesForSection(sectionLabel: String): List<String> {
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
