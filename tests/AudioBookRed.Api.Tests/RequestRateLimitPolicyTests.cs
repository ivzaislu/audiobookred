using AudioBookRed.Api.Infrastructure;
using Microsoft.AspNetCore.Http;

namespace AudioBookRed.Api.Tests;

public sealed class RequestRateLimitPolicyTests
{
    [Theory]
    [InlineData("GET", "/health/ready", "health")]
    [InlineData("GET", "/torznab/api", "torznab")]
    [InlineData("GET", "/api/v1/search", "read")]
    [InlineData("POST", "/api/v1/sources/rutracker/work", "administrative")]
    [InlineData("PUT", "/api/v1/sources/rutracker/settings", "administrative")]
    [InlineData("DELETE", "/api/v1/releases/1", "administrative")]
    public void GetCategory_classifies_request(string method, string path, string expected)
    {
        var context = new DefaultHttpContext();
        context.Request.Method = method;
        context.Request.Path = path;

        Assert.Equal(expected, RequestRateLimitPolicy.GetCategory(context));
    }
}
