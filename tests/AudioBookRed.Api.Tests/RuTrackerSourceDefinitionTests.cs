using AudioBookRed.Api.Services;

namespace AudioBookRed.Api.Tests;

public sealed class RuTrackerSourceDefinitionTests
{
    [Fact]
    public void Defaults_match_operational_configuration()
    {
        var definition = new RuTrackerSourceDefinition();

        Assert.Equal(23, definition.Categories.Count);
        Assert.Equal(1, definition.DefaultIncrementalPages);
        Assert.Equal(3, definition.DefaultWorkerJobLimit);
        Assert.Equal(20, definition.WorkerLeaseMinutes);
    }
}
