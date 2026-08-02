using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class SourceCrawlRepository(
    IConfiguration configuration,
    TitleNormalizer normalizer)
{
    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        ALTER TABLE audiobook_releases
          ADD COLUMN IF NOT EXISTS source_category_id INT NULL;
        ALTER TABLE audiobook_releases
          ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NULL;
        ALTER TABLE audiobook_releases
          ADD COLUMN IF NOT EXISTS last_listing_check_at TIMESTAMPTZ NULL;
        ALTER TABLE audiobook_releases
          ADD COLUMN IF NOT EXISTS listing_fingerprint TEXT NULL;
        ALTER TABLE audiobook_releases
          ADD COLUMN IF NOT EXISTS detail_fingerprint TEXT NULL;

        CREATE INDEX IF NOT EXISTS ix_audiobook_source_category
          ON audiobook_releases(source, source_category_id, last_seen_at DESC);

        CREATE TABLE IF NOT EXISTS source_crawl_state (
          source TEXT NOT NULL,
          category_id INT NOT NULL,
          category_order INT NOT NULL,
          bootstrap_next_page INT NOT NULL DEFAULT 1,
          bootstrap_last_page INT NULL,
          bootstrap_completed BOOLEAN NOT NULL DEFAULT FALSE,
          last_bootstrap_page_at TIMESTAMPTZ NULL,
          last_incremental_at TIMESTAMPTZ NULL,
          pages_scanned BIGINT NOT NULL DEFAULT 0,
          releases_seen BIGINT NOT NULL DEFAULT 0,
          releases_inserted BIGINT NOT NULL DEFAULT 0,
          releases_changed BIGINT NOT NULL DEFAULT 0,
          last_error TEXT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(source, category_id)
        );

        ALTER TABLE source_crawl_state ADD COLUMN IF NOT EXISTS bootstrap_last_page INT NULL;

        CREATE INDEX IF NOT EXISTS ix_source_crawl_bootstrap
          ON source_crawl_state(source, bootstrap_completed, last_bootstrap_page_at, category_order);

        CREATE TABLE IF NOT EXISTS source_crawl_control (
          source TEXT PRIMARY KEY,
          bootstrap_paused BOOLEAN NOT NULL DEFAULT FALSE,
          bootstrap_started_at TIMESTAMPTZ NULL,
          bootstrap_completed_at TIMESTAMPTZ NULL,
          last_incremental_started_at TIMESTAMPTZ NULL,
          last_incremental_completed_at TIMESTAMPTZ NULL,
          last_error TEXT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
    }

    public async Task EnsureSourceAsync(
        string source,
        IReadOnlyList<int> categories,
        CancellationToken ct)
    {
        const string controlSql = """
        INSERT INTO source_crawl_control(source)
        VALUES (@Source)
        ON CONFLICT (source) DO NOTHING;
        """;

        const string categorySql = """
        INSERT INTO source_crawl_state(source, category_id, category_order)
        SELECT @Source, category_id, ordinal::int
        FROM unnest(CAST(@Categories AS integer[])) WITH ORDINALITY AS c(category_id, ordinal)
        ON CONFLICT (source, category_id) DO UPDATE SET
          category_order = EXCLUDED.category_order,
          updated_at = NOW()
        WHERE source_crawl_state.category_order IS DISTINCT FROM EXCLUDED.category_order;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(
            controlSql,
            new { Source = source },
            tx,
            cancellationToken: ct));
        await db.ExecuteAsync(new CommandDefinition(
            categorySql,
            new { Source = source, Categories = categories.ToArray() },
            tx,
            cancellationToken: ct));
        await tx.CommitAsync(ct);
    }

    public async Task<SourceCategoryCrawlState?> GetNextBootstrapCategoryAsync(
        string source,
        CancellationToken ct)
    {
        const string sql = """
        SELECT source,
          category_id AS CategoryId,
          category_order AS CategoryOrder,
          bootstrap_next_page AS BootstrapNextPage,
          bootstrap_last_page AS BootstrapLastPage,
          bootstrap_completed AS BootstrapCompleted,
          last_bootstrap_page_at AS LastBootstrapPageAt,
          last_incremental_at AS LastIncrementalAt,
          pages_scanned AS PagesScanned,
          releases_seen AS ReleasesSeen,
          releases_inserted AS ReleasesInserted,
          releases_changed AS ReleasesChanged,
          last_error AS LastError,
          updated_at AS UpdatedAt
        FROM source_crawl_state
        WHERE source = @Source
          AND bootstrap_completed = FALSE
        ORDER BY last_bootstrap_page_at NULLS FIRST, category_order
        LIMIT 1;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.QuerySingleOrDefaultAsync<SourceCategoryCrawlState>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task RecordBootstrapPageAsync(
        string source,
        int categoryId,
        int nextPage,
        bool completed,
        int received,
        int inserted,
        int changed,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_state
        SET bootstrap_next_page = @NextPage,
            bootstrap_completed = @Completed,
            last_bootstrap_page_at = NOW(),
            pages_scanned = pages_scanned + 1,
            releases_seen = releases_seen + @Received,
            releases_inserted = releases_inserted + @Inserted,
            releases_changed = releases_changed + @Changed,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new
            {
                Source = source,
                CategoryId = categoryId,
                NextPage = nextPage,
                Completed = completed,
                Received = received,
                Inserted = inserted,
                Changed = changed
            },
            cancellationToken: ct));
    }

    public async Task RecordBootstrapErrorAsync(
        string source,
        int categoryId,
        string error,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_state
        SET last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;

        UPDATE source_crawl_control
        SET last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE source = @Source;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source, CategoryId = categoryId, Error = error },
            cancellationToken: ct));
    }

    public async Task MarkBootstrapStartedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET bootstrap_started_at = COALESCE(bootstrap_started_at, NOW()),
            bootstrap_completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task MarkBootstrapCompletedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET bootstrap_completed_at = COALESCE(bootstrap_completed_at, NOW()),
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task SetBootstrapPausedAsync(string source, bool paused, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET bootstrap_paused = @Paused,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source, Paused = paused },
            cancellationToken: ct));
    }

    public async Task ResetBootstrapAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_state
        SET bootstrap_next_page = 1,
            bootstrap_last_page = NULL,
            bootstrap_completed = FALSE,
            last_bootstrap_page_at = NULL,
            pages_scanned = 0,
            releases_seen = 0,
            releases_inserted = 0,
            releases_changed = 0,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;

        UPDATE source_crawl_control
        SET bootstrap_paused = FALSE,
            bootstrap_started_at = NULL,
            bootstrap_completed_at = NULL,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task<bool> IsBootstrapPausedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        SELECT bootstrap_paused
        FROM source_crawl_control
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<bool>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
    }

    public async Task<bool> IsBootstrapCatalogCompletedAsync(
        string source,
        int expectedCategories,
        CancellationToken ct)
    {
        const string sql = """
        SELECT COUNT(*)::int
        FROM source_crawl_state
        WHERE source = @Source
          AND bootstrap_completed = TRUE;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var completed = await db.ExecuteScalarAsync<int>(new CommandDefinition(
            sql,
            new { Source = source },
            cancellationToken: ct));
        return completed == expectedCategories;
    }

    public async Task MarkIncrementalStartedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET last_incremental_started_at = NOW(),
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task RecordIncrementalCategoryAsync(
        string source,
        int categoryId,
        int pages,
        int received,
        int inserted,
        int changed,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_state
        SET last_incremental_at = NOW(),
            pages_scanned = pages_scanned + @Pages,
            releases_seen = releases_seen + @Received,
            releases_inserted = releases_inserted + @Inserted,
            releases_changed = releases_changed + @Changed,
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new
            {
                Source = source,
                CategoryId = categoryId,
                Pages = pages,
                Received = received,
                Inserted = inserted,
                Changed = changed
            },
            cancellationToken: ct));
    }

    public async Task MarkIncrementalCompletedAsync(string source, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET last_incremental_completed_at = NOW(),
            last_error = NULL,
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Source = source }, cancellationToken: ct));
    }

    public async Task RecordCategoryErrorAsync(
        string source,
        int categoryId,
        string error,
        CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_state
        SET last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE source = @Source AND category_id = @CategoryId;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source, CategoryId = categoryId, Error = error },
            cancellationToken: ct));
    }

    public async Task MarkControlErrorAsync(string source, string error, CancellationToken ct)
    {
        const string sql = """
        UPDATE source_crawl_control
        SET last_error = LEFT(@Error, 2000),
            updated_at = NOW()
        WHERE source = @Source;
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Source = source, Error = error },
            cancellationToken: ct));
    }

    public async Task<IReadOnlyDictionary<long, ExistingListingState>> GetExistingListingStatesAsync(
        IReadOnlyCollection<long> topicIds,
        CancellationToken ct)
    {
        if (topicIds.Count == 0)
            return new Dictionary<long, ExistingListingState>();

        const string sql = """
        SELECT source_id::bigint AS TopicId,
          listing_fingerprint AS ListingFingerprint,
          detail_fingerprint AS DetailFingerprint,
          info_hash AS InfoHash,
          raw_title AS RawTitle,
          size_bytes AS SizeBytes,
          (magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> '') AS HasMagnet
        FROM audiobook_releases
        WHERE source = @Source
          AND source_id = ANY(CAST(@SourceIds AS text[]));
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var rows = await db.QueryAsync<ExistingListingState>(new CommandDefinition(
            sql,
            new
            {
                Source = RuTrackerSourceDefinition.Key,
                SourceIds = topicIds.Select(value => value.ToString()).ToArray()
            },
            cancellationToken: ct));
        return rows.ToDictionary(row => row.TopicId);
    }

    public async Task<CrawlUpsertResult?> UpdateExistingListingAsync(
        RuTrackerSearchItem item,
        int categoryId,
        string listingFingerprint,
        string? detailFingerprintBackfill,
        CancellationToken ct)
    {
        var parsed = normalizer.Parse(item.Title);
        const string sql = """
        WITH existing_row AS (
          SELECT id,
            raw_title IS DISTINCT FROM @RawTitle
            OR source_url IS DISTINCT FROM @SourceUrl
            OR size_bytes IS DISTINCT FROM @SizeBytes
            OR seeders IS DISTINCT FROM @Seeders
            OR leechers IS DISTINCT FROM @Leechers
            OR source_category_id IS DISTINCT FROM @CategoryId
            OR listing_fingerprint IS DISTINCT FROM @ListingFingerprint AS changed
          FROM audiobook_releases
          WHERE source = @Source
            AND source_id = @SourceId
            AND magnet_uri IS NOT NULL
            AND BTRIM(magnet_uri) <> ''
        ), updated AS (
          UPDATE audiobook_releases release
          SET title = CASE
                WHEN release.metadata_parser_version > 0 THEN release.title
                ELSE @Title
              END,
              normalized_title = CASE
                WHEN release.metadata_parser_version > 0 THEN release.normalized_title
                ELSE @NormalizedTitle
              END,
              author = CASE
                WHEN release.metadata_parser_version > 0 THEN release.author
                ELSE @Author
              END,
              normalized_author = CASE
                WHEN release.metadata_parser_version > 0 THEN release.normalized_author
                ELSE @NormalizedAuthor
              END,
              series = CASE
                WHEN release.metadata_parser_version > 0 THEN release.series
                ELSE COALESCE(@Series, release.series)
              END,
              normalized_series = CASE
                WHEN release.metadata_parser_version > 0 THEN release.normalized_series
                ELSE COALESCE(@NormalizedSeries, release.normalized_series)
              END,
              series_position = CASE
                WHEN release.metadata_parser_version > 0 THEN release.series_position
                ELSE COALESCE(@SeriesPosition, release.series_position)
              END,
              narrators = CASE
                WHEN release.metadata_parser_version > 0 THEN release.narrators
                WHEN cardinality(@Narrators) > 0 THEN @Narrators
                ELSE release.narrators
              END,
              language = CASE
                WHEN release.metadata_parser_version > 0 THEN release.language
                ELSE COALESCE(@Language, release.language)
              END,
              release_year = CASE
                WHEN release.metadata_parser_version > 0 THEN release.release_year
                ELSE COALESCE(@ReleaseYear, release.release_year)
              END,
              audio_format = CASE
                WHEN release.metadata_parser_version > 0 THEN release.audio_format
                ELSE COALESCE(@AudioFormat, release.audio_format)
              END,
              bitrate_kbps = CASE
                WHEN release.metadata_parser_version > 0 THEN release.bitrate_kbps
                ELSE COALESCE(@BitrateKbps, release.bitrate_kbps)
              END,
              is_abridged = CASE
                WHEN release.metadata_parser_version > 0 THEN release.is_abridged
                ELSE COALESCE(@IsAbridged, release.is_abridged)
              END,
              is_dramatized = CASE
                WHEN release.metadata_parser_version > 0 THEN release.is_dramatized
                ELSE COALESCE(@IsDramatized, release.is_dramatized)
              END,
              source_url = @SourceUrl,
              size_bytes = @SizeBytes,
              seeders = @Seeders,
              leechers = @Leechers,
              raw_title = @RawTitle,
              source_category_id = @CategoryId,
              listing_fingerprint = @ListingFingerprint,
              detail_fingerprint = COALESCE(release.detail_fingerprint, @DetailFingerprint),
              last_seen_at = NOW(),
              last_listing_check_at = NOW(),
              updated_at = CASE WHEN existing_row.changed THEN NOW() ELSE release.updated_at END
          FROM existing_row
          WHERE release.id = existing_row.id
          RETURNING release.id AS Id
        )
        SELECT updated.Id, FALSE AS Inserted, existing_row.changed AS Changed
        FROM updated
        JOIN existing_row ON existing_row.id = updated.Id;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.QuerySingleOrDefaultAsync<CrawlUpsertResult>(new CommandDefinition(
            sql,
            ListingArguments(
                item,
                categoryId,
                parsed,
                null,
                null,
                null,
                listingFingerprint,
                detailFingerprintBackfill),
            cancellationToken: ct));
    }

    public Task<CrawlUpsertResult> UpsertListingWithMagnetAsync(
        RuTrackerSearchItem item,
        int categoryId,
        string infoHash,
        string magnetUri,
        string listingFingerprint,
        string detailFingerprint,
        CancellationToken ct) =>
        UpsertListingWithTopicMetadataAsync(
            item,
            categoryId,
            infoHash,
            magnetUri,
            null,
            listingFingerprint,
            detailFingerprint,
            ct);

    public async Task<CrawlUpsertResult> UpsertListingWithTopicMetadataAsync(
        RuTrackerSearchItem item,
        int categoryId,
        string infoHash,
        string magnetUri,
        RuTrackerTopicMetadata? metadata,
        string listingFingerprint,
        string detailFingerprint,
        CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(magnetUri))
            throw new ArgumentException("Magnet-ссылка обязательна.", nameof(magnetUri));

        var parsed = metadata?.ParsedTitle ?? normalizer.Parse(item.Title);
        const string sql = """
        INSERT INTO audiobook_releases (
          title, normalized_title, author, normalized_author, series, normalized_series, series_position,
          narrators, language, release_year, duration_seconds, audio_format, bitrate_kbps,
          genres, publisher, sample_rate_hz, audio_channels, bitrate_mode,
          edition_type, edition_category, music, metadata_parser_version, metadata_parsed_at,
          is_abridged, is_dramatized, source, source_id, source_url,
          info_hash, magnet_uri, size_bytes, seeders, leechers, raw_title,
          source_category_id, listing_fingerprint, detail_fingerprint, last_seen_at, last_listing_check_at,
          magnet_attempts, magnet_attempted_at, magnet_error)
        VALUES (
          @Title, @NormalizedTitle, @Author, @NormalizedAuthor, @Series, @NormalizedSeries, @SeriesPosition,
          @Narrators, @Language, @ReleaseYear, @DurationSeconds, @AudioFormat, @BitrateKbps,
          @Genres, @Publisher, @SampleRateHz, @AudioChannels, @BitrateMode,
          @EditionType, @EditionCategory, @Music, @MetadataParserVersion,
          CASE WHEN @MetadataParserVersion > 0 THEN NOW() ELSE NULL END,
          @IsAbridged, @IsDramatized, @Source, @SourceId, @SourceUrl,
          @InfoHash, @MagnetUri, @SizeBytes, @Seeders, @Leechers, @RawTitle,
          @CategoryId, @ListingFingerprint, @DetailFingerprint, NOW(), NOW(), 0, NOW(), NULL)
        ON CONFLICT (source, source_id) DO UPDATE SET
          title = CASE
            WHEN EXCLUDED.metadata_parser_version > 0 THEN EXCLUDED.title
            WHEN audiobook_releases.metadata_parser_version > 0 THEN audiobook_releases.title
            ELSE EXCLUDED.title
          END,
          normalized_title = CASE
            WHEN EXCLUDED.metadata_parser_version > 0 THEN EXCLUDED.normalized_title
            WHEN audiobook_releases.metadata_parser_version > 0 THEN audiobook_releases.normalized_title
            ELSE EXCLUDED.normalized_title
          END,
          author = CASE
            WHEN EXCLUDED.metadata_parser_version > 0 THEN EXCLUDED.author
            WHEN audiobook_releases.metadata_parser_version > 0 THEN audiobook_releases.author
            ELSE EXCLUDED.author
          END,
          normalized_author = CASE
            WHEN EXCLUDED.metadata_parser_version > 0 THEN EXCLUDED.normalized_author
            WHEN audiobook_releases.metadata_parser_version > 0 THEN audiobook_releases.normalized_author
            ELSE EXCLUDED.normalized_author
          END,
          series = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.series, audiobook_releases.series)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.series
            ELSE COALESCE(EXCLUDED.series, audiobook_releases.series)
          END,
          normalized_series = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.normalized_series, audiobook_releases.normalized_series)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.normalized_series
            ELSE COALESCE(EXCLUDED.normalized_series, audiobook_releases.normalized_series)
          END,
          series_position = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
                 AND @ClearSeriesPosition THEN NULL
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.series_position, audiobook_releases.series_position)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.series_position
            ELSE COALESCE(EXCLUDED.series_position, audiobook_releases.series_position)
          END,
          narrators = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
                 AND cardinality(EXCLUDED.narrators) > 0 THEN EXCLUDED.narrators
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.narrators
            WHEN cardinality(EXCLUDED.narrators) > 0 THEN EXCLUDED.narrators
            ELSE audiobook_releases.narrators
          END,
          language = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.language, audiobook_releases.language)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.language
            ELSE COALESCE(EXCLUDED.language, audiobook_releases.language)
          END,
          release_year = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.release_year, audiobook_releases.release_year)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.release_year
            ELSE COALESCE(EXCLUDED.release_year, audiobook_releases.release_year)
          END,
          duration_seconds = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.duration_seconds, audiobook_releases.duration_seconds)
            ELSE audiobook_releases.duration_seconds
          END,
          audio_format = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.audio_format, audiobook_releases.audio_format)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.audio_format
            ELSE COALESCE(EXCLUDED.audio_format, audiobook_releases.audio_format)
          END,
          bitrate_kbps = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.bitrate_kbps, audiobook_releases.bitrate_kbps)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.bitrate_kbps
            ELSE COALESCE(EXCLUDED.bitrate_kbps, audiobook_releases.bitrate_kbps)
          END,
          genres = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
                 AND cardinality(EXCLUDED.genres) > 0 THEN EXCLUDED.genres
            ELSE audiobook_releases.genres
          END,
          publisher = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
                 AND @ClearPublisher THEN NULL
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.publisher, audiobook_releases.publisher)
            ELSE audiobook_releases.publisher
          END,
          sample_rate_hz = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.sample_rate_hz, audiobook_releases.sample_rate_hz)
            ELSE audiobook_releases.sample_rate_hz
          END,
          audio_channels = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.audio_channels, audiobook_releases.audio_channels)
            ELSE audiobook_releases.audio_channels
          END,
          bitrate_mode = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.bitrate_mode, audiobook_releases.bitrate_mode)
            ELSE audiobook_releases.bitrate_mode
          END,
          edition_type = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.edition_type, audiobook_releases.edition_type)
            ELSE audiobook_releases.edition_type
          END,
          edition_category = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.edition_category, audiobook_releases.edition_category)
            ELSE audiobook_releases.edition_category
          END,
          music = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.music, audiobook_releases.music)
            ELSE audiobook_releases.music
          END,
          metadata_parser_version = GREATEST(
            audiobook_releases.metadata_parser_version,
            EXCLUDED.metadata_parser_version),
          metadata_parsed_at = CASE
            WHEN EXCLUDED.metadata_parser_version > 0 THEN EXCLUDED.metadata_parsed_at
            ELSE audiobook_releases.metadata_parsed_at
          END,
          is_abridged = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.is_abridged, audiobook_releases.is_abridged)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.is_abridged
            ELSE COALESCE(EXCLUDED.is_abridged, audiobook_releases.is_abridged)
          END,
          is_dramatized = CASE
            WHEN EXCLUDED.metadata_parser_version > 0
              THEN COALESCE(EXCLUDED.is_dramatized, audiobook_releases.is_dramatized)
            WHEN audiobook_releases.metadata_parser_version > 0
              THEN audiobook_releases.is_dramatized
            ELSE COALESCE(EXCLUDED.is_dramatized, audiobook_releases.is_dramatized)
          END,
          source_url = EXCLUDED.source_url,
          info_hash = EXCLUDED.info_hash,
          magnet_uri = EXCLUDED.magnet_uri,
          size_bytes = EXCLUDED.size_bytes,
          seeders = EXCLUDED.seeders,
          leechers = EXCLUDED.leechers,
          raw_title = EXCLUDED.raw_title,
          source_category_id = EXCLUDED.source_category_id,
          listing_fingerprint = EXCLUDED.listing_fingerprint,
          detail_fingerprint = EXCLUDED.detail_fingerprint,
          last_seen_at = NOW(),
          last_listing_check_at = NOW(),
          magnet_attempts = 0,
          magnet_attempted_at = NOW(),
          magnet_error = NULL,
          updated_at = NOW()
        WHERE audiobook_releases.raw_title IS DISTINCT FROM EXCLUDED.raw_title
           OR audiobook_releases.source_url IS DISTINCT FROM EXCLUDED.source_url
           OR audiobook_releases.info_hash IS DISTINCT FROM EXCLUDED.info_hash
           OR audiobook_releases.magnet_uri IS DISTINCT FROM EXCLUDED.magnet_uri
           OR audiobook_releases.size_bytes IS DISTINCT FROM EXCLUDED.size_bytes
           OR audiobook_releases.seeders IS DISTINCT FROM EXCLUDED.seeders
           OR audiobook_releases.leechers IS DISTINCT FROM EXCLUDED.leechers
           OR audiobook_releases.source_category_id IS DISTINCT FROM EXCLUDED.source_category_id
           OR audiobook_releases.listing_fingerprint IS DISTINCT FROM EXCLUDED.listing_fingerprint
           OR audiobook_releases.detail_fingerprint IS DISTINCT FROM EXCLUDED.detail_fingerprint
           OR (
             EXCLUDED.metadata_parser_version > 0
             AND (
               audiobook_releases.title IS DISTINCT FROM EXCLUDED.title
               OR audiobook_releases.author IS DISTINCT FROM EXCLUDED.author
               OR audiobook_releases.series IS DISTINCT FROM EXCLUDED.series
               OR audiobook_releases.series_position IS DISTINCT FROM EXCLUDED.series_position
               OR audiobook_releases.narrators IS DISTINCT FROM EXCLUDED.narrators
               OR audiobook_releases.release_year IS DISTINCT FROM EXCLUDED.release_year
               OR audiobook_releases.duration_seconds IS DISTINCT FROM EXCLUDED.duration_seconds
               OR audiobook_releases.audio_format IS DISTINCT FROM EXCLUDED.audio_format
               OR audiobook_releases.bitrate_kbps IS DISTINCT FROM EXCLUDED.bitrate_kbps
               OR audiobook_releases.genres IS DISTINCT FROM EXCLUDED.genres
               OR audiobook_releases.publisher IS DISTINCT FROM EXCLUDED.publisher
               OR audiobook_releases.sample_rate_hz IS DISTINCT FROM EXCLUDED.sample_rate_hz
               OR audiobook_releases.audio_channels IS DISTINCT FROM EXCLUDED.audio_channels
               OR audiobook_releases.bitrate_mode IS DISTINCT FROM EXCLUDED.bitrate_mode
               OR audiobook_releases.edition_type IS DISTINCT FROM EXCLUDED.edition_type
               OR audiobook_releases.edition_category IS DISTINCT FROM EXCLUDED.edition_category
               OR audiobook_releases.music IS DISTINCT FROM EXCLUDED.music
               OR audiobook_releases.metadata_parser_version < EXCLUDED.metadata_parser_version
             )
           )
        RETURNING id AS Id, (xmax = 0) AS Inserted, TRUE AS Changed;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        var args = ListingArguments(
            item,
            categoryId,
            parsed,
            metadata,
            infoHash.ToLowerInvariant(),
            magnetUri,
            listingFingerprint,
            detailFingerprint);
        var result = await db.QuerySingleOrDefaultAsync<CrawlUpsertResult>(new CommandDefinition(
            sql,
            args,
            cancellationToken: ct));

        if (result is not null)
            return result;

        const string touchSql = """
        UPDATE audiobook_releases
        SET last_seen_at = NOW(),
            last_listing_check_at = NOW()
        WHERE source = @Source AND source_id = @SourceId
        RETURNING id;
        """;
        var id = await db.ExecuteScalarAsync<long>(new CommandDefinition(
            touchSql,
            new { Source = RuTrackerSourceDefinition.Key, SourceId = item.TopicId.ToString() },
            cancellationToken: ct));
        return new CrawlUpsertResult { Id = id, Inserted = false, Changed = false };
    }

    private object ListingArguments(
        RuTrackerSearchItem item,
        int categoryId,
        ParsedAudiobookTitle parsed,
        RuTrackerTopicMetadata? metadata,
        string? infoHash,
        string? magnetUri,
        string? listingFingerprint,
        string? detailFingerprint) => new
    {
        parsed.Title,
        NormalizedTitle = normalizer.Normalize(parsed.Title),
        parsed.Author,
        NormalizedAuthor = normalizer.Normalize(parsed.Author),
        parsed.Series,
        NormalizedSeries = parsed.Series is null
            ? null
            : normalizer.Normalize(parsed.Series),
        parsed.SeriesPosition,
        parsed.Narrators,
        parsed.Language,
        parsed.ReleaseYear,
        DurationSeconds = metadata?.DurationSeconds,
        parsed.AudioFormat,
        parsed.BitrateKbps,
        Genres = metadata?.Genres ?? [],
        Publisher = metadata?.Publisher,
        ClearSeriesPosition = metadata?.ClearSeriesPosition ?? false,
        ClearPublisher = metadata?.ClearPublisher ?? false,
        SampleRateHz = metadata?.SampleRateHz,
        AudioChannels = metadata?.AudioChannels,
        BitrateMode = metadata?.BitrateMode,
        EditionType = metadata?.EditionType,
        EditionCategory = metadata?.EditionCategory,
        Music = metadata?.Music,
        MetadataParserVersion = metadata?.ParserVersion ?? 0,
        parsed.IsAbridged,
        parsed.IsDramatized,
        Source = RuTrackerSourceDefinition.Key,
        SourceId = item.TopicId.ToString(),
        SourceUrl = item.TopicUrl,
        InfoHash = infoHash,
        MagnetUri = magnetUri,
        ListingFingerprint = listingFingerprint,
        DetailFingerprint = detailFingerprint,
        item.SizeBytes,
        item.Seeders,
        item.Leechers,
        RawTitle = item.Title,
        CategoryId = categoryId
    };

    public async Task<IReadOnlyList<RuTrackerMagnetCandidate>> GetEligibleMissingMagnetsAsync(
        IReadOnlyCollection<long>? releaseIds,
        int limit,
        int maxAttempts,
        int retryMinutes,
        CancellationToken ct)
    {
        const string sql = """
        SELECT id,
          source_id AS SourceId,
          source_url AS SourceUrl,
          title,
          magnet_attempts AS Attempts
        FROM audiobook_releases
        WHERE source = @Source
          AND source_url IS NOT NULL
          AND source_url <> ''
          AND (magnet_uri IS NULL OR magnet_uri = '')
          AND magnet_attempts < @MaxAttempts
          AND (
            magnet_attempted_at IS NULL OR
            magnet_attempted_at < NOW() - (@RetryMinutes * INTERVAL '1 minute')
          )
          AND (@UseIds = FALSE OR id = ANY(CAST(@ReleaseIds AS bigint[])))
        ORDER BY magnet_attempted_at NULLS FIRST, discovered_at ASC
        LIMIT @Limit;
        """;

        var ids = releaseIds?.Distinct().ToArray() ?? [];
        await using var db = new NpgsqlConnection(ConnectionString);
        var rows = await db.QueryAsync<RuTrackerMagnetCandidate>(new CommandDefinition(
            sql,
            new
            {
                Source = RuTrackerSourceDefinition.Key,
                UseIds = ids.Length > 0,
                ReleaseIds = ids,
                Limit = Math.Clamp(limit, 1, 100),
                MaxAttempts = Math.Clamp(maxAttempts, 1, 20),
                RetryMinutes = Math.Clamp(retryMinutes, 1, 10080)
            },
            cancellationToken: ct));
        return rows.AsList();
    }

    public async Task<int> CountEligibleMissingMagnetsAsync(
        int maxAttempts,
        int retryMinutes,
        CancellationToken ct)
    {
        const string sql = """
        SELECT COUNT(*)::int
        FROM audiobook_releases
        WHERE source = @Source
          AND source_url IS NOT NULL
          AND source_url <> ''
          AND (magnet_uri IS NULL OR magnet_uri = '')
          AND magnet_attempts < @MaxAttempts
          AND (
            magnet_attempted_at IS NULL OR
            magnet_attempted_at < NOW() - (@RetryMinutes * INTERVAL '1 minute')
          );
        """;
        await using var db = new NpgsqlConnection(ConnectionString);
        return await db.ExecuteScalarAsync<int>(new CommandDefinition(
            sql,
            new
            {
                Source = RuTrackerSourceDefinition.Key,
                MaxAttempts = Math.Clamp(maxAttempts, 1, 20),
                RetryMinutes = Math.Clamp(retryMinutes, 1, 10080)
            },
            cancellationToken: ct));
    }

    public async Task<(SourceCrawlControl Control, IReadOnlyList<SourceCategoryCrawlState> States)>
        GetStatusAsync(string source, CancellationToken ct)
    {
        const string controlSql = """
        SELECT source,
          bootstrap_paused AS BootstrapPaused,
          bootstrap_started_at AS BootstrapStartedAt,
          bootstrap_completed_at AS BootstrapCompletedAt,
          last_incremental_started_at AS LastIncrementalStartedAt,
          last_incremental_completed_at AS LastIncrementalCompletedAt,
          last_error AS LastError,
          updated_at AS UpdatedAt
        FROM source_crawl_control
        WHERE source = @Source;
        """;

        const string statesSql = """
        SELECT source,
          category_id AS CategoryId,
          category_order AS CategoryOrder,
          bootstrap_next_page AS BootstrapNextPage,
          bootstrap_last_page AS BootstrapLastPage,
          bootstrap_completed AS BootstrapCompleted,
          last_bootstrap_page_at AS LastBootstrapPageAt,
          last_incremental_at AS LastIncrementalAt,
          pages_scanned AS PagesScanned,
          releases_seen AS ReleasesSeen,
          releases_inserted AS ReleasesInserted,
          releases_changed AS ReleasesChanged,
          last_error AS LastError,
          updated_at AS UpdatedAt
        FROM source_crawl_state
        WHERE source = @Source
        ORDER BY category_order;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        var control = await db.QuerySingleAsync<SourceCrawlControl>(new CommandDefinition(
            controlSql,
            new { Source = source },
            cancellationToken: ct));
        var states = (await db.QueryAsync<SourceCategoryCrawlState>(new CommandDefinition(
            statesSql,
            new { Source = source },
            cancellationToken: ct))).AsList();
        return (control, states);
    }
}
