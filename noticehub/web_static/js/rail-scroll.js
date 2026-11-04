/* 同一次滚动的剩余距离沿「栏内列表 → 侧栏 → 页面」连续传递。 */
(function () {
  'use strict';
  var rail = document.querySelector('.side-rail');
  if (!rail) return;
  var desktop = matchMedia('(min-width:1024px)');
  var reduced = matchMedia('(prefers-reduced-motion:reduce)');
  var pending = 0, frame = 0, previousTime = 0, chain = [];

  function stop() {
    if (frame) cancelAnimationFrame(frame);
    pending = 0; frame = 0; previousTime = 0; chain = [];
  }
  function paused() {
    return reduced.matches || document.documentElement.dataset.motion === 'paused';
  }
  function containers(target) {
    var result = [], current = target;
    while (current && current !== rail) {
      if (current.scrollHeight > current.clientHeight + 1 && /^(auto|scroll)$/.test(getComputedStyle(current).overflowY)) result.push(current);
      current = current.parentElement;
    }
    result.push(rail, document.scrollingElement);
    return result;
  }
  function available(nodes, delta) {
    return nodes.some(function (node) {
      return delta > 0 ? node.scrollHeight - node.clientHeight - node.scrollTop > 0.5 : node.scrollTop > 0.5;
    });
  }
  function move(delta) {
    var remaining = delta;
    chain.forEach(function (node) {
      if (!node.isConnected || Math.abs(remaining) < 0.01) return;
      var before = node.scrollTop;
      var after = Math.max(0, Math.min(node.scrollHeight - node.clientHeight, before + remaining));
      var distance = after - before;
      if (!distance) return;
      // 每帧只推进一次；不再叠加浏览器自身的 smooth 动画。
      if (node === document.scrollingElement) window.scrollTo({top: after, behavior: 'instant'});
      else node.scrollTo({top: after, behavior: 'instant'});
      remaining -= distance;
    });
  }
  function tick(time) {
    frame = 0;
    if (!desktop.matches || document.body.dataset.view !== 'tasks') { stop(); return; }
    var elapsed = previousTime ? Math.min(32, time - previousTime) : 16;
    previousTime = time;
    var step = paused() || Math.abs(pending) < 0.75 ? pending : pending * (1 - Math.exp(-elapsed / 45));
    pending -= step;
    move(step);
    if (Math.abs(pending) > 0.01 && available(chain, pending)) frame = requestAnimationFrame(tick);
    else stop();
  }
  rail.addEventListener('wheel', function (event) {
    if (!desktop.matches || document.body.dataset.view !== 'tasks' || !event.cancelable || event.ctrlKey || event.metaKey || event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY) || !event.deltaY || event.target.closest('select,input[type=number]')) { stop(); return; }
    var nodes = containers(event.target);
    var unit = event.deltaMode === 1 ? (parseFloat(getComputedStyle(rail).lineHeight) || 20) : event.deltaMode === 2 ? rail.clientHeight : 1;
    var delta = event.deltaY * unit;
    if (!available(nodes, delta)) { stop(); return; }
    var sameChain = nodes.length === chain.length && nodes.every(function (node, index) { return node === chain[index]; });
    if (!sameChain || pending * delta < 0) stop();
    chain = nodes;
    event.preventDefault();
    // 小幅、连续像素输入通常来自触控板：逐次原量传递，不额外积累惯性。
    if (paused() || (!frame && event.deltaMode === 0 && Math.abs(delta) < 50)) {
      if (frame) cancelAnimationFrame(frame);
      move(pending + delta); stop(); return;
    }
    pending += delta;
    if (!frame) frame = requestAnimationFrame(tick);
  }, {passive: false});

  // 用户改用拖动、键盘或其他区域滚动时，立即取消未完成的缓动。
  document.addEventListener('pointerdown', stop, {passive: true, capture: true});
  document.addEventListener('keydown', stop, true);
  document.addEventListener('wheel', function (event) { if (!rail.contains(event.target)) stop(); }, {passive: true, capture: true});
  document.addEventListener('visibilitychange', stop);
  window.addEventListener('blur', stop);
  window.addEventListener('resize', stop, {passive: true});
})();
