// 指定真机与验收副本包名；不清除数据、不卸载已有 App，只操作本脚本创建的日程。
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';

const root=resolve(import.meta.dirname,'../..');
const serial=process.env.NOTICEHUB_PHONE_SERIAL;
if(!serial)throw Error('请明确设置 NOTICEHUB_PHONE_SERIAL，禁止自动选择设备');
const pkg='com.noticehub.app.validation', adb=process.env.NOTICEHUB_TEST_ADB||'adb';
const env=readFileSync(resolve(root,'.env'),'utf8');
const config=key=>(env.match(new RegExp('^'+key+'=(.*)$','m'))?.[1]||'').trim().replace(/^['"]|['"]$/g,'');
const local='http://127.0.0.1:'+(config('NOTICEHUB_WEB_PORT')||'8766'), base=config('NOTICEHUB_MOBILE_BASE_URL');
const headers={'X-Token':config('NOTICEHUB_WEB_TOKEN'),'Content-Type':'application/json'};
const output=resolve(root,'.tmp-qa/mobile-sync');mkdirSync(output,{recursive:true});
const checks=[],forwards=[];let socketUrl;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function shell(...args){return execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:20000}).trim()}
function assert(ok,label){if(!ok)throw Error(label);checks.push(label);process.stdout.write('PASS '+label+'\n')}
async function call(path,body){const response=await fetch(local+path,{headers,method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)});if(!response.ok)throw Error('本机接口 HTTP '+response.status);return response.json()}
async function until(fn,timeout=60000){let last;for(let end=Date.now()+timeout;Date.now()<end;){try{const result=await fn();if(result)return result}catch(error){last=error.message}await sleep(400)}throw Error('等待真机行为超时：'+(last||''))}
function evaluate(expression){return new Promise((accept,reject)=>{
  const ws=new WebSocket(socketUrl);let complete=false;
  const timeout=setTimeout(()=>{complete=true;ws.close();reject(Error('WebView 请求超时'))},12000);
  ws.addEventListener('open',()=>ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}})));
  ws.addEventListener('message',event=>{const result=JSON.parse(String(event.data));if(result.id!==1)return;complete=true;clearTimeout(timeout);ws.close();if(result.error||result.result?.exceptionDetails)reject(Error(JSON.stringify(result.error||result.result.exceptionDetails)));else accept(result.result?.result?.value)});
  ws.addEventListener('error',()=>{if(!complete){clearTimeout(timeout);reject(Error('WebView 连接失败'))}});
})}
async function debuggerConnect(){await until(async()=>{const pid=shell('shell','pidof',pkg).split(' ')[0];const port=shell('forward','tcp:0','localabstract:webview_devtools_remote_'+pid);forwards.push(port);const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();const page=pages.find(p=>p.url.endsWith('client.html'));if(!page)return false;socketUrl=page.webSocketDebuggerUrl;return true})}
async function pair(){const ticket=await call('/api/v1/admin/devices/ticket',{base});const quoted="'"+ticket.url.replaceAll("'","'\\''")+"'";shell('shell','am','start','-W','-a','android.intent.action.VIEW','-d',quoted,'-n',pkg+'/com.noticehub.app.MainActivity');await debuggerConnect();await until(()=>evaluate("document.getElementById('paired').textContent==='已配对'"))}
async function all(){const info=await call('/api/v1/info');const result=await call('/api/v1/sync/bootstrap?server_id='+info.server_id+'&epoch='+info.epoch);return result.items}
async function task(id){return(await all()).find(t=>t.uuid===id)}
async function screenshot(name){await sleep(500);shell('shell','screencap','-p','/sdcard/noticehub-check.png');shell('pull','/sdcard/noticehub-check.png',resolve(output,name))}
async function openTask(title){await evaluate(`document.querySelector('[data-page=tasks]').click();document.getElementById('filter').value='all';document.getElementById('filter').dispatchEvent(new Event('change'));Array.from(document.querySelectorAll('#list .task-title')).find(n=>n.textContent===${JSON.stringify(title)}).click();true`);await until(()=>evaluate("!document.getElementById('editor').hidden"))}
async function deleteCurrent(){const pending=evaluate("document.getElementById('delete').click();true");await sleep(600);shell('shell','uiautomator','dump','/sdcard/noticehub-dialog.xml');const xml=shell('shell','cat','/sdcard/noticehub-dialog.xml');const button=xml.match(/<node[^>]*resource-id="android:id\/button1"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);if(!button)throw Error('没有找到删除确认按钮');shell('shell','input','tap',String((+button[1]+ +button[3])>>1),String((+button[2]+ +button[4])>>1));await pending}
function service(action){execFileSync('pwsh.exe',['-NoProfile','-File',resolve(root,'tools/'+action+'_mobile.ps1')],{cwd:root,stdio:'ignore',timeout:60000})}

