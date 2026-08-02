using AudioBookRed.Api.Infrastructure;
using Microsoft.AspNetCore.Http;

namespace AudioBookRed.Api.Tests;

public sealed class SecurityHeadersPolicyTests
{
    [Fact]
    public void Apply_sets_security_headers_without_inline_script_allowance()
    {
        var headers = new HeaderDictionary();

        SecurityHeadersPolicy.Apply(headers);

        Assert.Equal("nosniff", headers["X-Content-Type-Options"].ToString());
        Assert.Equal("no-referrer", headers["Referrer-Policy"].ToString());
        Assert.Equal("DENY", headers["X-Frame-Options"].ToString());
        Assert.Equal(
            "camera=(), microphone=(), geolocation=()",
            headers["Permissions-Policy"].ToString());

        var csp = headers["Content-Security-Policy"].ToString();
        Assert.Contains("script-src 'self'", csp);
        Assert.Contains("img-src 'self' data:", csp);
        Assert.Contains("frame-ancestors 'none'", csp);
        Assert.DoesNotContain("'unsafe-inline'", csp);
    }
}
