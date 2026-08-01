using AudioBookRed.Api.Services;
using Dapper;
using Npgsql;

namespace AudioBookRed.Api.Data;

/// <summary>
/// Канонический каталог авторов, чтецов и серий. Исходные строки в
/// audiobook_releases сохраняются, а фасеты группируются по стабильным ID.
/// </summary>
public sealed class CanonicalFacetRepository(
    TitleNormalizer normalizer,
    PersonNameParser personNames,
    SeriesNameParser seriesNames,
    ILogger<CanonicalFacetRepository> logger)
{
    private const string MigrationKey = "canonical-facets-v2";

    public async Task InitializeAsync(NpgsqlConnection db, CancellationToken ct)
    {
        const string sql = """
        CREATE TABLE IF NOT EXISTS person_aliases (
          person_id BIGINT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
          alias_name TEXT NOT NULL,
          normalized_alias TEXT NOT NULL UNIQUE,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(person_id, normalized_alias)
        );
        CREATE INDEX IF NOT EXISTS ix_person_aliases_person
          ON person_aliases(person_id);
        CREATE INDEX IF NOT EXISTS ix_person_aliases_trgm
          ON person_aliases USING GIN(normalized_alias gin_trgm_ops);

        CREATE TABLE IF NOT EXISTS series_catalog (
          id BIGSERIAL PRIMARY KEY,
          display_name TEXT NOT NULL,
          normalized_name TEXT NOT NULL UNIQUE,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_series_catalog_trgm
          ON series_catalog USING GIN(normalized_name gin_trgm_ops);

        CREATE TABLE IF NOT EXISTS series_aliases (
          series_id BIGINT NOT NULL REFERENCES series_catalog(id) ON DELETE CASCADE,
          alias_name TEXT NOT NULL,
          normalized_alias TEXT NOT NULL UNIQUE,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(series_id, normalized_alias)
        );
        CREATE INDEX IF NOT EXISTS ix_series_aliases_series
          ON series_aliases(series_id);
        CREATE INDEX IF NOT EXISTS ix_series_aliases_trgm
          ON series_aliases USING GIN(normalized_alias gin_trgm_ops);

        CREATE TABLE IF NOT EXISTS release_series (
          release_id BIGINT PRIMARY KEY REFERENCES audiobook_releases(id) ON DELETE CASCADE,
          series_id BIGINT NOT NULL REFERENCES series_catalog(id) ON DELETE CASCADE,
          position NUMERIC(8,2) NULL,
          relation_type VARCHAR(16) NOT NULL DEFAULT 'primary'
        );
        CREATE INDEX IF NOT EXISTS ix_release_series_series
          ON release_series(series_id, release_id);

        CREATE TABLE IF NOT EXISTS app_migrations (
          migration_key TEXT PRIMARY KEY,
          completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        """;
        await db.ExecuteAsync(new CommandDefinition(sql, cancellationToken: ct));

        var completed = await db.ExecuteScalarAsync<bool>(new CommandDefinition(
            "SELECT EXISTS(SELECT 1 FROM app_migrations WHERE migration_key = @MigrationKey);",
            new { MigrationKey },
            cancellationToken: ct));
        if (!completed)
            await BackfillAsync(db, ct);
    }

    public async Task SyncReleaseAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        string author,
        IEnumerable<string> narrators,
        string? series,
        decimal? seriesPosition,
        string rawTitle,
        string currentTitle,
        CancellationToken ct)
    {
        var parsedRaw = TryParse(rawTitle);
        var canonicalSeries = ResolveSeries(series, seriesPosition, parsedRaw);
        var canonicalTitle = parsedRaw?.Series is not null && !string.IsNullOrWhiteSpace(parsedRaw.Title)
            ? parsedRaw.Title
            : currentTitle;

        await UpdateReleaseCanonicalFieldsAsync(
            db,
            transaction,
            releaseId,
            canonicalTitle,
            canonicalSeries,
            ct);

        await db.ExecuteAsync(new CommandDefinition(
            "DELETE FROM release_people WHERE release_id = @ReleaseId; DELETE FROM release_series WHERE release_id = @ReleaseId;",
            new { ReleaseId = releaseId },
            transaction,
            cancellationToken: ct));

        await InsertPeopleForRoleAsync(
            db,
            transaction,
            releaseId,
            "author",
            personNames.ParseAuthors(author),
            ct);
        await InsertPeopleForRoleAsync(
            db,
            transaction,
            releaseId,
            "narrator",
            personNames.ParseNarrators(narrators),
            ct);

        if (canonicalSeries is not null)
            await InsertSeriesAsync(db, transaction, releaseId, canonicalSeries, series, ct);

        await RefreshSearchTextAsync(db, transaction, releaseId, ct);
    }

    private async Task BackfillAsync(NpgsqlConnection db, CancellationToken ct)
    {
        logger.LogInformation("Начинается канонизация авторов, чтецов и серий.");

        const string readSql = """
        SELECT id AS Id,
          raw_title AS RawTitle,
          title AS Title,
          author AS Author,
          series AS Series,
          series_position AS SeriesPosition,
          narrators AS Narrators
        FROM audiobook_releases
        ORDER BY id;
        """;
        var releases = (await db.QueryAsync<FacetBackfillRelease>(new CommandDefinition(
            readSql,
            cancellationToken: ct))).AsList();

        var people = new Dictionary<string, string>(StringComparer.Ordinal);
        var personAliases = new Dictionary<string, PendingAlias>(StringComparer.Ordinal);
        var personLinks = new List<PendingPersonLink>();
        var seriesCatalog = new Dictionary<string, string>(StringComparer.Ordinal);
        var seriesAliases = new Dictionary<string, PendingAlias>(StringComparer.Ordinal);
        var seriesLinks = new List<PendingSeriesLink>();
        var releaseUpdates = new List<PendingReleaseUpdate>(releases.Count);

        foreach (var release in releases)
        {
            var parsedRaw = TryParse(release.RawTitle);
            var canonicalSeries = ResolveSeries(release.Series, release.SeriesPosition, parsedRaw);
            var canonicalTitle = parsedRaw?.Series is not null && !string.IsNullOrWhiteSpace(parsedRaw.Title)
                ? parsedRaw.Title
                : release.Title;

            releaseUpdates.Add(new PendingReleaseUpdate(
                release.Id,
                canonicalTitle,
                normalizer.Normalize(canonicalTitle),
                normalizer.Normalize(release.Author),
                canonicalSeries?.DisplayName,
                canonicalSeries?.NormalizedName,
                canonicalSeries?.Position));

            AddPeople(
                release.Id,
                "author",
                personNames.ParseAuthors(release.Author),
                people,
                personAliases,
                personLinks);
            AddPeople(
                release.Id,
                "narrator",
                personNames.ParseNarrators(release.Narrators),
                people,
                personAliases,
                personLinks);

            if (canonicalSeries is not null)
            {
                if (!seriesCatalog.TryGetValue(canonicalSeries.NormalizedName, out var current) ||
                    BetterSeriesDisplay(canonicalSeries.DisplayName, current))
                {
                    seriesCatalog[canonicalSeries.NormalizedName] = canonicalSeries.DisplayName;
                }

                AddAlias(
                    seriesAliases,
                    canonicalSeries.AliasNormalizedName,
                    canonicalSeries.AliasName,
                    canonicalSeries.NormalizedName);
                AddAlias(
                    seriesAliases,
                    canonicalSeries.NormalizedName,
                    canonicalSeries.DisplayName,
                    canonicalSeries.NormalizedName);
                var originalSeries = seriesNames.Parse(release.Series, release.SeriesPosition);
                if (originalSeries is not null)
                {
                    AddAlias(
                        seriesAliases,
                        originalSeries.AliasNormalizedName,
                        originalSeries.AliasName,
                        canonicalSeries.NormalizedName);
                }
                seriesLinks.Add(new PendingSeriesLink(
                    release.Id,
                    canonicalSeries.NormalizedName,
                    canonicalSeries.Position));
            }
        }

        await using var transaction = await db.BeginTransactionAsync(ct);
        await db.ExecuteAsync(new CommandDefinition(
            """
            DELETE FROM release_people;
            DELETE FROM release_series;
            DELETE FROM person_aliases;
            DELETE FROM series_aliases;
            DELETE FROM people;
            DELETE FROM series_catalog;
            DROP INDEX IF EXISTS ix_audiobook_search_text_trgm;
            """,
            transaction: transaction,
            cancellationToken: ct));

        foreach (var batch in releaseUpdates.Chunk(2000))
        {
            const string updateSql = """
            UPDATE audiobook_releases release
            SET title = stage.title,
                normalized_title = stage.normalized_title,
                normalized_author = stage.normalized_author,
                series = stage.series,
                normalized_series = stage.normalized_series,
                series_position = stage.series_position
            FROM UNNEST(
              @Ids::bigint[],
              @Titles::text[],
              @NormalizedTitles::text[],
              @NormalizedAuthors::text[],
              @Series::text[],
              @NormalizedSeries::text[],
              @SeriesPositions::numeric[])
              AS stage(id, title, normalized_title, normalized_author, series, normalized_series, series_position)
            WHERE release.id = stage.id;
            """;
            await db.ExecuteAsync(new CommandDefinition(
                updateSql,
                new
                {
                    Ids = batch.Select(item => item.ReleaseId).ToArray(),
                    Titles = batch.Select(item => item.Title).ToArray(),
                    NormalizedTitles = batch.Select(item => item.NormalizedTitle).ToArray(),
                    NormalizedAuthors = batch.Select(item => item.NormalizedAuthor).ToArray(),
                    Series = batch.Select(item => item.Series).ToArray(),
                    NormalizedSeries = batch.Select(item => item.NormalizedSeries).ToArray(),
                    SeriesPositions = batch.Select(item => item.SeriesPosition).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        foreach (var batch in people.Chunk(4000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO people(display_name, normalized_name)
                SELECT stage.display_name, stage.normalized_name
                FROM UNNEST(@DisplayNames::text[], @NormalizedNames::text[])
                  AS stage(display_name, normalized_name);
                """,
                new
                {
                    DisplayNames = batch.Select(item => item.Value).ToArray(),
                    NormalizedNames = batch.Select(item => item.Key).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        var personRows = (await db.QueryAsync<IdByName>(new CommandDefinition(
            "SELECT id AS Id, normalized_name AS NormalizedName FROM people;",
            transaction: transaction,
            cancellationToken: ct))).ToDictionary(row => row.NormalizedName, row => row.Id, StringComparer.Ordinal);

        foreach (var batch in personAliases.Values
                     .Where(alias => personRows.ContainsKey(alias.CanonicalName))
                     .Chunk(5000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO person_aliases(person_id, alias_name, normalized_alias)
                SELECT stage.person_id, stage.alias_name, stage.normalized_alias
                FROM UNNEST(@PersonIds::bigint[], @AliasNames::text[], @NormalizedAliases::text[])
                  AS stage(person_id, alias_name, normalized_alias)
                ON CONFLICT(normalized_alias) DO NOTHING;
                """,
                new
                {
                    PersonIds = batch.Select(alias => personRows[alias.CanonicalName]).ToArray(),
                    AliasNames = batch.Select(alias => alias.AliasName).ToArray(),
                    NormalizedAliases = batch.Select(alias => alias.NormalizedAlias).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        foreach (var batch in personLinks
                     .Where(link => personRows.ContainsKey(link.CanonicalName))
                     .Chunk(5000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO release_people(release_id, person_id, role, position)
                SELECT stage.release_id, stage.person_id, stage.role, stage.position
                FROM UNNEST(@ReleaseIds::bigint[], @PersonIds::bigint[], @Roles::text[], @Positions::int[])
                  AS stage(release_id, person_id, role, position)
                ON CONFLICT(release_id, person_id, role) DO UPDATE SET
                  position = LEAST(release_people.position, EXCLUDED.position);
                """,
                new
                {
                    ReleaseIds = batch.Select(link => link.ReleaseId).ToArray(),
                    PersonIds = batch.Select(link => personRows[link.CanonicalName]).ToArray(),
                    Roles = batch.Select(link => link.Role).ToArray(),
                    Positions = batch.Select(link => link.Position).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        foreach (var batch in seriesCatalog.Chunk(4000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO series_catalog(display_name, normalized_name)
                SELECT stage.display_name, stage.normalized_name
                FROM UNNEST(@DisplayNames::text[], @NormalizedNames::text[])
                  AS stage(display_name, normalized_name);
                """,
                new
                {
                    DisplayNames = batch.Select(item => item.Value).ToArray(),
                    NormalizedNames = batch.Select(item => item.Key).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        var seriesRows = (await db.QueryAsync<IdByName>(new CommandDefinition(
            "SELECT id AS Id, normalized_name AS NormalizedName FROM series_catalog;",
            transaction: transaction,
            cancellationToken: ct))).ToDictionary(row => row.NormalizedName, row => row.Id, StringComparer.Ordinal);

        foreach (var batch in seriesAliases.Values
                     .Where(alias => seriesRows.ContainsKey(alias.CanonicalName))
                     .Chunk(5000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO series_aliases(series_id, alias_name, normalized_alias)
                SELECT stage.series_id, stage.alias_name, stage.normalized_alias
                FROM UNNEST(@SeriesIds::bigint[], @AliasNames::text[], @NormalizedAliases::text[])
                  AS stage(series_id, alias_name, normalized_alias)
                ON CONFLICT(normalized_alias) DO NOTHING;
                """,
                new
                {
                    SeriesIds = batch.Select(alias => seriesRows[alias.CanonicalName]).ToArray(),
                    AliasNames = batch.Select(alias => alias.AliasName).ToArray(),
                    NormalizedAliases = batch.Select(alias => alias.NormalizedAlias).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        foreach (var batch in seriesLinks
                     .Where(link => seriesRows.ContainsKey(link.CanonicalName))
                     .Chunk(5000))
        {
            await db.ExecuteAsync(new CommandDefinition(
                """
                INSERT INTO release_series(release_id, series_id, position)
                SELECT stage.release_id, stage.series_id, stage.position
                FROM UNNEST(@ReleaseIds::bigint[], @SeriesIds::bigint[], @Positions::numeric[])
                  AS stage(release_id, series_id, position)
                ON CONFLICT(release_id) DO UPDATE SET
                  series_id = EXCLUDED.series_id,
                  position = EXCLUDED.position;
                """,
                new
                {
                    ReleaseIds = batch.Select(link => link.ReleaseId).ToArray(),
                    SeriesIds = batch.Select(link => seriesRows[link.CanonicalName]).ToArray(),
                    Positions = batch.Select(link => link.Position).ToArray()
                },
                transaction,
                cancellationToken: ct));
        }

        await RefreshAllSearchTextAsync(db, transaction, ct);
        await db.ExecuteAsync(new CommandDefinition(
            "CREATE INDEX IF NOT EXISTS ix_audiobook_search_text_trgm ON audiobook_releases USING GIN(search_text gin_trgm_ops);",
            transaction: transaction,
            commandTimeout: 300,
            cancellationToken: ct));
        await db.ExecuteAsync(new CommandDefinition(
            "INSERT INTO app_migrations(migration_key) VALUES (@MigrationKey) ON CONFLICT DO NOTHING;",
            new { MigrationKey },
            transaction,
            cancellationToken: ct));
        await transaction.CommitAsync(ct);

        logger.LogInformation(
            "Канонические фасеты построены: книг {ReleaseCount}, людей {PeopleCount}, серий {SeriesCount}, связей людей {PersonLinks}, связей серий {SeriesLinks}.",
            releases.Count,
            people.Count,
            seriesCatalog.Count,
            personLinks.Count,
            seriesLinks.Count);
    }

    private async Task InsertPeopleForRoleAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        string role,
        IReadOnlyList<PersonNamePart> parsed,
        CancellationToken ct)
    {
        const string upsertPersonSql = """
        INSERT INTO people(display_name, normalized_name)
        VALUES (@DisplayName, @NormalizedName)
        ON CONFLICT(normalized_name) DO UPDATE SET
          display_name = CASE
            WHEN LENGTH(EXCLUDED.display_name) < LENGTH(people.display_name)
              THEN EXCLUDED.display_name
            ELSE people.display_name
          END,
          updated_at = NOW()
        RETURNING id;
        """;
        const string aliasSql = """
        INSERT INTO person_aliases(person_id, alias_name, normalized_alias)
        VALUES (@PersonId, @AliasName, @AliasNormalizedName)
        ON CONFLICT(normalized_alias) DO NOTHING;
        """;
        const string linkSql = """
        INSERT INTO release_people(release_id, person_id, role, position)
        VALUES (@ReleaseId, @PersonId, @Role, @Position)
        ON CONFLICT(release_id, person_id, role) DO UPDATE SET position = EXCLUDED.position;
        """;

        for (var index = 0; index < parsed.Count; index++)
        {
            var person = parsed[index];
            var personId = await db.ExecuteScalarAsync<long>(new CommandDefinition(
                upsertPersonSql,
                person,
                transaction,
                cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(
                aliasSql,
                new
                {
                    PersonId = personId,
                    person.AliasName,
                    person.AliasNormalizedName
                },
                transaction,
                cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(
                aliasSql,
                new
                {
                    PersonId = personId,
                    AliasName = person.DisplayName,
                    AliasNormalizedName = person.NormalizedName
                },
                transaction,
                cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(
                aliasSql,
                new
                {
                    PersonId = personId,
                    AliasName = person.DisplayName,
                    AliasNormalizedName = normalizer.Normalize(person.DisplayName)
                },
                transaction,
                cancellationToken: ct));
            await db.ExecuteAsync(new CommandDefinition(
                linkSql,
                new { ReleaseId = releaseId, PersonId = personId, Role = role, Position = index + 1 },
                transaction,
                cancellationToken: ct));
        }
    }

    private async Task InsertSeriesAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        SeriesNamePart series,
        string? sourceSeries,
        CancellationToken ct)
    {
        const string upsertSql = """
        INSERT INTO series_catalog(display_name, normalized_name)
        VALUES (@DisplayName, @NormalizedName)
        ON CONFLICT(normalized_name) DO UPDATE SET
          display_name = CASE
            WHEN LENGTH(EXCLUDED.display_name) < LENGTH(series_catalog.display_name)
              THEN EXCLUDED.display_name
            ELSE series_catalog.display_name
          END,
          updated_at = NOW()
        RETURNING id;
        """;
        var seriesId = await db.ExecuteScalarAsync<long>(new CommandDefinition(
            upsertSql,
            series,
            transaction,
            cancellationToken: ct));

        const string aliasSql = """
        INSERT INTO series_aliases(series_id, alias_name, normalized_alias)
        VALUES (@SeriesId, @AliasName, @NormalizedAlias)
        ON CONFLICT(normalized_alias) DO NOTHING;
        """;
        await db.ExecuteAsync(new CommandDefinition(
            aliasSql,
            new
            {
                SeriesId = seriesId,
                AliasName = series.AliasName,
                NormalizedAlias = series.AliasNormalizedName
            },
            transaction,
            cancellationToken: ct));
        await db.ExecuteAsync(new CommandDefinition(
            aliasSql,
            new
            {
                SeriesId = seriesId,
                AliasName = series.DisplayName,
                NormalizedAlias = series.NormalizedName
            },
            transaction,
            cancellationToken: ct));

        var sourceAlias = seriesNames.Parse(sourceSeries, series.Position);
        if (sourceAlias is not null)
        {
            await db.ExecuteAsync(new CommandDefinition(
                aliasSql,
                new
                {
                    SeriesId = seriesId,
                    AliasName = sourceAlias.AliasName,
                    NormalizedAlias = sourceAlias.AliasNormalizedName
                },
                transaction,
                cancellationToken: ct));
        }

        await db.ExecuteAsync(new CommandDefinition(
            """
            INSERT INTO release_series(release_id, series_id, position)
            VALUES (@ReleaseId, @SeriesId, @Position)
            ON CONFLICT(release_id) DO UPDATE SET
              series_id = EXCLUDED.series_id,
              position = EXCLUDED.position;
            """,
            new { ReleaseId = releaseId, SeriesId = seriesId, Position = series.Position },
            transaction,
            cancellationToken: ct));
    }

    private async Task UpdateReleaseCanonicalFieldsAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        string title,
        SeriesNamePart? series,
        CancellationToken ct)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            UPDATE audiobook_releases
            SET title = @Title,
                normalized_title = @NormalizedTitle,
                series = @Series,
                normalized_series = @NormalizedSeries,
                series_position = @SeriesPosition
            WHERE id = @ReleaseId;
            """,
            new
            {
                ReleaseId = releaseId,
                Title = title,
                NormalizedTitle = normalizer.Normalize(title),
                Series = series?.DisplayName,
                NormalizedSeries = series?.NormalizedName,
                SeriesPosition = series?.Position
            },
            transaction,
            cancellationToken: ct));
    }

    private static async Task RefreshSearchTextAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        long releaseId,
        CancellationToken ct)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            UPDATE audiobook_releases release
            SET search_text = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(CONCAT_WS(' ',
              release.title,
              release.author,
              release.series,
              ARRAY_TO_STRING(release.narrators, ' '),
              release.raw_title,
              (SELECT STRING_AGG(DISTINCT aliases.alias_name, ' ')
               FROM release_people rp
               JOIN people p ON p.id = rp.person_id
               LEFT JOIN LATERAL (
                 SELECT p.display_name AS alias_name
                 UNION ALL
                 SELECT pa.alias_name FROM person_aliases pa WHERE pa.person_id = p.id
               ) aliases ON TRUE
               WHERE rp.release_id = release.id),
              (SELECT STRING_AGG(DISTINCT aliases.alias_name, ' ')
               FROM release_series rs
               JOIN series_catalog s ON s.id = rs.series_id
               LEFT JOIN LATERAL (
                 SELECT s.display_name AS alias_name
                 UNION ALL
                 SELECT sa.alias_name FROM series_aliases sa WHERE sa.series_id = s.id
               ) aliases ON TRUE
               WHERE rs.release_id = release.id)), '\s+', ' ', 'g')), 'ё', 'е'))
            WHERE release.id = @ReleaseId;
            """,
            new { ReleaseId = releaseId },
            transaction,
            cancellationToken: ct));
    }

    private static async Task RefreshAllSearchTextAsync(
        NpgsqlConnection db,
        NpgsqlTransaction transaction,
        CancellationToken ct)
    {
        await db.ExecuteAsync(new CommandDefinition(
            """
            UPDATE audiobook_releases release
            SET search_text = LOWER(REPLACE(BTRIM(REGEXP_REPLACE(CONCAT_WS(' ',
              release.title,
              release.author,
              release.series,
              ARRAY_TO_STRING(release.narrators, ' '),
              release.raw_title,
              (SELECT STRING_AGG(DISTINCT aliases.alias_name, ' ')
               FROM release_people rp
               JOIN people p ON p.id = rp.person_id
               LEFT JOIN LATERAL (
                 SELECT p.display_name AS alias_name
                 UNION ALL
                 SELECT pa.alias_name FROM person_aliases pa WHERE pa.person_id = p.id
               ) aliases ON TRUE
               WHERE rp.release_id = release.id),
              (SELECT STRING_AGG(DISTINCT aliases.alias_name, ' ')
               FROM release_series rs
               JOIN series_catalog s ON s.id = rs.series_id
               LEFT JOIN LATERAL (
                 SELECT s.display_name AS alias_name
                 UNION ALL
                 SELECT sa.alias_name FROM series_aliases sa WHERE sa.series_id = s.id
               ) aliases ON TRUE
               WHERE rs.release_id = release.id)), '\s+', ' ', 'g')), 'ё', 'е'));
            """,
            transaction: transaction,
            commandTimeout: 300,
            cancellationToken: ct));
    }

    private AudioBookRed.Api.Models.ParsedAudiobookTitle? TryParse(string rawTitle)
    {
        try
        {
            return normalizer.Parse(rawTitle);
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Не удалось повторно разобрать raw_title при канонизации: {RawTitle}", rawTitle);
            return null;
        }
    }

    private SeriesNamePart? ResolveSeries(
        string? currentSeries,
        decimal? currentPosition,
        AudioBookRed.Api.Models.ParsedAudiobookTitle? parsedRaw)
    {
        if (!string.IsNullOrWhiteSpace(parsedRaw?.Series))
            return seriesNames.Parse(parsedRaw.Series, parsedRaw.SeriesPosition);
        return seriesNames.Parse(currentSeries, currentPosition);
    }

    private void AddPeople(
        long releaseId,
        string role,
        IReadOnlyList<PersonNamePart> parsed,
        IDictionary<string, string> people,
        IDictionary<string, PendingAlias> aliases,
        ICollection<PendingPersonLink> links)
    {
        for (var index = 0; index < parsed.Count; index++)
        {
            var person = parsed[index];
            if (!people.TryGetValue(person.NormalizedName, out var current) ||
                BetterPersonDisplay(person.DisplayName, current))
            {
                people[person.NormalizedName] = person.DisplayName;
            }

            AddAlias(aliases, person.AliasNormalizedName, person.AliasName, person.NormalizedName);
            AddAlias(aliases, person.NormalizedName, person.DisplayName, person.NormalizedName);
            AddAlias(
                aliases,
                normalizer.Normalize(person.DisplayName),
                person.DisplayName,
                person.NormalizedName);
            links.Add(new PendingPersonLink(
                releaseId,
                person.NormalizedName,
                role,
                index + 1));
        }
    }

    private static void AddAlias(
        IDictionary<string, PendingAlias> aliases,
        string normalizedAlias,
        string aliasName,
        string canonicalName)
    {
        if (string.IsNullOrWhiteSpace(normalizedAlias)) return;
        if (!aliases.TryGetValue(normalizedAlias, out var current) || aliasName.Length < current.AliasName.Length)
            aliases[normalizedAlias] = new PendingAlias(aliasName, normalizedAlias, canonicalName);
    }

    private static bool BetterPersonDisplay(string candidate, string current) =>
        DisplayPenalty(candidate) < DisplayPenalty(current) ||
        (DisplayPenalty(candidate) == DisplayPenalty(current) && candidate.Length < current.Length);

    private static bool BetterSeriesDisplay(string candidate, string current) =>
        DisplayPenalty(candidate) < DisplayPenalty(current) ||
        (DisplayPenalty(candidate) == DisplayPenalty(current) && candidate.Length < current.Length);

    private static int DisplayPenalty(string value)
    {
        var penalty = 0;
        if (value.Contains(" и др", StringComparison.OrdinalIgnoreCase) ||
            value.Contains(" и другие", StringComparison.OrdinalIgnoreCase)) penalty += 100;
        if (value.All(ch => !char.IsLetter(ch) || char.IsUpper(ch))) penalty += 20;
        if (value.All(ch => !char.IsLetter(ch) || char.IsLower(ch))) penalty += 10;
        if (value.Any(char.IsDigit)) penalty += 3;
        return penalty;
    }

    private sealed class FacetBackfillRelease
    {
        public long Id { get; set; }
        public string RawTitle { get; set; } = string.Empty;
        public string Title { get; set; } = string.Empty;
        public string Author { get; set; } = string.Empty;
        public string? Series { get; set; }
        public decimal? SeriesPosition { get; set; }
        public string[] Narrators { get; set; } = [];
    }

    private sealed class IdByName
    {
        public long Id { get; set; }
        public string NormalizedName { get; set; } = string.Empty;
    }

    private sealed record PendingAlias(string AliasName, string NormalizedAlias, string CanonicalName);
    private sealed record PendingPersonLink(long ReleaseId, string CanonicalName, string Role, int Position);
    private sealed record PendingSeriesLink(long ReleaseId, string CanonicalName, decimal? Position);
    private sealed record PendingReleaseUpdate(
        long ReleaseId,
        string Title,
        string NormalizedTitle,
        string NormalizedAuthor,
        string? Series,
        string? NormalizedSeries,
        decimal? SeriesPosition);
}
