/**
 * Sidebar advanced search: tag pills, status filters, testcase text — filters consolidated rows and suite cards.
 */
(function mervAdvancedSearch() {
  var TAG_PANEL_KEY = 'merv.tagPanel.open';
  var selectedTags = {};
  var currentStatusFilter = 'all';
  var statusFilters = { passed: true, failed: true, skipped: true, in_progress: true };

  function escHtml(s) {
    return String(s || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function normStatus(st) {
    var x = String(st || '').toLowerCase().replace(/\s+/g, '_');
    if (x === 'passed' || x === 'failed' || x === 'skipped' || x === 'in_progress') return x;
    return 'in_progress';
  }

  function rowTagsFromAttr(raw) {
    if (!raw) return [];
    return String(raw)
      .split('|')
      .map(function (t) {
        return t.trim();
      })
      .filter(Boolean);
  }

  function getTextQuery() {
    var adv = document.getElementById('adv-text-search');
    var cons = document.getElementById('consolidated-search');
    var parts = [];
    if (adv && adv.value) parts.push(String(adv.value).toLowerCase().trim());
    if (cons && cons.value) parts.push(String(cons.value).toLowerCase().trim());
    return parts.join(' ').trim();
  }

  function getSelectedTagList() {
    return Object.keys(selectedTags).filter(function (k) {
      return selectedTags[k];
    });
  }

  function anyStatusEnabled() {
    return statusFilters.passed || statusFilters.failed || statusFilters.skipped || statusFilters.in_progress;
  }

  function hasActiveAdvFilters() {
    var tags = getSelectedTagList();
    if (tags.length) return true;
    if (!anyStatusEnabled()) return true;
    var allOn =
      statusFilters.passed &&
      statusFilters.failed &&
      statusFilters.skipped &&
      statusFilters.in_progress;
    if (!allOn) return true;
    var adv = document.getElementById('adv-text-search');
    if (adv && String(adv.value || '').trim()) return true;
    return false;
  }

  function rowMatchesFilters(cr) {
    var status = normStatus(cr.getAttribute('data-status') || '');
    if (!statusFilters[status]) return false;

    var tags = rowTagsFromAttr(cr.getAttribute('data-tags') || '');
    var wantTags = getSelectedTagList();
    if (wantTags.length) {
      var hit = false;
      for (var i = 0; i < wantTags.length; i++) {
        if (tags.indexOf(wantTags[i]) >= 0) {
          hit = true;
          break;
        }
      }
      if (!hit) return false;
    }

    var q = getTextQuery();
    if (q) {
      var nm = (cr.getAttribute('data-name') || '').toLowerCase();
      if (nm.indexOf(q) < 0) return false;
    }
    return true;
  }

  function applyConsolidatedFilters() {
    var consBody = document.getElementById('consolidated-body');
    if (!consBody) return;

    var cases = consBody.querySelectorAll('tr[data-kind="tc"]');
    cases.forEach(function (cr) {
      var key = cr.getAttribute('data-testcase-key') || '';
      var kids = consBody.querySelectorAll('tr[data-parent-key="' + key.replace(/"/g, '') + '"]');
      var showCase = rowMatchesFilters(cr);
      cr.style.display = showCase ? '' : 'none';
      kids.forEach(function (kr) {
        if (!showCase) {
          kr.style.display = 'none';
          return;
        }
        var toggle = cr.querySelector('.cons-toggle');
        if (!toggle || !toggle.classList.contains('expanded')) {
          kr.style.display = 'none';
          return;
        }
        kr.style.display = '';
      });
    });

    var tagRoot = document.getElementById('consolidated-tag-root');
    if (tagRoot) {
      tagRoot.querySelectorAll('tr[data-kind="tc"]').forEach(function (cr) {
        cr.style.display = rowMatchesFilters(cr) ? '' : 'none';
        var key = cr.getAttribute('data-testcase-key') || '';
        var kids = tagRoot.querySelectorAll('tr[data-parent-key="' + key.replace(/"/g, '') + '"]');
        kids.forEach(function (kr) {
          kr.style.display = rowMatchesFilters(cr) && cr.style.display !== 'none' ? '' : 'none';
        });
      });
    }
  }

  function testcaseMatchesSnapFilters(row) {
    var st = normStatus(row.status);
    if (!statusFilters[st]) return false;

    var tags = [];
    if (Array.isArray(row.tags)) {
      tags = row.tags.map(function (t) {
        return String(t || '').trim();
      }).filter(Boolean);
    }
    var wantTags = getSelectedTagList();
    if (wantTags.length) {
      var hit = false;
      for (var i = 0; i < wantTags.length; i++) {
        if (tags.indexOf(wantTags[i]) >= 0) {
          hit = true;
          break;
        }
      }
      if (!hit) return false;
    }

    var q = getTextQuery();
    if (q) {
      var nm = (String(row.testcaseName || '') + ' ' + tags.join(' ')).toLowerCase();
      if (nm.indexOf(q) < 0) return false;
    }
    return true;
  }

  function applySuiteCardFilters() {
    var suiteQ = '';
    var ss = document.getElementById('suite-search');
    if (ss) suiteQ = String(ss.value || '').toLowerCase().trim();

    var snaps = null;
    if (window.__mervLiveDashboard && typeof window.__mervLiveDashboard.getSnaps === 'function') {
      snaps = window.__mervLiveDashboard.getSnaps();
    }

    var folderHasMatch = {};
    if (hasActiveAdvFilters() && snaps && snaps.length) {
      snaps.forEach(function (d) {
        var folder = d && d.__mervFolder;
        if (!folder) return;
        var tc = (d.testSuite && d.testSuite.testCases) || [];
        for (var j = 0; j < tc.length; j++) {
          if (testcaseMatchesSnapFilters(tc[j] || {})) {
            folderHasMatch[folder] = true;
            break;
          }
        }
      });
    }

    document.querySelectorAll('.suite-card').forEach(function (card) {
      var dataQ = card.getAttribute('data-q') || '';
      var json = card.getAttribute('data-json') || '';
      var folderEnc = json.replace(/\/json\/merv-report\.json$/, '');
      var show = true;
      if (suiteQ && dataQ.indexOf(suiteQ) < 0) show = false;
      if (show && hasActiveAdvFilters() && snaps && snaps.length) {
        show = !!folderHasMatch[folderEnc];
      }
      card.style.display = show ? '' : 'none';
    });
  }

  function applyAllFilters() {
    applyConsolidatedFilters();
    applySuiteCardFilters();
    var statusEl = document.getElementById('adv-filter-status');
    if (statusEl) {
      var n = document.querySelectorAll('#consolidated-body tr[data-kind="tc"]');
      var visible = 0;
      n.forEach(function (r) {
        if (r.style.display !== 'none') visible++;
      });
      statusEl.textContent = hasActiveAdvFilters()
        ? visible + ' testcase' + (visible === 1 ? '' : 's') + ' shown'
        : '';
    }
  }

  function collectAllTagsFromSnaps() {
    var seen = {};
    var out = [];
    var snaps = null;
    if (window.__mervLiveDashboard && typeof window.__mervLiveDashboard.getSnaps === 'function') {
      snaps = window.__mervLiveDashboard.getSnaps();
    }
    if (snaps && snaps.length) {
      snaps.forEach(function (d) {
        var tc = (d && d.testSuite && d.testSuite.testCases) || [];
        tc.forEach(function (row) {
          (row.tags || []).forEach(function (tg) {
            var t = String(tg || '').trim();
            if (t && !seen[t]) {
              seen[t] = 1;
              out.push(t);
            }
          });
        });
      });
    } else {
      document.querySelectorAll('#consolidated-body tr[data-kind="tc"]').forEach(function (tr) {
        rowTagsFromAttr(tr.getAttribute('data-tags')).forEach(function (t) {
          if (!seen[t]) {
            seen[t] = 1;
            out.push(t);
          }
        });
      });
    }
    out.sort(function (a, b) {
      return a.localeCompare(b);
    });
    return out;
  }

  function renderTagCloud() {
    var cloud = document.getElementById('adv-tag-cloud');
    if (!cloud) return;
    var tags = collectAllTagsFromSnaps();
    if (!tags.length) {
      cloud.innerHTML = '<span class="adv-tag-empty">No tags yet — add @tag in test titles.</span>';
      return;
    }
    cloud.innerHTML = tags
      .map(function (t) {
        var on = !!selectedTags[t];
        return (
          '<button type="button" class="adv-tag-pill' +
          (on ? ' selected' : '') +
          '" data-tag="' +
          escHtml(t) +
          '">' +
          escHtml(t) +
          '</button>'
        );
      })
      .join('');
    cloud.querySelectorAll('.adv-tag-pill').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var t = btn.getAttribute('data-tag') || '';
        if (!t) return;
        selectedTags[t] = !selectedTags[t];
        btn.classList.toggle('selected', !!selectedTags[t]);
        applyAllFilters();
        if (typeof window.setConsSubView === 'function') {
          window.setConsSubView('testcase');
        }
        if (typeof showIndexView === 'function') {
          showIndexView('consolidated');
        }
      });
    });
  }

  function syncStatusFromFilter() {
    var f = currentStatusFilter || 'all';
    statusFilters.passed = f === 'all' || f === 'passed';
    statusFilters.failed = f === 'all' || f === 'failed';
    statusFilters.skipped = f === 'all' || f === 'skipped';
    statusFilters.in_progress = f === 'all';
  }

  function setStatusFilter(st) {
    currentStatusFilter = st || 'all';
    document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function (btn) {
      btn.classList.toggle('active', (btn.getAttribute('data-status') || '') === currentStatusFilter);
    });
    syncStatusFromFilter();
    applyAllFilters();
  }

  function bindStatusFilterButtons() {
    document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function (btn) {
      btn.addEventListener('click', function () {
        setStatusFilter(btn.getAttribute('data-status') || 'all');
      });
    });
    syncStatusFromFilter();
  }

  function clearFilters() {
    selectedTags = {};
    currentStatusFilter = 'all';
    syncStatusFromFilter();
    document.querySelectorAll('.sidebar-filters .filter-btn').forEach(function (btn) {
      btn.classList.toggle('active', (btn.getAttribute('data-status') || '') === 'all');
    });
    var adv = document.getElementById('adv-text-search');
    if (adv) adv.value = '';
    var cons = document.getElementById('consolidated-search');
    if (cons) cons.value = '';
    renderTagCloud();
    applyAllFilters();
  }

  function setTagPanelOpen(open) {
    var panel = document.getElementById('sidebar-tag-panel');
    var btn = document.getElementById('sidebar-filter-btn');
    if (!panel || !btn) return;
    panel.hidden = !open;
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    try {
      localStorage.setItem(TAG_PANEL_KEY, open ? '1' : '0');
    } catch (e) {
      /* ignore */
    }
    if (open) renderTagCloud();
  }

  function hookPollAll() {
    var api = window.__mervLiveDashboard;
    if (!api || !api.pollAll || api.__mervAdvHooked) return;
    var orig = api.pollAll;
    api.pollAll = function () {
      return Promise.resolve(orig()).then(function () {
        renderTagCloud();
        applyAllFilters();
      });
    };
    api.__mervAdvHooked = true;
  }

  function hookConsolidatedSearch() {
    var cons = document.getElementById('consolidated-search');
    if (!cons || cons.__mervAdvHooked) return;
    cons.addEventListener('input', function () {
      applyAllFilters();
    });
    cons.__mervAdvHooked = true;
  }

  function hookSuiteSearch() {
    var ss = document.getElementById('suite-search');
    if (!ss || ss.__mervAdvHooked) return;
    ss.addEventListener('input', function () {
      applyAllFilters();
    });
    ss.__mervAdvHooked = true;
  }

  function init() {
    var filterBtn = document.getElementById('sidebar-filter-btn');
    var clearBtn = document.getElementById('adv-clear-btn');
    var advText = document.getElementById('adv-text-search');
    var tagOpen = false;
    try {
      tagOpen = localStorage.getItem(TAG_PANEL_KEY) === '1';
    } catch (e) {
      /* ignore */
    }
    setTagPanelOpen(tagOpen);
    if (filterBtn) {
      filterBtn.addEventListener('click', function () {
        var panel = document.getElementById('sidebar-tag-panel');
        setTagPanelOpen(panel && panel.hidden);
      });
    }
    if (clearBtn) clearBtn.addEventListener('click', clearFilters);
    if (advText) advText.addEventListener('input', applyAllFilters);
    bindStatusFilterButtons();
    hookPollAll();
    hookConsolidatedSearch();
    hookSuiteSearch();
    var tries = 0;
    var iv = setInterval(function () {
      hookPollAll();
      renderTagCloud();
      tries++;
      if (tries > 40) clearInterval(iv);
    }, 250);
  }

  window.__mervApplyAdvancedFilters = applyAllFilters;
  window.__mervRenderAdvTagCloud = renderTagCloud;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
