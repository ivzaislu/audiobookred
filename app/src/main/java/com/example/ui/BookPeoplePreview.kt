package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.PersonDto
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText

internal fun shouldUsePeopleSheet(count: Int): Boolean = count > 2
internal fun peoplePreviewNames(people: List<PersonDto>): List<PersonDto> = people.take(2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookPeoplePreview(
    title: String,
    people: List<PersonDto>,
    emptyText: String = "",
    narrator: Boolean = false,
    onPerson: (PersonDto) -> Unit
) {
    var showAll by remember(people) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    if (people.isEmpty()) {
        if (emptyText.isNotBlank()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val preview = peoplePreviewNames(people)
    val many = shouldUsePeopleSheet(people.size)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(horizontal = AbredSpacing.Xs, vertical = AbredSpacing.Xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (narrator) Icons.Default.Headphones else Icons.Default.Person,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(
                    if (many) "$title · ${people.size}" else title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(AbredSpacing.Xxs))
            preview.forEach { person ->
                Text(
                    person.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPerson(person) }
                        .padding(vertical = AbredSpacing.Xxs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.abredAccentText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (many) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAll = true }
                        .padding(vertical = AbredSpacing.Xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Показать всех · ещё ${people.size - preview.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.abredAccentText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showAll) {
        ModalBottomSheet(
            onDismissRequest = { showAll = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = AbredSpacing.Md, end = AbredSpacing.Xs, bottom = AbredSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${people.size} ${if (narrator) "чтецов" else "авторов"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showAll = false }) {
                        Icon(Icons.Default.Close, "Закрыть")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)
                ) {
                    items(people, key = { it.id }) { person ->
                        ListItem(
                            headlineContent = {
                                Text(person.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Icon(
                                    if (narrator) Icons.Default.Headphones else Icons.Default.Person,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable {
                                showAll = false
                                onPerson(person)
                            }
                        )
                    }
                }
            }
        }
    }
}
