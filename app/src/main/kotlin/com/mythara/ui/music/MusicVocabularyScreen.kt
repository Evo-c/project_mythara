package com.mythara.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.music.MusicColors
import com.mythara.music.MusicToneEngine
import com.mythara.music.MusicVocabulary
import com.mythara.music.Motif
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dedicated view of the user's evolving Music-Mode vocabulary —
 * every word the agent has ever encoded as a motif, with its tone
 * pattern, colour, and learning stats. Lets the user study word-by-
 * word at their own pace instead of having to wait for the next
 * agent reply.
 *
 * Each row:
 *   - the word in its motif's colour (the same colour the chat
 *     bubble paints it),
 *   - one filled dot per note in the motif (each dot in its
 *     individual note's colour, so the user sees the building
 *     blocks of the blended word colour),
 *   - the note frequencies (Hz),
 *   - hits / misses / generation badge,
 *   - tap the row → engine plays that motif (with the OM drone
 *     underneath).
 *
 * Sorted by hit-count descending — the words the user is making
 * most progress on float to the top, followed by recently-minted
 * unfamiliar ones. Reads from [MusicVocabulary.vocab] live, so a
 * fresh agent reply that mints new words shows up immediately.
 */
@HiltViewModel
class MusicVocabularyViewModel @Inject constructor(
    val vocabulary: MusicVocabulary,
    private val toneEngine: MusicToneEngine,
) : ViewModel() {

    fun playMotif(token: String, motif: Motif) {
        if (motif.notes.isEmpty()) return
        // Use the token as the source key so any open chat bubble
        // that happens to contain this exact word would also see
        // the highlight glow — though typically the user is on
        // this screen alone and won't notice.
        toneEngine.play(listOf(motif), sourceKey = token)
    }

    /** Preview a single OM-harmonic pitch in isolation. Used by the
     *  glossary's grammar-particle + suffix rows so the user can
     *  tap a category and immediately hear the marker tone. */
    fun playPitch(hz: Float) {
        toneEngine.play(listOf(Motif(notes = listOf(hz))), sourceKey = "")
    }
}

@Composable
fun MusicVocabularyScreen(
    onBack: () -> Unit,
    vm: MusicVocabularyViewModel = hiltViewModel(),
) {
    val vocab by vm.vocabulary.vocab.collectAsState()
    val insets = WindowInsets.systemBars.asPaddingValues()

    val sorted = vocab.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Motif>> { it.value.hits }
                .thenByDescending { it.value.misses + it.value.hits }
                .thenBy { it.key },
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(insets),
    ) {
        // Phase D — MytharaScaffold provides header (← back / ◇
        // music vocabulary). Body keeps the word-count summary
        // inline as a right-aligned action row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "${vocab.size} ${if (vocab.size == 1) "word" else "words"}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Explainer.
        Text(
            text = "Tap a word to hear its motif. Each colour = one OM-harmonic note. " +
                "Words are arranged by how often you've heard them. The agent decides " +
                "when (and which) words evolve to a fresh tone — your dictionary is yours.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ─── Glossary header — teach the language at a glance.
            //     Three small reference cards: pitch ↔ colour,
            //     vowel ↔ pitch, grammar particles + suffix system.
            //     User can collapse mentally once they've learnt the
            //     pattern; renders cheap on every screen visit.
            item("glossary") { GlossaryCard() }
            item("particles") { ParticleCard(playPitch = { vm.playPitch(it) }) }
            item("suffixes") { SuffixCard(playPitch = { vm.playPitch(it) }) }

            if (sorted.isEmpty()) {
                item("empty") { EmptyState() }
            } else {
                item("lex-header") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${Glyph.DiamondOutline} your lexicon — content words mythara has minted",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(items = sorted, key = { it.key }) { entry ->
                    VocabRow(token = entry.key, motif = entry.value) {
                        vm.viewModelScope.launch { vm.playMotif(entry.key, entry.value) }
                    }
                }
            }
        }
    }
}

/** Pitch → colour reference card — the foundation of the language.
 *  Each OM harmonic gets one row with its swatch + pitch + emotive
 *  label so the user can decode "this colour = this sound" at a
 *  glance, without having to play every word in the lexicon. */
