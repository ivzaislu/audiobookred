package com.example.ui

import androidx.compose.runtime.Composable
import com.example.data.model.BookDetailDto
import com.example.data.model.PersonDto

@Composable
internal fun PlayerBookPeople(
    book: BookDetailDto,
    tiny: Boolean,
    interactive: Boolean,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
) {
    PlayerPeopleLine(
        people = book.authors,
        emptyText = "Автор не указан",
        prefix = null,
        tiny = tiny,
        interactive = interactive,
        onPerson = onAuthor,
    )
    if (book.narrators.isNotEmpty()) {
        PlayerPeopleLine(
            people = book.narrators,
            emptyText = "",
            prefix = "Читает: ",
            tiny = tiny,
            interactive = interactive,
            onPerson = onNarrator,
        )
    }
}
