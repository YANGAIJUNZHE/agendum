/* 日程管理 v1。旧接口保留兼容；浏览器写操作使用与手机相同的协议。 */
window.NhSchedule = (function () {
  'use strict';
  var info = null, cursor = 0, polling = false;
  var fields = ['summary','notes','deadline','category','status','pinned','urgent_override','snooze_until'];
  function id() { return window.crypto.randomUUID ? window.crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){var r=window.crypto.getRandomValues(new Uint8Array(1))[0]%16;return(c==='x'?r:(r&3|8)).toString(16);}); }
  function none() { return {kind:'none',value:''}; }
  function when(value) {
    if (!value) return none();
    if (value.length === 10) return {kind:'date',value:value};
    return {kind:'datetime',value:new Date(value).toISOString()};
  }
  function metadata() { return api('/api/v1/info').then(function(x){info=x;return x;}); }
  function base(task, changes, deletion) {
    var result={}; (deletion?fields:Object.keys(changes)).forEach(function(k){result[k]=task[k];}); return result;
  }
  function submit(task, kind, changes, key) {
    var stored;
    try { stored = sessionStorage.getItem('nh_schedule_op:'+key); } catch (ignore) {}
    return (stored ? Promise.resolve(JSON.parse(stored)) : metadata().then(function(meta){
      var request={operation_id:id(),uuid:task?task.uuid:id(),type:kind,server_id:meta.server_id,epoch:meta.epoch,
        base_version:task?task.version:0,base:task?base(task,changes,kind==='delete'):{},changes:changes};
      try { sessionStorage.setItem('nh_schedule_op:'+key,JSON.stringify(request)); } catch(ignore) {}
      return request;
    })).then(function(request){
      return api('/api/v1/mutations',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)}).then(function(result){
        if (result.code==='reset_required') {
          try { sessionStorage.removeItem('nh_schedule_op:'+key); } catch(ignore) {}
          throw Error('电脑已重启，请重新提交；当前输入仍保留');
        }
        if (!result.ok) throw Error(result.code==='conflict'?'手机或其他页面已经修改了这条日程，请刷新后核对再编辑':'这条日程已删除，请刷新列表');
        try { sessionStorage.removeItem('nh_schedule_op:'+key); } catch(ignore) {}
        return result;
      });
    });
  }
  function lookup(taskId) { return api('/api/v1/tasks/lookup?id='+encodeURIComponent(taskId)).then(function(x){return x.task;}); }
  function editTask(task) {
    var panel = el('details','correction'); panel.appendChild(el('summary','','编辑内容与备注'));
    var form=el('form','correct-grid'), title=el('input','correct-date'), notes=el('textarea','correct-date'), deadline=el('input','correct-date');
    title.setAttribute('aria-label','待办内容'); title.maxLength=200; title.required=true; title.value=task.summary||'';
    notes.setAttribute('aria-label','人工备注'); notes.maxLength=10000; notes.value=task.notes||'';
    deadline.setAttribute('aria-label','截止时间'); deadline.type='datetime-local'; deadline.value=task.deadline?String(task.deadline).slice(0,16):'';
    var dateOnly=el('input'); dateOnly.type='checkbox'; dateOnly.checked=String(task.deadline||'').length===10;
    var dateLabel=el('label','','仅设置日期'); dateLabel.prepend(dateOnly);
    if(dateOnly.checked){deadline.type='date';deadline.value=String(task.deadline).slice(0,10);}
    dateOnly.onchange=function(){var v=deadline.value;deadline.type=dateOnly.checked?'date':'datetime-local';deadline.value=dateOnly.checked?v.slice(0,10):(v.length===10?v+'T18:00':v);};
    var save=el('button','btn primary','保存内容'), remove=el('button','btn ghost','删除日程'); remove.type='button';
    form.appendChild(title);form.appendChild(notes);form.appendChild(dateLabel);form.appendChild(deadline);form.appendChild(save);form.appendChild(remove);panel.appendChild(form);
    // 展开时锁定用户看见的版本；保存时不能用重新查询的新版掩盖并发冲突。
    var openedTask=null,loading=false;
    panel.addEventListener('toggle',function(){
      if(!panel.open||openedTask||loading)return;loading=true;
      [title,notes,deadline,dateOnly,save,remove].forEach(function(input){input.disabled=true;});
      lookup(task.id).then(function(t){
        openedTask=t;title.value=t.summary;notes.value=t.notes||'';dateOnly.checked=t.deadline.kind==='date';
        deadline.type=dateOnly.checked?'date':'datetime-local';
        var stamp=t.deadline.value;
        if(t.deadline.kind==='datetime'){var d=new Date(stamp);stamp=new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);}
        deadline.value=stamp;
      }).catch(function(e){showFeedback(e.message,'error');}).then(function(){loading=false;[title,notes,deadline,dateOnly,save,remove].forEach(function(input){input.disabled=false;});});
    });
    form.onsubmit=function(event){event.preventDefault();save.disabled=true;
      (openedTask?Promise.resolve(openedTask):Promise.reject(Error('请重新展开编辑，等待读取日程后再保存'))).then(function(t){
        return submit(t,'update',{summary:title.value.trim(),notes:notes.value,deadline:when(deadline.value)},'editor:'+task.id+':'+JSON.stringify([title.value,notes.value,deadline.value]));
      }).then(function(){return syncTasks();}).then(function(){showFeedback('内容已保存并同步','success');}).catch(function(e){showFeedback(e.message,'error');}).then(function(){save.disabled=false;});
    };
    remove.onclick=function(){if(!window.confirm('删除这条日程？'))return;
      (openedTask?Promise.resolve(openedTask):Promise.reject(Error('请重新展开编辑，等待读取日程后再删除'))).then(function(t){return submit(t,'delete',{},'delete:'+task.id);}).then(syncTasks).catch(function(e){showFeedback(e.message,'error');});};
    return panel;
  }
  function intercept(path, options) {
    if (!options || options.method !== 'POST' || path.indexOf('/api/tasks/') !== 0) return null;
    var p; try { p=JSON.parse(options.body||'{}'); } catch(ignore){return null;}
    var taskId=/^\/api\/tasks\/(\d+)$/.exec(path), key=path+':'+JSON.stringify(p);
    if (path==='/api/tasks/create') return submit(null,'create',{summary:p.summary,deadline:when(p.deadline)},key).then(function(r){return{ok:true,task_id:r.task.id,summary:r.task.summary,deadline:r.task.deadline.value};});
    // 批量清理保留专用接口：一次提交后按服务端当前状态删除，删除记录由
    // SQLite 同步账本一起写入。不要先全量拉取，再在浏览器逐条删除。
    if (path==='/api/tasks/clear-finished') return null;
    if (!taskId && path!=='/api/tasks/pin' && path!=='/api/tasks/urgent') return null;
    // 误判、重复通知等纠错仍走原学习入口；该入口已进入统一事务账本。
    if(p.action==='correct'&&['deadline','clear_deadline','category'].indexOf(p.correction)<0)return null;
    return lookup(taskId?taskId[1]:p.task_id).then(function(t){
      var changes={},kind='update';
      if(path==='/api/tasks/pin')changes.pinned=p.pinned;
      else if(path==='/api/tasks/urgent')changes.urgent_override=p.urgent;
      else if(p.action==='delete')kind='delete';
      else if(p.action==='correct'){
        if(p.correction==='category')changes.category=p.value;
        else changes.deadline=when(p.correction==='clear_deadline'?'':p.value);
      } else if(p.action==='snooze'){
        var next=new Date();next.setDate(next.getDate()+1);next.setHours(7,30,0,0);changes.snooze_until={kind:'datetime',value:next.toISOString()};
      } else {
        var action=p.action||(p.done?'done':'reopen');changes.status=({done:'done',reopen:'open',confirm:'open',dismiss:'dismissed'})[action];
        if(!changes.status)throw Error('不支持的待办操作');
        if(changes.status==='done'||changes.status==='dismissed'){changes.pinned=false;changes.snooze_until=none();}
      }
      return submit(t,kind,changes,key);
    });
  }
  function mobileCard() {
    var card=el('section','surface hosting-settings');card.id='mobile-sync-settings';card.appendChild(el('h2','','手机日程同步'));
    card.appendChild(el('p','setting-note','新版 App 可离线编辑。电脑上线后同步；这里的配对凭据不含电脑管理令牌。'));
    var address=el('input','sync-url');address.placeholder='https://sync.example.com';address.setAttribute('aria-label','手机同步入口');card.appendChild(address);
    var ticket=el('button','btn primary','生成一次性配对二维码'),refresh=el('button','btn','刷新设备');card.appendChild(ticket);card.appendChild(refresh);
    var image=el('img');image.alt='手机日程同步配对二维码';image.width=240;image.height=240;image.hidden=true;card.appendChild(image);
    var note=el('p','setting-note'),pending=el('div'),devices=el('div');card.appendChild(note);card.appendChild(pending);card.appendChild(devices);
    function load(){return api('/api/v1/admin/devices').then(function(x){
      if(x.base)address.value=x.base;pending.textContent='';devices.textContent='';
      (x.pending||[]).forEach(function(p){var row=el('div','preference'),button=el('button','btn','核对并允许');row.appendChild(el('span','',p.name+' · 配对码 '+p.code));button.onclick=function(){api('/api/v1/admin/devices/approve',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:p.id})}).then(load);};row.appendChild(button);pending.appendChild(row);});
      (x.devices||[]).forEach(function(d){var row=el('div','preference');row.appendChild(el('span','',d.name+(d.revoked?' · 已撤销':'')));if(!d.revoked){var button=el('button','btn','撤销连接');button.onclick=function(){if(confirm('撤销这台手机的连接？手机本地编辑仍会保留。'))api('/api/v1/admin/devices/revoke',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({device_id:d.id})}).then(load);};row.appendChild(button);}devices.appendChild(row);});
    }).catch(function(e){note.textContent=e.message;});}
    ticket.onclick=function(){ticket.disabled=true;api('/api/v1/admin/devices/ticket',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({base:address.value})}).then(function(x){image.src=x.qr;image.hidden=false;note.textContent='二维码五分钟内有效，扫码成功后立即失效。';setTimeout(function(){image.hidden=true;note.textContent='二维码已过期，需要时重新生成。';},300000);}).catch(function(e){note.textContent=e.message;}).then(function(){ticket.disabled=false;});};
    refresh.onclick=load;load();var timer=setInterval(function(){if(!card.isConnected){clearInterval(timer);return;}if(!document.hidden)load();},4000);return card;
  }
  function poll(){
    if(polling||document.hidden)return;polling=true;
    metadata().then(function(meta){return api('/api/v1/sync/changes?server_id='+encodeURIComponent(meta.server_id)+'&epoch='+encodeURIComponent(meta.epoch)+'&after='+cursor);})
      .then(function(x){if(x.code==='reset_required'){cursor=0;return syncTasks();}var changed=(x.items||[]).length>0;cursor=x.cursor||0;if(changed)return syncTasks();})
      .catch(function(){cursor=0;}).then(function(){polling=false;});
  }
  window.addEventListener('load',function(){setInterval(poll,10000);});
  return {intercept:intercept,editor:editTask,mobileCard:mobileCard};
})();
