# Fantasy Worlds parser fixtures

`book.html`, `catalog.html`, `series.html`: book, catalog and series HTML attached
by the user. Kept the actual Book microdata, bibliography, duplicated cover links
and pagination; removed surrounding scripts, forms, analytics and user comments.

`search.html`, `author.html`: captured from public Fantasy Worlds search for
«Лабиринт отражений» and `/author/id168/` during implementation. Kept the real
book microdata / bibliography links. The author capture contains 71 book links.

No full book text or downloaded FB2 archive is included in these fixtures.

`book-31866.html`: metadata excerpt from the user-attached MHT page
«Екатерина Каблукова — Декан моего сердца» (`/lib/id31866/`). Keeps the actual
author, title, cover, cycle, year and download link; excludes scripts, comments,
saved image data and the description. The attachment is a book card, not the author bibliography.
