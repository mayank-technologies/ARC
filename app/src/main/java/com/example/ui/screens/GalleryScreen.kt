package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.GalleryPhotoEntity
import com.example.ui.ArcViewModel
import com.example.ui.components.ArcBackgroundGrid
import com.example.ui.theme.ArcAlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcGlassBorder
import com.example.ui.theme.ArcSurface
import com.example.ui.theme.ArcTextMuted
import com.example.ui.theme.ArcTextPrimary
import com.example.ui.theme.ArcTextSecondary
import com.example.ui.theme.ArcVoid
import java.time.LocalDate

/**
 * In-app date-filtered photo grid (Play-Policy compliant via Photo Picker + ARC captures).
 * Exists inside ARC's UI because external Android gallery apps cannot be deep-linked to a specific date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: ArcViewModel,
    initialDateIso: String?,
    onBack: () -> Unit
) {
    val photos by viewModel.galleryPhotos.collectAsStateWithLifecycle()
    var selectedDate by remember(initialDateIso) { mutableStateOf(initialDateIso ?: "ALL") }
    var inspectedPhoto by remember { mutableStateOf<GalleryPhotoEntity?>(null) }

    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 12)
    ) { uris ->
        uris.forEach { uri ->
            viewModel.saveCapturedPhotoUri(uri, "Picked Photo")
        }
    }

    val dates = remember(photos) {
        val unique = photos.map { it.dateIso }.distinct().sortedDescending()
        listOf("ALL", LocalDate.now().toString()) + unique.filterNot { it == LocalDate.now().toString() }
    }

    val filtered = remember(photos, selectedDate) {
        if (selectedDate == "ALL") photos else photos.filter { it.dateIso == selectedDate }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArcBackgroundGrid()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("ARC Date Gallery", color = ArcCyan, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ArcCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.statusBarsPadding()
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        multiPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    containerColor = ArcCyan,
                    contentColor = ArcVoid,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import Photos by Picker")
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(dates.distinct()) { date ->
                        FilterChip(
                            selected = selectedDate == date,
                            onClick = { selectedDate = date },
                            label = { Text(if (date == "ALL") "All Dates" else date) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan,
                                selectedLabelColor = ArcVoid
                            )
                        )
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No photos indexed for $selectedDate",
                                style = MaterialTheme.typography.titleMedium,
                                color = ArcTextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Tap + to pick photos securely without broad storage permissions, or capture via ARC Vision.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ArcTextMuted
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, ArcGlassBorder),
                                colors = CardDefaults.cardColors(containerColor = ArcSurface),
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable { inspectedPhoto = item }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = Uri.parse(item.uriString),
                                        contentDescription = item.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedPhoto?.let { photo ->
        AlertDialog(
            onDismissRequest = { inspectedPhoto = null },
            containerColor = ArcSurface,
            title = { Text("${photo.dateIso} • ${photo.label}", color = ArcCyan) },
            text = {
                AsyncImage(
                    model = Uri.parse(photo.uriString),
                    contentDescription = photo.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.analyzePhotoUri(Uri.parse(photo.uriString), "Describe this photo for me.")
                        inspectedPhoto = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ArcVoid)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Ask ARC Vision")
                }
            },
            dismissButton = {
                Row {
                    IconButton(onClick = {
                        viewModel.deleteGalleryPhoto(photo.id)
                        inspectedPhoto = null
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove from ARC gallery", tint = ArcAlertCrimson)
                    }
                    TextButton(onClick = { inspectedPhoto = null }) {
                        Text("Close", color = ArcTextSecondary)
                    }
                }
            }
        )
    }
}
