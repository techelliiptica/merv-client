/**
 * Polls merv-index-data.json every 5s (folders + suite card fields). Updates suite cards and KPI charts
 * without rewriting index.html or reloading the page. Falls back to fetching index.html when the manifest
 * is missing (older reports).
 */
(function mervIndexAjaxSync() {
  var MANIFEST_MS = 5000;
  var lastManifestSig = '';

  function escHtml(s) {
    return String(s || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function fmtAgo(epochMs) {
    var diff = Math.max(0, Date.now() - epochMs);
    var sec = Math.floor(diff / 1000);
    if (sec < 45) return 'just now';
    var min = Math.floor(sec / 60);
    if (min < 60) return min === 1 ? '1 min ago' : min + ' mins ago';
    var hr = Math.floor(min / 60);
    if (hr < 24) return hr === 1 ? '1 hour ago' : hr + ' hours ago';
    var day = Math.floor(hr / 24);
    return day === 1 ? '1 day ago' : day + ' days ago';
  }

  function fmtWhen(epochMs) {
    var d = new Date(epochMs);
    if (isNaN(d.getTime())) return '—';
    var dd = String(d.getDate()).padStart(2, '0');
    var mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()];
    var yyyy = d.getFullYear();
    var wk = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()];
    var h = d.getHours();
    var ampm = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    return dd + '-' + mon + '-' + yyyy + ' (' + wk + ') ' + String(h).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ampm;
  }

  function donutStyle(pass, fail, skip) {
    var t = pass + fail + skip;
    if (t <= 0) return 'background:#e9ecef;';
    var pEnd = (360 * pass) / t;
    var fEnd = pEnd + (360 * fail) / t;
    return (
      'background:conic-gradient(#28a745 0deg ' +
      pEnd +
      'deg, #dc3545 ' +
      pEnd +
      'deg ' +
      fEnd +
      'deg, #ffc107 ' +
      fEnd +
      'deg 360deg);'
    );
  }

  function renderSuiteCard(e, idx) {
    var isLatest = idx === 0 ? ' is-latest' : '';
    var statusCls = e.running ? 'run' : 'done';
    var statusText = e.running ? 'In progress' : 'Completed';
    var skipSeg = e.skip > 0 ? '' : ' style="display:none"';
    var tags = (e.tags || []).length
      ? e.tags.map(function (t) {
          return '<span class="tag-pill">' + escHtml(t) + '</span>';
        }).join('')
      : '<span class="tag-pill tag-pill-empty" style="opacity:.5">—</span>';
    var href = e.encodedFolderName + '/html/' + (e.running ? 'merv-report-live.html' : 'merv-report.html');
    var dataQ = escHtml((e.folderName + ' ' + e.title + ' ' + (e.tagsDisplay || '')).toLowerCase());

    var el = document.createElement('article');
    el.className = 'suite-card' + isLatest;
    el.setAttribute('data-q', dataQ);
    el.setAttribute('data-card-idx', String(idx));
    el.setAttribute('data-json', escHtml(e.encodedFolderName + '/json/merv-report.json'));
    el.setAttribute('data-folder-name', escHtml(e.folderName));
    el.innerHTML =
      '<div class="suite-top"><div class="suite-title-block"><h3 class="suite-name"></h3><p class="suite-folder"></p></div><span class="status-badge ' +
      statusCls +
      '"></span></div>' +
      '<div class="suite-counts"><strong>Total</strong> <span class="cnt-total">0</span> &nbsp;|&nbsp; <strong>Pass</strong> <span class="cnt-pass">0</span> &nbsp;|&nbsp; <strong>Fail</strong> <span class="cnt-fail">0</span><span class="cnt-skip-seg"' +
      skipSeg +
      '> &nbsp;|&nbsp; <strong>Skip</strong> <span class="cnt-skip">0</span></span></div>' +
      '<div class="suite-meta"><div class="suite-meta-row"><dl class="suite-meta-dl"><dt>Environment</dt><dd>Local</dd><dt>Release</dt><dd>—</dd><dt>Sprint</dt><dd>—</dd></dl><div class="suite-meta-donut"><div class="donut" title="Pass / Fail / Skip"></div></div></div>' +
      '<div class="suite-tags-block"><div class="tag-row"></div></div></div>' +
      '<div class="suite-foot"><div class="suite-when"></div><div class="suite-actions"></div></div>';
    patchSuiteCard(el, e, idx);
    return el;
  }

  function patchSuiteCard(el, e, idx) {
    if (!el) return;
    el.classList.toggle('is-latest', idx === 0);
    el.setAttribute('data-card-idx', String(idx));
    var statusCls = e.running ? 'run' : 'done';
    var statusText = e.running ? 'In progress' : 'Completed';
    var href = e.encodedFolderName + '/html/' + (e.running ? 'merv-report-live.html' : 'merv-report.html');

    var nameEl = el.querySelector('.suite-name');
    if (nameEl) nameEl.textContent = e.title || 'Test execution';
    var folderEl = el.querySelector('.suite-folder');
    if (folderEl) folderEl.textContent = e.folderName || '';
    var badge = el.querySelector('.status-badge');
    if (badge) {
      badge.className = 'status-badge ' + statusCls;
      badge.textContent = statusText;
    }
    var setCnt = function (sel, v) {
      var n = el.querySelector(sel);
      if (n) n.textContent = String(v);
    };
    setCnt('.cnt-total', e.total);
    setCnt('.cnt-pass', e.pass);
    setCnt('.cnt-fail', e.fail);
    setCnt('.cnt-skip', e.skip);
    var skipSeg = el.querySelector('.cnt-skip-seg');
    if (skipSeg) skipSeg.style.display = e.skip > 0 ? '' : 'none';
    var donut = el.querySelector('.donut');
    if (donut) donut.setAttribute('style', donutStyle(e.pass, e.fail, e.skip));
    var tagRow = el.querySelector('.tag-row');
    if (tagRow) {
      tagRow.innerHTML = (e.tags || []).length
        ? e.tags
            .map(function (t) {
              return '<span class="tag-pill">' + escHtml(t) + '</span>';
            })
            .join('')
        : '<span class="tag-pill tag-pill-empty" style="opacity:.5">—</span>';
    }
    var when = el.querySelector('.suite-when');
    if (when) {
      when.innerHTML = escHtml(fmtWhen(e.lastModified)) + '<span class="rel">' + escHtml(fmtAgo(e.lastModified)) + '</span>';
    }
    var actions = el.querySelector('.suite-actions');
    if (actions) {
      actions.innerHTML =
        '<a class="btn-view" href="' +
        escHtml(href) +
        '">View Cases</a><a class="icon-btn" title="Open in new tab" href="' +
        escHtml(href) +
        '" target="_blank" rel="noopener">↗</a><button type="button" class="btn-delete" title="Delete this report folder" data-folder="' +
        escHtml(e.folderName) +
        '" onclick="deleteReport(this)">Delete</button>';
    }
  }

  function manifestSig(data) {
    if (!data || !data.entries) return '';
    return (data.entries || [])
      .map(function (e) {
        return [
          e.folderName,
          e.total,
          e.pass,
          e.fail,
          e.skip,
          e.running ? 1 : 0,
          e.lastModified,
        ].join('|');
      })
      .join('\n');
  }

  function applyFoldersToApi(folders) {
    var api = window.__mervLiveDashboard;
    if (!api || !api.folders) return;
    var cur = (api.folders || []).join('\u0001');
    var next = (folders || []).join('\u0001');
    if (cur === next) return;
    api.folders.length = 0;
    (folders || []).forEach(function (f) {
      api.folders.push(f);
    });
    window.MERV_REPORT_FOLDERS = folders.slice();
  }

  function syncSuiteGrid(entries) {
    var grid = document.getElementById('suite-grid');
    if (!grid) return;
    var scroll = grid.scrollTop;
    var byFolder = new Map();
    grid.querySelectorAll('.suite-card[data-folder-name]').forEach(function (el) {
      byFolder.set(el.getAttribute('data-folder-name'), el);
    });
    var keep = new Set();
    (entries || []).forEach(function (e, idx) {
      keep.add(e.folderName);
      var el = byFolder.get(e.folderName);
      if (!el) {
        el = renderSuiteCard(e, idx);
        byFolder.set(e.folderName, el);
      } else {
        patchSuiteCard(el, e, idx);
      }
      grid.appendChild(el);
    });
    grid.querySelectorAll('.suite-card[data-folder-name]').forEach(function (el) {
      var fn = el.getAttribute('data-folder-name');
      if (fn && !keep.has(fn)) el.remove();
    });
    grid.scrollTop = scroll;
    var ss = document.getElementById('suite-search');
    if (ss) {
      var q = (ss.value || '').toLowerCase().trim();
      document.querySelectorAll('.suite-card').forEach(function (c) {
        var d = c.getAttribute('data-q') || '';
        c.style.display = !q || d.indexOf(q) >= 0 ? '' : 'none';
      });
    }
  }

  function applyManifest(data) {
    if (!data || !Array.isArray(data.entries)) return false;
    var sig = manifestSig(data);
    if (sig === lastManifestSig) return false;
    lastManifestSig = sig;
    applyFoldersToApi(data.folders || data.entries.map(function (e) {
      return e.encodedFolderName;
    }));
    syncSuiteGrid(data.entries);
    var api = window.__mervLiveDashboard;
    if (api && typeof api.pollAll === 'function') api.pollAll();
    return true;
  }

  function parseFoldersFromIndexHtml(html) {
    var m = String(html || '').match(/window\.MERV_REPORT_FOLDERS\s*=\s*(\[[\s\S]*?\]);/);
    if (!m) return null;
    try {
      return JSON.parse(m[1]);
    } catch (e) {
      return null;
    }
  }

  async function fetchManifest() {
    try {
      var r = await fetch('./merv-index-data.json?cb=' + Date.now(), { cache: 'no-store' });
      if (r.ok) return await r.json();
    } catch (e) {}
    return null;
  }

  async function syncFromManifest() {
    var data = await fetchManifest();
    if (data) {
      applyManifest(data);
      return;
    }
    try {
      var r = await fetch('./index.html?cb=' + Date.now(), { cache: 'no-store' });
      if (!r.ok) return;
      var html = await r.text();
      var nf = parseFoldersFromIndexHtml(html);
      if (!nf || !Array.isArray(nf)) return;
      var api = window.__mervLiveDashboard;
      if (!api || !api.folders) return;
      var curSig = (api.folders || []).join('\u0001');
      var nextSig = nf.join('\u0001');
      if (nextSig === curSig) return;
      applyFoldersToApi(nf);
      var grid = document.getElementById('suite-grid');
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var srcGrid = doc.getElementById('suite-grid');
      if (grid && srcGrid && grid.innerHTML !== srcGrid.innerHTML) {
        var scroll = grid.scrollTop;
        grid.innerHTML = srcGrid.innerHTML;
        grid.scrollTop = scroll;
      }
      if (api.pollAll) api.pollAll();
    } catch (e) {}
  }

  function start() {
    if (!window.__mervLiveDashboard) {
      setTimeout(start, 200);
      return;
    }
    setTimeout(syncFromManifest, 400);
    setInterval(syncFromManifest, MANIFEST_MS);
  }

  window.syncIndexFolders = syncFromManifest;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
