using System.Net;
using System.Text.RegularExpressions;
using AngleSharp.Html.Parser;
using AudioBookRed.Api.Models;

namespace AudioBookRed.Api.Services;

public sealed partial class RuTrackerMagnetClient(
    IConfiguration configuration,
    RuTrackerTransport transport,
    RuTrackerTopicMetadataParser metadataParser)
{
    public bool Enabled => configuration.GetValue<bool?>("RuTrackerMagnet:Enabled") ?? true;
    public int IntervalMinutes => Math.Clamp(
        configuration.GetValue<int?>("RuTrackerMagnet:IntervalMinutes") ?? 10,
        1,
        1440);
    public int BatchSize => Math.Clamp(
        configuration.GetValue<int?>("RuTrackerMagnet:BatchSize") ?? 20,
        1,
        100);
    public int DelayMilliseconds => Math.Clamp(
        configuration.GetValue<int?>("RuTrackerMagnet:DelayMilliseconds") ?? 2000,
        500,
        30000);
    public int MaxAttempts => Math.Clamp(
        configuration.GetValue<int?>("RuTrackerMagnet:MaxAttempts") ?? 5,
        1,
        20);
    public int RetryMinutes => Math.Clamp(
        configuration.GetValue<int?>("RuTrackerMagnet:RetryMinutes") ?? 60,
        1,
        10080);

    public async Task<RuTrackerMagnetValue?> FetchAsync(string topicUrl, string title, CancellationToken ct)
    {
        if (!Uri.TryCreate(topicUrl, UriKind.Absolute, out var uri))
            throw new ArgumentException("Некорректный адрес темы RuTracker.", nameof(topicUrl));

        var html = await transport.GetAuthenticatedHtmlAsync(uri, uri, ct);
        var parser = new HtmlParser();
        var document = await parser.ParseDocumentAsync(html, ct);
        var metadata = metadataParser.Parse(document, title);

        // Точный способ актуального JacRed: href у a.magnet-link.
        var magnet = document.QuerySelector("a.magnet-link")?.GetAttribute("href");
        if (!string.IsNullOrWhiteSpace(magnet))
        {
            magnet = WebUtility.HtmlDecode(magnet).Trim();
            var hash = ExtractInfoHash(magnet);
            if (hash is not null)
                return new RuTrackerMagnetValue(magnet, hash) { Metadata = metadata };
        }

        // Запасной вариант: отдельный tor-hash в HTML.
        var decoded = WebUtility.HtmlDecode(html);
        var hashMatch = TorrentHash().Match(decoded);
        if (!hashMatch.Success)
            hashMatch = MagnetHash().Match(decoded);

        if (!hashMatch.Success)
            return null;

        var infoHash = hashMatch.Groups[1].Value.ToLowerInvariant();
        var generated = $"magnet:?xt=urn:btih:{infoHash}&dn={Uri.EscapeDataString(title)}";
        return new RuTrackerMagnetValue(generated, infoHash) { Metadata = metadata };
    }

    private static string? ExtractInfoHash(string magnet)
    {
        var match = MagnetHash().Match(magnet);
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : null;
    }

    [GeneratedRegex("(?:xt=urn:btih:)([a-fA-F0-9]{40})", RegexOptions.IgnoreCase)]
    private static partial Regex MagnetHash();

    [GeneratedRegex("id\\s*=\\s*[\\\"']tor-hash[\\\"'][^>]*>\\s*([a-fA-F0-9]{40})\\s*<", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex TorrentHash();
}
