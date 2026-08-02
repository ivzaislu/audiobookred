using System.Threading.RateLimiting;
using AudioBookRed.Api.Compatibility;
using Microsoft.AspNetCore.RateLimiting;

namespace AudioBookRed.Api.Infrastructure;

public static class RequestRateLimitPolicy
{
    public const int ReadPermitLimit = 180;
    public const int AdministrativePermitLimit = 30;
    public const int TorznabPermitLimit = 300;

    public static string GetCategory(HttpContext context)
    {
        if (context.Request.Path.StartsWithSegments("/health"))
            return "health";
        if (TorznabEndpoints.IsCompatibilityPath(context.Request.Path))
            return "torznab";
        if (HttpMethods.IsPost(context.Request.Method) ||
            HttpMethods.IsPut(context.Request.Method) ||
            HttpMethods.IsPatch(context.Request.Method) ||
            HttpMethods.IsDelete(context.Request.Method))
            return "administrative";
        return "read";
    }

    public static RateLimitPartition<string> CreatePartition(HttpContext context)
    {
        var category = GetCategory(context);
        if (category == "health")
            return RateLimitPartition.GetNoLimiter("health");

        var client = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        var permitLimit = category switch
        {
            "torznab" => TorznabPermitLimit,
            "administrative" => AdministrativePermitLimit,
            _ => ReadPermitLimit
        };

        return RateLimitPartition.GetFixedWindowLimiter(
            $"{category}:{client}",
            _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = permitLimit,
                Window = TimeSpan.FromMinutes(1),
                QueueLimit = 0,
                AutoReplenishment = true
            });
    }

    public static async ValueTask WriteRejectedResponseAsync(
        OnRejectedContext context,
        CancellationToken cancellationToken)
    {
        var response = context.HttpContext.Response;
        response.Headers["Retry-After"] = "60";

        if (TorznabEndpoints.IsCompatibilityPath(context.HttpContext.Request.Path))
        {
            response.ContentType = "application/xml; charset=utf-8";
            await response.WriteAsync(
                TorznabXmlFormatter.CreateError("429", "Too many requests"),
                cancellationToken);
            return;
        }

        await response.WriteAsJsonAsync(
            new { error = "rate_limited", retryAfterSeconds = 60 },
            cancellationToken: cancellationToken);
    }
}
