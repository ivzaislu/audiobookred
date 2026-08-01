using AudioBookRed.Api.Models;
using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

public sealed class AudiobookRepository(
    IConfiguration configuration,
    TitleNormalizer normalizer,
    PersonNameParser personNames,
    SeriesNameParser seriesNames,
    CanonicalFacetRepository canonicalFacets,
    ILogger<AudiobookRepository> logger)
{
    private const string AuthorRole = "author";
    private const string NarratorRole = "narrator";

    private string ConnectionString => configuration.GetConnectionString("DefaultConnection")
        ?? throw new InvalidOperationException("ConnectionStrings:DefaultConnection is missing");

    public async Task InitializeAsync(CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS audiobook_releases (
          id BIGSERIAL PRIMARY KEY,
          title TEXT NOT NULL,
          normalized_title TEXT NOT NULL,
          author TEXT NOT NULL,
          normalized_author TEXT NOT NULL,
          series TEXT NULL,
          normalized_series TEXT NULL,
          series_position NUMERIC(8,2) NULL,
          narrators TEXT[] NOT NULL DEFAULT '{}',
          language VARCHAR(12) NULL,
          release_year INT NULL,
          duration_seconds BIGINT NULL,
          audio_format VARCHAR(16) NULL,
          bitrate_kbps INT NULL,
          is_abridged BOOLEAN NULL,
          is_dramatized BOOLEAN NULL,
          source TEXT NOT NULL,
          source_id TEXT NOT NULL,
          source_url TEXT NULL,
          info_hash TEXT NULL,
          magnet_uri TEXT NULL,
          size_bytes BIGINT NULL,
          seeders INT NULL,
          leechers INT NULL,
          raw_title TEXT NOT NULL,
          magnet_attempts INT NOT NULL DEFAULT 0,
          magnet_attempted_at TIMESTAMPTZ NULL,
          magnet_error TEXT NULL,
          discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          UNIQUE(source, source_id)
        );
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS magnet_attempts INT NOT NULL DEFAULT 0;
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS magnet_attempted_at TIMESTAMPTZ NULL;
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS magnet_error TEXT NULL;
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS normalized_series TEXT NULL;
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS search_text TEXT NULL;

        CREATE EXTENSION IF NOT EXISTS pg_trgm;

        CREATE OR REPLACE FUNCTION audiobookred_refresh_search_text()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          NEW.search_text := LOWER(REPLACE(BTRIM(REGEXP_REPLACE(
            CONCAT_WS(' ', NEW.title, NEW.author, NEW.series, ARRAY_TO_STRING(NEW.narrators, ' '), NEW.raw_title),
            '\s+', ' ', 'g')), 'ё', 'е'));
          RETURN NEW;
        END;
        $$;

        DROP TRIGGER IF EXISTS trg_audiobookred_refresh_search_text ON audiobook_releases;
        CREATE TRIGGER trg_audiobookred_refresh_search_text
          BEFORE INSERT OR UPDATE OF title, author, series, narrators, raw_title
          ON audiobook_releases
          FOR EACH ROW
          EXECUTE FUNCTION audiobookred_refresh_search_text();

        UPDATE audiobook_releases
        SET search_text = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(
          CONCAT_WS(' ', title, author, series, ARRAY_TO_STRING(narrators, ' '), raw_title),
          '\s+', ' ', 'g')), 'ё', 'е'))
        WHERE search_text IS NULL OR BTRIM(search_text) = '';

        CREATE INDEX IF NOT EXISTS ix_audiobook_search_text_trgm
          ON audiobook_releases USING GIN(search_text gin_trgm_ops);

        UPDATE audiobook_releases
        SET normalized_series = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(series, '\s+', ' ', 'g')), 'ё', 'е'))
        WHERE series IS NOT NULL
          AND BTRIM(series) <> ''
          AND (normalized_series IS NULL OR BTRIM(normalized_series) = '');

        DELETE FROM audiobook_releases
        WHERE magnet_uri IS NULL OR BTRIM(magnet_uri) = '';

        ALTER TABLE audiobook_releases
          DROP CONSTRAINT IF EXISTS ck_audiobook_magnet_required;
        ALTER TABLE audiobook_releases
          ADD CONSTRAINT ck_audiobook_magnet_required
          CHECK (magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> '');

        DROP INDEX IF EXISTS ux_audiobook_info_hash;
        CREATE INDEX IF NOT EXISTS ix_audiobook_info_hash
          ON audiobook_releases(info_hash) WHERE info_hash IS NOT NULL AND info_hash <> '';

        WITH duplicates AS (
          SELECT id,
            ROW_NUMBER() OVER (
              PARTITION BY source, LOWER(info_hash)
              ORDER BY seeders DESC NULLS LAST, updated_at DESC, id DESC) AS row_number
          FROM audiobook_releases
          WHERE info_hash IS NOT NULL AND BTRIM(info_hash) <> ''
        )
        DELETE FROM audiobook_releases release
        USING duplicates
        WHERE release.id = duplicates.id AND duplicates.row_number > 1;

        CREATE UNIQUE INDEX IF NOT EXISTS ux_audiobook_source_info_hash
          ON audiobook_releases(source, LOWER(info_hash))
          WHERE info_hash IS NOT NULL AND BTRIM(info_hash) <> '';
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS listing_fingerprint TEXT NULL;
        ALTER TABLE audiobook_releases ADD COLUMN IF NOT EXISTS detail_fingerprint TEXT NULL;
        CREATE INDEX IF NOT EXISTS ix_audiobook_search
          ON audiobook_releases(normalized_author, normalized_title);
        CREATE INDEX IF NOT EXISTS ix_audiobook_series
          ON audiobook_releases(normalized_series) WHERE normalized_series IS NOT NULL;
        CREATE INDEX IF NOT EXISTS ix_audiobook_source
          ON audiobook_releases(LOWER(source));
        CREATE INDEX IF NOT EXISTS ix_audiobook_format
          ON audiobook_releases(LOWER(audio_format)) WHERE audio_format IS NOT NULL;
        CREATE INDEX IF NOT EXISTS ix_audiobook_year
          ON audiobook_releases(release_year) WHERE release_year IS NOT NULL;
        CREATE INDEX IF NOT EXISTS ix_audiobook_bitrate
          ON audiobook_releases(bitrate_kbps) WHERE bitrate_kbps IS NOT NULL;
        CREATE INDEX IF NOT EXISTS ix_audiobook_updated
          ON audiobook_releases(updated_at DESC);
        DROP INDEX IF EXISTS ix_audiobook_missing_magnet;

        CREATE TABLE IF NOT EXISTS people (
          id BIGSERIAL PRIMARY KEY,
          display_name TEXT NOT NULL,
          normalized_name TEXT NOT NULL UNIQUE,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );

        CREATE INDEX IF NOT EXISTS ix_people_normalized_name_trgm
          ON people USING GIN(normalized_name gin_trgm_ops);

        CREATE TABLE IF NOT EXISTS release_people (
          release_id BIGINT NOT NULL REFERENCES audiobook_releases(id) ON DELETE CASCADE,
          person_id BIGINT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
          role VARCHAR(16) NOT NULL CHECK (role IN ('author', 'narrator')),
          position INT NOT NULL DEFAULT 1,
          PRIMARY KEY(release_id, person_id, role)
        );
        CREATE INDEX IF NOT EXISTS ix_release_people_person_role
          ON release_people(person_id, role, release_id);
        CREATE INDEX IF NOT EXISTS ix_release_people_release_role
          ON release_people(release_id, role, position);

        CREATE TABLE IF NOT EXISTS app_migrations (
          migration_key TEXT PRIMARY KEY,
          completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));
        await canonicalFacets.InitializeAsync(db, ct);
    }

    public async Task<long?> UpsertAsync(CreateAudiobookRelease input, CancellationToken ct)
    {
        var parsed = normalizer.Parse(input.RawTitle);
        var canonicalSeries = seriesNames.Parse(parsed.Series, parsed.SeriesPosition);
        var args = new
        {
            parsed.Title,
            NormalizedTitle = normalizer.Normalize(parsed.Title),
            parsed.Author,
            NormalizedAuthor = normalizer.Normalize(parsed.Author),
            Series = canonicalSeries?.DisplayName,
            NormalizedSeries = canonicalSeries?.NormalizedName,
            SeriesPosition = canonicalSeries?.Position,
            parsed.Narrators,
            parsed.Language,
            parsed.ReleaseYear,
            parsed.AudioFormat,
            parsed.BitrateKbps,
            parsed.IsAbridged,
            parsed.IsDramatized,
            input.Source,
            input.SourceId,
            input.SourceUrl,
            InfoHash = input.InfoHash?.ToLowerInvariant(),
            input.MagnetUri,
            input.SizeBytes,
            input.Seeders,
            input.Leechers,
            input.RawTitle
        };

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        await using var transaction = await db.BeginTransactionAsync(ct);

        long? releaseId;
        if (string.IsNullOrWhiteSpace(input.MagnetUri))
        {
            const string updateExistingSql = """
            UPDATE audiobook_releases
            SET title = @Title,
                normalized_title = @NormalizedTitle,
                author = @Author,
                normalized_author = @NormalizedAuthor,
                series = COALESCE(@Series, series),
                normalized_series = COALESCE(@NormalizedSeries, normalized_series),
                series_position = COALESCE(@SeriesPosition, series_position),
                narrators = CASE WHEN cardinality(@Narrators) > 0 THEN @Narrators ELSE narrators END,
                language = COALESCE(@Language, language),
                release_year = COALESCE(@ReleaseYear, release_year),
                audio_format = COALESCE(@AudioFormat, audio_format),
                bitrate_kbps = COALESCE(@BitrateKbps, bitrate_kbps),
                is_abridged = COALESCE(@IsAbridged, is_abridged),
                is_dramatized = COALESCE(@IsDramatized, is_dramatized),
                source_url = COALESCE(@SourceUrl, source_url),
                size_bytes = COALESCE(@SizeBytes, size_bytes),
                seeders = COALESCE(@Seeders, seeders),
                leechers = COALESCE(@Leechers, leechers),
                raw_title = @RawTitle,
                updated_at = NOW()
            WHERE source = @Source
              AND source_id = @SourceId
              AND magnet_uri IS NOT NULL
              AND BTRIM(magnet_uri) <> ''
            RETURNING id;
            """;

            releaseId = await db.QuerySingleOrDefaultAsync<long?>(new CommandDefinition(
                updateExistingSql,
                args,
                transaction,
                cancellationToken: ct));
        }
        else
        {
            const string upsertSql = """
            INSERT INTO audiobook_releases (
              title, normalized_title, author, normalized_author, series, normalized_series, series_position,
              narrators, language, release_year, audio_format, bitrate_kbps,
              is_abridged, is_dramatized, source, source_id, source_url, info_hash,
              magnet_uri, size_bytes, seeders, leechers, raw_title)
            VALUES (
              @Title, @NormalizedTitle, @Author, @NormalizedAuthor, @Series, @NormalizedSeries, @SeriesPosition,
              @Narrators, @Language, @ReleaseYear, @AudioFormat, @BitrateKbps,
              @IsAbridged, @IsDramatized, @Source, @SourceId, @SourceUrl, @InfoHash,
              @MagnetUri, @SizeBytes, @Seeders, @Leechers, @RawTitle)
            ON CONFLICT (source, source_id) DO UPDATE SET
              title = EXCLUDED.title,
              normalized_title = EXCLUDED.normalized_title,
              author = EXCLUDED.author,
              normalized_author = EXCLUDED.normalized_author,
              series = COALESCE(EXCLUDED.series, audiobook_releases.series),
              normalized_series = COALESCE(EXCLUDED.normalized_series, audiobook_releases.normalized_series),
              series_position = COALESCE(EXCLUDED.series_position, audiobook_releases.series_position),
              narrators = CASE
                WHEN cardinality(EXCLUDED.narrators) > 0 THEN EXCLUDED.narrators
                ELSE audiobook_releases.narrators
              END,
              language = COALESCE(EXCLUDED.language, audiobook_releases.language),
              release_year = COALESCE(EXCLUDED.release_year, audiobook_releases.release_year),
              audio_format = COALESCE(EXCLUDED.audio_format, audiobook_releases.audio_format),
              bitrate_kbps = COALESCE(EXCLUDED.bitrate_kbps, audiobook_releases.bitrate_kbps),
              is_abridged = COALESCE(EXCLUDED.is_abridged, audiobook_releases.is_abridged),
              is_dramatized = COALESCE(EXCLUDED.is_dramatized, audiobook_releases.is_dramatized),
              source_url = COALESCE(EXCLUDED.source_url, audiobook_releases.source_url),
              info_hash = COALESCE(EXCLUDED.info_hash, audiobook_releases.info_hash),
              magnet_uri = EXCLUDED.magnet_uri,
              size_bytes = COALESCE(EXCLUDED.size_bytes, audiobook_releases.size_bytes),
              seeders = COALESCE(EXCLUDED.seeders, audiobook_releases.seeders),
              leechers = COALESCE(EXCLUDED.leechers, audiobook_releases.leechers),
              raw_title = EXCLUDED.raw_title,
              magnet_attempts = 0,
              magnet_attempted_at = NOW(),
              magnet_error = NULL,
              updated_at = NOW()
            RETURNING id;
            """;

            releaseId = await db.ExecuteScalarAsync<long>(new CommandDefinition(
                upsertSql,
                args,
                transaction,
                cancellationToken: ct));
        }

        if (releaseId is not null)
        {
            var savedRelease = await db.QuerySingleAsync<ReleaseFacetSource>(new CommandDefinition(
                """
                SELECT author AS Author, narrators AS Narrators, series AS Series,
                  series_position AS SeriesPosition, raw_title AS RawTitle, title AS Title
                FROM audiobook_releases WHERE id = @Id;
                """,
                new { Id = releaseId.Value },
                transaction,
                cancellationToken: ct));
            await canonicalFacets.SyncReleaseAsync(
                db,
                transaction,
                releaseId.Value,
                savedRelease.Author,
                savedRelease.Narrators,
                savedRelease.Series,
                savedRelease.SeriesPosition,
                savedRelease.RawTitle,
                savedRelease.Title,
                ct);
        }

        await transaction.CommitAsync(ct);
        return releaseId;
    }

    public async Task<IReadOnlyList<RuTrackerMagnetCandidate>> GetMissingMagnetsAsync(
        int limit,
        int maxAttempts,
        int retryMinutes,
        CancellationToken ct)
    {
        await Task.CompletedTask;
        return [];
    }

    public async Task UpdateMagnetAsync(long id, string infoHash, string magnetUri, CancellationToken ct)
    {
        const string sql = """
        UPDATE audiobook_releases
        SET info_hash = @InfoHash,
            magnet_uri = @MagnetUri,
            magnet_attempts = magnet_attempts + 1,
            magnet_attempted_at = NOW(),
            magnet_error = NULL,
            updated_at = NOW()
        WHERE id = @Id;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(
            sql,
            new { Id = id, InfoHash = infoHash.ToLowerInvariant(), MagnetUri = magnetUri },
            cancellationToken: ct));
    }

    public async Task MarkMagnetFailureAsync(long id, string error, CancellationToken ct)
    {
        const string sql = """
        DELETE FROM audiobook_releases
        WHERE id = @Id
          AND (magnet_uri IS NULL OR BTRIM(magnet_uri) = '');
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.ExecuteAsync(new CommandDefinition(sql, new { Id = id }, cancellationToken: ct));
    }

    public async Task<int> ResetMagnetFailuresAsync(CancellationToken ct)
    {
        await Task.CompletedTask;
        return 0;
    }

    public async Task<IReadOnlyList<AudiobookRelease>> SearchAsync(
        AudiobookSearchRequest request,
        CancellationToken ct)
    {
        var response = await SearchFacetedAsync(request, includeFacets: false, ct);
        return response.Items;
    }

    public async Task<AudiobookSearchResponse> SearchFacetedAsync(
        AudiobookSearchRequest request,
        CancellationToken ct) => await SearchFacetedAsync(request, includeFacets: true, ct);

    private async Task<AudiobookSearchResponse> SearchFacetedAsync(
        AudiobookSearchRequest request,
        bool includeFacets,
        CancellationToken ct)
    {
        var filters = BuildFilters(request);
        var parameters = BuildParameters(filters);
        var allWhere = BuildWhere(filters, excludeFacet: null);
        var orderBy = BuildOrderBy(filters.Sort);

        var sql = $"""
        SELECT id, title, normalized_title AS NormalizedTitle, author, normalized_author AS NormalizedAuthor,
          series, series_position AS SeriesPosition, narrators, language, release_year AS ReleaseYear,
          duration_seconds AS DurationSeconds, audio_format AS AudioFormat, bitrate_kbps AS BitrateKbps,
          is_abridged AS IsAbridged, is_dramatized AS IsDramatized, source, source_id AS SourceId,
          source_url AS SourceUrl, info_hash AS InfoHash, magnet_uri AS MagnetUri, size_bytes AS SizeBytes,
          seeders, leechers, discovered_at AS DiscoveredAt, updated_at AS UpdatedAt
        FROM audiobook_releases r
        WHERE {allWhere}
        ORDER BY {orderBy}
        LIMIT @Limit;

        SELECT COUNT(*)::bigint
        FROM audiobook_releases r
        WHERE {allWhere};
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);

        IReadOnlyList<AudiobookRelease> items;
        long total;
        using (var result = await db.QueryMultipleAsync(new CommandDefinition(
            sql,
            parameters,
            commandTimeout: 60,
            cancellationToken: ct)))
        {
            items = (await result.ReadAsync<AudiobookRelease>()).AsList();
            total = await result.ReadSingleAsync<long>();
        }

        if (!includeFacets)
            return new AudiobookSearchResponse(total, items, EmptyFacets());

        // JacRed-style staged search: first obtain the result set, then calculate each
        // facet independently. A slow facet can no longer hold the whole multi-result
        // response open inside Npgsql, and every query can use the PostgreSQL indexes.
        var facetQueries = BuildFacetSql(filters);
        var facets = new IReadOnlyList<FacetOption>[facetQueries.Count];
        for (var index = 0; index < facetQueries.Count; index++)
        {
            var rows = await db.QueryAsync<FacetDbRow>(new CommandDefinition(
                facetQueries[index],
                parameters,
                commandTimeout: 60,
                cancellationToken: ct));
            facets[index] = ConvertFacets(rows);
        }

        return new AudiobookSearchResponse(
            total,
            items,
            new AudiobookSearchFacets(
                facets[0], facets[1], facets[2], facets[3], facets[4], facets[5], facets[6]));
    }

    public async Task<DatabaseStatistics> GetStatisticsAsync(CancellationToken ct)
    {
        const string sql = """
        SELECT COUNT(*)::bigint AS Total,
          COUNT(*) FILTER (WHERE discovered_at >= NOW() - INTERVAL '24 hours')::bigint AS AddedLast24Hours,
          COUNT(*) FILTER (WHERE updated_at >= NOW() - INTERVAL '24 hours')::bigint AS UpdatedLast24Hours,
          NOW() AS RefreshedAt
        FROM audiobook_releases
        WHERE magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> '';

        SELECT source AS Source,
          COUNT(*)::bigint AS Count,
          COUNT(*) FILTER (WHERE discovered_at >= NOW() - INTERVAL '24 hours')::bigint AS AddedLast24Hours,
          COUNT(*) FILTER (WHERE updated_at >= NOW() - INTERVAL '24 hours')::bigint AS UpdatedLast24Hours,
          MAX(discovered_at) AS LastDiscoveredAt,
          MAX(updated_at) AS LastUpdatedAt,
          0 AS PendingJobs, 0 AS RunningJobs, 0 AS RetryJobs, 0 AS FailedJobs,
          NULL::timestamptz AS LastSuccessfulCrawlAt,
          NOW() AS RefreshedAt
        FROM audiobook_releases
        WHERE magnet_uri IS NOT NULL AND BTRIM(magnet_uri) <> ''
        GROUP BY source
        ORDER BY COUNT(*) DESC, source;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        using var result = await db.QueryMultipleAsync(new CommandDefinition(sql, cancellationToken: ct));
        var summary = await result.ReadSingleAsync<StatisticsSummary>();
        var sources = (await result.ReadAsync<DatabaseSourceStatistics>()).AsList();
        return new DatabaseStatistics(
            summary.Total,
            summary.AddedLast24Hours,
            summary.UpdatedLast24Hours,
            summary.RefreshedAt,
            sources);
    }

    private SearchFilterValues BuildFilters(AudiobookSearchRequest request)
    {
        var quality = request.Quality?.Trim().ToLowerInvariant();
        int? qualityBitrate = null;
        if (!string.IsNullOrWhiteSpace(quality) && quality.StartsWith("bitrate:", StringComparison.Ordinal))
        {
            if (int.TryParse(quality[8..], out var parsedBitrate)) qualityBitrate = parsedBitrate;
            else quality = null;
        }
        else if (quality is not null && quality != "lossless")
        {
            quality = null;
        }

        var (authorId, authorName) = ParsePersonFilter(request.Author, personNames.ParseAuthors);
        var (narratorId, narratorName) = ParsePersonFilter(request.Narrator, value => personNames.ParseNarrators(new[] { value }));
        var (seriesId, seriesName) = ParseSeriesFilter(request.Series);

        return new SearchFilterValues(
            NormalizeOrNull(request.Query),
            authorId,
            authorName,
            narratorId,
            narratorName,
            seriesId,
            seriesName,
            NormalizeSimple(request.Source),
            NormalizeSimple(request.AudioFormat),
            quality,
            qualityBitrate,
            request.Year is >= 1900 and <= 2200 ? request.Year : null,
            NormalizeSimple(request.Magnet),
            string.IsNullOrWhiteSpace(request.Sort) ? "seeders" : request.Sort.Trim(),
            Math.Clamp(request.Limit, 1, 250));
    }

    private static DynamicParameters BuildParameters(SearchFilterValues filters)
    {
        var parameters = new DynamicParameters();
        parameters.Add("QueryPattern", filters.Query is null ? null : $"%{filters.Query}%");
        parameters.Add("AuthorId", filters.AuthorId);
        parameters.Add("Author", filters.Author);
        parameters.Add("NarratorId", filters.NarratorId);
        parameters.Add("Narrator", filters.Narrator);
        parameters.Add("SeriesId", filters.SeriesId);
        parameters.Add("Series", filters.Series);
        parameters.Add("Source", filters.Source);
        parameters.Add("AudioFormat", filters.AudioFormat);
        parameters.Add("Quality", filters.Quality);
        parameters.Add("QualityBitrate", filters.QualityBitrate);
        parameters.Add("Year", filters.Year);
        parameters.Add("Magnet", filters.Magnet);
        parameters.Add("Limit", filters.Limit);
        return parameters;
    }

    private static string BuildWhere(SearchFilterValues filters, string? excludeFacet)
    {
        var clauses = new List<string>
        {
            "r.magnet_uri IS NOT NULL",
            "BTRIM(r.magnet_uri) <> ''"
        };

        if (filters.Query is not null)
            clauses.Add("r.search_text LIKE @QueryPattern");

        if (excludeFacet != "author" && (filters.AuthorId is not null || filters.Author is not null))
        {
            clauses.Add(filters.AuthorId is not null
                ? """
                  EXISTS (
                    SELECT 1 FROM release_people author_rp
                    WHERE author_rp.release_id = r.id
                      AND author_rp.role = 'author'
                      AND author_rp.person_id = @AuthorId)
                  """
                : """
                  EXISTS (
                    SELECT 1 FROM release_people author_rp
                    JOIN people author_p ON author_p.id = author_rp.person_id
                    LEFT JOIN person_aliases author_alias ON author_alias.person_id = author_p.id
                    WHERE author_rp.release_id = r.id
                      AND author_rp.role = 'author'
                      AND (author_p.normalized_name = @Author OR author_alias.normalized_alias = @Author))
                  """);
        }

        if (excludeFacet != "narrator" && (filters.NarratorId is not null || filters.Narrator is not null))
        {
            clauses.Add(filters.NarratorId is not null
                ? """
                  EXISTS (
                    SELECT 1 FROM release_people narrator_rp
                    WHERE narrator_rp.release_id = r.id
                      AND narrator_rp.role = 'narrator'
                      AND narrator_rp.person_id = @NarratorId)
                  """
                : """
                  EXISTS (
                    SELECT 1 FROM release_people narrator_rp
                    JOIN people narrator_p ON narrator_p.id = narrator_rp.person_id
                    LEFT JOIN person_aliases narrator_alias ON narrator_alias.person_id = narrator_p.id
                    WHERE narrator_rp.release_id = r.id
                      AND narrator_rp.role = 'narrator'
                      AND (narrator_p.normalized_name = @Narrator OR narrator_alias.normalized_alias = @Narrator))
                  """);
        }

        if (excludeFacet != "series" && (filters.SeriesId is not null || filters.Series is not null))
        {
            clauses.Add(filters.SeriesId is not null
                ? """
                  EXISTS (SELECT 1 FROM release_series selected_series
                    WHERE selected_series.release_id = r.id AND selected_series.series_id = @SeriesId)
                  """
                : """
                  EXISTS (
                    SELECT 1 FROM release_series selected_series
                    JOIN series_catalog selected_catalog ON selected_catalog.id = selected_series.series_id
                    LEFT JOIN series_aliases selected_alias ON selected_alias.series_id = selected_catalog.id
                    WHERE selected_series.release_id = r.id
                      AND (selected_catalog.normalized_name = @Series OR selected_alias.normalized_alias = @Series))
                  """);
        }
        if (excludeFacet != "source" && filters.Source is not null)
            clauses.Add("LOWER(r.source) = @Source");
        if (excludeFacet != "format" && filters.AudioFormat is not null)
            clauses.Add("LOWER(r.audio_format) = @AudioFormat");
        if (excludeFacet != "year" && filters.Year is not null)
            clauses.Add("r.release_year = @Year");

        if (excludeFacet != "quality" && filters.Quality is not null)
        {
            clauses.Add(filters.Quality == "lossless"
                ? "LOWER(COALESCE(r.audio_format, '')) IN ('flac', 'ape', 'alac', 'wav', 'wavpack', 'wv', 'lossless')"
                : "r.bitrate_kbps = @QualityBitrate");
        }

        if (excludeFacet != "magnet" && filters.Magnet is not null)
        {
            if (filters.Magnet == "yes") clauses.Add("r.magnet_uri IS NOT NULL AND BTRIM(r.magnet_uri) <> ''");
            else if (filters.Magnet == "no") clauses.Add("(r.magnet_uri IS NULL OR BTRIM(r.magnet_uri) = '')");
        }

        return string.Join(" AND ", clauses.Select(clause => $"({clause.Trim()})"));
    }

    private static string BuildOrderBy(string sort) => sort switch
    {
        "updatedAt" => "r.updated_at DESC, r.seeders DESC NULLS LAST, r.id DESC",
        "sizeBytes" => "r.size_bytes DESC NULLS LAST, r.seeders DESC NULLS LAST, r.id DESC",
        "title" => "r.normalized_title ASC, r.seeders DESC NULLS LAST, r.id DESC",
        _ => "r.seeders DESC NULLS LAST, r.updated_at DESC, r.id DESC"
    };

    private static IReadOnlyList<string> BuildFacetSql(SearchFilterValues filters)
    {
        var authorWhere = BuildWhere(filters, "author");
        var narratorWhere = BuildWhere(filters, "narrator");
        var seriesWhere = BuildWhere(filters, "series");
        var sourceWhere = BuildWhere(filters, "source");
        var formatWhere = BuildWhere(filters, "format");
        var qualityWhere = BuildWhere(filters, "quality");
        var yearWhere = BuildWhere(filters, "year");

        return
        [
            $"""
            SELECT 'p:' || p.id::text AS Value,
              p.display_name AS Label,
              COUNT(DISTINCT r.id)::bigint AS Count,
              CASE WHEN @QueryPattern IS NULL THEN FALSE ELSE
                p.normalized_name LIKE @QueryPattern OR EXISTS (
                  SELECT 1 FROM person_aliases query_alias
                  WHERE query_alias.person_id = p.id
                    AND query_alias.normalized_alias LIKE @QueryPattern)
              END AS MatchesQuery
            FROM audiobook_releases r
            JOIN release_people rp ON rp.release_id = r.id AND rp.role = 'author'
            JOIN people p ON p.id = rp.person_id
            WHERE {authorWhere}
            GROUP BY p.id, p.normalized_name, p.display_name
            ORDER BY MatchesQuery DESC, COUNT(DISTINCT r.id) DESC, p.display_name
            LIMIT 250;
            """,

            $"""
            SELECT 'p:' || p.id::text AS Value,
              p.display_name AS Label,
              COUNT(DISTINCT r.id)::bigint AS Count,
              CASE WHEN @QueryPattern IS NULL THEN FALSE ELSE
                p.normalized_name LIKE @QueryPattern OR EXISTS (
                  SELECT 1 FROM person_aliases query_alias
                  WHERE query_alias.person_id = p.id
                    AND query_alias.normalized_alias LIKE @QueryPattern)
              END AS MatchesQuery
            FROM audiobook_releases r
            JOIN release_people rp ON rp.release_id = r.id AND rp.role = 'narrator'
            JOIN people p ON p.id = rp.person_id
            WHERE {narratorWhere}
            GROUP BY p.id, p.normalized_name, p.display_name
            ORDER BY MatchesQuery DESC, COUNT(DISTINCT r.id) DESC, p.display_name
            LIMIT 250;
            """,

            $"""
            SELECT 's:' || catalog.id::text AS Value,
              catalog.display_name AS Label,
              COUNT(DISTINCT r.id)::bigint AS Count,
              CASE WHEN @QueryPattern IS NULL THEN FALSE ELSE
                catalog.normalized_name LIKE @QueryPattern OR EXISTS (
                  SELECT 1 FROM series_aliases query_alias
                  WHERE query_alias.series_id = catalog.id
                    AND query_alias.normalized_alias LIKE @QueryPattern)
              END AS MatchesQuery
            FROM audiobook_releases r
            JOIN release_series relation ON relation.release_id = r.id
            JOIN series_catalog catalog ON catalog.id = relation.series_id
            WHERE {seriesWhere}
            GROUP BY catalog.id, catalog.normalized_name, catalog.display_name
            ORDER BY MatchesQuery DESC, COUNT(DISTINCT r.id) DESC, catalog.display_name
            LIMIT 250;
            """,

            $"""
            SELECT LOWER(r.source) AS Value,
              MIN(r.source) AS Label,
              COUNT(*)::bigint AS Count,
              FALSE AS MatchesQuery
            FROM audiobook_releases r
            WHERE {sourceWhere}
            GROUP BY LOWER(r.source)
            ORDER BY COUNT(*) DESC, MIN(r.source);
            """,

            $"""
            SELECT LOWER(r.audio_format) AS Value,
              UPPER(MIN(r.audio_format)) AS Label,
              COUNT(*)::bigint AS Count,
              FALSE AS MatchesQuery
            FROM audiobook_releases r
            WHERE {formatWhere}
              AND r.audio_format IS NOT NULL
              AND BTRIM(r.audio_format) <> ''
            GROUP BY LOWER(r.audio_format)
            ORDER BY COUNT(*) DESC, UPPER(MIN(r.audio_format));
            """,

            $"""
            SELECT quality.Value, quality.Label, COUNT(*)::bigint AS Count,
              FALSE AS MatchesQuery
            FROM audiobook_releases r
            CROSS JOIN LATERAL (
              SELECT CASE
                  WHEN r.bitrate_kbps IS NOT NULL AND r.bitrate_kbps > 0
                    THEN 'bitrate:' || r.bitrate_kbps::text
                  WHEN LOWER(COALESCE(r.audio_format, '')) IN ('flac', 'ape', 'alac', 'wav', 'wavpack', 'wv', 'lossless')
                    THEN 'lossless'
                  ELSE NULL
                END AS Value,
                CASE
                  WHEN r.bitrate_kbps IS NOT NULL AND r.bitrate_kbps > 0
                    THEN r.bitrate_kbps::text || ' кбит/с'
                  WHEN LOWER(COALESCE(r.audio_format, '')) IN ('flac', 'ape', 'alac', 'wav', 'wavpack', 'wv', 'lossless')
                    THEN 'Без потерь'
                  ELSE NULL
                END AS Label
            ) quality
            WHERE {qualityWhere}
              AND quality.Value IS NOT NULL
            GROUP BY quality.Value, quality.Label
            ORDER BY CASE WHEN quality.Value = 'lossless' THEN 100000 ELSE SPLIT_PART(quality.Value, ':', 2)::int END DESC;
            """,

            $"""
            SELECT r.release_year::text AS Value,
              r.release_year::text AS Label,
              COUNT(*)::bigint AS Count,
              FALSE AS MatchesQuery
            FROM audiobook_releases r
            WHERE {yearWhere}
              AND r.release_year IS NOT NULL
            GROUP BY r.release_year
            ORDER BY r.release_year DESC;
            """
        ];
    }

    public async Task RefreshPeopleAsync(long releaseId, CancellationToken ct)
    {
        const string selectSql = """
        SELECT author AS Author, narrators AS Narrators, series AS Series,
          series_position AS SeriesPosition, raw_title AS RawTitle, title AS Title
        FROM audiobook_releases
        WHERE id = @ReleaseId;
        """;

        await using var db = new NpgsqlConnection(ConnectionString);
        await db.OpenAsync(ct);
        var row = await db.QuerySingleOrDefaultAsync<ReleaseFacetSource>(new CommandDefinition(
            selectSql,
            new { ReleaseId = releaseId },
            cancellationToken: ct));
        if (row is null) return;

        await using var transaction = await db.BeginTransactionAsync(ct);
        await canonicalFacets.SyncReleaseAsync(
            db,
            transaction,
            releaseId,
            row.Author,
            row.Narrators,
            row.Series,
            row.SeriesPosition,
            row.RawTitle,
            row.Title,
            ct);
        await transaction.CommitAsync(ct);
    }

    private async Task BackfillPeopleAsync(NpgsqlConnection db, CancellationToken ct)
    {
        const string migrationKey = "release-people-v1";
        var completed = await db.ExecuteScalarAsync<bool>(new CommandDefinition(
            "SELECT EXISTS(SELECT 1 FROM app_migrations WHERE migration_key = @MigrationKey);",
            new { MigrationKey = migrationKey },
            cancellationToken: ct));
        if (completed) return;

        logger.LogInformation("Начинается одноразовый индекс авторов и чтецов для фасетного поиска.");

        const string readSql = """
        SELECT id AS Id, author AS Author, narrators AS Narrators
        FROM audiobook_releases
        ORDER BY id;
        """;

        var releases = (await db.QueryAsync<PeopleBackfillRelease>(new CommandDefinition(readSql, cancellationToken: ct))).AsList();
        var people = new Dictionary<string, string>(StringComparer.Ordinal);
        var pendingLinks = new List<PendingPersonLink>();

        foreach (var release in releases)
        {
            AddPendingLinks(release.Id, AuthorRole, personNames.ParseAuthors(release.Author), people, pendingLinks);
            AddPendingLinks(release.Id, NarratorRole, personNames.ParseNarrators(release.Narrators), people, pendingLinks);
        }

        await using var transaction = await db.BeginTransactionAsync(ct);
        await db.ExecuteAsync(new CommandDefinition("DELETE FROM release_people;", transaction: transaction, cancellationToken: ct));

        foreach (var batch in people.Chunk(4000))
        {
            var displayNames = batch.Select(item => item.Value).ToArray();
            var normalizedNames = batch.Select(item => item.Key).ToArray();
            const string insertPeopleSql = """
            INSERT INTO people(display_name, normalized_name)
            SELECT stage.display_name, stage.normalized_name
            FROM UNNEST(@DisplayNames::text[], @NormalizedNames::text[])
              AS stage(display_name, normalized_name)
            ON CONFLICT(normalized_name) DO UPDATE SET
              display_name = CASE
                WHEN LENGTH(EXCLUDED.display_name) > LENGTH(people.display_name)
                  THEN EXCLUDED.display_name
                ELSE people.display_name
              END,
              updated_at = NOW();
            """;
            await db.ExecuteAsync(new CommandDefinition(
                insertPeopleSql,
                new { DisplayNames = displayNames, NormalizedNames = normalizedNames },
                transaction,
                cancellationToken: ct));
        }

        var personRows = await db.QueryAsync<PersonIdRow>(new CommandDefinition(
            "SELECT id AS Id, normalized_name AS NormalizedName FROM people;",
            transaction: transaction,
            cancellationToken: ct));
        var personIds = personRows.ToDictionary(row => row.NormalizedName, row => row.Id, StringComparer.Ordinal);

        var links = pendingLinks
            .Where(link => personIds.ContainsKey(link.NormalizedName))
            .Select(link => new PersonLink(link.ReleaseId, personIds[link.NormalizedName], link.Role, link.Position))
            .Distinct()
            .ToArray();

        foreach (var batch in links.Chunk(5000))
        {
            const string insertLinksSql = """
            INSERT INTO release_people(release_id, person_id, role, position)
            SELECT stage.release_id, stage.person_id, stage.role, stage.position
            FROM UNNEST(@ReleaseIds::bigint[], @PersonIds::bigint[], @Roles::text[], @Positions::int[])
              AS stage(release_id, person_id, role, position)
            ON CONFLICT(release_id, person_id, role) DO UPDATE SET
              position = LEAST(release_people.position, EXCLUDED.position);
            """;
            await db.ExecuteAsync(new CommandDefinition(
                insertLinksSql,
                new
                {
                    ReleaseIds = batch.Select(link => link.ReleaseId).ToArray(),
                    PersonIds = batch.Select(link => link.PersonId).ToArray(),
                    Roles = batch.Select(link => link.Role).ToArray(),
                    Positions = batch.Select(link => link.Position).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        await db.ExecuteAsync(new CommandDefinition(
            "INSERT INTO app_migrations(migration_key) VALUES (@MigrationKey) ON CONFLICT DO NOTHING;",
            new { MigrationKey = migrationKey },
            transaction,
            cancellationToken: ct));
        await transaction.CommitAsync(ct);
        logger.LogInformation(
            "Индекс авторов и чтецов построен: записей {ReleaseCount}, людей {PeopleCount}, связей {LinkCount}.",
            releases.Count,
            people.Count,
            links.Length);
    }

    private async Task SyncPeopleAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        string author,
        IEnumerable<string> narrators,
        CancellationToken ct)
    {
        await db.ExecuteAsync(new CommandDefinition(
            "DELETE FROM release_people WHERE release_id = @ReleaseId;",
            new { ReleaseId = releaseId },
            transaction,
            cancellationToken: ct));

        await InsertPeopleForRoleAsync(
            db, transaction, releaseId, AuthorRole, personNames.ParseAuthors(author), ct);
        await InsertPeopleForRoleAsync(
            db, transaction, releaseId, NarratorRole, personNames.ParseNarrators(narrators), ct);
    }

    private static async Task InsertPeopleForRoleAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        string role,
        IReadOnlyList<PersonNamePart> people,
        CancellationToken ct)
    {
        const string upsertPersonSql = """
        INSERT INTO people(display_name, normalized_name)
        VALUES (@DisplayName, @NormalizedName)
        ON CONFLICT(normalized_name) DO UPDATE SET
          display_name = CASE
            WHEN LENGTH(EXCLUDED.display_name) > LENGTH(people.display_name)
              THEN EXCLUDED.display_name
            ELSE people.display_name
          END,
          updated_at = NOW()
        RETURNING id;
        """;

        const string linkSql = """
        INSERT INTO release_people(release_id, person_id, role, position)
        VALUES (@ReleaseId, @PersonId, @Role, @Position)
        ON CONFLICT(release_id, person_id, role) DO UPDATE SET position = EXCLUDED.position;
        """;

        for (var index = 0; index < people.Count; index++)
        {
            var person = people[index];
            var personId = await db.ExecuteScalarAsync<long>(new CommandDefinition(
                upsertPersonSql,
                person,
                transaction,
                cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(
                linkSql,
                new { ReleaseId = releaseId, PersonId = personId, Role = role, Position = index + 1 },
                transaction,
                cancellationToken: ct));
        }
    }

    private static void AddPendingLinks(
        long releaseId,
        string role,
        IReadOnlyList<PersonNamePart> parsed,
        IDictionary<string, string> people,
        ICollection<PendingPersonLink> links)
    {
        for (var index = 0; index < parsed.Count; index++)
        {
            var person = parsed[index];
            if (!people.TryGetValue(person.NormalizedName, out var current) || person.DisplayName.Length > current.Length)
                people[person.NormalizedName] = person.DisplayName;
            links.Add(new PendingPersonLink(releaseId, person.NormalizedName, role, index + 1));
        }
    }

    private static (long? Id, string? Name) ParsePersonFilter(
        string? value,
        Func<string, IReadOnlyList<PersonNamePart>> parser)
    {
        if (string.IsNullOrWhiteSpace(value)) return (null, null);
        var trimmed = value.Trim();
        var idText = trimmed.StartsWith("p:", StringComparison.OrdinalIgnoreCase)
            ? trimmed[2..]
            : trimmed;
        if (long.TryParse(idText, out var id) && id > 0) return (id, null);
        return (null, parser(trimmed).FirstOrDefault()?.NormalizedName);
    }

    private (long? Id, string? Name) ParseSeriesFilter(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return (null, null);
        var trimmed = value.Trim();
        var idText = trimmed.StartsWith("s:", StringComparison.OrdinalIgnoreCase)
            ? trimmed[2..]
            : trimmed;
        if (long.TryParse(idText, out var id) && id > 0) return (id, null);
        return (null, seriesNames.Parse(trimmed)?.NormalizedName);
    }

    private string? NormalizeOrNull(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : NullIfEmpty(normalizer.Normalize(value));

    private static string? NormalizeSimple(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Trim().ToLowerInvariant();

    private static string? NullIfEmpty(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value;

    private static IReadOnlyList<FacetOption> ConvertFacets(IEnumerable<FacetDbRow> rows) => rows
        .Where(row => !string.IsNullOrWhiteSpace(row.Value) && !string.IsNullOrWhiteSpace(row.Label))
        .Select(row => new FacetOption(row.Value!, row.Label!, row.Count, row.MatchesQuery))
        .ToList();

    private static AudiobookSearchFacets EmptyFacets() => new([], [], [], [], [], [], []);

    private sealed record SearchFilterValues(
        string? Query,
        long? AuthorId,
        string? Author,
        long? NarratorId,
        string? Narrator,
        long? SeriesId,
        string? Series,
        string? Source,
        string? AudioFormat,
        string? Quality,
        int? QualityBitrate,
        int? Year,
        string? Magnet,
        string Sort,
        int Limit);

    private sealed class FacetDbRow
    {
        public string? Value { get; set; }
        public string? Label { get; set; }
        public long Count { get; set; }
        public bool MatchesQuery { get; set; }
    }

    private sealed class StatisticsSummary
    {
        public long Total { get; set; }
        public long AddedLast24Hours { get; set; }
        public long UpdatedLast24Hours { get; set; }
        public DateTimeOffset? RefreshedAt { get; set; }
    }


    private sealed class ReleaseFacetSource
    {
        public string Author { get; set; } = string.Empty;
        public string[] Narrators { get; set; } = [];
        public string? Series { get; set; }
        public decimal? SeriesPosition { get; set; }
        public string RawTitle { get; set; } = string.Empty;
        public string Title { get; set; } = string.Empty;
    }

    private sealed class ReleasePeopleSource
    {
        public string Author { get; set; } = string.Empty;
        public string[] Narrators { get; set; } = [];
    }

    private sealed class PeopleBackfillRelease
    {
        public long Id { get; set; }
        public string Author { get; set; } = string.Empty;
        public string[] Narrators { get; set; } = [];
    }

    private sealed class PersonIdRow
    {
        public long Id { get; set; }
        public string NormalizedName { get; set; } = string.Empty;
    }

    private sealed record PendingPersonLink(long ReleaseId, string NormalizedName, string Role, int Position);
    private sealed record PersonLink(long ReleaseId, long PersonId, string Role, int Position);
}