@Composable
private fun GlossaryCard() {
    val rows = listOf(
        Triple(MusicVocabulary.OM_HARMONICS[0], "OM fundamental ॐ — deep, grounding", "/o/"),
        Triple(MusicVocabulary.OM_HARMONICS[1], "octave above OM — warm body", "—"),
        Triple(MusicVocabulary.OM_HARMONICS[2], "perfect 5th — resonant centre", "/a/"),
        Triple(MusicVocabulary.OM_HARMONICS[3], "two octaves — warm forward", "/u/"),
        Triple(MusicVocabulary.OM_HARMONICS[4], "major 3rd above — bright open", "/e/"),
        Triple(MusicVocabulary.OM_HARMONICS[5], "5th above — clear edge", "—"),
        Triple(MusicVocabulary.OM_HARMONICS[6], "minor 7th — edge-bright", "/y/"),
        Triple(MusicVocabulary.OM_HARMONICS[7], "three octaves — piercing top", "/i/"),
        Triple(MusicVocabulary.OM_HARMONICS[8], "major 2nd above — sparkle", "—"),
    )
    GlossarySection(
        title = "${Glyph.DiamondFilled} pitch ↔ colour ↔ vowel — the language alphabet",
    ) {
        for ((hz, label, vowel) in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteDot(Color(MusicColors.colorForNote(hz)))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${hz.toInt()} Hz",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(80.dp),
                )
                Text(
                    text = vowel,
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = label,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Grammatical particle card — every function word category and
 *  the pitch its single-note motif rides on. Tap a category to
 *  hear its particle tone in isolation. */
@Composable
private fun ParticleCard(playPitch: (Float) -> Unit) {
    GlossarySection(
        title = "${Glyph.DiamondFilled} grammar particles — function words ride one constant tone",
    ) {
        Text(
            text = "every function word category gets one pitch. learn the category, hear the syntax.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for (p in com.mythara.music.MusicGrammar.Particle.entries) {
            val examples = com.mythara.music.MusicGrammar.FUNCTION_WORDS
                .entries
                .asSequence()
                .filter { it.value == p }
                .map { it.key }
                .take(4)
                .toList()
                .joinToString(" · ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { playPitch(p.hz) }
                    .padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteDot(Color(MusicColors.colorForNote(p.hz)))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${p.label}  ·  ${p.hz.toInt()} Hz",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (examples.isNotEmpty()) {
                        Text(
                            text = examples,
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = "▶",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Suffix card — the morphology markers that turn `walk` into
 *  `walking` / `walked` / `walks` audibly. Tap any row to preview
 *  the marker tone in isolation. */
@Composable
private fun SuffixCard(playPitch: (Float) -> Unit) {
    val labels = mapOf(
        "ing" to "continuous · ongoing",
        "ed" to "past · settled",
        "s" to "plural · sharp",
        "es" to "plural · sharp",
        "ly" to "adverbial · edge",
        "tion" to "nominalisation · warm",
        "sion" to "nominalisation · warm",
        "er" to "comparative / agent",
        "est" to "superlative · topmost",
        "ment" to "abstract noun",
        "ness" to "quality noun",
        "less" to "privative",
        "ful" to "augmentative",
    )
    GlossarySection(
        title = "${Glyph.DiamondFilled} morphology — endings append one extra tone",
    ) {
        Text(
            text = "`walking` = motif(walk) + ing-tone · `dogs` = motif(dog) + plural-tone. " +
                "tense + plurality become audible without inflating the dictionary.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for ((suffix, pitch) in com.mythara.music.MusicGrammar.SUFFIXES) {
            val gloss = labels[suffix] ?: ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { playPitch(pitch) }
                    .padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NoteDot(Color(MusicColors.colorForNote(pitch)))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "-$suffix",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(64.dp),
                )
                Text(
                    text = gloss,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "▶",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GlossarySection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun VocabRow(token: String, motif: Motif, onPlay: () -> Unit) {
    val wordColor = Color(MusicColors.colorForMotif(motif))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .clickable { onPlay() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = token,
                color = wordColor,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            // Per-note colour dots — show the word's tone pattern as
            // building blocks. Same colour space as the chat bubble.
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (n in motif.notes) {
                    NoteDot(Color(MusicColors.colorForNote(n)))
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = motif.notes.joinToString(" · ") { "${it.toInt()}Hz" },
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${Glyph.Check} ${motif.hits}  ${Glyph.Cross} ${motif.misses}",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
            if (motif.generation > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "gen ${motif.generation}",
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NoteDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "♪ no words yet",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Turn on Music Mode in the chat composer and send a message. Every word the agent uses will mint a fresh motif and show up here for word-by-word study.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
