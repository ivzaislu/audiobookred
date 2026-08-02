using Microsoft.Extensions.DependencyInjection;

namespace AudioBookRed.Api.Sources;

public static class SourceStartupValidation
{
    public static SourceRegistry ValidateSourceRegistry(
        this IServiceProvider services)
    {
        ArgumentNullException.ThrowIfNull(services);
        return services.GetRequiredService<SourceRegistry>();
    }
}
