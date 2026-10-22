package com.example.data.textsource

internal data class RemoteTextSourceInfo(val code: String, val name: String)

/** Only registered providers handle links and network requests; UI has no site-specific routes. */
internal class RemoteTextSourceRegistry(providers: List<RemoteTextBookProvider>) {
    private val providers = providers.toList()
    init {
        require(this.providers.isNotEmpty())
        require(this.providers.map { it.code }.distinct().size == this.providers.size)
        require(this.providers.all { it.code.matches(Regex("[a-z][a-z0-9_]*")) })
    }
    val default: RemoteTextBookProvider get() = providers.first()
    val sources: List<RemoteTextSourceInfo> get() = providers.map { RemoteTextSourceInfo(it.code, it.name) }
    fun provider(code: String): RemoteTextBookProvider = providers.firstOrNull { it.code == code }
        ?: error("Источник книги недоступен.")
    fun forBookUrl(url: String): RemoteTextBookProvider? = providers.firstOrNull { it.bookId(url) != null }
}
