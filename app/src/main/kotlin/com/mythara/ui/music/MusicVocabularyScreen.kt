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

        if (sorted.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = sorted, key = { it.key }) { entry ->
                    VocabRow(token = entry.key, motif = entry.value) {
                        vm.viewModelScope.launch { vm.playMotif(entry.key, entry.value) }
                    }
                }
            }
        }
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
