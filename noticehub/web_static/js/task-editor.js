/* 新建和编辑共用独立表单；列表刷新不会销毁正在填写的内容。 */
(function () {
  'use strict';
  var dialog = document.getElementById('task-editor-dialog');
  var form = document.getElementById('rail-new-form');
  var title = document.getElementById('rail-new-summary');
  var notes = document.getElementById('task-editor-notes');
  var deadline = document.getElementById('rail-new-deadline');
  var dateOnly = document.getElementById('task-editor-date-only');
  var save = document.getElementById('rail-new-save');
  var status = document.getElementById('task-editor-status');
  var discard = document.getElementById('task-editor-discard');
  var retry = document.getElementById('task-editor-retry');
  var options = null, initial = '', busy = false, loading = false, opener = null, generation = 0;

  function values() { return {summary: title.value.trim(), notes: notes.value, deadline: deadline.value}; }
  function snapshot() { return JSON.stringify([title.value, notes.value, deadline.value, dateOnly.checked]); }
  function fill(value) {
    title.value = value.summary || ''; notes.value = value.notes || '';
    var raw = value.deadline || '';
    dateOnly.checked = raw.length === 10;
    deadline.type = dateOnly.checked ? 'date' : 'datetime-local';
    deadline.value = raw;
    initial = snapshot();
  }
  function setBusy(value) {
    busy = value;
    [title, notes, deadline, dateOnly, save].forEach(function (input) { input.disabled = value; });
    dialog.querySelectorAll('[data-editor-close]').forEach(function (button) { button.disabled = value && !loading; });
    save.textContent = value ? (loading ? '正在读取…' : '正在保存…') : options.submitLabel;
    dialog.setAttribute('aria-busy', String(value));
  }
  function close() {
    generation++;
    dialog.close();
  }
  function requestClose() {
    if (busy && !loading) return;
    if (snapshot() !== initial) {
      discard.hidden = false;
      document.getElementById('task-editor-continue').focus();
    } else close();
  }
  function load() {
    var current = generation;
    loading = true; setBusy(true); status.textContent = '正在读取待办…'; retry.hidden = true;
    Promise.resolve().then(options.load).then(function (value) {
      if (current !== generation) return;
      fill(value); status.textContent = ''; loading = false; setBusy(false); title.focus();
    }).catch(function (error) {
      if (current !== generation) return;
      loading = false; setBusy(false);
      [title, notes, deadline, dateOnly, save].forEach(function (input) { input.disabled = true; });
      status.textContent = '读取失败：' + error.message; retry.hidden = false;
    });
  }
  dateOnly.addEventListener('change', function () {
    var value = deadline.value;
    deadline.type = dateOnly.checked ? 'date' : 'datetime-local';
    deadline.value = dateOnly.checked ? value.slice(0, 10) : (value.length === 10 ? value + 'T18:00' : value);
  });
  form.addEventListener('input', function () { discard.hidden = true; });
  dialog.querySelectorAll('[data-editor-close]').forEach(function (button) { button.addEventListener('click', requestClose); });
  dialog.addEventListener('cancel', function (event) { event.preventDefault(); requestClose(); });
  // 只在按下、松开都在背景时关闭，避免从输入框拖选文字松手时误关。
  var backdrop = false;
  dialog.addEventListener('pointerdown', function (event) { backdrop = event.target === dialog; });
  dialog.addEventListener('click', function (event) { if (backdrop && event.target === dialog) requestClose(); backdrop = false; });
  dialog.addEventListener('close', function () {
    document.body.classList.remove('task-editor-open');
    var target = opener && opener.isConnected ? opener : (opener && document.getElementById(opener.id)) || document.getElementById('task-new-button');
    if (target && target.getClientRects().length) target.focus({preventScroll: true});
  });
  document.getElementById('task-editor-continue').onclick = function () { discard.hidden = true; title.focus(); };
  document.getElementById('task-editor-abandon').onclick = close;
  retry.onclick = load;
  form.addEventListener('submit', function (event) {
    event.preventDefault();
    if (busy || save.disabled) return;
    if (!title.value.trim()) { status.textContent = '请填写待办内容。'; title.focus(); return; }
    var current = options;
    setBusy(true); discard.hidden = true; status.textContent = '正在保存…';
    Promise.resolve().then(function () { return current.onSave(values()); }).then(function (result) {
      setBusy(false); close();
      return Promise.resolve().then(function () { return current.onSaved(result); }).catch(function (error) {
        showFeedback('已保存，但列表刷新失败：' + error.message, 'error', syncTasks);
      });
    }).catch(function (error) {
      setBusy(false); status.textContent = '保存失败：' + error.message + '。输入已保留。';
    });
  });
  window.NhTaskEditor = {
    open: function (config) {
      if (dialog.open) return;
      options = config; opener = config.trigger || document.activeElement; generation++;
      form.reset(); fill(config.values || {}); status.textContent = ''; discard.hidden = true; retry.hidden = true;
      document.getElementById('task-editor-title').textContent = config.title;
      loading = false; setBusy(false);
      document.body.classList.add('task-editor-open'); dialog.showModal(); title.focus();
      if (config.load) load();
    },
    close: requestClose
  };
})();
