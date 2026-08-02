namespace AudioBookRed.Api.Models;

public sealed record SourceMetadataReparseRequest(
    IReadOnlyList<long>? TopicIds = null,
    bool Force = false);

public sealed record SourceMetadataReparseResult(
    string Source,
    string Mode,
    int ParserVersion,
    int Requested,
    int Matched,
    int Queued,
    int AlreadyCurrent,
    int Busy,
    int Missing,
    long StaleAfterEnqueue,
    IReadOnlyList<long> TopicIds);

public sealed record SourceMetadataStatus(
    string Source,
    int ParserVersion,
    long Total,
    long Current,
    long Stale,
    int Queued,
    int Running,
    DateTimeOffset? FirstParsedAt,
    DateTimeOffset? LastParsedAt,
    DateTimeOffset RefreshedAt);

public static class SourceMetadataReparsePolicy
{
    public const int MaxTopicIds = 100;
    public const int DefaultBatchLimit = 25;
    public const int MaxBatchLimit = 100;

    public static long[] NormalizeTopicIds(IEnumerable<long>? topicIds)
    {
        if (topicIds is null)
            throw new ArgumentException(
                "Нужно передать хотя бы один topic_id.",
                nameof(topicIds));

        var seen = new HashSet<long>();
        var normalized = new List<long>();

        foreach (var topicId in topicIds)
        {
            if (topicId <= 0)
            {
                throw new ArgumentException(
                    "topic_id должен быть положительным целым числом.",
                    nameof(topicIds));
            }

            if (!seen.Add(topicId))
                continue;

            normalized.Add(topicId);
            if (normalized.Count > MaxTopicIds)
            {
                throw new ArgumentException(
                    $"За один запрос разрешено не более {MaxTopicIds} topic_id.",
                    nameof(topicIds));
            }
        }

        if (normalized.Count == 0)
        {
            throw new ArgumentException(
                "Нужно передать хотя бы один topic_id.",
                nameof(topicIds));
        }

        return normalized.ToArray();
    }

    public static int NormalizeBatchLimit(int? requestedLimit)
    {
        var limit = requestedLimit ?? DefaultBatchLimit;
        if (limit is < 1 or > MaxBatchLimit)
        {
            throw new ArgumentOutOfRangeException(
                nameof(requestedLimit),
                $"limit должен быть 1..{MaxBatchLimit}.");
        }

        return limit;
    }
}
