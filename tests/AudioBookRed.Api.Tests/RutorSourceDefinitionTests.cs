using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RutorSourceDefinitionTests
{
    [Fact]
    public void Defaults_are_safe_for_initial_registration()
    {
        var definition = new RutorSourceDefinition();

        Assert.Equal(RutorSourceDefinition.Key, definition.SourceKey);
        Assert.False(definition.EnabledByDefault);
        Assert.Equal(new[] { RutorSourceDefinition.BooksCategoryId }, definition.Categories);
        Assert.Equal(1, definition.RuntimeDefaults.IncrementalPages);
        Assert.Contains("listing-magnet", definition.Capabilities);
        Assert.Contains("topic-details", definition.Capabilities);
        Assert.Contains("metadata-reparse", definition.Capabilities);
    }
}
