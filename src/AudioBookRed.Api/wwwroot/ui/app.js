(() => {
  'use strict';

  const API_KEY_STORAGE = 'audiobookred_api_key';
  const QUERY_STORAGE = 'audiobookred_ui_query';
  const SORT_STORAGE = 'audiobookred_ui_sort';

  const els = {
    form: document.querySelector('#search'),
    q: document.querySelector('#q'),
    limit: document.querySelector('#limit'),
    filterArea: document.querySelector('#filterArea'),
    authorFilter: document.querySelector('#authorFilter'),
    narratorFilter: document.querySelector('#narratorFilter'),
    seriesFilter: document.querySelector('#seriesFilter'),
    sourceFilter: document.querySelector('#sourceFilter'),
    formatFilter: document.querySelector('#formatFilter'),
    qualityFilter: document.querySelector('#qualityFilter'),
    yearFilter: document.querySelector('#yearFilter'),
    magnetFilter: document.querySelector('#magnetFilter'),
    resetFilters: document.querySelector('#resetFilters'),
    sortBar: document.querySelector('#sortBar'),
    facetHint: document.querySelector('#facetHint'),
    summaryRow: document.querySelector('#summaryRow'),
    results: document.querySelector('#results'),
    welcome: document.querySelector('#welcome'),
    loading: document.querySelector('#loading'),
    noresults: document.querySelector('#noresults'),
    error: document.querySelector('#error'),
    summary: document.querySelector('#summary'),
    apiStatus: document.querySelector('#apiStatus'),
    databaseStats: document.querySelector('#databaseStats'),
    databaseStatsDetails: document.querySelector('#databaseStatsDetails'),
    apiKeyButton: document.querySelector('#apiKeyButton'),
    reloadButton: document.querySelector('#reloadButton'),
    template: document.querySelector('#releaseTemplate')
  };

  const filterControls = {
    author: els.authorFilter,
    narrator: els.narratorFilter,
    series: els.seriesFilter,
    source: els.sourceFilter,
    audioFormat: els.formatFilter,
    quality: els.qualityFilter,
    year: els.yearFilter,
    magnet: els.magnetFilter
  };

  let rows = [];
  let total = 0;
  let lastRequest = null;
  let hasSearched = false;
  let requestSequence = 0;

  function setVisible(element, visible) {
    element.hidden = !visible;
  }

  function setApiStatus(kind, text) {
    els.apiStatus.classList.remove('ok', 'bad');
    if (kind) els.apiStatus.classList.add(kind);
    els.apiStatus.textContent = text;
  }

  function getApiKey() {
    return localStorage.getItem(API_KEY_STORAGE) || '';
  }

  function sourceLabel(source) {
    const value = String(source ?? '').trim();
    const known = { rutracker: 'RuTracker' };
    return known[value.toLocaleLowerCase('ru-RU')] || value;
  }

  function formatCount(value) {
    return new Intl.NumberFormat('ru-RU').format(Number(value) || 0);
  }

  function formatStatsDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'
    }).format(date);
  }

  function renderDatabaseStatsDetails(data) {
    const sources = Array.isArray(data.sources) ? data.sources : [];
    const fragment = document.createDocumentFragment();

    for (const source of sources) {
      const row = document.createElement('div');
      row.className = 'database-source-stat';

      const heading = document.createElement('strong');
      heading.textContent = sourceLabel(source.source);
      row.append(heading);

      const lines = [
        `Всего: ${formatCount(source.count)}`,
        `Добавлено за 24 ч: ${formatCount(source.addedLast24Hours)}`,
        `Обновлено за 24 ч: ${formatCount(source.updatedLast24Hours)}`,
        `Очередь: ${formatCount((source.pendingJobs || 0) + (source.retryJobs || 0))}`,
        `В работе: ${formatCount(source.runningJobs)}`,
        `Ошибки: ${formatCount(source.failedJobs)}`,
        `Последний успешный обход: ${formatStatsDate(source.lastSuccessfulCrawlAt)}`
      ];

      const text = document.createElement('span');
      text.textContent = lines.join(' · ');
      row.append(text);
      fragment.append(row);
    }

    const refreshed = document.createElement('div');
    refreshed.className = 'database-stats-refreshed';
    refreshed.textContent = `Статистика обновлена: ${formatStatsDate(data.refreshedAt)}`;
    fragment.append(refreshed);
    els.databaseStatsDetails.replaceChildren(fragment);
  }

  async function loadDatabaseStats() {
    const key = getApiKey();
    if (!key) {
      setVisible(els.databaseStats, false);
      setVisible(els.databaseStatsDetails, false);
      return;
    }

    try {
      const response = await fetch('/api/v1/stats', {
        headers: { 'X-Api-Key': key },
        cache: 'no-store'
      });

      if (response.status === 401) {
        setApiStatus('bad', 'ключ отклонён');
        setVisible(els.databaseStats, false);
        setVisible(els.databaseStatsDetails, false);
        return;
      }

      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      const sources = Array.isArray(data.sources) ? data.sources : [];
      const parts = [`В базе: ${formatCount(data.total)}`];

      for (const source of sources) {
        parts.push(`${sourceLabel(source.source)}: ${formatCount(source.count)}`);
      }
      if (Number(data.addedLast24Hours) > 0) {
        parts.push(`+${formatCount(data.addedLast24Hours)} за сутки`);
      }

      els.databaseStats.textContent = parts.join(' · ');
      els.databaseStats.title = 'Показать статистику базы и источников';
      renderDatabaseStatsDetails(data);
      setVisible(els.databaseStats, true);
    } catch {
      setVisible(els.databaseStats, false);
      setVisible(els.databaseStatsDetails, false);
    }
  }

  function requestApiKey(message = 'Введите API_KEY AudioBookRed:') {
    const current = getApiKey();
    const entered = window.prompt(message, current);
    if (entered === null) return null;

    const clean = entered.trim();
    if (clean) {
      localStorage.setItem(API_KEY_STORAGE, clean);
      setApiStatus('', 'ключ сохранён');
    } else {
      localStorage.removeItem(API_KEY_STORAGE);
      setApiStatus('bad', 'нет ключа');
    }
    return clean;
  }

  function getNarrators(row) {
    if (Array.isArray(row.narrators)) {
      return row.narrators.map(value => String(value).trim()).filter(Boolean);
    }
    if (typeof row.narrator === 'string' && row.narrator.trim()) {
      return row.narrator.split(/[,;/]/).map(value => value.trim()).filter(Boolean);
    }
    return [];
  }

  function resetResultFilters({ resetSort = false } = {}) {
    Object.values(filterControls).forEach(select => { select.value = ''; });

    if (resetSort) {
      const defaultSort = document.querySelector('input[name="sort"][value="seeders"]');
      if (defaultSort) defaultSort.checked = true;
      localStorage.setItem(SORT_STORAGE, 'seeders');
    }
  }

  function showWelcome() {
    rows = [];
    total = 0;
    lastRequest = null;
    hasSearched = false;
    els.results.replaceChildren();
    setVisible(els.welcome, true);
    setVisible(els.filterArea, false);
    setVisible(els.sortBar, false);
    setVisible(els.facetHint, false);
    setVisible(els.summaryRow, false);
    setVisible(els.loading, false);
    setVisible(els.noresults, false);
    setVisible(els.error, false);
  }

  function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return null;
    const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const amount = bytes / (1024 ** index);
    return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
  }

  function formatDate(value) {
    if (!value) return null;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    return new Intl.DateTimeFormat('ru-RU', {
      year: 'numeric', month: '2-digit', day: '2-digit'
    }).format(date);
  }

  function appendText(parent, text, className) {
    const span = document.createElement('span');
    if (className) span.className = className;
    span.textContent = text;
    parent.append(span);
    return span;
  }

  function makeChip(text) {
    const chip = document.createElement('span');
    chip.className = 'meta-chip';
    chip.textContent = text;
    return chip;
  }

  function buildSubtitle(row) {
    const parts = [];
    if (row.author) parts.push(String(row.author).trim());
    if (row.series) {
      parts.push(row.seriesPosition != null
        ? `${String(row.series).trim()} #${row.seriesPosition}`
        : String(row.series).trim());
    }

    const narrators = [...new Set(getNarrators(row).map(value => value.toLocaleLowerCase('ru-RU')))]
      .map(key => getNarrators(row).find(value => value.toLocaleLowerCase('ru-RU') === key));
    if (narrators.length) parts.push(`читает: ${narrators.join(', ')}`);
    return parts.join(' · ');
  }

  function renderRow(row) {
    const fragment = els.template.content.cloneNode(true);
    const card = fragment.querySelector('.release-card');
    const title = fragment.querySelector('.release-title');
    const source = fragment.querySelector('.source-badge');
    const subtitle = fragment.querySelector('.release-subtitle');
    const meta = fragment.querySelector('.release-meta');
    const stats = fragment.querySelector('.stats');
    const actions = fragment.querySelector('.actions');

    title.textContent = row.title || row.rawTitle || `Запись #${row.id}`;
    if (row.sourceUrl) {
      title.href = row.sourceUrl;
    } else {
      title.removeAttribute('href');
      title.classList.add('disabled-link');
    }

    source.textContent = sourceLabel(row.source || 'unknown');
    subtitle.textContent = buildSubtitle(row);

    const chips = [];
    if (row.releaseYear) chips.push(String(row.releaseYear));
    if (row.audioFormat) chips.push(row.audioFormat);
    if (row.bitrateKbps) chips.push(`${row.bitrateKbps} кбит/с`);
    if (row.language) chips.push(row.language);
    if (row.isAbridged === true) chips.push('сокращённая');
    if (row.isDramatized === true) chips.push('аудиоспектакль');
    chips.forEach(value => meta.append(makeChip(value)));

    const size = formatBytes(row.sizeBytes);
    const date = formatDate(row.updatedAt || row.discoveredAt);
    if (size) appendText(stats, size, 'stat size');
    if (date) appendText(stats, date, 'stat date');
    appendText(stats, `⬆ ${row.seeders ?? 0}`, 'stat seeders');
    appendText(stats, `⬇ ${row.leechers ?? 0}`, 'stat leechers');

    if (row.sourceUrl) {
      const sourceLink = document.createElement('a');
      sourceLink.className = 'action-link';
      sourceLink.href = row.sourceUrl;
      sourceLink.target = '_blank';
      sourceLink.rel = 'noopener noreferrer';
      sourceLink.textContent = 'Источник';
      actions.append(sourceLink);
    }

    if (row.magnetUri) {
      const magnet = document.createElement('a');
      magnet.className = 'action-link magnet-link';
      magnet.href = row.magnetUri;
      magnet.textContent = 'Magnet';
      actions.append(magnet);

      const copy = document.createElement('button');
      copy.className = 'copy-button';
      copy.type = 'button';
      copy.textContent = 'Копировать';
      copy.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(row.magnetUri);
          copy.textContent = 'Скопировано';
          window.setTimeout(() => { copy.textContent = 'Копировать'; }, 1300);
        } catch {
          window.prompt('Скопируйте magnet-ссылку:', row.magnetUri);
        }
      });
      actions.append(copy);
    }

    card.dataset.id = String(row.id ?? '');
    return fragment;
  }

  function renderRows() {
    els.results.replaceChildren();
    const fragment = document.createDocumentFragment();
    rows.forEach(row => fragment.append(renderRow(row)));
    els.results.append(fragment);

    setVisible(els.noresults, hasSearched && total === 0);
    els.summary.textContent = total > rows.length
      ? `Показано ${formatCount(rows.length)} из ${formatCount(total)}`
      : `Найдено записей: ${formatCount(total)}`;
  }

  function selectedSort() {
    return document.querySelector('input[name="sort"]:checked')?.value || 'seeders';
  }

  function selectedFilters() {
    return Object.fromEntries(Object.entries(filterControls).map(([name, select]) => [name, select.value]));
  }

  function hasAnyFilter(filters) {
    return Object.values(filters).some(Boolean);
  }

  function appendRestoredOption(select, value) {
    if (!value) return;
    if (![...select.options].some(option => option.value === value)) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value;
      select.append(option);
    }
    select.value = value;
  }

  function refillFacetSelect(select, options, firstLabel, {
    numeric = false,
    preserveOrder = false,
    preferQueryMatches = false
  } = {}) {
    const previous = select.value;
    const previousLabel = select.selectedOptions[0]?.textContent?.replace(/\s+\(\d+\)$/, '') || previous;
    const allValues = Array.isArray(options) ? [...options] : [];

    if (!preserveOrder) {
      allValues.sort((a, b) => numeric
        ? Number(b.label) - Number(a.label)
        : String(a.label).localeCompare(String(b.label), 'ru', { sensitivity: 'base' }));
    }

    const matchedValues = allValues.filter(item => item?.matchesQuery === true);
    const values = preferQueryMatches && matchedValues.length > 0
      ? matchedValues
      : allValues;

    select.replaceChildren();
    const first = document.createElement('option');
    first.value = '';
    first.textContent = firstLabel;
    select.append(first);

    for (const item of values) {
      if (!item || !item.value || !item.label) continue;
      const option = document.createElement('option');
      option.value = String(item.value);
      option.textContent = `${item.label} (${formatCount(item.count)})`;
      select.append(option);
    }

    // Уже выбранный соавтор/чтец остаётся доступным, даже если новый текстовый
    // запрос предпочёл только непосредственно совпавшие имена.
    if (previous && ![...select.options].some(option => option.value === previous)) {
      const previousItem = allValues.find(item => String(item?.value) === previous);
      const missing = document.createElement('option');
      missing.value = previous;
      missing.textContent = previousItem
        ? `${previousItem.label} (${formatCount(previousItem.count)})`
        : `${previousLabel} (0)`;
      select.append(missing);
    }
    select.value = previous;
  }

  function updateFilterOptions(facets) {
    const data = facets && typeof facets === 'object' ? facets : {};
    refillFacetSelect(els.authorFilter, data.authors, 'Любой', { preferQueryMatches: true });
    refillFacetSelect(els.narratorFilter, data.narrators, 'Любой', { preferQueryMatches: true });
    refillFacetSelect(els.seriesFilter, data.series, 'Любая', { preferQueryMatches: true });
    refillFacetSelect(els.sourceFilter, data.sources, 'Любой');
    refillFacetSelect(els.formatFilter, data.formats, 'Любой');
    refillFacetSelect(els.qualityFilter, data.qualities, 'Любое', { preserveOrder: true });
    refillFacetSelect(els.yearFilter, data.years, 'Любой', { numeric: true });
  }

  function buildRequest() {
    const q = els.q.value.trim();
    const filters = selectedFilters();
    if (!q && !hasAnyFilter(filters)) return null;

    const limit = Math.max(1, Math.min(250, Number(els.limit.value) || 100));
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    params.set('limit', String(limit));

    for (const [name, value] of Object.entries(filters)) {
      if (value) params.set(name, value);
    }

    const sort = selectedSort();
    if (sort) params.set('sort', sort);
    localStorage.setItem(QUERY_STORAGE, JSON.stringify({ q, limit }));
    localStorage.setItem(SORT_STORAGE, sort);
    return { apiUrl: `/api/v1/search?${params}`, params };
  }

  function updateBrowserUrl(params) {
    const url = new URL(window.location.href);
    url.search = params.toString();
    window.history.replaceState(null, '', url);
  }

  function setControlsDisabled(disabled) {
    Object.values(filterControls).forEach(control => { control.disabled = disabled; });
    document.querySelectorAll('input[name="sort"]').forEach(control => { control.disabled = disabled; });
    els.resetFilters.disabled = disabled;
  }

  async function loadRows(retryAuth = true, { resetFilters = false } = {}) {
    if (resetFilters) resetResultFilters();
    const request = buildRequest();
    if (!request) {
      showWelcome();
      els.q.focus();
      return;
    }

    const key = getApiKey() || requestApiKey();
    if (!key) {
      rows = [];
      total = 0;
      setVisible(els.welcome, false);
      setVisible(els.loading, false);
      setVisible(els.error, true);
      setVisible(els.summaryRow, true);
      els.error.textContent = 'Для поиска нужен API_KEY.';
      els.summary.textContent = 'Нет API-ключа';
      return;
    }

    const sequence = ++requestSequence;
    lastRequest = request.apiUrl;
    hasSearched = true;
    setVisible(els.welcome, false);
    setVisible(els.summaryRow, true);
    setVisible(els.loading, true);
    setVisible(els.error, false);
    setVisible(els.noresults, false);
    els.summary.textContent = 'Запрос к API…';
    setControlsDisabled(true);

    try {
      const response = await fetch(request.apiUrl, {
        headers: { 'X-Api-Key': key },
        cache: 'no-store'
      });

      if (response.status === 401 && retryAuth) {
        localStorage.removeItem(API_KEY_STORAGE);
        setApiStatus('bad', 'ключ отклонён');
        const replacement = requestApiKey('API_KEY отклонён. Введите правильный ключ:');
        if (replacement) return loadRows(false, { resetFilters: false });
      }

      if (!response.ok) {
        const body = await response.text();
        throw new Error(`HTTP ${response.status}${body ? `: ${body.slice(0, 250)}` : ''}`);
      }

      const data = await response.json();
      if (!data || !Array.isArray(data.items) || typeof data.total !== 'number') {
        throw new Error('API вернул неожиданный формат данных.');
      }
      if (sequence !== requestSequence) return;

      rows = data.items;
      total = data.total;
      setApiStatus('ok', 'API доступен');
      void loadDatabaseStats();
      updateFilterOptions(data.facets);
      setVisible(els.filterArea, true);
      setVisible(els.facetHint, true);
      setVisible(els.sortBar, true);
      renderRows();
      updateBrowserUrl(request.params);
    } catch (error) {
      if (sequence !== requestSequence) return;
      rows = [];
      total = 0;
      setApiStatus('bad', 'ошибка API');
      setVisible(els.error, true);
      els.error.textContent = error instanceof Error ? error.message : String(error);
      els.summary.textContent = 'Ошибка загрузки';
    } finally {
      if (sequence === requestSequence) {
        setVisible(els.loading, false);
        setControlsDisabled(false);
      }
    }
  }

  function restoreState() {
    const urlParams = new URLSearchParams(window.location.search);
    let saved = {};
    try {
      saved = JSON.parse(localStorage.getItem(QUERY_STORAGE) || '{}');
    } catch {
      localStorage.removeItem(QUERY_STORAGE);
    }

    els.q.value = urlParams.get('q') || saved.q || '';
    const requestedLimit = Number(urlParams.get('limit') || saved.limit);
    if ([...els.limit.options].some(option => Number(option.value) === requestedLimit)) {
      els.limit.value = String(requestedLimit);
    }

    for (const [name, select] of Object.entries(filterControls)) {
      appendRestoredOption(select, urlParams.get(name) || '');
    }

    const sort = urlParams.get('sort') || localStorage.getItem(SORT_STORAGE);
    const sortInput = sort && document.querySelector(`input[name="sort"][value="${CSS.escape(sort)}"]`);
    if (sortInput) sortInput.checked = true;

    setApiStatus(getApiKey() ? '' : 'bad', getApiKey() ? 'ключ сохранён' : 'нет ключа');
  }

  els.form.addEventListener('submit', event => {
    event.preventDefault();
    void loadRows(true, { resetFilters: true });
  });

  Object.values(filterControls).forEach(select => {
    select.addEventListener('change', () => {
      if (hasSearched) void loadRows(true, { resetFilters: false });
    });
  });

  document.querySelectorAll('input[name="sort"]').forEach(input => {
    input.addEventListener('change', () => {
      if (hasSearched) void loadRows(true, { resetFilters: false });
    });
  });

  els.limit.addEventListener('change', () => {
    if (hasSearched) void loadRows(true, { resetFilters: false });
  });

  els.resetFilters.addEventListener('click', event => {
    event.preventDefault();
    resetResultFilters({ resetSort: true });
    if (hasSearched) void loadRows(true, { resetFilters: false });
  });

  els.apiKeyButton.addEventListener('click', () => {
    const key = requestApiKey('Введите новый API_KEY. Пустое значение удалит сохранённый ключ:');
    if (key !== null) void loadDatabaseStats();
    if (key !== null && hasSearched && lastRequest) {
      void loadRows(false, { resetFilters: false });
    }
  });

  els.reloadButton.addEventListener('click', () => {
    if (hasSearched && lastRequest) void loadRows(true, { resetFilters: false });
  });

  els.databaseStats.addEventListener('click', () => {
    const show = els.databaseStatsDetails.hidden;
    setVisible(els.databaseStatsDetails, show);
    els.databaseStats.setAttribute('aria-expanded', String(show));
  });

  restoreState();
  const restoredRequest = buildRequest();
  if (restoredRequest) void loadRows(true, { resetFilters: false });
  else showWelcome();
  void loadDatabaseStats();
})();
