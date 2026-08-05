using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed class RutorListingClient(
    RutorTransport transport,
    RutorHtmlParser parser,
    RutorSourceDefinition definition)
{
    public async Task<RutorListingPage> FetchPageAsync(
        int categoryId,
        int page,
        CancellationToken ct)
    {
        if (!definition.Categories.Contains(categoryId))
            throw new ArgumentOutOfRangeException(nameof(categoryId), "Неизвестная категория Rutor.");
        if (page < 1)
            throw new ArgumentOutOfRangeException(nameof(page), "Страница должна быть >= 1.");

        var rutorPage = page - 1;
        var response = await transport.GetHtmlAsync(
            $"browse/{rutorPage}/{categoryId}/0/0",
            ct);
        return await parser.ParseListingAsync(
            response.Html,
            response.Uri,
            categoryId,
            page,
            ct);
    }
}
