/* 清单和月历共享筛选结果；切换视图只改变呈现，不另取一份业务数据。 */
(function () {
  'use strict';
  var data = null;
  var view = 'list';
  var root = document.getElementById('tab-tasks');
  var toolbar = document.querySelector('.workspace-toolbar');
  var calendar = document.getElementById('calendar-panel');
  var list = document.createElement('div');
  list.id = 'task-list-panel';
  root.appendChild(list);
  root.appendChild(calendar);
  calendar.hidden = true;

  function node(tag, cls, text) {
    var result = document.createElement(tag);
    if (cls) result.className = cls;
    if (text) result.textContent = text;
    return result;
  }
  function control(label, id, options) {
    var wrapper = node('label', 'task-filter');
    wrapper.appendChild(node('span', '', label));
    var select = node('select'); select.id = id;
    options.forEach(function (item) { var option = node('option', '', item[1]); option.value = item[0]; select.appendChild(option); });
    wrapper.appendChild(select);
    select.addEventListener('change', refresh);
    filters.appendChild(wrapper);
    return select;
  }
  var switcher = node('div', 'task-view-switch'); switcher.setAttribute('role', 'group'); switcher.setAttribute('aria-label', '日程显示方式');
  [['list', '清单'], ['calendar', '月历']].forEach(function (item) {
    var button = node('button', '', item[1]); button.type = 'button'; button.id = 'task-view-' + item[0];
    button.setAttribute('aria-controls', item[0] === 'list' ? list.id : calendar.id);
    button.addEventListener('click', function () { setView(item[0]); }); switcher.appendChild(button);
  });
  toolbar.querySelector('.workspace-toolbar-actions').prepend(switcher);
  var filters = node('div', 'task-filters');
  var dateFilter = control('日期', 'task-date-filter', [['all', '全部日期'], ['today', '今天'], ['week', '未来 7 天'], ['overdue', '已逾期'], ['undated', '未设日期']]);
  var stateFilter = control('状态', 'task-state-filter', [['pending', '待处理'], ['candidates', '待确认'], ['done', '已完成'], ['all', '全部状态']]);
  var sort = control('排序', 'task-sort', [['deadline', '最近到期'], ['created', '最近添加']]);
  var searchLabel = node('label', 'task-filter task-search'); searchLabel.appendChild(node('span', '', '搜索'));
  var search = node('input'); search.type = 'search'; search.id = 'task-search'; search.placeholder = '搜索事项或群名';
  search.addEventListener('input', refresh); searchLabel.appendChild(search); filters.appendChild(searchLabel);
  var results = node('div', 'task-results');
  var count = node('p', '', '正在读取日程…'); count.id = 'task-result-count'; count.setAttribute('role', 'status'); results.appendChild(count);
  var clear = node('button', 'btn', '清空已完成'); clear.type = 'button'; clear.id = 'tasks-clear-done';
  clear.setAttribute('aria-label', '清空已完成、已忽略、已过期的任务'); results.appendChild(clear);
  var controls = node('div', 'task-workspace-controls'); controls.appendChild(filters); controls.appendChild(results);
  toolbar.after(controls);

  function stamp(date) { return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'); }
  function deadline(task) {
    var raw = String(task.deadline || '');
    if (!raw) return Infinity;
    var time = new Date(raw.length === 10 ? raw + 'T23:59:59' : raw.replace(' ', 'T')).getTime();
    return Number.isFinite(time) ? time : Infinity;
  }
  function allTasks(source) {
    var seen = new Set();
    return ['today', 'week', 'later', 'candidates', 'done', 'pinned'].flatMap(function (key) { return source[key] || []; }).filter(function (task) {
      var key = String(task.id); if (seen.has(key)) return false; seen.add(key); return true;
    });
  }
  function items(source) {
    if (!source) return [];
    var now = new Date(), today = stamp(now), end = new Date(now); end.setDate(end.getDate() + 6);
    var query = search.value.trim().toLocaleLowerCase();
    return allTasks(source).filter(function (task) {
      var status = task.status || (task.done ? 'done' : 'open');
      if (stateFilter.value === 'pending' && status !== 'open' && status !== 'candidate') return false;
      if (stateFilter.value === 'candidates' && status !== 'candidate') return false;
      if (stateFilter.value === 'done' && status !== 'done') return false;
      var day = String(task.deadline || '').slice(0, 10);
      if (dateFilter.value === 'today' && day !== today) return false;
      if (dateFilter.value === 'week' && (!day || day < today || day > stamp(end))) return false;
      if (dateFilter.value === 'undated' && day) return false;
      if (dateFilter.value === 'overdue' && (status === 'done' || !day || deadline(task) >= now.getTime())) return false;
      return !query || [task.summary, task.text, task.notes, (task.groups || []).join(' ')].join(' ').toLocaleLowerCase().includes(query);
    }).sort(function (a, b) {
      if (sort.value === 'created') {
        var createdA = Date.parse(a.created_at || '') || 0, createdB = Date.parse(b.created_at || '') || 0;
        if (createdA !== createdB) return createdB - createdA;
        return Number(b.id) - Number(a.id);
      }
      var aDue = deadline(a), bDue = deadline(b);
      return aDue === bDue ? Number(a.id) - Number(b.id) : aDue < bDue ? -1 : 1;
    });
  }
  function updateCount() {
    if (!data) return;
    var selected = items(data), undated = selected.filter(function (task) { return !task.deadline; }).length;
    count.textContent = view === 'calendar'
      ? '已筛选 ' + selected.length + ' 项，按截止日期显示' + (undated ? '；' + undated + ' 项未设日期，可在清单中查看' : '')
      : '共 ' + selected.length + ' 项' + (dateFilter.value === 'overdue' ? '逾期事项' : '') + ' · ' + (sort.value === 'deadline' ? '按截止时间排列，未设日期的排在最后' : '按添加时间从新到旧排列');
  }
  function setView(next) {
    view = next;
    list.hidden = view !== 'list'; calendar.hidden = view !== 'calendar';
    calendar.classList.remove('calendar-collapsed');
    ['list', 'calendar'].forEach(function (key) { document.getElementById('task-view-' + key).setAttribute('aria-pressed', String(view === key)); });
    sort.disabled = view === 'calendar';
    updateCount();
  }
  function refresh() {
    if (!data) return;
    render(data);
    if (window.syncCalendarTasks) window.syncCalendarTasks(data);
  }
  function revealTask(id) {
    if (document.body.dataset.view !== 'tasks') document.getElementById('tab-tasks-button').click();
    dateFilter.value = 'all'; stateFilter.value = 'all'; search.value = '';
    setView('list'); refresh();
    var target = document.getElementById('task-' + id);
    if (target) { scrollTargetIntoView(target, 'smooth'); target.tabIndex = -1; target.focus({preventScroll: true}); }
  }
  window.NhTaskView = {
    items: items,
    receive: function (source) { data = source; updateCount(); return items(source); },
    setView: setView,
    revealTask: revealTask,
    error: function (message) { count.textContent = '日程读取失败：' + message; }
  };
  setView('list');
  document.addEventListener('click', function (event) {
    var link = event.target.closest('a[href^="#task-"]');
    if (!link) return;
    event.preventDefault(); revealTask(link.getAttribute('href').slice(6));
  });
})();
