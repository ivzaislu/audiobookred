using System.Diagnostics.CodeAnalysis;
using System.Text.RegularExpressions;

namespace AudioBookRed.Api.Sources;

public sealed partial class SourceRegistry
{
    private readonly IReadOnlyDictionary<string, ISourceModule> _modules;
    private readonly IReadOnlyDictionary<string, ISourceCrawler> _crawlers;
    private readonly IReadOnlyList<SourceModuleDescriptor> _descriptors;
    private readonly IReadOnlyList<string> _availableSources;

    public SourceRegistry(
        IEnumerable<ISourceModule> modules,
        IEnumerable<ISourceCrawler> crawlers)
    {
        _modules = BuildIndex(modules, module => module.SourceKey, "module");
        _crawlers = BuildIndex(crawlers, crawler => crawler.SourceKey, "crawler");

        var missingCrawlers = _modules.Keys
            .Except(_crawlers.Keys, StringComparer.OrdinalIgnoreCase)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        var missingModules = _crawlers.Keys
            .Except(_modules.Keys, StringComparer.OrdinalIgnoreCase)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();

        if (missingCrawlers.Length > 0 || missingModules.Length > 0)
        {
            throw new InvalidOperationException(
                "Source registration is incomplete. " +
                $"Missing crawlers: {JoinKeys(missingCrawlers)}; " +
                $"missing modules: {JoinKeys(missingModules)}.");
        }

        _descriptors = _modules.Values
            .OrderBy(module => module.SourceKey, StringComparer.Ordinal)
            .Select(module => new SourceModuleDescriptor(
                module.SourceKey,
                module.DisplayName,
                module.EnabledByDefault,
                module.Categories,
                module.RuntimeDefaults,
                module.Capabilities))
            .ToArray();

        _availableSources = _descriptors
            .Select(descriptor => descriptor.Source)
            .ToArray();
    }

    public IReadOnlyList<SourceModuleDescriptor> Describe() => _descriptors;

    public IReadOnlyList<string> AvailableSources => _availableSources;

    public bool TryGetModule(
        string? source,
        [NotNullWhen(true)] out ISourceModule? module)
    {
        module = null;
        return TryNormalize(source, out var key)
            && _modules.TryGetValue(key, out module);
    }

    public bool TryGetCrawler(
        string? source,
        [NotNullWhen(true)] out ISourceCrawler? crawler)
    {
        crawler = null;
        return TryNormalize(source, out var key)
            && _crawlers.TryGetValue(key, out crawler);
    }

    private static IReadOnlyDictionary<string, T> BuildIndex<T>(
        IEnumerable<T> items,
        Func<T, string> keySelector,
        string registrationKind)
    {
        var result = new Dictionary<string, T>(StringComparer.OrdinalIgnoreCase);

        foreach (var item in items)
        {
            var key = NormalizeOrThrow(keySelector(item), registrationKind);
            if (!result.TryAdd(key, item))
            {
                throw new InvalidOperationException(
                    $"Duplicate source {registrationKind} registration: '{key}'.");
            }
        }

        if (result.Count == 0)
        {
            throw new InvalidOperationException(
                $"No source {registrationKind} registrations were found.");
        }

        return result;
    }

    private static string NormalizeOrThrow(string? source, string registrationKind)
    {
        if (!TryNormalize(source, out var normalized))
        {
            throw new InvalidOperationException(
                $"Invalid source key in {registrationKind} registration: '{source}'.");
        }

        return normalized;
    }

    private static bool TryNormalize(
        string? source,
        [NotNullWhen(true)] out string? normalized)
    {
        normalized = source?.Trim().ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(normalized)
            || !SourceKeyPattern().IsMatch(normalized))
        {
            normalized = null;
            return false;
        }

        return true;
    }

    private static string JoinKeys(IReadOnlyCollection<string> keys) =>
        keys.Count == 0 ? "none" : string.Join(", ", keys);

    [GeneratedRegex("^[a-z0-9][a-z0-9_-]*$", RegexOptions.CultureInvariant)]
    private static partial Regex SourceKeyPattern();
}
