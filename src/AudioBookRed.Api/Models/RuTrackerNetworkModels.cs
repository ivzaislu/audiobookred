namespace AudioBookRed.Api.Models;

public sealed record RuTrackerNetworkStatus(
    bool CredentialsConfigured,
    bool ProxyConfigured,
    bool AliasConfigured,
    string? AliasUrl,
    string Route,
    bool Authenticated,
    DateTimeOffset? AuthenticatedUntil,
    int? LastHttpStatus,
    string? LastError);

public sealed record RuTrackerNetworkProbeResult(
    bool ProxyConfigured,
    bool AliasConfigured,
    string? AliasUrl,
    string Route,
    int? AnonymousHttpStatus,
    string? AnonymousPageTitle,
    bool AnonymousChallenge,
    bool PublicRouteOk,
    bool LoginAttempted,
    bool LoginOk,
    int? LoginHttpStatus,
    DateTimeOffset? AuthenticatedUntil,
    string? Error);
