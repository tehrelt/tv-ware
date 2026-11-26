package com.tvware.launcher.web

internal fun buildWebSettingsHtml(): String = """
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TV Launcher — Настройки</title>
  <style>
    :root {
      --bg: #0a0a0b;
      --card: #1c1c1e;
      --elevated: #2c2c2e;
      --border: rgba(245, 245, 247, 0.10);
      --text: #f5f5f7;
      --muted: #a1a1a6;
      --accent: #0a84ff;
      --green: #4cc38a;
      --danger: #ff453a;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      padding: 20px 16px 48px;
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      line-height: 1.45;
    }
    .wrap { max-width: 680px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
    header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 4px 2px; }
    h1 { margin: 0; font-size: 22px; font-weight: 600; }
    .status { font-size: 13px; color: var(--muted); }
    .status.ok { color: var(--green); }
    .status.err { color: var(--danger); }
    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .card h2 { margin: 0; font-size: 15px; font-weight: 600; color: var(--muted); }
    .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    button, .file-btn {
      appearance: none;
      border: 1px solid var(--border);
      background: var(--elevated);
      color: var(--text);
      border-radius: 10px;
      padding: 10px 14px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    button.active {
      background: rgba(10, 132, 255, 0.22);
      border-color: rgba(10, 132, 255, 0.75);
    }
    button.primary {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
    }
    button.danger {
      color: var(--danger);
    }
    button:disabled { opacity: 0.5; cursor: default; }
    input[type="password"], input[type="text"] {
      flex: 1 1 220px;
      min-width: 0;
      background: var(--bg);
      color: var(--text);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 10px 12px;
      font-size: 14px;
    }
    input[type="file"] { display: none; }
    .hint { font-size: 13px; color: var(--muted); margin: 0; }
    .app-list { display: flex; flex-direction: column; gap: 8px; }
    .app-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      padding: 10px 12px;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 12px;
    }
    .app-meta { min-width: 0; }
    .app-name { font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .app-pkg { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  </style>
</head>
<body>
  <div class="wrap">
    <header>
      <h1>TV Launcher</h1>
      <span id="connStatus" class="status">Подключение...</span>
    </header>

    <section class="card">
      <h2>Фон</h2>
      <div class="row">
        <button type="button" data-bg="BLACK">Чёрный</button>
        <button type="button" data-bg="GRADIENT">Графит</button>
        <button type="button" data-bg="CUSTOM_FILE" id="bgCustomBtn">Изображение</button>
        <label class="file-btn" for="bgFile">Загрузить фото</label>
        <input id="bgFile" type="file" accept="image/*">
        <button type="button" id="bgClearBtn" class="danger" style="display:none">Сбросить</button>
      </div>
    </section>

    <section class="card">
      <h2>Колонок в сетке</h2>
      <div class="row">
        <button type="button" data-cols="4">4</button>
        <button type="button" data-cols="5">5</button>
        <button type="button" data-cols="6">6</button>
      </div>
    </section>

    <section class="card">
      <h2>Медиа-экран</h2>
      <div class="row">
        <button type="button" data-media="true">Включён</button>
        <button type="button" data-media="false">Выключен</button>
      </div>
      <p id="mediaAccessHint" class="hint"></p>
    </section>

    <section class="card">
      <h2>Тексты песен (Spicy Lyrics)</h2>
      <div class="row">
        <button type="button" data-lyrics="true">Включены</button>
        <button type="button" data-lyrics="false">Выключены</button>
      </div>
      <div class="row">
        <input id="spicyKeyInput" type="password" placeholder="Вставьте API-ключ Spicy Lyrics" autocomplete="off">
        <button type="button" id="saveKeyBtn" class="primary">Сохранить</button>
        <button type="button" id="clearKeyBtn" class="danger" style="display:none">Удалить</button>
      </div>
      <p id="spicyKeyHint" class="hint"></p>
    </section>

    <section class="card">
      <h2>Автозапуск Happ</h2>
      <div class="row">
        <button type="button" data-happ="true">Включён</button>
        <button type="button" data-happ="false">Выключен</button>
      </div>
      <p class="hint">Подключает выбранный профиль Happ при запуске лаунчера.</p>
    </section>

    <section class="card">
      <h2>Скрытые приложения</h2>
      <div id="hiddenList" class="app-list"></div>
    </section>

    <section class="card">
      <h2>Приложения</h2>
      <div id="visibleList" class="app-list"></div>
    </section>
  </div>

  <script>
    const TOKEN = new URLSearchParams(location.search).get('t') || '';
    const statusEl = document.getElementById('connStatus');

    function setStatus(text, cls) {
      statusEl.textContent = text;
      statusEl.className = 'status ' + (cls || '');
    }

    async function api(path, options = {}) {
      const headers = Object.assign({}, options.headers || {}, { 'X-Auth-Token': TOKEN });
      const res = await fetch(path, Object.assign({}, options, { headers }));
      if (!res.ok) {
        const msg = await res.text().catch(() => '');
        throw new Error(msg || ('HTTP ' + res.status));
      }
      return res.json();
    }

    async function patchSettings(payload) {
      setStatus('Сохранение...', '');
      try {
        const state = await api('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        render(state);
        setStatus('Сохранено', 'ok');
      } catch (e) {
        setStatus('Ошибка: ' + e.message, 'err');
      }
    }

    function render(state) {
      document.querySelectorAll('[data-bg]').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.bg === state.backgroundMode);
      });
      document.getElementById('bgCustomBtn').style.display = state.hasCustomBackground ? '' : 'none';
      document.getElementById('bgClearBtn').style.display = state.hasCustomBackground ? '' : 'none';

      document.querySelectorAll('[data-cols]').forEach(btn => {
        btn.classList.toggle('active', Number(btn.dataset.cols) === state.gridColumns);
      });

      document.querySelectorAll('[data-media]').forEach(btn => {
        btn.classList.toggle('active', (btn.dataset.media === 'true') === state.mediaScreenEnabled);
      });
      const accessEl = document.getElementById('mediaAccessHint');
      accessEl.textContent = state.mediaAccessGranted
        ? 'Доступ к уведомлениям активен.'
        : 'Доступ к уведомлениям не выдан — разрешите его на экране ТВ.';

      document.querySelectorAll('[data-lyrics]').forEach(btn => {
        btn.classList.toggle('active', (btn.dataset.lyrics === 'true') === state.lyricsEnabled);
      });
      document.getElementById('clearKeyBtn').style.display = state.hasSpicyLyricsKey ? '' : 'none';
      document.getElementById('spicyKeyHint').textContent = state.hasSpicyLyricsKey
        ? ('Ключ сохранён (' + (state.spicyLyricsKeyHint || 'активен') + ')')
        : 'Ключ не задан. Получите его на developers.spicylyrics.org.';

      document.querySelectorAll('[data-happ]').forEach(btn => {
        btn.classList.toggle('active', (btn.dataset.happ === 'true') === state.autoStartHapp);
      });

      const hiddenList = document.getElementById('hiddenList');
      hiddenList.replaceChildren();
      if (!state.hiddenApps.length) {
        const p = document.createElement('p');
        p.className = 'hint';
        p.textContent = 'Нет скрытых приложений.';
        hiddenList.appendChild(p);
      } else {
        state.hiddenApps.forEach(app => {
          hiddenList.appendChild(buildAppRow(app, [
            { label: 'Показать', onClick: () => patchSettings({ unhidePackage: app.packageName }) }
          ]));
        });
      }

      const visibleList = document.getElementById('visibleList');
      visibleList.replaceChildren();
      state.visibleApps.forEach(app => {
        visibleList.appendChild(buildAppRow(app, [
          {
            label: app.isPinned ? 'В избранном' : 'В избранное',
            active: app.isPinned,
            onClick: () => patchSettings({ togglePinPackage: app.packageName })
          },
          {
            label: 'Скрыть',
            danger: true,
            onClick: () => patchSettings({ hidePackage: app.packageName })
          }
        ]));
      });
    }

    function buildAppRow(app, actions) {
      const row = document.createElement('div');
      row.className = 'app-item';
      const meta = document.createElement('div');
      meta.className = 'app-meta';
      const name = document.createElement('div');
      name.className = 'app-name';
      name.textContent = app.label;
      const pkg = document.createElement('div');
      pkg.className = 'app-pkg';
      pkg.textContent = app.packageName;
      meta.append(name, pkg);

      const btns = document.createElement('div');
      btns.className = 'row';
      actions.forEach(a => {
        const b = document.createElement('button');
        b.type = 'button';
        b.textContent = a.label;
        if (a.active) b.classList.add('active');
        if (a.danger) b.classList.add('danger');
        b.addEventListener('click', a.onClick);
        btns.appendChild(b);
      });
      row.append(meta, btns);
      return row;
    }

    document.querySelectorAll('[data-bg]').forEach(btn => {
      btn.addEventListener('click', () => patchSettings({ backgroundMode: btn.dataset.bg }));
    });
    document.querySelectorAll('[data-cols]').forEach(btn => {
      btn.addEventListener('click', () => patchSettings({ gridColumns: Number(btn.dataset.cols) }));
    });
    document.querySelectorAll('[data-media]').forEach(btn => {
      btn.addEventListener('click', () => patchSettings({ mediaScreenEnabled: btn.dataset.media === 'true' }));
    });
    document.querySelectorAll('[data-lyrics]').forEach(btn => {
      btn.addEventListener('click', () => patchSettings({ lyricsEnabled: btn.dataset.lyrics === 'true' }));
    });
    document.querySelectorAll('[data-happ]').forEach(btn => {
      btn.addEventListener('click', () => patchSettings({ autoStartHapp: btn.dataset.happ === 'true' }));
    });

    document.getElementById('saveKeyBtn').addEventListener('click', async () => {
      const input = document.getElementById('spicyKeyInput');
      const val = input.value.trim();
      if (!val) return;
      await patchSettings({ spicyLyricsApiKey: val });
      input.value = '';
    });

    document.getElementById('clearKeyBtn').addEventListener('click', () => {
      patchSettings({ clearSpicyLyricsKey: true });
    });

    document.getElementById('bgFile').addEventListener('change', async (ev) => {
      const file = ev.target.files && ev.target.files[0];
      ev.target.value = '';
      if (!file) return;
      setStatus('Загрузка фото...', '');
      try {
        const state = await api('/api/background', {
          method: 'POST',
          headers: { 'Content-Type': file.type || 'application/octet-stream' },
          body: file,
        });
        render(state);
        setStatus('Фон обновлён', 'ok');
      } catch (e) {
        setStatus('Ошибка: ' + e.message, 'err');
      }
    });

    document.getElementById('bgClearBtn').addEventListener('click', async () => {
      setStatus('Сброс фона...', '');
      try {
        const state = await api('/api/background', { method: 'DELETE' });
        render(state);
        setStatus('Фон сброшен', 'ok');
      } catch (e) {
        setStatus('Ошибка: ' + e.message, 'err');
      }
    });

    api('/api/state')
      .then(state => { render(state); setStatus('Подключено', 'ok'); })
      .catch(e => setStatus('Ошибка: ' + e.message, 'err'));
  </script>
</body>
</html>
""".trimIndent()