let id,title='真机验收 '+randomUUID().slice(0,8),offlineTitle=title+' 离线修改',resolvedTitle=title+' 冲突合并';
try{
  const publicInfo=await(await fetch(base+'/api/v1/info')).json();assert(publicInfo.server_id===(await call('/api/v1/info')).server_id,'真机固定域名指向本机后端');
  await pair();assert(true,'真机通过 HTTPS 一次性配对，原 App 保留');
  await evaluate(`document.getElementById('new').click();document.getElementById('edit-summary').value=${JSON.stringify(title)};document.getElementById('edit-notes').value='真机原生数据库验收';document.getElementById('edit-kind').value='date';document.getElementById('edit-kind').dispatchEvent(new Event('change'));document.getElementById('edit-date').value='2026-11-06';document.getElementById('edit-pinned').checked=true;document.getElementById('edit-urgent').value='true';document.getElementById('save').click();true`);
  const created=await until(async()=>(await all()).find(t=>!t.deleted&&t.summary===title));id=created.uuid;
  writeFileSync(resolve(output,'phone-run.json'),JSON.stringify({id,title,time:new Date().toISOString()}));
  assert(created.notes==='真机原生数据库验收'&&created.deadline.kind==='date'&&created.pinned&&created.urgent_override,'真机新建、备注、仅日期、置顶与紧急标记同步到电脑');
  await until(()=>evaluate(`document.getElementById('editor').hidden&&document.getElementById('pending-count').textContent==='0'&&document.getElementById('list').textContent.includes(${JSON.stringify(title)})`));
  await openTask(title);service('stop');
  await evaluate(`document.getElementById('edit-summary').value=${JSON.stringify(offlineTitle)};document.getElementById('edit-notes').value='电脑离线，手机冷启动后保留';document.getElementById('save').click();true`);
  await until(()=>evaluate(`document.getElementById('editor').hidden&&document.getElementById('list').textContent.includes(${JSON.stringify(offlineTitle)})`),10000);
  await screenshot('phone-offline.png');shell('shell','am','force-stop',pkg);shell('shell','am','start','-W','-n',pkg+'/com.noticehub.app.MainActivity');await debuggerConnect();
  await until(()=>evaluate(`document.getElementById('list').textContent.includes(${JSON.stringify(offlineTitle)})&&document.getElementById('summary').textContent.includes('待同步')`));assert(true,'电脑停止后真机离线保存，强制停止并重开仍保留日程与队列');
  service('start');await until(async()=>{const response=await fetch(base+'/api/v1/info');return response.ok});await evaluate('AndroidBridge.refresh();true');
  await until(async()=>(await task(id))?.summary===offlineTitle);assert((await task(id)).notes==='电脑离线，手机冷启动后保留','后端重启重新校准后上传离线编辑');
  await until(()=>evaluate("document.getElementById('pending-count').textContent==='0'"));await openTask(offlineTitle);
  const current=await task(id),info=await call('/api/v1/info'),remoteTitle=title+' 电脑并发修改';
  await call('/api/v1/mutations',{...info,operation_id:randomUUID(),uuid:id,type:'update',base_version:current.version,base:{summary:current.summary},changes:{summary:remoteTitle}});
  await evaluate('AndroidBridge.refresh();true');await until(()=>evaluate(`document.getElementById('list').textContent.includes(${JSON.stringify(remoteTitle)})`));
  await evaluate(`document.getElementById('edit-summary').value=${JSON.stringify(resolvedTitle)};document.getElementById('save').click();true`);
  await until(()=>evaluate(`document.getElementById('conflicts').textContent.includes(${JSON.stringify(remoteTitle)})`));
  assert((await task(id)).summary===remoteTitle,'真机编辑期间收到同字段更新时保留双方值并暂停提交');
  await evaluate("document.querySelector('[data-page=sync]').click();true");await screenshot('phone-conflict.png');await evaluate("document.querySelector('#conflicts .primary').click();true");await until(async()=>(await task(id)).summary===resolvedTitle);assert(true,'真机逐字段解决冲突后两端一致');
  await until(()=>evaluate(`document.getElementById('pending-count').textContent==='0'&&document.getElementById('list').textContent.includes(${JSON.stringify(resolvedTitle)})`));
  await evaluate(`document.querySelector('[data-page=tasks]').click();Array.from(document.querySelectorAll('#list .task-title')).find(n=>n.textContent===${JSON.stringify(resolvedTitle)}).closest('article').querySelector('.task-done').click();true`);await until(async()=>(await task(id)).status==='done');assert(!(await task(id)).pinned,'真机完成操作同时解除置顶');
  await until(()=>evaluate(`document.getElementById('pending-count').textContent==='0'&&Array.from(document.querySelectorAll('#list .task-title')).find(n=>n.textContent===${JSON.stringify(resolvedTitle)})?.closest('article').querySelector('.task-done').textContent==='恢复'`));
  await evaluate(`Array.from(document.querySelectorAll('#list .task-title')).find(n=>n.textContent===${JSON.stringify(resolvedTitle)}).closest('article').querySelector('.task-done').click();true`);await until(async()=>(await task(id)).status==='open');assert(true,'真机恢复完成的日程');
  await openTask(resolvedTitle);await deleteCurrent();await until(async()=>(await task(id)).deleted);assert(true,'真机确认删除后电脑保留删除记录');
  const old=shell('shell','dumpsys','package','com.noticehub.app');assert(old.includes('versionName=1.1.0-beta.15'),'原正式 App 仍保持 beta.15，未卸载或清除数据');
  shell('shell','am','force-stop',pkg);await pair();assert(true,'已有配对时从深链冷启动仍显示本地日程，连接未被替换');
  writeFileSync(resolve(output,'phone-acceptance.json'),JSON.stringify({time:new Date().toISOString(),model:shell('shell','getprop','ro.product.model'),api:shell('shell','getprop','ro.build.version.sdk'),package:pkg,base,checks,passed:true},null,2));
}catch(error){
  try{process.stdout.write('PHONE_STATE '+JSON.stringify(await evaluate("({body:document.body.innerText,error:document.getElementById('feedback').textContent})"))+'\n')}catch{}
  throw error;
}finally{
  // 出错也恢复电脑服务；只清理本脚本建立的调试转发。
  try{await call('/api/v1/info')}catch{service('start')}
  for(const port of forwards)try{shell('forward','--remove','tcp:'+port)}catch{}
}
