using System.Reflection;

namespace AudioBookRed.Api.Infrastructure;

public static class ApplicationVersion
{
    public static string Value { get; } = Resolve();

    private static string Resolve()
    {
        var assembly = typeof(ApplicationVersion).Assembly;
        var informationalVersion = assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion;

        if (!string.IsNullOrWhiteSpace(informationalVersion))
        {
            var metadataSeparator = informationalVersion.IndexOf('+');
            return metadataSeparator >= 0
                ? informationalVersion[..metadataSeparator]
                : informationalVersion;
        }

        var assemblyVersion = assembly.GetName().Version;
        if (assemblyVersion is null)
            return "unknown";

        return assemblyVersion.Build >= 0
            ? $"{assemblyVersion.Major}.{assemblyVersion.Minor}.{assemblyVersion.Build}"
            : $"{assemblyVersion.Major}.{assemblyVersion.Minor}";
    }
}
