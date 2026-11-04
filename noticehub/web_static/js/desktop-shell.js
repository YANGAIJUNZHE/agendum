/* 桌面导航只管理展示与本机偏好；表单、请求和保存仍由各功能模块负责。 */
(function () {
  'use strict';
  var rail = document.querySelector('.side-rail');
  var defaults = ['pinned-panel', 'rail-actions', 'rail-summary', 'connect', 'groupbox', 'rail-health', 'rail-about'];
  var storageKey = 'nh_workspace_v1';
  var state = {order: defaults.slice(), closed: {}};
  var pages = {};
  var groups = [];
  var activePage = 'appearance';
  var settingsRoot = null;
  var portals = [];
  var dragging = null;

  function make(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  }
  function button(text, className, action) {
    var node = make('button', className, text);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
  }
  function save() {
    try { localStorage.setItem(storageKey, JSON.stringify(state)); } catch (error) { /* 隐私模式仍可临时调整。 */ }
  }
  try {
    var saved = JSON.parse(localStorage.getItem(storageKey) || 'null');
    if (saved && Array.isArray(saved.order)) {
      state.order = saved.order.filter(function (id, index, list) { return defaults.indexOf(id) >= 0 && list.indexOf(id) === index; });
      defaults.forEach(function (id) { if (state.order.indexOf(id) < 0) state.order.push(id); });
    }
    if (saved && saved.closed && typeof saved.closed === 'object') {
      defaults.forEach(function (id) { if (typeof saved.closed[id] === 'boolean') state.closed[id] = saved.closed[id]; });
    }
  } catch (error) { /* 损坏或旧版偏好回退到默认布局。 */ }

  function setClosed(panel, closed, persist) {
    var body = panel.querySelector('.rail-panel-body');
    var toggle = panel.querySelector('.rail-panel-toggle');
    if (!body || !toggle) return;
    body.hidden = closed;
    toggle.setAttribute('aria-expanded', String(!closed));
    panel.classList.toggle('rail-collapsed', closed);
    if (persist) { state.closed[panel.id] = closed; save(); }
  }
  function applyOrder() {
    state.order.forEach(function (id) {
      var panel = document.getElementById(id);
      if (panel && panel.parentNode === rail) rail.appendChild(panel);
    });
    var visible = state.order.filter(function (id) { var panel = document.getElementById(id); return panel && !panel.hidden; });
    visible.forEach(function (id, index) {
      var panel = document.getElementById(id);
      panel.querySelector('[data-move="up"]').disabled = index === 0;
      panel.querySelector('[data-move="down"]').disabled = index === visible.length - 1;
    });
  }
  function move(id, offset) {
    var visible = state.order.filter(function (key) { return !document.getElementById(key).hidden; });
    var destination = visible[visible.indexOf(id) + offset];
    if (!destination) return;
    var from = state.order.indexOf(id), to = state.order.indexOf(destination);
    state.order.splice(from, 1); state.order.splice(to, 0, id);
    applyOrder(); save();
    document.getElementById('rail-layout-status').textContent = '已移动「' + document.getElementById(id).querySelector('h2').textContent + '」';
  }
  function initRail() {
    rail.setAttribute('aria-label', '辅助面板');
    var head = make('div', 'rail-toolbar');
    head.appendChild(make('h2', '', '辅助面板'));
    var edit = button('整理', 'btn rail-customize', function () {
      var editing = rail.classList.toggle('rail-editing');
      edit.textContent = editing ? '完成' : '整理';
      edit.setAttribute('aria-pressed', String(editing));
      reset.hidden = !editing;
    });
    edit.setAttribute('aria-pressed', 'false');
    head.appendChild(edit);
    var reset = button('恢复默认', 'btn', function () {
      state = {order: defaults.slice(), closed: {}};
      defaults.forEach(function (id) { setClosed(document.getElementById(id), id === 'connect' ? document.getElementById('qrbox').hidden : id !== 'pinned-panel', false); });
      applyOrder(); save();
      document.getElementById('rail-layout-status').textContent = '已恢复默认顺序和折叠状态';
    });
    reset.hidden = true;
    head.appendChild(reset);
    rail.prepend(head);
    var status = make('span', 'sr-only'); status.id = 'rail-layout-status'; status.setAttribute('role', 'status'); head.appendChild(status);
    defaults.forEach(function (id) {
      var panel = document.getElementById(id);
      if (!panel) return;
      var heading = panel.querySelector('.panel-heading');
      var title = heading.querySelector('h2');
      var body = make('div', 'rail-panel-body'); body.id = id + '-body';
      Array.from(panel.children).forEach(function (child) { if (child !== heading) body.appendChild(child); });
      panel.appendChild(body);
      heading.querySelectorAll('.eyebrow').forEach(function (node) { node.remove(); });
      heading.querySelectorAll('.panel-index:not(#pinned-count)').forEach(function (node) { node.remove(); });
      var toggle = button('', 'rail-panel-toggle', function () { setClosed(panel, !body.hidden, true); });
      toggle.appendChild(make('span', '', title.textContent));
      toggle.setAttribute('aria-controls', body.id);
      title.textContent = ''; title.appendChild(toggle);
      var controls = make('div', 'rail-order-controls');
      [['up', -1, '上移'], ['down', 1, '下移']].forEach(function (item) {
        var control = button(item[2], 'btn', function () {
          move(id, item[1]);
          // 到达边界时原按钮被禁用，把焦点交回另一个可用的排序按钮。
          var focus = control.disabled ? controls.querySelector('button:not(:disabled)') : control;
          if (focus) focus.focus();
        });
        control.dataset.move = item[0]; control.setAttribute('aria-label', item[2] + title.textContent); controls.appendChild(control);
      });
      var handle = button('拖动', 'btn rail-drag', function () {});
      handle.setAttribute('aria-label', '拖动排序' + title.textContent + '，也可使用上移和下移');
      handle.draggable = true;
      handle.addEventListener('dragstart', function (event) {
        dragging = id; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', id); panel.classList.add('rail-dragging');
      });
      handle.addEventListener('dragend', function () { dragging = null; panel.classList.remove('rail-dragging'); rail.querySelectorAll('.rail-drop-target').forEach(function (node) { node.classList.remove('rail-drop-target'); }); });
      controls.appendChild(handle); heading.appendChild(controls);
      panel.addEventListener('dragover', function (event) {
        if (!dragging || dragging === id || !rail.classList.contains('rail-editing')) return;
        event.preventDefault(); event.dataTransfer.dropEffect = 'move'; panel.classList.add('rail-drop-target');
      });
      panel.addEventListener('dragleave', function (event) { if (!panel.contains(event.relatedTarget)) panel.classList.remove('rail-drop-target'); });
      panel.addEventListener('drop', function (event) {
        if (!dragging || dragging === id) return;
        event.preventDefault();
        var from = state.order.indexOf(dragging), to = state.order.indexOf(id);
        if (from < 0 || to < 0) return;
        state.order.splice(from, 1); state.order.splice(to, 0, dragging); applyOrder(); save();
        panel.classList.remove('rail-drop-target'); status.textContent = '面板顺序已保存';
      });
      setClosed(panel, Object.prototype.hasOwnProperty.call(state.closed, id) ? state.closed[id] : id !== 'pinned-panel' && id !== 'connect', false);
    });
    applyOrder();
    // 初次配置保留登录入口；已连接的账号默认收起。用户手动选择优先于轮询。
    var qr = document.getElementById('qrbox');
    var syncConnection = function () {
      if (!Object.prototype.hasOwnProperty.call(state.closed, 'connect') && document.getElementById('connect').parentNode === rail) setClosed(document.getElementById('connect'), qr.hidden, false);
      applyOrder();
    };
    new MutationObserver(syncConnection).observe(qr, {attributes: true, attributeFilter: ['hidden']});
  }

  function releasePortals() {
    portals.forEach(function (entry) {
      rail.appendChild(entry.node);
      entry.node.querySelector('.rail-panel-toggle').disabled = false;
      setClosed(entry.node, entry.closed, false);
    });
    portals = [];
    applyOrder();
  }
  function movePortal(id, destination) {
    var node = document.getElementById(id);
    if (!node || portals.some(function (entry) { return entry.node === node; })) return;
    portals.push({node: node, closed: node.querySelector('.rail-panel-body').hidden});
    setClosed(node, false, false); node.querySelector('.rail-panel-toggle').disabled = true; destination.appendChild(node);
  }
  function selectPage(id, focus) {
    if (!pages[id]) return;
    activePage = id;
    Object.keys(pages).forEach(function (key) { pages[key].panel.hidden = key !== id; pages[key].button.setAttribute('aria-current', key === id ? 'page' : 'false'); });
    groups.forEach(function (group) {
      var selected = group.keys.indexOf(id) >= 0;
      group.list.hidden = !selected; group.button.setAttribute('aria-expanded', String(selected));
    });
    var current = pages[id];
    document.getElementById('settings-location').textContent = current.group + ' / ' + current.label;
    if (focus) { current.panel.querySelector('.settings-page-title').focus({preventScroll: true}); settingsRoot.scrollIntoView({block: 'start'}); }
  }
  function mountSettings(root) {
    settingsRoot = root; pages = {}; groups = [];
    var original = Array.from(root.children);
    var shell = make('div', 'settings-shell');
    var nav = make('nav', 'settings-nav'); nav.setAttribute('aria-label', '设置分类');
    var navTitle = make('h1', '', '设置'); nav.appendChild(navTitle);
    var label = make('label', 'settings-search-label', '查找设置'); label.htmlFor = 'settings-search';
    var search = make('input', 'settings-search'); search.id = 'settings-search'; search.type = 'search'; search.placeholder = '搜索设置项'; search.autocomplete = 'off';
    nav.appendChild(label); nav.appendChild(search);
    var results = make('div', 'settings-search-results'); results.hidden = true; results.setAttribute('aria-label', '匹配的设置'); nav.appendChild(results);
    var resultNote = make('p', 'setting-note'); resultNote.setAttribute('role', 'status'); results.appendChild(resultNote);
    var body = make('div', 'settings-body');
    var location = make('p', 'settings-location'); location.id = 'settings-location'; body.appendChild(location);
    shell.appendChild(nav); shell.appendChild(body); root.appendChild(shell);
    var definitions = [
      ['常用设置', [['appearance', '外观与布局', '主题、动效与侧栏顺序'], ['hosting', '托管与启动', 'QQ 托管、退出恢复与开机启动']]],
      ['消息来源', [['qq', 'QQ 与订阅群', '引擎、登录与群订阅'], ['wechat', '微信', '数据目录、读取频率与群选择'], ['dingtalk', '钉钉', '账户与群选择'], ['chaoxing', '学习通', '课程、登录与同步'], ['catchup', '历史回溯', '自动补拉离线期间的消息']]],
      ['通知与提取', [['push', '消息推送', '手机提醒与推送通道'], ['llm', 'AI 摘要', '服务商、模型与密钥'], ['rules', '识别规则', '关键词与提取规则']]],
      ['日程与数据', [['mobile', '手机日程同步', '设备配对、访问与撤销'], ['calendar', '日历订阅', 'iPhone 订阅地址与二维码'], ['backup', '数据备份', '导出本机待办数据库']]],
      ['系统与帮助', [['setup', '首次配置', '接入平台与配置向导'], ['runtime', '运行信息', '服务端口、状态与纠错提示'], ['updates', '版本更新', '更新通道与检查更新'], ['announcements', '服务公告', '公告历史'], ['support', '关于与支持', '使用须知、社区在线数与支持开发者']]]
    ];
    definitions.forEach(function (definition) {
      var list = make('div', 'settings-subnav');
      var group = {keys: [], list: list};
      group.button = button(definition[0], 'settings-category', function () { selectPage(group.keys[0], true); });
      list.id = 'settings-group-' + groups.length; group.button.setAttribute('aria-controls', list.id);
      nav.appendChild(group.button); nav.appendChild(list); groups.push(group);
      definition[1].forEach(function (item) {
        var panel = make('section', 'settings-page'); panel.id = 'settings-page-' + item[0]; panel.hidden = true;
        var heading = make('h2', 'settings-page-title', item[1]); heading.tabIndex = -1; heading.id = panel.id + '-title'; panel.setAttribute('aria-labelledby', heading.id);
        panel.appendChild(heading); panel.appendChild(make('p', 'settings-page-description', item[2])); body.appendChild(panel);
        var link = button(item[1], 'settings-page-link', function () { selectPage(item[0], true); }); link.setAttribute('aria-controls', panel.id); list.appendChild(link);
        pages[item[0]] = {panel: panel, button: link, label: item[1], group: definition[0], description: item[2]}; group.keys.push(item[0]);
      });
    });
    function put(id, key, closest) {
      var node = document.getElementById(id);
      if (!node) return;
      if (closest) node = node.closest(closest);
      if (node) pages[key].panel.appendChild(node);
    }
    put('pref-motion-enabled', 'appearance', 'fieldset');
    put('pref-catchup-enabled', 'catchup', 'fieldset');
    put('push-settings', 'push'); put('llm-settings', 'llm');
    var mapping = {'appearance-settings': 'appearance', 'hosting-settings-card': 'hosting', 'wechat-settings-card': 'wechat', 'dingtalk-settings-card': 'dingtalk', 'chaoxing-settings-card': 'chaoxing', 'lab-settings-card': 'rules', 'mobile-sync-settings': 'mobile', 'pair-settings-card': 'mobile', 'calendar-settings-card': 'calendar', 'backup-card': 'backup', 'platform-setup-settings': 'setup', 'runtime-settings-card': 'runtime', 'update-card': 'updates', 'announcement-settings-card': 'announcements', 'support-settings-card': 'support'};
    original.forEach(function (node) { pages[mapping[node.id] || 'runtime'].panel.appendChild(node); });
    // 动效与主题放在一起，且主题在前。
    var theme = document.getElementById('appearance-settings');
    var motion = document.getElementById('pref-motion-enabled').closest('fieldset'); theme.appendChild(motion);
    var layout = button('整理首页侧栏', 'btn', function () { document.getElementById('tab-tasks-button').click(); if (!rail.classList.contains('rail-editing')) rail.querySelector('.rail-customize').click(); rail.scrollIntoView({block: 'start'}); });
    theme.appendChild(layout);
    movePortal('connect', pages.qq.panel); movePortal('groupbox', pages.qq.panel);
    var about = make('p', 'settings-help-links');
    var notice = button('使用须知与风险提示', 'btn', function () { document.getElementById('onboarding-open').click(); }); about.appendChild(notice);
    [['项目主页', 'https://github.com/ExpertKT/notice-hub'], ['反馈问题', 'https://github.com/ExpertKT/notice-hub/issues']].forEach(function (item) { var link = make('a', 'btn', item[0]); link.href = item[1]; link.target = '_blank'; link.rel = 'noopener noreferrer'; about.appendChild(link); });
    pages.support.panel.appendChild(about);
    search.addEventListener('input', function () {
      results.querySelectorAll('button').forEach(function (node) { node.remove(); });
      var query = search.value.trim().toLocaleLowerCase(); results.hidden = !query;
      var matches = Object.keys(pages).filter(function (key) { var page = pages[key]; return query && (page.group + page.label + page.description + page.panel.textContent).toLocaleLowerCase().includes(query); });
      resultNote.textContent = matches.length ? '找到 ' + matches.length + ' 个设置页面' : '没有匹配项，请换个关键词';
      matches.forEach(function (key) { results.appendChild(button(pages[key].group + ' / ' + pages[key].label, 'settings-search-result', function () { search.value = ''; results.hidden = true; selectPage(key, true); })); });
    });
    selectPage(pages[activePage] ? activePage : 'appearance', false);
  }
  function reveal(target) {
    if (!target) return;
    var page = target.closest('.settings-page');
    if (page) selectPage(page.id.replace('settings-page-', ''), false);
    var panel = target.closest('.side-rail > section');
    if (panel) setClosed(panel, false, false);
    var parent = target.parentElement;
    while (parent) { if (parent.tagName === 'DETAILS') parent.open = true; parent = parent.parentElement; }
  }
  function changeView(tab) {
    var previous = document.body.dataset.view;
    document.body.dataset.view = tab;
    if (tab !== 'settings') releasePortals();
    if (previous && previous !== tab) window.scrollTo({top: 0, behavior: 'instant'});
  }
  window.NhDesktop = {mountSettings: mountSettings, releasePortals: releasePortals, reveal: reveal, changeView: changeView, selectSettingsPage: selectPage};
  initRail();
  changeView('tasks');
  var mainTabs = document.querySelector('.tabs');
  var desktopNavigation = window.matchMedia('(min-width:1024px)');
  function syncNavigationDirection() { mainTabs.setAttribute('aria-orientation', desktopNavigation.matches ? 'vertical' : 'horizontal'); }
  syncNavigationDirection();
  desktopNavigation.addEventListener('change', syncNavigationDirection);
  var toolbar = make('div', 'workspace-toolbar');
  toolbar.appendChild(make('h1', '', '日程与待办'));
  var actions = make('div', 'workspace-toolbar-actions');
  var createTask = button('新建待办', 'btn primary', function () { var target = document.getElementById('rail-new-form'); reveal(target); if (target.hidden) document.getElementById('rail-new-task').click(); scrollTargetIntoView(target, 'smooth'); document.getElementById('rail-new-summary').focus({preventScroll: true}); });
  createTask.id = 'task-new-button'; actions.appendChild(createTask);
  toolbar.appendChild(actions); document.querySelector('.primary-view').prepend(toolbar);
  // 引导仍能逐步操作，但不再把日程挤出首屏。
  var journey = document.getElementById('journey');
  var disclosure = make('details', 'setup-disclosure');
  disclosure.appendChild(make('summary', '', '接入进度与配置指引'));
  var content = make('div', 'journey-content');
  Array.from(journey.children).forEach(function (child) { content.appendChild(child); });
  disclosure.appendChild(content); journey.appendChild(disclosure);
  document.addEventListener('click', function (event) {
    var link = event.target.closest('a[href^="#"]');
    if (link) { var target = document.getElementById(link.getAttribute('href').slice(1)); if (target) reveal(target); }
  });
})();
