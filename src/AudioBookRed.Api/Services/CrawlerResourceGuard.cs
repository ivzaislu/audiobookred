namespace AudioBookRed.Api.Services;

public sealed class CrawlerResourceGuard(RuTrackerSourceDefinition definition)
{
    public void EnsureEnoughDiskSpace()
    {
        var root = new DriveInfo(Path.GetPathRoot(AppContext.BaseDirectory) ?? "/");
        var free = root.AvailableFreeSpace;
        var ratio = root.TotalSize > 0 ? (double)free / root.TotalSize : 0;
        var belowBytes = free < definition.MinimumFreeBytes;
        var belowRatio = definition.MinimumFreeRatio > 0 && ratio < definition.MinimumFreeRatio;

        if (!belowBytes && !belowRatio)
            return;

        var requirement = definition.MinimumFreeRatio > 0
            ? $"минимум {definition.MinimumFreeBytes / 1024 / 1024} MiB и {definition.MinimumFreeRatio:P0}"
            : $"минимум {definition.MinimumFreeBytes / 1024 / 1024} MiB";

        throw new InvalidOperationException(
            $"Crawler остановлен: мало места на диске. Свободно {free / 1024 / 1024} MiB " +
            $"({ratio:P1}); требуется {requirement}.");
    }
}
