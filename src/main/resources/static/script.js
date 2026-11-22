'use strict';

const $ = id => document.getElementById(id);

const homeView = $('home-view');
const resultsView = $('results-view');
const mainSearch = $('main-search');
const miniSearch = $('mini-search');
const resultsList = $('results-list');
const knowledgePanel = $('knowledge-panel');
const autocorrectMsg = $('autocorrect-msg');
const correctedWord = $('corrected-word');
const queryMeta = $('query-meta');
const popularHints = $('popular-hints');
const tabsEl = $('tabs');
const infoBanner = $('info-banner');
const suggestRow = $('suggest-row');

const TABS = [
    ['all', 'Все'],
    ['news', 'Новости'],
    ['videos', 'Видео'],
    ['events', 'Афиша']
];

let lastQuery = '';
let data = null;
let activeTab = 'all';
let requestId = 0;
const photoCache = new Map();

/* ============ УТИЛИТЫ ============ */

// Всё, что пришло с сервера (особенно новости из интернета), экранируем перед вставкой в HTML
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));
const safeUrl = u => (/^https?:\/\//i.test(u || '') ? u : '#');
const initials = name => (name || '').split(' ').map(w => w[0] || '').join('').slice(0, 2).toUpperCase();

function isUpcoming(d) {
    const m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(d || '');
    if (!m) return true;                       // дату не поняли, оставляем
    return new Date(+m[3], +m[2] - 1, +m[1], 23, 59) >= new Date();
}

// «5 мин назад», «3 ч назад», «2 дн назад»; старше недели показываем дату как есть
function ago(ts, fallback) {
    const s = Math.floor(Date.now() / 1000) - Number(ts);
    if (!ts || s < 0 || s > 7 * 86400) return fallback;
    if (s < 3600) return Math.max(1, Math.floor(s / 60)) + ' мин назад';
    if (s < 86400) return Math.floor(s / 3600) + ' ч назад';
    return Math.floor(s / 86400) + ' дн назад';
}

// Если за две недели нет ни одной новости, честно говорим об этом
function staleNote(news) {
    if (!news.length) return '';
    const fresh = news.some(n => Number(n.ts) && (Date.now() / 1000 - Number(n.ts)) < 14 * 86400);
    return fresh ? '' : '<div class="info-banner">Свежих новостей за последние две недели нет. Показаны самые поздние из найденных.</div>';
}

function emptyState(icon, title, desc) {
    return `<div class="empty-state"><div class="empty-icon">${icon}</div>
        <div class="empty-title">${title}</div><div class="empty-desc">${desc}</div></div>`;
}

/* ============ СТАРТ ============ */

document.addEventListener('DOMContentLoaded', () => {
    if (mainSearch) mainSearch.focus();
    renderPopularHints();
    loadHomeNews();
    loadStats();
});

async function loadStats() {
    const box = $('home-stats');
    if (!box) return;
    try {
        const st = await (await fetch('/api/stats')).json();
        box.textContent = `В базе ${st.total} комиков · фото, новости и видео подгружаются из интернета`;
    } catch (e) { /* счётчик не обязателен */ }
}

async function loadHomeNews() {
    const box = $('home-news');
    if (!box) return;
    try {
        const items = await (await fetch('/api/latest')).json();
        if (!items.length) return;
        box.innerHTML = '<h2>Свежее в стендапе</h2><div class="home-news-grid">' + items.slice(0, 3).map(n =>
            `<a class="home-news-card" href="${esc(safeUrl(n.link))}" target="_blank" rel="noopener">
                ${n.image ? `<img src="${esc(n.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}
                <div><b>${esc(n.title)}</b><small>${esc(n.source)} · ${esc(ago(n.ts, n.time))}</small></div></a>`
        ).join('') + '</div>';
    } catch (e) { /* без новостей главная остаётся чистой */ }
}

if (mainSearch) mainSearch.addEventListener('keydown', e => { if (e.key === 'Enter') doSearch(); });
if (miniSearch) miniSearch.addEventListener('keydown', e => { if (e.key === 'Enter') doSearch(miniSearch.value); });

// Один обработчик на все кликабельные элементы: подсказки, чипы, карточки, вкладки
document.addEventListener('click', e => {
    const q = e.target.closest('[data-q]');
    if (q) { e.preventDefault(); doSearch(q.dataset.q); return; }

    const t = e.target.closest('[data-tab]');
    if (t && data) { activeTab = t.dataset.tab; renderTabs(); renderTab(); }
});

function renderPopularHints() {
    if (!popularHints) return;
    const hints = ['Павел Воля', 'Нурлан Сабуров', 'Алексей Щербаков', 'Гурам Амарян',
        'Юлия Ахмедова', 'Сергей Орлов', 'топ 10 комиков'];
    const shuffled = [...hints].sort(() => Math.random() - 0.5).slice(0, 5);
    popularHints.innerHTML = shuffled.map(h => `<a data-q="${esc(h)}">${esc(h)}</a>`).join('');
}

function randomPunchline() {
    const names = ['Павел Воля', 'Нурлан Сабуров', 'Алексей Щербаков', 'Гурам Амарян', 'Юлия Ахмедова', 'Сергей Орлов'];
    doSearch(names[Math.floor(Math.random() * names.length)]);
}

function quickSearch(q) { doSearch(q); }

/* ============ ПОИСК ============ */

function doSearch(query) {
    const q = (typeof query === 'string' ? query : mainSearch.value).trim();
    if (!q) return;

    lastQuery = q;
    mainSearch.value = q;

    if (homeView.style.display !== 'none') {
        homeView.style.opacity = '0';
        setTimeout(() => {
            homeView.style.display = 'none';
            resultsView.style.display = 'block';
            miniSearch.value = q;
            fetchAndRender(q);
        }, 400);
    } else {
        miniSearch.value = q;
        fetchAndRender(q);
    }
}

function resetView() {
    autocorrectMsg.style.display = 'none';
    queryMeta.style.display = 'none';
    infoBanner.style.display = 'none';
    suggestRow.style.display = 'none';
    tabsEl.innerHTML = '';
}

async function fetchAndRender(query) {
    const myId = ++requestId;
    resetView();
    resultsList.innerHTML = `<div class="loading-state"><div class="spinner"></div><p>Поиск...</p></div>`;
    knowledgePanel.innerHTML = `<div class="k-empty"><div class="k-empty-icon">⏳</div><p>Загрузка...</p></div>`;

    let json;
    try {
        const res = await fetch('/api/search?q=' + encodeURIComponent(query));
        if (!res.ok) throw new Error('Сервер ответил: ' + res.status);
        json = await res.json();
    } catch (e) {
        if (myId !== requestId) return;
        resultsList.innerHTML = emptyState('⚠️', 'Ошибка', esc(e.message));
        knowledgePanel.innerHTML = '';
        return;
    }
    if (myId !== requestId) return;      // пришёл ответ на устаревший запрос

    data = json;
    activeTab = 'all';

    if (data.invalid || data.type === 'invalid') { renderInvalid(); return; }

    const nothing = !(data.results || []).length && !(data.news || []).length && !(data.videos || []).length;
    if (data.type === 'empty' || nothing) { renderEmpty(); return; }

    renderResults();
    renderKnowledgePanel();
}

function renderInvalid() {
    resultsList.innerHTML = emptyState('🚫', 'Не понимаю запрос',
        'Исправьте запрос и попробуйте ещё раз: напишите имя комика или, например, «топ 10 комиков»');
    knowledgePanel.innerHTML = `<div class="k-empty"><div class="k-empty-icon">🚫</div><p>Запрос не распознан</p></div>`;
}

function renderEmpty() {
    renderCorrection();
    renderSuggestions();
    const hasSuggest = (data.suggestions || []).length > 0;
    resultsList.innerHTML = emptyState('🔍', 'Ничего не нашли',
        hasSuggest ? 'Точного совпадения нет, посмотрите варианты выше' : 'Попробуйте другое имя или запрос');
    knowledgePanel.innerHTML = `<div class="k-empty"><div class="k-empty-icon">🔍</div><p>Не удалось определить комика.</p></div>`;
}

/* ============ ВЕРХНЯЯ ЧАСТЬ ВЫДАЧИ ============ */

function renderCorrection() {
    if (data.wasCorrected && data.corrected) {
        correctedWord.textContent = data.corrected;
        autocorrectMsg.style.display = 'block';
    }
}

function renderSuggestions() {
    const s = data.suggestions || [];
    if (!s.length) { suggestRow.style.display = 'none'; return; }
    suggestRow.innerHTML = 'Возможно, вы искали: ' +
        s.map(n => `<a class="chip" data-q="${esc(n)}">${esc(n)}</a>`).join('');
    suggestRow.style.display = 'flex';
}

function renderResults() {
    renderCorrection();
    renderSuggestions();

    if (data.type === 'category' && data.categoryInfo) {
        infoBanner.textContent = data.categoryInfo;
        infoBanner.style.display = 'block';
    }

    queryMeta.style.display = 'flex';
    queryMeta.innerHTML = `
        <span class="stat">Найдено: ${data.count || 0}</span>
        <span class="stat">Время: ${data.timeMs || 0} мс</span>`;

    renderTabs();
    renderTab();
}

/* ============ ВКЛАДКИ ============ */

function upcomingEvents() {
    return (data.events || []).filter(e => isUpcoming(e.date));
}

function isDeceased() {
    const f = (data.results || [])[0];
    const c = f && (f.comedian || f);
    return !!(c && c.deathDate);
}

function lifeText(c) {
    if (c.deathDate) return (c.birthDate || '?') + ' — ' + c.deathDate;
    return c.birthDate || '';
}

function renderTabs() {
    const counts = {
        news: (data.news || []).length,
        videos: (data.videos || []).length,
        events: upcomingEvents().length
    };
    tabsEl.innerHTML = TABS.filter(([key]) => !(key === 'events' && isDeceased())).map(([key, label]) => {
        const n = counts[key];
        return `<button class="tab${key === activeTab ? ' active' : ''}" data-tab="${key}">${label}${n ? ` <small>${n}</small>` : ''}</button>`;
    }).join('');
}

function renderTab() {
    const results = data.results || [];
    const news = data.news || [];
    const videos = data.videos || [];
    const events = upcomingEvents();
    const clickable = data.type === 'category';
    let html = '';

    if (activeTab === 'all') {
        html = results.map(r => comedianCard(r, clickable)).join('')
            + videos.map(videoCard).join('')
            + staleNote(news) + news.map(newsCard).join('')
            + events.map(eventCard).join('');
        if (!html) html = emptyState('🔍', 'Ничего не нашли', 'Попробуйте другой запрос');
    } else if (activeTab === 'news') {
        html = (news.length ? staleNote(news) + news.map(newsCard).join('') : '')
            || emptyState('📰', 'Свежих новостей не нашли', 'Повторите запрос чуть позже');
    } else if (activeTab === 'videos') {
        html = videos.map(videoCard).join('')
            || emptyState('▶', 'Видео не нашли', 'Попробуйте другой запрос');
    } else if (activeTab === 'events') {
        html = events.map(eventCard).join('')
            || emptyState('📅', 'Ближайших выступлений нет',
                (data.events || []).length ? 'Все известные даты уже прошли' : 'Афиша пока пуста');
    } else if (activeTab === 'images') {
        html = galleryHtml();
    }

    resultsList.innerHTML = html;
    hydratePhotos(resultsList);
}

/* ============ КАРТОЧКИ ============ */

function comedianCard(r, clickable) {
    const c = r.comedian || r;
    return `<div class="result-card comedian"${clickable ? ` data-q="${esc(c.name)}"` : ''}>
        <div class="comedian-row">
            <div class="avatar" data-photo="${esc(c.name)}"><span class="ini">${esc(initials(c.name))}</span></div>
            <div class="comedian-main">
                <div class="result-type">🎭 Комик</div>
                <div class="result-title">${esc(c.name)}</div>
                <div class="result-desc">
                    ${c.style ? `<b>Стиль:</b> ${esc(c.style)}<br>` : ''}
                    ${c.project ? `<b>Проект:</b> ${esc(c.project)}<br>` : ''}
                    ${esc(c.bio || '')}
                </div>
                ${c.fact ? `<div class="result-desc" style="margin-top:10px;color:var(--violet);font-style:italic;">${esc(c.fact)}</div>` : ''}
                <div class="result-meta">
                    ${lifeText(c) ? `<span>${esc(lifeText(c))}</span>` : ''}
                    ${c.birthPlace ? `<span>${esc(c.birthPlace)}</span>` : ''}
                </div>
            </div>
        </div>
    </div>`;
}

function videoCard(v) {
    const url = esc(safeUrl(v.url));
    return `<div class="result-card video">
        <div class="result-type">▶ Видео</div>
        ${v.thumbnail ? `<a class="video-thumb-mini" href="${url}" target="_blank" rel="noopener">
            <img src="${esc(v.thumbnail)}" alt="" loading="lazy" onerror="this.style.display='none'"></a>` : ''}
        <div class="result-title"><a href="${url}" target="_blank" rel="noopener">${esc(v.title)}</a></div>
        <div class="result-desc">${esc(v.source || 'YouTube')}${v.time ? ' · ' + esc(v.time) : ''}</div>
    </div>`;
}

function newsCard(n) {
    const url = esc(safeUrl(n.link));
    return `<div class="result-card news${n.image ? ' has-img' : ''}">
        ${n.image ? `<a class="news-img" href="${url}" target="_blank" rel="noopener">
            <img src="${esc(n.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.parentNode.remove()"></a>` : ''}
        <div class="news-body">
            <div class="result-type">⚡ Новость</div>
            <div class="result-title"><a href="${url}" target="_blank" rel="noopener">${esc(n.title)}</a></div>
            <div class="result-meta"><span>${esc(n.source)}</span>${n.time ? `<span>${esc(ago(n.ts, n.time))}</span>` : ''}</div>
        </div>
    </div>`;
}

function eventCard(e) {
    return `<div class="result-card event">
        <div class="result-type">📅 Афиша</div>
        <div class="result-title">${esc(e.title || lastQuery)}</div>
        <div class="result-desc">${esc(e.city || '')}${e.venue ? ' · ' + esc(e.venue) : ''}</div>
        <div class="result-meta"><span>${esc(e.date || 'Дата уточняется')}</span>${e.price ? `<span>${esc(e.price)} ₽</span>` : ''}</div>
    </div>`;
}

function galleryHtml() {
    const tiles = [];

    (data.results || []).forEach(r => {
        const c = r.comedian || r;
        tiles.push(`<div class="tile" data-q="${esc(c.name)}">
            <div class="avatar" data-photo="${esc(c.name)}"><span class="ini">${esc(initials(c.name))}</span></div>
            <div class="tile-cap">${esc(c.name)}</div></div>`);
    });
    (data.news || []).forEach(n => {
        if (!n.image) return;
        tiles.push(`<a class="tile" href="${esc(safeUrl(n.link))}" target="_blank" rel="noopener">
            <img src="${esc(n.image)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.parentNode.remove()">
            <div class="tile-cap">${esc(n.title)}</div></a>`);
    });

    return tiles.length
        ? `<div class="gallery">${tiles.join('')}</div>`
        : emptyState('🖼', 'Картинок нет', 'Попробуйте другой запрос');
}

/* ============ ПРАВАЯ ПАНЕЛЬ ============ */

function renderKnowledgePanel() {
    if (data.type === 'category') {
        knowledgePanel.innerHTML = `
            <div class="k-image"><div class="k-avatar-big">🎤</div></div>
            <div class="k-title">Стендап</div>
            <div class="k-subtitle">Жанр разговорной комедии</div>
            <div class="k-bio">Комик выходит на сцену один и рассказывает монолог из шуток и историй от первого лица.</div>`;
        return;
    }

    const first = (data.results || [])[0];
    if (!first) {
        knowledgePanel.innerHTML = `<div class="k-empty"><div class="k-empty-icon">🔍</div><p>Не удалось определить комика.</p></div>`;
        return;
    }

    const c = first.comedian || first;
    knowledgePanel.innerHTML = `
        <div class="k-image" data-photo="${esc(c.name)}"><div class="k-avatar-big">${esc(initials(c.name))}</div></div>
        <div class="k-title">${esc(c.name)}</div>
        <div class="k-subtitle">${esc(c.project || 'Стендап-комик')}</div>
        ${c.fullName && c.fullName !== c.name ? `<div class="k-fact"><span>Полное имя:</span><span>${esc(c.fullName)}</span></div>` : ''}
        ${c.style ? `<div class="k-fact"><span>Стиль:</span><span>${esc(c.style)}</span></div>` : ''}
        ${lifeText(c) ? `<div class="k-fact"><span>${c.deathDate ? 'Годы жизни' : 'Дата рождения'}:</span><span>${esc(lifeText(c))}</span></div>` : ''}
        ${c.birthPlace ? `<div class="k-fact"><span>Место:</span><span>${esc(c.birthPlace)}</span></div>` : ''}
        ${c.fact ? `<div class="k-bio"><b>💡 Факт:</b><br>${esc(c.fact)}</div>` : ''}`;
    hydratePhotos(knowledgePanel);
}

/* ============ ФОТО (подгружаются отдельно, поиск не тормозит) ============ */

function getPhoto(name) {
    if (!photoCache.has(name)) {
        photoCache.set(name, fetch('/api/photo?name=' + encodeURIComponent(name))
            .then(r => r.json())
            .then(j => { if (!j.url) photoCache.delete(name); return j.url || ''; })
            .catch(() => ''));
    }
    return photoCache.get(name);
}

// Сначала своя фотография из static/img/<Имя Фамилия>.jpg, потом Википедия
// Нет своего фото и фото из Википедии: берём кадр из видео (только на странице одного комика)
function videoFallback() {
    return data && (data.results || []).length === 1 && (data.videos || [])[0] ? data.videos[0].thumbnail : '';
}

function resolvePhoto(name) {
    const key = 'local:' + name;
    if (!photoCache.has(key)) {
        const p = new Promise(resolve => {
            const local = 'img/' + encodeURIComponent(name) + '.jpg';
            const probe = new Image();
            probe.onload = () => resolve(local);
            probe.onerror = () => resolve(getPhoto(name));
            probe.src = local;
        }).then(u => { if (!u) photoCache.delete(key); return u; });   // пустой ответ не запоминаем
        photoCache.set(key, p);
    }
    return photoCache.get(key);
}

function hydratePhotos(root) {
    root.querySelectorAll('[data-photo]').forEach(async el => {
        const url = (await resolvePhoto(el.dataset.photo)) || videoFallback();
        if (!url || !el.isConnected) return;

        if (el.classList.contains('k-image')) {
            el.style.backgroundImage = `url("${url.replace(/"/g, '%22')}")`;
            el.classList.add('has-photo');
            return;
        }

        const img = new Image();
        img.alt = el.dataset.photo;
        img.referrerPolicy = 'no-referrer';
        img.onload = () => { if (el.isConnected) { el.prepend(img); el.classList.add('has-photo'); } };
        img.src = url;
    });
}

/* ============ НАЗАД ============ */

function goHome() {
    requestId++;
    resultsView.style.display = 'none';
    homeView.style.display = 'flex';
    setTimeout(() => {
        homeView.style.opacity = '1';
        mainSearch.value = '';
        mainSearch.focus();
        renderPopularHints();
    }, 50);
}
