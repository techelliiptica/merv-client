/**
 * Collapse tag lists to N rows with a more/less toggle.
 * Markup: .merv-tags-shell[data-merv-tag-rows="1|3"] wrapping the flex tag container.
 */
(function (global) {
  'use strict';

  var DEFAULT_ROW_PX = 22;
  var DEFAULT_GAP_PX = 6;

  function rowsToMaxHeight(rows, rowPx, gapPx) {
    var r = Math.max(1, rows | 0);
    return r * rowPx + Math.max(0, r - 1) * gapPx;
  }

  function parseNum(attr, fallback) {
    var n = parseInt(attr || '', 10);
    return isNaN(n) || n < 1 ? fallback : n;
  }

  function tagFlexChild(shell) {
    return (
      shell.querySelector('.tag-row') ||
      shell.querySelector('.suite-adv-tag-cloud') ||
      shell.querySelector('.testcase-tags') ||
      shell.querySelector('.sidebar-item-tags') ||
      shell.firstElementChild
    );
  }

  function collapseMaxHeight(shell) {
    var rows = parseNum(shell.getAttribute('data-merv-tag-rows'), 1);
    var rowPx = parseNum(shell.getAttribute('data-merv-tag-row-px'), DEFAULT_ROW_PX);
    var gapPx = parseNum(shell.getAttribute('data-merv-tag-gap-px'), DEFAULT_GAP_PX);
    var child = tagFlexChild(shell);
    if (child && global.getComputedStyle) {
      try {
        var cs = global.getComputedStyle(child);
        var g = parseInt(cs.rowGap || cs.gap || '', 10);
        if (!isNaN(g)) gapPx = g;
      } catch (ex) {}
    }
    return rowsToMaxHeight(rows, rowPx, gapPx);
  }

  function ensureMoreBtn(shell) {
    var next = shell.nextElementSibling;
    if (next && next.classList && next.classList.contains('merv-tags-more')) return next;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'merv-tags-more';
    btn.textContent = 'more';
    btn.addEventListener('click', function () {
      var expanded = shell.classList.toggle('merv-tags-expanded');
      shell.classList.toggle('merv-tags-collapsed', !expanded);
      if (expanded) {
        shell.style.maxHeight = 'none';
        btn.textContent = 'less';
      } else {
        shell.style.maxHeight = collapseMaxHeight(shell) + 'px';
        btn.textContent = 'more';
        refreshShell(shell);
      }
    });
    shell.parentNode.insertBefore(btn, shell.nextSibling);
    return btn;
  }

  function refreshShell(shell) {
    if (!shell || !shell.classList || !shell.classList.contains('merv-tags-shell')) return;
    var btn = ensureMoreBtn(shell);
    if (shell.classList.contains('merv-tags-expanded')) {
      shell.style.maxHeight = 'none';
      if (btn) {
        btn.textContent = 'less';
        btn.hidden = false;
      }
      return;
    }
    shell.classList.add('merv-tags-collapsed');
    var maxH = collapseMaxHeight(shell);
    shell.style.maxHeight = maxH + 'px';
    var overflow = shell.scrollHeight > shell.clientHeight + 2;
    if (btn) {
      btn.hidden = !overflow;
      btn.textContent = 'more';
    }
    if (!overflow) {
      shell.classList.remove('merv-tags-collapsed');
      shell.style.maxHeight = '';
      if (btn) btn.hidden = true;
    }
  }

  function refreshTagCollapse(root) {
    if (!root || !root.querySelectorAll) {
      root = document;
    }
    if (root.classList && root.classList.contains('merv-tags-shell')) {
      refreshShell(root);
      return;
    }
    var shells = root.querySelectorAll('.merv-tags-shell');
    for (var i = 0; i < shells.length; i++) refreshShell(shells[i]);
  }

  global.mervRefreshTagCollapse = refreshTagCollapse;

  function onReady() {
    refreshTagCollapse(document);
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', onReady);
    } else {
      onReady();
    }
  }
})(typeof window !== 'undefined' ? window : globalThis);
