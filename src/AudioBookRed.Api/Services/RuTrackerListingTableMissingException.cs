namespace AudioBookRed.Api.Services;

public sealed class RuTrackerListingTableMissingException : IOException
{
    public RuTrackerListingTableMissingException(
        int categoryId,
        int page,
        string pageTitle)
        : base(
            $"RuTracker вернул HTML без таблицы каталога для категории {categoryId}, страницы {page}. " +
            $"Заголовок: {pageTitle}.")
    {
        CategoryId = categoryId;
        Page = page;
        PageTitle = pageTitle;
    }

    public int CategoryId { get; }

    public int Page { get; }

    public string PageTitle { get; }
}
